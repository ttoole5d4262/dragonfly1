package com.fieldintelligence.wp500thermal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;

public class SensorRecordService extends Service implements SensorEventListener {
    public static final String ACTION_START = "com.fieldintelligence.wp500thermal.START";
    public static final String ACTION_STOP = "com.fieldintelligence.wp500thermal.STOP";
    private static final String CHANNEL = "wp500_recording";
    private SensorManager sm;
    private final float[] gravity = new float[3];
    private final float[] magnetic = new float[3];
    private boolean haveGravity, haveMagnetic;

    @Override public void onCreate() {
        super.onCreate();
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "WP500 recording", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            return START_NOT_STICKY;
        }
        try {
            startForeground(500, notification("Recording synchronized thermal + sensors"));
            SessionRecorder.start(this);
            registerAll();
        } catch (Exception e) {
            stopSelf();
        }
        return START_STICKY;
    }

    private Notification notification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("WP500 Thermal Recorder")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true).build();
    }

    private void registerAll() {
        for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
            int t = s.getType();
            if (t == Sensor.TYPE_ACCELEROMETER || t == Sensor.TYPE_GYROSCOPE || t == Sensor.TYPE_MAGNETIC_FIELD ||
                    t == Sensor.TYPE_GRAVITY || t == Sensor.TYPE_LINEAR_ACCELERATION || t == Sensor.TYPE_ROTATION_VECTOR ||
                    t == Sensor.TYPE_GAME_ROTATION_VECTOR || t == Sensor.TYPE_LIGHT || t == Sensor.TYPE_PROXIMITY ||
                    t == Sensor.TYPE_PRESSURE || t == Sensor.TYPE_AMBIENT_TEMPERATURE || t == Sensor.TYPE_RELATIVE_HUMIDITY) {
                sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
            }
        }
    }

    private void stopRecording() {
        if (sm != null) sm.unregisterListener(this);
        SessionRecorder.stop();
        stopForeground(true);
        stopSelf();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        SessionRecorder.appendSensor(event);
        if (event.sensor.getType() == Sensor.TYPE_GRAVITY || event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (event.values.length >= 3) { System.arraycopy(event.values, 0, gravity, 0, 3); haveGravity = true; }
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            if (event.values.length >= 3) { System.arraycopy(event.values, 0, magnetic, 0, 3); haveMagnetic = true; }
        }
        if (haveGravity && haveMagnetic) {
            float[] R = new float[9], I = new float[9];
            if (SensorManager.getRotationMatrix(R, I, gravity, magnetic)) {
                float[] o = SensorManager.getOrientation(R, new float[3]);
                float az = (float)Math.toDegrees(o[0]); if (az < 0) az += 360f;
                SessionRecorder.appendOrientation(event.timestamp, az, (float)Math.toDegrees(o[1]), (float)Math.toDegrees(o[2]));
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { if (sm != null) sm.unregisterListener(this); super.onDestroy(); }
}
