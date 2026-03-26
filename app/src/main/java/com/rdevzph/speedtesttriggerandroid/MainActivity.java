package com.rdevzph.speedtesttriggerandroid;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.SharedPreferences;
import java.util.Locale;
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean running = false;

    private SharedPreferences peakPrefs;

    private TextView peakDownloadText;
    private TextView peakUploadText;
    private TextView peakServerText;
    private TextView peakPingText;

    private static final String PREFS_NAME = "speedtest_peaks";
    private static final String KEY_PEAK_DOWNLOAD_MBPS = "peak_download_mbps";
    private static final String KEY_PEAK_DOWNLOAD_TEXT = "peak_download_text";
    private static final String KEY_PEAK_UPLOAD_MBPS = "peak_upload_mbps";
    private static final String KEY_PEAK_UPLOAD_TEXT = "peak_upload_text";
    private static final String KEY_PEAK_SERVER_TEXT = "peak_server_text";
    private static final String KEY_PEAK_PING_MS = "peak_ping_ms";
    private static final String KEY_PEAK_PING_TEXT = "peak_ping_text";

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

        startButton.setOnClickListener(v -> startLoop());
        stopButton.setOnClickListener(v -> stopLoop());
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

    private void startLoop() {
        if (running) return;

        running = true;
        resetFields();
        appendLog("Loop started. Interval: " + getInterval() + "s | Mode: " + getModeLabel());
        updateButtons(true);

        executor.execute(() -> {
            while (running) {
                String loopResult = runSpeedtestOnce();
                if (!running) break;

                int waitTime = "retry_soon".equals(loopResult) ? 5 : getInterval();

                for (int remaining = waitTime; remaining > 0 && running; remaining--) {
                    final int countdown = remaining;
                    mainHandler.post(() -> setText(statusText, "Waiting " + countdown + "s"));
                    sleepOneSecond();
                }
            }

            mainHandler.post(() -> {
                if (!"Stopped".contentEquals(statusText.getText())) {
                    setText(statusText, "Stopped");
                }
                updateButtons(false);
            });
        });
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
            mainHandler.post(this::loadPeakData);
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

    private void stopLoop() {
        running = false;
        setText(statusText, "Stopped");
        setText(resultText, "Stopped");
        appendLog("Process stopped by user.");
        updateButtons(false);
    }

    private String runSpeedtestOnce() {
        mainHandler.post(() -> {
            setText(statusText, "Checking ISP and best server");
            setText(modeText, getModeLabel());
            setText(downloadText, "-");
            setText(uploadText, "-");
            setText(resultText, "-");
        });

        try {
            Python py = Python.getInstance();
            PyObject module = py.getModule("speedtest_bridge");
            PyObject result = module.callAttr(
                    "run_speedtest",
                    noDownloadCheck.isChecked(),
                    noUploadCheck.isChecked()
            );

            JSONObject json = new JSONObject(result.toString());
            boolean ok = json.optBoolean("ok", false);

            if (!ok) {
                String error = json.optString("error", "Unknown error");
                appendLog("Error: " + error);

                mainHandler.post(() -> {
                    setText(statusText, "Retrying");
                    setText(resultText, "Retrying soon");
                });

                return "retry_soon";
            }

            String isp = json.optString("isp", "-");
            String ip = json.optString("ip", "-");
            String sponsor = json.optString("sponsor", "-");
            String server = json.optString("server", "-");
            String ping = json.optString("ping", "-");
            String mode = json.optString("mode", getModeLabel());
            String download = json.optString("download_text", "Skipped");
            String upload = json.optString("upload_text", "Skipped");
            String resultTextValue = json.optString("result_text", "Updated");

            mainHandler.post(() -> {
                setText(statusText, "Updated");
                setText(ispText, isp + " (" + ip + ")");
                setText(serverText, sponsor + " - " + server);
                setText(pingText, ping + " ms");
                setText(modeText, mode);
                setText(downloadText, download);
                setText(uploadText, upload);
                setText(resultText, resultTextValue);
            });

            appendLog("ISP: " + isp + " (" + ip + ")");
            appendLog("Best Server: " + sponsor + " - " + server);
            appendLog("Ping: " + ping + " ms");
            appendLog("Result: " + resultTextValue);
            String serverLabel = sponsor + " - " + server;
            String pingLabel = ping + " ms";
            updatePeakData(serverLabel, pingLabel, download, upload);

            return "ok";

        } catch (PyException pyException) {
            appendLog("Python error: " + pyException.getMessage());

            mainHandler.post(() -> {
                setText(statusText, "Retrying");
                setText(resultText, "Retrying soon");
            });

            return "retry_soon";

        } catch (Exception exception) {
            appendLog("App error: " + exception.getMessage());

            mainHandler.post(() -> {
                setText(statusText, "Retrying");
                setText(resultText, "Retrying soon");
            });

            return "retry_soon";
        }
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
        view.setText(value);
    }

    private void updateButtons(boolean isRunning) {
        startButton.setEnabled(!isRunning);
        stopButton.setEnabled(isRunning);
        intervalInput.setEnabled(!isRunning);
        noDownloadCheck.setEnabled(!isRunning);
        noUploadCheck.setEnabled(!isRunning);
    }

    private void appendLog(String message) {
        mainHandler.post(() -> {
            logText.append(message + "\n");

            View parent = (View) logText.getParent();
            if (parent instanceof ScrollView) {
                parent.post(() -> ((ScrollView) parent).fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void sleepOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        executor.shutdownNow();
    }
}