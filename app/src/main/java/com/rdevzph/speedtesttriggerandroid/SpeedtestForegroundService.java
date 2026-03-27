package com.rdevzph.speedtesttriggerandroid;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SpeedtestForegroundService extends Service {

    public static final String ACTION_START = "com.rdevzph.speedtesttriggerandroid.action.START";
    public static final String ACTION_STOP = "com.rdevzph.speedtesttriggerandroid.action.STOP";

    public static final String EXTRA_INTERVAL = "extra_interval";
    public static final String EXTRA_NO_DOWNLOAD = "extra_no_download";
    public static final String EXTRA_NO_UPLOAD = "extra_no_upload";

    public static final String BROADCAST_UPDATE = "com.rdevzph.speedtesttriggerandroid.broadcast.UPDATE";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ISP = "isp";
    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_PING = "ping";
    public static final String EXTRA_DOWNLOAD = "download";
    public static final String EXTRA_UPLOAD = "upload";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_RUNNING = "running";

    private static final String CHANNEL_ID = "speedtest_foreground_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;

    private int intervalSeconds = 30;
    private boolean noDownload = false;
    private boolean noUpload = true;

    private String lastStatus = "Idle";
    private String lastIsp = "-";
    private String lastIp = "-";
    private String lastServer = "-";
    private String lastPing = "-";
    private String lastDownload = "-";
    private String lastUpload = "-";
    private String lastResult = "-";
    private String lastMode = "Download only";

    @Override
    public void onCreate() {
        super.onCreate();

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopMonitoring();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            intervalSeconds = Math.max(intent.getIntExtra(EXTRA_INTERVAL, 30), 5);
            noDownload = intent.getBooleanExtra(EXTRA_NO_DOWNLOAD, false);
            noUpload = intent.getBooleanExtra(EXTRA_NO_UPLOAD, true);
            lastMode = getModeLabel(noDownload, noUpload);

            if (running) {
                updateNotification();
                broadcastState();
                return START_STICKY;
            }

            running = true;
            lastStatus = "Starting...";
            lastResult = "Preparing speed monitor";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForegroundInternal();
            }
            broadcastState();
            startMonitoringLoop();

            return START_STICKY;
        }

        return START_STICKY;
    }

    private synchronized void startMonitoringLoop() {
        if (!running) {
            return;
        }

        executor.execute(() -> {
            while (running) {
                String loopResult = runSpeedtestOnce();
                if (!running) break;

                int waitTime = "retry_soon".equals(loopResult) ? 5 : intervalSeconds;

                for (int remaining = waitTime; remaining > 0 && running; remaining--) {
                    lastStatus = "Waiting " + remaining + "s";
                    updateNotification();
                    broadcastState();
                    SystemClock.sleep(1000);
                }
            }
        });
    }

    private String runSpeedtestOnce() {
        try {
            lastStatus = "Checking ISP and best server";
            lastResult = "-";
            lastDownload = "-";
            lastUpload = "-";
            updateNotification();
            broadcastState();

            Python py = Python.getInstance();
            PyObject module = py.getModule("speedtest_bridge");
            PyObject result = module.callAttr("run_speedtest", noDownload, noUpload);

            JSONObject json = new JSONObject(result.toString());
            boolean ok = json.optBoolean("ok", false);

            if (!ok) {
                String error = json.optString("error", "Unknown error");
                lastStatus = "Retrying";
                lastResult = "Retrying soon";
                lastServer = "Error";
                lastPing = "-";
                lastDownload = "-";
                lastUpload = error;
                updateNotification();
                broadcastState();
                return "retry_soon";
            }

            lastIsp = json.optString("isp", "-");
            lastIp = json.optString("ip", "-");
            String sponsor = json.optString("sponsor", "-");
            String server = json.optString("server", "-");
            lastServer = sponsor + " - " + server;
            lastPing = json.optString("ping", "-") + " ms";
            lastDownload = json.optString("download_text", "Skipped");
            lastUpload = json.optString("upload_text", "Skipped");
            lastResult = json.optString("result_text", "Updated");
            lastMode = json.optString("mode", getModeLabel(noDownload, noUpload));
            lastStatus = "Updated";

            updateNotification();
            broadcastState();
            return "ok";

        } catch (PyException e) {
            lastStatus = "Retrying";
            lastResult = "Retrying soon";
            lastUpload = "Python error";
            updateNotification();
            broadcastState();
            return "retry_soon";

        } catch (Exception e) {
            lastStatus = "Retrying";
            lastResult = "Retrying soon";
            lastUpload = "App error";
            updateNotification();
            broadcastState();
            return "retry_soon";
        }
    }

    private void stopMonitoring() {
        running = false;
        lastStatus = "Stopped";
        lastResult = "Stopped";
        broadcastState();

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void startForegroundInternal() {
        Notification notification = buildNotification();

        ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        );
    }

    private void updateNotification() {
        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                pendingIntentFlags()
        );

        Intent stopIntent = new Intent(this, NotificationActionReceiver.class);
        stopIntent.setAction(ACTION_STOP);

        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
                this,
                2,
                stopIntent,
                pendingIntentFlags()
        );

        Intent startIntent = new Intent(this, NotificationActionReceiver.class);
        startIntent.setAction(ACTION_START);
        startIntent.putExtra(EXTRA_INTERVAL, intervalSeconds);
        startIntent.putExtra(EXTRA_NO_DOWNLOAD, noDownload);
        startIntent.putExtra(EXTRA_NO_UPLOAD, noUpload);

        PendingIntent startPendingIntent = PendingIntent.getBroadcast(
                this,
                3,
                startIntent,
                pendingIntentFlags()
        );

        String contentText = "Server: " + safe(lastServer) + " | Ping: " + safe(lastPing);

        String bigText = "Status: " + safe(lastStatus)
                + "\nISP: " + safe(lastIsp) + " (" + safe(lastIp) + ")"
                + "\nServer: " + safe(lastServer)
                + "\nPing: " + safe(lastPing)
                + "\nDownload: " + safe(lastDownload)
                + "\nUpload: " + safe(lastUpload)
                + "\nMode: " + safe(lastMode)
                + "\nResult: " + safe(lastResult);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle("Speedtest Trigger")
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setOnlyAlertOnce(true)
                .setOngoing(running)
                .setContentIntent(contentPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        if (running) {
            builder.addAction(0, "Stop", stopPendingIntent);
        } else {
            builder.addAction(0, "Start", startPendingIntent);
        }

        return builder.build();
    }

    private void broadcastState() {
        Intent intent = new Intent(BROADCAST_UPDATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, lastStatus);
        intent.putExtra(EXTRA_ISP, lastIsp);
        intent.putExtra(EXTRA_IP, lastIp);
        intent.putExtra(EXTRA_SERVER, lastServer);
        intent.putExtra(EXTRA_PING, lastPing);
        intent.putExtra(EXTRA_DOWNLOAD, lastDownload);
        intent.putExtra(EXTRA_UPLOAD, lastUpload);
        intent.putExtra(EXTRA_RESULT, lastResult);
        intent.putExtra(EXTRA_MODE, lastMode);
        intent.putExtra(EXTRA_RUNNING, running);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Speedtest Background Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows latest server, ping, download, and upload results.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private String getModeLabel(boolean noDownload, boolean noUpload) {
        if (noDownload && noUpload) return "Checker mode";
        if (noDownload) return "Upload only";
        if (noUpload) return "Download only";
        return "Download + Upload";
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        executor.shutdownNow();
        super.onDestroy();
    }
}