package com.fieldintelligence.wp500thermal;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Point;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct bridge into the AC020/Tiny2C SDK already installed in the official
 * com.energy.tc2c package on the WP500. Vendor code/binaries are not bundled.
 */
public class VendorThermalBridge {
    public interface Listener {
        void onStatus(String s);
        void onFrame(byte[] bytes, int vendorType, long frameIndex);
        void onTemperatureStats(Float center, Float min, Float max, Float average, String source);
    }

    public static final int IMAGE_W = 256;
    public static final int IMAGE_H = 192;
    public static final int STREAM_H = 386;
    public static final int IMAGE_BYTES = IMAGE_W * IMAGE_H * 2;
    public static final int INFO_BYTES = IMAGE_W * 2 * 2;
    public static final int TEMP_OFFSET = IMAGE_BYTES + INFO_BYTES;
    public static final int TEMP_BYTES = IMAGE_W * IMAGE_H * 2;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong frames = new AtomicLong();

    private ClassLoader loader;
    private Object usbMonitor, ircamEngine, ircmdEngine, libIrTemp;
    private volatile Float centerTemp, minTemp, maxTemp, avgTemp;
    private volatile boolean started;
    private volatile boolean radiometricAvailable = true;

    public VendorThermalBridge(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void connect() { worker.execute(this::connectInternal); }

    private void connectInternal() {
        try {
            if (started) { status("Thermal stream is already connected"); return; }
            status("Loading WP500 Infisense AC020 backend…");
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo("com.energy.tc2c", 0);
            File opt = new File(context.getCodeCacheDir(), "tc2c_dex");
            if (!opt.exists()) opt.mkdirs();
            loader = new DexClassLoader(ai.sourceDir, opt.getAbsolutePath(), ai.nativeLibraryDir, context.getClassLoader());

            try {
                Class<?> tempClass = loader.loadClass("com.energy.irutilslibrary.LibIRTemp");
                libIrTemp = tempClass.getConstructor(int.class, int.class).newInstance(IMAGE_W, IMAGE_H);
                status("Radiometric temperature engine loaded");
            } catch (Throwable t) {
                radiometricAvailable = false;
                status("Radiometric parser unavailable; center temperature command remains enabled: " + root(t));
            }

            Class<?> usbMonClass = loader.loadClass("com.energy.iruvccamera.usb.USBMonitor");
            Class<?> connectIface = loader.loadClass("com.energy.iruvccamera.usb.OnDeviceConnectListener");

            Object connectProxy = Proxy.newProxyInstance(loader, new Class[]{connectIface}, (proxy, method, args) -> {
                String n = method.getName();
                if ("onAttach".equals(n) && args != null && args.length > 0) {
                    UsbDevice d = (UsbDevice) args[0]; status("Thermal USB attached: " + describe(d));
                } else if ("onGranted".equals(n)) {
                    status("Thermal USB permission granted");
                } else if ("onConnect".equals(n) && args != null && args.length >= 2) {
                    status("Thermal USB connected — opening AC020 stream…");
                    Object ctrlBlock = args[1]; worker.execute(() -> openEngine(ctrlBlock));
                } else if ("onDisconnect".equals(n) || "onDetach".equals(n)) {
                    status("Thermal module disconnected"); started = false;
                } else if ("onCancel".equals(n)) {
                    status("USB permission cancelled");
                } else if ("onException".equals(n)) {
                    status("USB error: " + (args == null ? "unknown" : String.valueOf(args[0])));
                }
                return null;
            });

            Constructor<?> ctor = usbMonClass.getConstructor(Context.class, boolean.class, connectIface);
            usbMonitor = ctor.newInstance(context, false, connectProxy);
            call(usbMonitor, "register");
            @SuppressWarnings("unchecked") List<UsbDevice> list = (List<UsbDevice>) call(usbMonitor, "getDeviceList");
            if (list == null || list.isEmpty()) {
                status("Tiny2C is not exposed to Android yet. Use PREPARE THERMAL once, return here, then CONNECT.");
                return;
            }
            UsbDevice chosen = choose(list);
            status("Found thermal USB: " + describe(chosen));
            boolean has = (Boolean) call(usbMonitor, "hasPermission", new Class[]{UsbDevice.class}, chosen);
            if (has) {
                Object ctrl = call(usbMonitor, "openDevice", new Class[]{UsbDevice.class}, chosen);
                openEngine(ctrl);
            } else {
                status("Requesting USB permission for thermal module…");
                call(usbMonitor, "requestPermission", new Class[]{UsbDevice.class}, chosen);
            }
        } catch (Throwable t) {
            status("Direct thermal backend error: " + root(t));
        }
    }

    private UsbDevice choose(List<UsbDevice> list) {
        for (UsbDevice d : list) {
            for (int i=0;i<d.getInterfaceCount();i++) {
                if (d.getInterface(i).getInterfaceClass() == 14) return d;
            }
        }
        return list.get(0);
    }

    private void openEngine(Object ctrlBlock) {
        if (ctrlBlock == null || started) return;
        try {
            Class<?> ctrlClass = loader.loadClass("com.energy.iruvccamera.usb.USBMonitor$UsbControlBlock");
            Class<?> dualParamClass = loader.loadClass("com.energy.ac020library.bean.DualUvcHandleParam");
            Object param = dualParamClass.getConstructor().newInstance();
            call(param, "setCtrlBlock", new Class[]{ctrlClass}, ctrlBlock);
            call(param, "setIrFps", new Class[]{int.class}, 25);
            call(param, "setBandwidth", new Class[]{float.class}, 1.0f);
            call(param, "setVlWidth", new Class[]{int.class}, 1440);
            call(param, "setVlHeight", new Class[]{int.class}, 1080);
            call(param, "setVlFps", new Class[]{int.class}, 25);

            Class<?> engineClass = loader.loadClass("com.energy.ac020library.IrcamEngine");
            Object builder = engineClass.getMethod("Builder").invoke(null);
            Class<?> logEnum = loader.loadClass("com.energy.ac020library.bean.CommonParams$LogLevel");
            Class<?> driverEnum = loader.loadClass("com.energy.ac020library.bean.CommonParams$DriverType");
            @SuppressWarnings("unchecked")
            Object noLog = Enum.valueOf((Class<? extends Enum>) logEnum.asSubclass(Enum.class), "SDK_LOG_NO_PRINT");
            @SuppressWarnings("unchecked")
            Object driver = Enum.valueOf((Class<? extends Enum>) driverEnum.asSubclass(Enum.class), "USB_DUAL_NATIVE_CAM");
            call(builder, "setLogLevel", new Class[]{logEnum}, noLog);
            call(builder, "setStreamWidth", new Class[]{int.class}, IMAGE_W);
            call(builder, "setStreamHeight", new Class[]{int.class}, STREAM_H);
            call(builder, "setDriverType", new Class[]{driverEnum}, driver);
            call(builder, "setDualUvcHandleParam", new Class[]{dualParamClass}, param);
            ircamEngine = call(builder, "build");

            Class<?> initCb = loader.loadClass("com.energy.ac020library.bean.HandleInitCallback");
            Object initProxy = Proxy.newProxyInstance(loader, new Class[]{initCb}, (proxy, method, args) -> {
                if ("onSuccess".equals(method.getName())) {
                    ircmdEngine = args[0];
                    status("AC020 initialized — starting 256×192 radiometric stream…");
                    startStream();
                } else if ("onFail".equals(method.getName())) {
                    status("AC020 init failed: " + (args == null ? "unknown" : String.valueOf(args[0])));
                }
                return null;
            });
            call(ircamEngine, "initHandle", new Class[]{initCb}, initProxy);
        } catch (Throwable t) {
            status("AC020 open error: " + root(t));
        }
    }

    private void startStream() {
        try {
            Class<?> frameCb = loader.loadClass("com.energy.ac020library.bean.IIrFrameCallback");
            Object frameProxy = Proxy.newProxyInstance(loader, new Class[]{frameCb}, (proxy, method, args) -> {
                if ("onFrame".equals(method.getName()) && args != null && args.length >= 2) {
                    byte[] bytes = (byte[]) args[0];
                    int type = ((Number) args[1]).intValue();
                    long seq = frames.incrementAndGet();
                    long ns = SystemClock.elapsedRealtimeNanos();

                    byte[] tempPlane = extractTemperaturePlane(bytes);
                    if (tempPlane != null && seq % 5 == 0 && radiometricAvailable) {
                        parseRadiometricStats(tempPlane);
                    }
                    if (seq % 15 == 0 && centerTemp == null) worker.execute(this::queryCenterTemperature);

                    SessionRecorder.appendThermal(ns, seq, bytes == null ? 0 : bytes.length,
                            centerTemp, minTemp, maxTemp, avgTemp, type, tempPlane);

                    if (bytes != null && seq % 2 == 0) main.post(() -> listener.onFrame(bytes, type, seq));
                }
                return null;
            });
            call(ircamEngine, "setIrFrameCallback", new Class[]{frameCb}, frameProxy);
            Object result = call(ircamEngine, "startVideoStream");
            started = true;
            status("THERMAL LIVE — 256×192 @ 25 fps — result=" + result);
        } catch (Throwable t) {
            status("Thermal stream error: " + root(t));
        }
    }

    private byte[] extractTemperaturePlane(byte[] frame) {
        if (frame == null || frame.length < TEMP_OFFSET + TEMP_BYTES) return null;
        return Arrays.copyOfRange(frame, TEMP_OFFSET, TEMP_OFFSET + TEMP_BYTES);
    }

    private void parseRadiometricStats(byte[] tempPlane) {
        if (libIrTemp == null || tempPlane == null) return;
        try {
            call(libIrTemp, "setTempData", new Class[]{byte[].class}, tempPlane);
            Object all = call(libIrTemp, "getTemperatureOfCurrentFrame");
            Float min = fieldFloat(all, "minTemperature");
            Float max = fieldFloat(all, "maxTemperature");
            Float avg = fieldFloat(all, "averageTemperature");

            Float center = null;
            try {
                Object point = call(libIrTemp, "getTemperatureOfPoint", new Class[]{Point.class}, new Point(IMAGE_W/2, IMAGE_H/2));
                center = firstValid(fieldFloat(point, "averageTemperature"), fieldFloat(point, "maxTemperature"), fieldFloat(point, "minTemperature"));
            } catch (Throwable ignored) {}

            if (validTemp(center)) centerTemp = center;
            if (validTemp(min)) minTemp = min;
            if (validTemp(max)) maxTemp = max;
            if (validTemp(avg)) avgTemp = avg;

            Float c = centerTemp, lo = minTemp, hi = maxTemp, av = avgTemp;
            main.post(() -> listener.onTemperatureStats(c, lo, hi, av, "LibIRTemp radiometric plane"));
        } catch (Throwable t) {
            radiometricAvailable = false;
            status("Radiometric plane parser failed; using AC020 command temperature fallback: " + root(t));
            worker.execute(this::queryCenterTemperature);
        }
    }

    private void queryCenterTemperature() {
        if (ircmdEngine == null) return;
        try {
            float[] out = new float[8];
            call(ircmdEngine, "basicPointTempInfoGet", new Class[]{int.class,int.class,float[].class}, IMAGE_W/2, IMAGE_H/2, out);
            Float picked = null;
            for (float f : out) if (validTemp(f) && Math.abs(f) > 0.0001f) { picked = f; break; }
            if (picked != null) centerTemp = picked;
            final Float send = centerTemp;
            main.post(() -> listener.onTemperatureStats(send, minTemp, maxTemp, avgTemp, "AC020 point command"));
        } catch (Throwable ignored) {}
    }

    public void manualFfc() {
        worker.execute(() -> {
            if (ircmdEngine == null) { status("FFC unavailable until AC020 is initialized"); return; }
            try { status("FFC / shutter correction: " + call(ircmdEngine, "basicFFCUpdate")); }
            catch (Throwable t) { status("FFC error: " + root(t)); }
        });
    }

    public void stop() {
        worker.execute(() -> {
            try { if (ircamEngine != null && started) call(ircamEngine, "stopVideoStream"); } catch (Throwable ignored) {}
            try { if (ircamEngine != null) call(ircamEngine, "releaseVideoStream"); } catch (Throwable ignored) {}
            try { if (ircamEngine != null) call(ircamEngine, "destroyHandle"); } catch (Throwable ignored) {}
            try { if (usbMonitor != null) call(usbMonitor, "unregister"); } catch (Throwable ignored) {}
            started = false;
        });
    }

    private Float fieldFloat(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true);
            Object v = f.get(obj); return v instanceof Number ? ((Number)v).floatValue() : null;
        } catch (Throwable t) { return null; }
    }

    private Float firstValid(Float... values) {
        for (Float f : values) if (validTemp(f)) return f;
        return null;
    }

    private boolean validTemp(Float f) {
        return f != null && Float.isFinite(f) && f > -100f && f < 1000f;
    }

    private Object call(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name); return m.invoke(target);
    }
    private Object call(Object target, String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = target.getClass().getMethod(name, sig); return m.invoke(target, args);
    }
    private void status(String s) { main.post(() -> listener.onStatus(s)); }
    private String describe(UsbDevice d) {
        if (d == null) return "null";
        return String.format(Locale.US, "%s VID=%04x PID=%04x class=%d", d.getDeviceName(), d.getVendorId(), d.getProductId(), d.getDeviceClass());
    }
    private String root(Throwable t) {
        Throwable x=t; while (x.getCause()!=null && x.getCause()!=x) x=x.getCause();
        return x.getClass().getSimpleName()+": "+String.valueOf(x.getMessage());
    }
}
