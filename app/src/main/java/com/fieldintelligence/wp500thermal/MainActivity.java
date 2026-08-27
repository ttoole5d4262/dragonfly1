package com.fieldintelligence.wp500thermal;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener, VendorThermalBridge.Listener {
    private SensorManager sm;
    private ThermalPreviewView thermalView;
    private TextView thermalStatus, tempText, sensorText, recordText;
    private VendorThermalBridge thermal;
    private int frameCounter;
    private final float[] gravity = new float[3], magnetic = new float[3];
    private boolean haveGravity, haveMagnetic;
    private String compass = "—";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sm=(SensorManager)getSystemService(SENSOR_SERVICE);
        buildUi();
        thermal=new VendorThermalBridge(this,this);
        registerLiveSensors();
        requestRuntimePermissions();
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 20);
    }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this); LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(14),dp(12),dp(14),dp(24)); root.setBackgroundColor(0xff111111);
        scroll.addView(root);
        TextView title=t("WP500 THERMAL + SENSOR RECORDER",22,Color.WHITE); title.setGravity(Gravity.CENTER); root.addView(title);
        thermalStatus=t("Direct AC020 backend ready",14,0xff76ff03); root.addView(thermalStatus);
        thermalView=new ThermalPreviewView(this); root.addView(thermalView,new LinearLayout.LayoutParams(-1,dp(330)));
        tempText=t("CENTER —   MIN —   MAX —\nAVERAGE —",18,Color.WHITE); tempText.setGravity(Gravity.CENTER); root.addView(tempText);

        Button connect=button("CONNECT INTERNAL THERMAL"); connect.setOnClickListener(v->thermal.connect()); root.addView(connect);
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        Button ffc=button("FFC / SHUTTER"); ffc.setOnClickListener(v->thermal.manualFfc()); row.addView(ffc,new LinearLayout.LayoutParams(0,-2,1));
        Button pal=button("PALETTE"); pal.setOnClickListener(v->{thermalView.togglePalette(); pal.setText(thermalView.getPalette().name());}); row.addView(pal,new LinearLayout.LayoutParams(0,-2,1)); root.addView(row);

        recordText=t("NOT RECORDING",14,0xffffb300); root.addView(recordText);
        Button rec=button("START SYNCHRONIZED RECORDING"); rec.setOnClickListener(v->toggleRecording(rec)); root.addView(rec);

        sensorText=t("Sensors…",13,0xffdddddd); sensorText.setTypeface(android.graphics.Typeface.MONOSPACE); root.addView(sensorText);

        Button prepare=button("PREPARE THERMAL HARDWARE"); prepare.setOnClickListener(v->openFactory()); root.addView(prepare);
        Button stock=button("OPEN STOCK THERMAL CAM + KEEP SENSOR LOGGING"); stock.setOnClickListener(v->openStock()); root.addView(stock);
        TextView note=t("Normally use CONNECT first. If Tiny2C is not exposed, PREPARE THERMAL opens the manufacturer's calibrated thermal test; return here and press CONNECT again. Never choose Factory Reset.",12,0xffaaaaaa); root.addView(note);
        setContentView(scroll);
    }

    private void toggleRecording(Button b) {
        if (!SessionRecorder.isActive()) {
            Intent i=new Intent(this,SensorRecordService.class).setAction(SensorRecordService.ACTION_START);
            if (Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
            recordText.setText("RECORDING — 25-fps thermal timestamps + ~5-fps radiometric planes + Android sensors");
            recordText.setTextColor(0xff76ff03);
            b.setText("STOP + SAVE SESSION");
            Toast.makeText(this,"Synchronized recording started",Toast.LENGTH_SHORT).show();
        } else {
            startService(new Intent(this,SensorRecordService.class).setAction(SensorRecordService.ACTION_STOP));
            String p=SessionRecorder.currentPath();
            recordText.setText("SAVED CSV + RADIOMETRIC DATA\n"+p);
            recordText.setTextColor(0xffffb300); b.setText("START SYNCHRONIZED RECORDING");
        }
    }

    private void openStock() {
        try {
            SessionRecorder.appendEvent("external_thermal_cam", "com.energy.tc2c launched");
            Intent i=getPackageManager().getLaunchIntentForPackage("com.energy.tc2c");
            if(i==null) throw new Exception("Thermal Cam package not installed"); startActivity(i);
        } catch(Exception e){ toast(e.getMessage()); }
    }

    private void openFactory() {
        String pkg="com.android.hxyfactorytest";
        try {
            Intent direct=new Intent();
            direct.setComponent(new ComponentName(pkg,"com.android.hxyfactorytest.itemtest.item.accessories.InfisenseTest"));
            startActivity(direct); return;
        } catch(Exception ignored) {}
        try {
            Intent main=new Intent();
            main.setComponent(new ComponentName(pkg,"com.android.hxyfactorytest.itemtest.MainActivity"));
            startActivity(main);
        } catch(Exception e){ toast("Could not open FactoryTest: "+e.getMessage()); }
    }

    private void registerLiveSensors() {
        int[] ts={Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GYROSCOPE,Sensor.TYPE_MAGNETIC_FIELD,Sensor.TYPE_GRAVITY,Sensor.TYPE_LIGHT,Sensor.TYPE_PROXIMITY};
        for(int type:ts){Sensor s=sm.getDefaultSensor(type); if(s!=null) sm.registerListener(this,s,SensorManager.SENSOR_DELAY_UI);}
    }

    private String accel="—",gyro="—",mag="—",grav="—",light="—",prox="—";
    @Override public void onSensorChanged(SensorEvent e) {
        String v=vals(e.values);
        switch(e.sensor.getType()){
            case Sensor.TYPE_ACCELEROMETER:
                accel=v; if(e.values.length>=3){System.arraycopy(e.values,0,gravity,0,3);haveGravity=true;} break;
            case Sensor.TYPE_GYROSCOPE:gyro=v;break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                mag=v; if(e.values.length>=3){System.arraycopy(e.values,0,magnetic,0,3);haveMagnetic=true;} break;
            case Sensor.TYPE_GRAVITY:
                grav=v; if(e.values.length>=3){System.arraycopy(e.values,0,gravity,0,3);haveGravity=true;} break;
            case Sensor.TYPE_LIGHT:light=v;break;
            case Sensor.TYPE_PROXIMITY:prox=v;break;
        }
        updateCompass();
        sensorText.setText("COMPASS "+compass+"\nACC  "+accel+"\nGYRO "+gyro+"\nMAG  "+mag+"\nGRAV "+grav+"\nLIGHT "+light+"\nPROX "+prox);
    }

    private void updateCompass() {
        if(!haveGravity || !haveMagnetic) return;
        float[] R=new float[9], I=new float[9];
        if(SensorManager.getRotationMatrix(R,I,gravity,magnetic)){
            float[] o=SensorManager.getOrientation(R,new float[3]);
            float az=(float)Math.toDegrees(o[0]); if(az<0)az+=360f;
            compass=String.format(Locale.US,"%6.1f°  pitch %6.1f°  roll %6.1f°",az,Math.toDegrees(o[1]),Math.toDegrees(o[2]));
        }
    }

    private String vals(float[] a){StringBuilder s=new StringBuilder(); for(int i=0;i<Math.min(3,a.length);i++){if(i>0)s.append("  ");s.append(String.format(Locale.US,"%8.3f",a[i]));}return s.toString();}
    @Override public void onAccuracyChanged(Sensor s,int a){}

    @Override public void onStatus(String s){thermalStatus.setText(s);}
    @Override public void onFrame(byte[] b,int type,long seq){
        frameCounter++; thermalView.updateYuyv(b);
        if(frameCounter%15==0) thermalStatus.setText("THERMAL LIVE  •  frame "+seq+"  •  "+b.length+" bytes");
    }
    @Override public void onTemperatureStats(Float center,Float min,Float max,Float avg,String source){
        tempText.setText("CENTER "+fmtTemp(center)+"   MIN "+fmtTemp(min)+"   MAX "+fmtTemp(max)+"\nAVERAGE "+fmtTemp(avg));
    }
    private String fmtTemp(Float f){return f==null?"—":String.format(Locale.US,"%.2f °C",f);}

    @Override protected void onDestroy(){sm.unregisterListener(this); if(thermal!=null)thermal.stop(); super.onDestroy();}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private TextView t(String s,float z,int c){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(c);t.setPadding(0,dp(7),0,dp(7));return t;}
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
