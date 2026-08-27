package com.fieldintelligence.wp500thermal;

import android.content.Context;
import android.hardware.SensorEvent;
import android.os.Environment;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class SessionRecorder {
    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
    private static DataOutputStream radiometricWriter;
    private static File file, radiometricFile;
    private static volatile boolean active;
    private static long wallMinusElapsedMs;

    private SessionRecorder() {}

    public static boolean isActive() { return active; }
    public static String currentPath() { return file == null ? "" : file.getAbsolutePath(); }
    public static String currentRadiometricPath() { return radiometricFile == null ? "" : radiometricFile.getAbsolutePath(); }

    public static void start(Context context) throws IOException {
        synchronized (LOCK) {
            if (active) return;
            File base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (base == null) base = context.getFilesDir();
            File dir = new File(base, "WP500Sessions");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            file = new File(dir, "wp500_session_" + stamp + ".csv");
            radiometricFile = new File(dir, "wp500_radiometric_" + stamp + ".bin");
            writer = new BufferedWriter(new FileWriter(file, false), 64 * 1024);
            radiometricWriter = new DataOutputStream(new FileOutputStream(radiometricFile, false));
            wallMinusElapsedMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            writer.write("wall_time_ms,elapsed_ns,source,name,type,v0,v1,v2,v3,v4,v5,v6,v7,accuracy,extra\n");
            radiometricWriter.writeUTF("WP500_RAD_V1");
            radiometricWriter.writeInt(VendorThermalBridge.IMAGE_W);
            radiometricWriter.writeInt(VendorThermalBridge.IMAGE_H);
            radiometricWriter.writeInt(5);
            active = true;
            appendEvent("session_start", "WP500 synchronized sensor/thermal session");
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            if (!active) return;
            try { appendEvent("session_stop", ""); } catch (Exception ignored) {}
            active = false;
            try { if (writer != null) writer.flush(); } catch (Exception ignored) {}
            try { if (radiometricWriter != null) radiometricWriter.flush(); } catch (Exception ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
            try { if (radiometricWriter != null) radiometricWriter.close(); } catch (Exception ignored) {}
            writer = null; radiometricWriter = null;
        }
    }

    public static void appendSensor(SensorEvent e) {
        if (!active || e == null || e.sensor == null) return;
        float[] v = e.values;
        write(e.timestamp, "sensor", safe(e.sensor.getName()), String.valueOf(e.sensor.getType()),
                val(v,0), val(v,1), val(v,2), val(v,3), "", "", "", "", e.accuracy, e.sensor.getStringType());
    }

    public static void appendOrientation(long elapsedNs, float azimuthDeg, float pitchDeg, float rollDeg) {
        if (!active) return;
        write(elapsedNs, "derived", "orientation", "azimuth_pitch_roll_deg",
                f(azimuthDeg), f(pitchDeg), f(rollDeg), "", "", "", "", "", 0,
                "v0=azimuth_deg;v1=pitch_deg;v2=roll_deg");
    }

    public static void appendThermal(long elapsedNs, long frame, int frameBytes,
                                     Float centerTemp, Float minTemp, Float maxTemp, Float avgTemp,
                                     int frameType, byte[] tempPlane) {
        if (!active) return;
        write(elapsedNs, "thermal", "AC020/Tiny2C", "frame",
                f(centerTemp), f(minTemp), f(maxTemp), f(avgTemp),
                String.valueOf(frameBytes), String.valueOf(frame), String.valueOf(frameType),
                tempPlane == null ? "0" : String.valueOf(tempPlane.length), 0,
                "v0=center_C;v1=min_C;v2=max_C;v3=avg_C;v4=frame_bytes;v5=frame_index;v6=vendor_type;v7=radiometric_bytes");

        if (tempPlane != null && frame % 5 == 0) appendRadiometric(elapsedNs, frame, tempPlane);
    }

    private static void appendRadiometric(long elapsedNs, long frame, byte[] plane) {
        synchronized (LOCK) {
            if (!active || radiometricWriter == null) return;
            try {
                radiometricWriter.writeLong(elapsedNs);
                radiometricWriter.writeLong(frame);
                radiometricWriter.writeInt(plane.length);
                radiometricWriter.write(plane);
            } catch (IOException ignored) {}
        }
    }

    public static void appendEvent(String name, String extra) {
        if (!active) return;
        long ns = SystemClock.elapsedRealtimeNanos();
        write(ns, "event", name, "", "", "", "", "", "", "", "", "", 0, extra);
    }

    private static String val(float[] v, int i) {
        return (v != null && i < v.length) ? f(v[i]) : "";
    }
    private static String f(Float v) { return v == null ? "" : String.format(Locale.US, "%.7g", v); }
    private static String f(float v) { return String.format(Locale.US, "%.7g", v); }

    private static void write(long elapsedNs, String source, String name, String type,
                              String v0, String v1, String v2, String v3,
                              String v4, String v5, String v6, String v7,
                              int accuracy, String extra) {
        synchronized (LOCK) {
            if (!active || writer == null) return;
            long wallMs = wallMinusElapsedMs + elapsedNs / 1_000_000L;
            try {
                writer.write(wallMs + "," + elapsedNs + "," + csv(source) + "," + csv(name) + "," + csv(type) + "," +
                        csv(v0) + "," + csv(v1) + "," + csv(v2) + "," + csv(v3) + "," +
                        csv(v4) + "," + csv(v5) + "," + csv(v6) + "," + csv(v7) + "," + accuracy + "," + csv(extra) + "\n");
            } catch (IOException ignored) {}
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
