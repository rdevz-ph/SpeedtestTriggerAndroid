package com.rdevzph.speedtesttriggerandroid;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private View rootLayout;
    private MaterialToolbar topAppBar;

    private TextView statusText;
    private TextView ispText;
    private TextView serverText;
    private TextView pingText;
    private TextView modeText;
    private TextView downloadText;
    private TextView uploadText;
    private TextView resultText;
    private TextView logText;

    private EditText intervalInput;
    private CheckBox noDownloadCheck;
    private CheckBox noUploadCheck;
    private Button startButton;
    private Button stopButton;

    private SharedPreferences peakPrefs;

    private TextView peakDownloadText;
    private TextView peakUploadText;
    private TextView peakServerText;
    private TextView peakPingText;

    private boolean receiverRegistered = false;

    private static final String PREFS_NAME = "speedtest_peaks";
    private static final String KEY_PEAK_DOWNLOAD_MBPS = "peak_download_mbps";
    private static final String KEY_PEAK_DOWNLOAD_TEXT = "peak_download_text";
    private static final String KEY_PEAK_UPLOAD_MBPS = "peak_upload_mbps";
    private static final String KEY_PEAK_UPLOAD_TEXT = "peak_upload_text";
    private static final String KEY_PEAK_SERVER_TEXT = "peak_server_text";
    private static final String KEY_PEAK_PING_MS = "peak_ping_ms";
    private static final String KEY_PEAK_PING_TEXT = "peak_ping_text";

    private static final String SERVICE_PREFS = "speedtest_service_state";
    private static final String KEY_SERVICE_RUNNING = "service_running";

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    appendLog("Notification permission denied. Foreground notification may not appear properly.");
                }
            });

    private final BroadcastReceiver serviceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            String status = safe(intent.getStringExtra(SpeedtestForegroundService.EXTRA_STATUS));
            String isp = safe(intent.getStringExtra(SpeedtestForegroundService.EXTRA_ISP));
            String ip = safe(intent.getStringExtra(SpeedtestForegroundService.EXTRA_IP));
            String server = safe(intent.getStringExtra(SpeedtestForegroundService.EXTRA_SERVER));
            String ping = safe(intent.getStringExtra(SpeedtestForegroundService.EXTRA_PING));
            String mode = safe(intent.getStringExtra(SpeedtestForegroundService.EXTRA_MODE));
            String download = safe(intent.getStringExtra(SpeedtestForegroundService.EXTRA_DOWNLOAD));
            String upload = safe(intent.getStringExtra(SpeedtestForegroundService.EXTRA_UPLOAD));
            String result = safe(intent.getStringExtra(SpeedtestForegroundService.EXTRA_RESULT));
            boolean isRunning = intent.getBooleanExtra(SpeedtestForegroundService.EXTRA_RUNNING, false);

            setText(statusText, status);
            setText(ispText, isp + " (" + ip + ")");
            setText(serverText, server);
            setText(pingText, ping);
            setText(modeText, mode);
            setText(downloadText, download);
            setText(uploadText, upload);
            setText(resultText, result);

            if (!"-".equals(server) && !"-".equals(ping)) {
                updatePeakData(server, ping, download, upload);
            }

            updateButtons(isRunning);

            if ("Updated".equalsIgnoreCase(status)) {
                appendLog("ISP: " + isp + " (" + ip + ")");
                appendLog("Best Server: " + server);
                appendLog("Ping: " + ping);
                appendLog("Download: " + download);
                appendLog("Upload: " + upload);
                appendLog("Result: " + result);
            } else if ("Retrying".equalsIgnoreCase(status)) {
                appendLog("Retrying soon...");
            } else if ("Stopped".equalsIgnoreCase(status)) {
                appendLog("Process stopped.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        peakPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        bindViews();
        setupToolbar();
        applyWindowInsets();
        setupUi();
        resetFields();
        loadPeakData();
        requestNotificationPermissionIfNeeded();

        startButton.setOnClickListener(v -> startForegroundMonitor());
        stopButton.setOnClickListener(v -> stopForegroundMonitor());
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerServiceReceiverIfNeeded();
        syncButtonsWithServiceState();
    }

    @Override
    protected void onStop() {
        unregisterServiceReceiverIfNeeded();
        super.onStop();
    }

    private void bindViews() {
        rootLayout = findViewById(R.id.rootLayout);
        topAppBar = findViewById(R.id.topAppBar);

        statusText = findViewById(R.id.statusText);
        ispText = findViewById(R.id.ispText);
        serverText = findViewById(R.id.serverText);
        pingText = findViewById(R.id.pingText);
        modeText = findViewById(R.id.modeText);
        downloadText = findViewById(R.id.downloadText);
        uploadText = findViewById(R.id.uploadText);
        resultText = findViewById(R.id.resultText);
        logText = findViewById(R.id.logText);

        intervalInput = findViewById(R.id.intervalInput);
        noDownloadCheck = findViewById(R.id.noDownloadCheck);
        noUploadCheck = findViewById(R.id.noUploadCheck);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        peakDownloadText = findViewById(R.id.peakDownloadText);
        peakUploadText = findViewById(R.id.peakUploadText);
        peakServerText = findViewById(R.id.peakServerText);
        peakPingText = findViewById(R.id.peakPingText);
    }

    private void setupToolbar() {
        if (topAppBar != null) {
            setSupportActionBar(topAppBar);
            topAppBar.setTitle("Speedtest Trigger");
        }
    }

    private void applyWindowInsets() {
        if (topAppBar != null) {
            ViewCompat.setOnApplyWindowInsetsListener(topAppBar, (view, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                view.setPadding(
                        view.getPaddingLeft(),
                        systemBars.top,
                        view.getPaddingRight(),
                        view.getPaddingBottom()
                );
                return insets;
            });
        }

        if (rootLayout != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (view, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                view.setPadding(
                        view.getPaddingLeft(),
                        view.getPaddingTop(),
                        view.getPaddingRight(),
                        systemBars.bottom
                );
                return insets;
            });
        }
    }

    @SuppressLint("SetTextI18n")
    private void setupUi() {
        logText.setMovementMethod(new ScrollingMovementMethod());
        intervalInput.setText("30");
        noDownloadCheck.setChecked(false);
        noUploadCheck.setChecked(true);
        updateButtons(false);
        updateModeLabel();
    }

    private void resetFields() {
        setText(statusText, "Idle");
        setText(ispText, "-");
        setText(serverText, "-");
        setText(pingText, "-");
        setText(modeText, getModeLabel());
        setText(downloadText, "-");
        setText(uploadText, "-");
        setText(resultText, "-");
        setText(logText, "");
    }

    private void startForegroundMonitor() {
        Intent intent = new Intent(this, SpeedtestForegroundService.class);
        intent.setAction(SpeedtestForegroundService.ACTION_START);
        intent.putExtra(SpeedtestForegroundService.EXTRA_INTERVAL, getInterval());
        intent.putExtra(SpeedtestForegroundService.EXTRA_NO_DOWNLOAD, noDownloadCheck.isChecked());
        intent.putExtra(SpeedtestForegroundService.EXTRA_NO_UPLOAD, noUploadCheck.isChecked());

        ContextCompat.startForegroundService(this, intent);

        setText(statusText, "Starting...");
        setText(modeText, getModeLabel());
        setText(resultText, "Preparing speed monitor");
        appendLog("Foreground monitor started. Interval: " + getInterval() + "s | Mode: " + getModeLabel());
        updateButtons(true);
    }

    private void stopForegroundMonitor() {
        Intent intent = new Intent(this, SpeedtestForegroundService.class);
        intent.setAction(SpeedtestForegroundService.ACTION_STOP);
        startService(intent);

        setText(statusText, "Stopped");
        setText(resultText, "Stopped");
        appendLog("Process stopped by user.");
        updateButtons(false);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerServiceReceiverIfNeeded() {
        if (receiverRegistered) return;

        IntentFilter filter = new IntentFilter(SpeedtestForegroundService.BROADCAST_UPDATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceUpdateReceiver, filter);
        }

        receiverRegistered = true;
    }

    private void unregisterServiceReceiverIfNeeded() {
        if (!receiverRegistered) return;

        try {
            unregisterReceiver(serviceUpdateReceiver);
        } catch (Exception ignored) {
        }

        receiverRegistered = false;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void syncButtonsWithServiceState() {
        boolean isRunning = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
                .getBoolean(KEY_SERVICE_RUNNING, false);

        updateButtons(isRunning);

        if (!isRunning) {
            setText(statusText, "Stopped");
            setText(resultText, "Stopped");
        }
    }

    private void loadPeakData() {
        float peakDownloadMbps = peakPrefs.getFloat(KEY_PEAK_DOWNLOAD_MBPS, -1f);
        float peakUploadMbps = peakPrefs.getFloat(KEY_PEAK_UPLOAD_MBPS, -1f);
        float peakPingMs = peakPrefs.getFloat(KEY_PEAK_PING_MS, -1f);

        String peakDownloadTextValue = peakPrefs.getString(KEY_PEAK_DOWNLOAD_TEXT, "-");
        String peakUploadTextValue = peakPrefs.getString(KEY_PEAK_UPLOAD_TEXT, "-");
        String peakServerTextValue = peakPrefs.getString(KEY_PEAK_SERVER_TEXT, "-");
        String peakPingTextValue = peakPrefs.getString(KEY_PEAK_PING_TEXT, "-");

        setText(peakDownloadText, peakDownloadMbps >= 0 ? peakDownloadTextValue : "-");
        setText(peakUploadText, peakUploadMbps >= 0 ? peakUploadTextValue : "-");
        setText(peakServerText, peakServerTextValue);
        setText(peakPingText, peakPingMs >= 0 ? peakPingTextValue : "-");
    }

    private void updatePeakData(String serverLabel, String pingTextValue, String downloadTextValue, String uploadTextValue) {
        float currentDownload = extractMbps(downloadTextValue);
        float currentUpload = extractMbps(uploadTextValue);
        float currentPing = extractPing(pingTextValue);

        SharedPreferences.Editor editor = peakPrefs.edit();
        boolean changed = false;

        float savedDownload = peakPrefs.getFloat(KEY_PEAK_DOWNLOAD_MBPS, -1f);
        if (currentDownload >= 0 && currentDownload > savedDownload) {
            editor.putFloat(KEY_PEAK_DOWNLOAD_MBPS, currentDownload);
            editor.putString(KEY_PEAK_DOWNLOAD_TEXT, formatPeakSpeed("Download", currentDownload, serverLabel, currentPing));
            changed = true;
        }

        float savedUpload = peakPrefs.getFloat(KEY_PEAK_UPLOAD_MBPS, -1f);
        if (currentUpload >= 0 && currentUpload > savedUpload) {
            editor.putFloat(KEY_PEAK_UPLOAD_MBPS, currentUpload);
            editor.putString(KEY_PEAK_UPLOAD_TEXT, formatPeakSpeed("Upload", currentUpload, serverLabel, currentPing));
            changed = true;
        }

        float savedPing = peakPrefs.getFloat(KEY_PEAK_PING_MS, -1f);
        if (currentPing >= 0 && (savedPing < 0 || currentPing < savedPing)) {
            editor.putFloat(KEY_PEAK_PING_MS, currentPing);
            editor.putString(KEY_PEAK_PING_TEXT, String.format(Locale.US, "%.2f ms (%s)", currentPing, serverLabel));
            editor.putString(KEY_PEAK_SERVER_TEXT, serverLabel);
            changed = true;
        }

        if (changed) {
            editor.apply();
            loadPeakData();
        }
    }

    private float extractMbps(String value) {
        if (value == null) return -1f;
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*Mbps", Pattern.CASE_INSENSITIVE).matcher(value);
        if (matcher.find()) {
            try {
                return Float.parseFloat(Objects.requireNonNull(matcher.group(1)));
            } catch (Exception ignored) {
            }
        }
        return -1f;
    }

    private float extractPing(String value) {
        if (value == null) return -1f;
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(value);
        if (matcher.find()) {
            try {
                return Float.parseFloat(Objects.requireNonNull(matcher.group(1)));
            } catch (Exception ignored) {
            }
        }
        return -1f;
    }

    private String formatPeakSpeed(String label, float mbps, String serverLabel, float pingMs) {
        if (pingMs >= 0) {
            return String.format(Locale.US, "%s: %.2f Mbps | %s | %.2f ms", label, mbps, serverLabel, pingMs);
        }
        return String.format(Locale.US, "%s: %.2f Mbps | %s", label, mbps, serverLabel);
    }

    private int getInterval() {
        try {
            int value = Integer.parseInt(intervalInput.getText().toString().trim());
            return Math.max(value, 5);
        } catch (Exception e) {
            return 30;
        }
    }

    private String getModeLabel() {
        boolean noDownload = noDownloadCheck.isChecked();
        boolean noUpload = noUploadCheck.isChecked();

        if (noDownload && noUpload) return "Checker mode";
        if (noDownload) return "Upload only";
        if (noUpload) return "Download only";
        return "Download + Upload";
    }

    private void updateModeLabel() {
        setText(modeText, getModeLabel());
    }

    private void setText(TextView view, String value) {
        if (view != null) {
            view.setText(value != null ? value : "-");
        }
    }

    private void updateButtons(boolean isRunning) {
        startButton.setEnabled(!isRunning);
        stopButton.setEnabled(isRunning);
        intervalInput.setEnabled(!isRunning);
        noDownloadCheck.setEnabled(!isRunning);
        noUploadCheck.setEnabled(!isRunning);
    }

    private void appendLog(String message) {
        if (message == null || message.trim().isEmpty()) return;

        logText.append(message + "\n");

        View parent = (View) logText.getParent();
        if (parent instanceof ScrollView) {
            parent.post(() -> ((ScrollView) parent).fullScroll(View.FOCUS_DOWN));
        }
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value;
    }

    @Override
    protected void onDestroy() {
        unregisterServiceReceiverIfNeeded();
        super.onDestroy();
    }
}