package com.codex.locationstartup;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import org.json.JSONException;

public final class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 1;
    private static final String TAG = "LocationStartupProbe";
    private static final String CAPTURE_POLICY = "com.codex.locationstartup.CAPTURE_POLICY";
    private static final String CAPTURE_ALTITUDE_REFERENCE = "com.codex.locationstartup.CAPTURE_ALTITUDE_REFERENCE";
    private static final String RESTART_RECORDING = "com.codex.locationstartup.RESTART_RECORDING";
    private final long owner = SystemClock.elapsedRealtimeNanos();
    private final Runnable observer = this::render;
    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            StartupRecorder.Stamp received = StartupRecorder.Stamp.now();
            String action = intent.getAction();
            if (!CAPTURE_POLICY.equals(action) && !RESTART_RECORDING.equals(action)
                    && !CAPTURE_ALTITUDE_REFERENCE.equals(action)) return;
            if (!visible || !hasWindowFocus()) {
                reply(RESULT_CANCELED, "not_foreground");
                return;
            }
            try {
                if (CAPTURE_POLICY.equals(action)) {
                    String file = PolicyCapture.write(MainActivity.this, received);
                    notice = "权限状态已记录 · " + file;
                    render();
                    reply(RESULT_OK, file);
                } else if (CAPTURE_ALTITUDE_REFERENCE.equals(action)) {
                    String file = AltitudeReference.enqueue(getApplicationContext(), received,
                        intent.getStringExtra("lat"), intent.getStringExtra("lon"), intent.getStringExtra("alt"),
                        intent.getStringExtra("vertical_accuracy"), intent.hasExtra("vertical_accuracy"));
                    if (file == null) {
                        reply(RESULT_CANCELED, "altitude_reference_busy");
                    } else {
                        notice = "已接受高程模型诊断 · " + file;
                        render();
                        reply(RESULT_OK, file);
                    }
                } else if (recorder.hasPermission()) {
                    Bundle extras = intent.getExtras();
                    boolean includeNmea = booleanExtra(extras, "include_nmea", true);
                    boolean includeGnssStatus = booleanExtra(extras, "include_gnss_status", false);
                    boolean includeLateGnssStatus = booleanExtra(extras, "include_late_gnss_status", false);
                    if (includeLateGnssStatus && !includeGnssStatus) {
                        throw new IllegalArgumentException("include_late_gnss_status requires include_gnss_status");
                    }
                    restartRecording("control_restart", received, includeNmea, includeGnssStatus, includeLateGnssStatus);
                    reply(RESULT_OK, "restart_requested");
                } else {
                    reply(RESULT_CANCELED, "location_permission_missing");
                }
            } catch (IOException | JSONException | RuntimeException failure) {
                notice = "诊断命令失败 · " + failure.getClass().getName();
                render();
                reply(RESULT_CANCELED, failure.getClass().getName());
            }
        }

        private void reply(int result, String detail) {
            if (isOrderedBroadcast()) {
                setResultCode(result);
                setResultData(detail);
            }
            Log.i(TAG, "Control result " + result + " " + detail);
        }
    };

    @SuppressWarnings("deprecation")
    private static boolean booleanExtra(Bundle extras, String key, boolean defaultValue) {
        if (extras == null || !extras.containsKey(key)) return defaultValue;
        if (extras.get(key) instanceof Boolean include) return include;
        throw new IllegalArgumentException(key + " must be a Boolean extra");
    }

    private StartupRecorder recorder;
    private boolean autoRecord;
    private boolean visible;
    private boolean controlRegistered;
    private String notice = "准备阶段。请先授予定位权限，再重新冷启动进行正式记录。";
    private TextView status;
    private TextView summary;
    private TextView reportPath;
    private TextView noticeView;
    private Button permissionButton;
    private Button restartButton;
    private Button stopButton;
    private Button copyButton;

    @Override protected void onCreate(Bundle saved) {
        StartupRecorder.Stamp entered = StartupRecorder.Stamp.now();
        super.onCreate(saved);
        recorder = ((StartupApplication) getApplication()).recorder();
        autoRecord = recorder.hasPermission();
        if (autoRecord) {
            recorder.ensureActiveSession(owner, "activity_on_create", "warm_activity_create", entered);
            notice = "";
        }
        createUi();
        render();
    }

    @Override protected void onStart() {
        StartupRecorder.Stamp entered = StartupRecorder.Stamp.now();
        super.onStart();
        visible = true;
        if (autoRecord) recorder.ensureActiveSession(owner, "activity_start", "warm_foreground", entered);
        recorder.observe(observer);
        registerControlReceiver();
    }

    @Override protected void onResume() {
        StartupRecorder.Stamp entered = StartupRecorder.Stamp.now();
        super.onResume();
        recorder.mark(owner, "activity_resume", entered, null, true);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        StartupRecorder.Stamp entered = StartupRecorder.Stamp.now();
        super.onWindowFocusChanged(focused);
        if (recorder != null) recorder.mark(owner, "activity_window_focus", entered, focused, focused);
    }

    @Override protected void onPause() {
        recorder.mark(owner, "activity_pause", StartupRecorder.Stamp.now(), null, true);
        super.onPause();
    }

    @Override protected void onStop() {
        visible = false;
        if (controlRegistered) {
            controlRegistered = false;
            try {
                unregisterReceiver(controlReceiver);
            } catch (IllegalArgumentException failure) {
                Log.w(TAG, "Control receiver cleanup " + failure.getClass().getName());
            }
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recorder.unobserve(observer);
        recorder.stop(owner, "left_foreground", "activity_stop", StartupRecorder.Stamp.now());
        super.onStop();
    }

    private void registerControlReceiver() {
        IntentFilter filter = new IntentFilter(CAPTURE_POLICY);
        filter.addAction(RESTART_RECORDING);
        filter.addAction(CAPTURE_ALTITUDE_REFERENCE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(controlReceiver, filter, Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(controlReceiver, filter, Manifest.permission.DUMP, null);
            }
            controlRegistered = true;
        } catch (RuntimeException failure) {
            notice = "诊断命令登记失败 · " + failure.getClass().getName();
            Log.w(TAG, "Control receiver registration " + failure.getClass().getName());
            render();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == LOCATION_PERMISSION_REQUEST) {
            notice = recorder.hasPermission() ? "定位权限已授予。请结束此应用后重新启动，正式记录冷启动。"
                : "当前未授予定位权限。准备阶段没有采样。";
            render();
        }
    }

    private void createUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(32), dp(20), dp(24));
        scroll.addView(content);
        TextView title = addText(content, "定位启动诊断", 27);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status = addText(content, "", 18);
        summary = addText(content, "", 15);
        reportPath = addText(content, "", 13);
        reportPath.setTextIsSelectable(true);
        TextView pkg = addText(content, "包名 " + getPackageName() + "\nThanox GPSApp\n" + getPackageName() + " +", 15);
        pkg.setTypeface(Typeface.MONOSPACE);
        pkg.setTextIsSelectable(true);
        permissionButton = addButton(content, "授予定位权限", () -> requestPermissions(new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST));
        restartButton = addButton(content, "重新记录 30 秒",
            () -> restartRecording("manual", StartupRecorder.Stamp.now(), true, false, false));
        stopButton = addButton(content, "停止记录", () -> recorder.stop(owner, "manual_stop", null, StartupRecorder.Stamp.now()));
        copyButton = addButton(content, "复制当前报告", this::copyReport);
        noticeView = addText(content, "", 14);
        addText(content, "未收到定位不代表无泄漏，mock 字段不是判定真实的充分依据。\n报告含定位坐标，请按需分享。最多保留最近 20 份报告。", 14);
        setContentView(scroll);
    }

    private void restartRecording(String trigger, StartupRecorder.Stamp entry, boolean includeNmea,
                                  boolean includeGnssStatus, boolean includeLateGnssStatus) {
        recorder.begin(trigger, owner, entry, includeNmea, includeGnssStatus, includeLateGnssStatus);
        recorder.mark(owner, "activity_window_focus", entry, hasWindowFocus(), false);
        notice = "control_restart".equals(trigger) ? "已请求重新记录，时间窗口从接收诊断命令开始。"
            : "手动记录，时间窗口从本次点击开始。";
        render();
    }

    private TextView addText(LinearLayout parent, String text, int size) {
        TextView result = new TextView(this);
        result.setText(text);
        result.setTextSize(size);
        result.setPadding(0, dp(7), 0, dp(7));
        parent.addView(result);
        return result;
    }

    private Button addButton(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void render() {
        if (status == null) return;
        StartupRecorder.ViewState value = recorder.view();
        if (visible && value.running()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean granted = recorder.hasPermission();
        boolean prepared = value.file() != null || value.reason() != null;
        if (!prepared) {
            status.setText(granted ? "等待记录" : "准备阶段");
            summary.setText(value.coverage());
        } else {
            status.setText((value.running() ? "记录中" : "记录结束") + " · " + value.count() + " 条事件");
            String sdkState = value.locationEnabled() == null ? "未知" : value.locationEnabled() == 1 ? "开启" : "关闭";
            String firstCallback = value.firstCallbackMs() < 0 ? "未观察到" : value.firstCallbackMs() + " ms";
            String focus = value.firstFocusDelayMs() < 0 ? "未观察到页面获得焦点"
                : "首次页面焦点 · 启动锚点后 " + value.firstFocusDelayMs() + " ms\n"
                    + windows(value.focusWindows()[0], value.focusWindows()[1], value.focusWindows()[2]);
            summary.setText("定位开关（SDK 读数）" + sdkState + " · 异常 " + value.errors() + "\n\n"
                + sessionOrigin(value.trigger()) + "\n"
                + windows(value.cached(), value.callbacks(), value.currentLocations()) + "\n首个普通监听回调 "
                + firstCallback + "\n\n" + focus + "\n\n" + value.coverage() + "\n\n首条定位\n"
                + fallback(value.first()) + "\n最新定位\n" + fallback(value.latest()));
        }
        reportPath.setText(value.file() == null ? "" : value.file());
        String details = notice;
        if (value.fileError() != null) details += "\n报告文件异常 · " + value.fileError();
        if (value.reason() != null) details += "\n结束原因 " + value.reason();
        noticeView.setText(details.trim());
        permissionButton.setEnabled(!granted);
        restartButton.setEnabled(granted);
        stopButton.setEnabled(value.running());
        copyButton.setText(value.complete() ? "复制当前报告" : "复制未结束快照");
        copyButton.setEnabled(value.file() != null && value.count() > 0);
    }

    private static String sessionOrigin(String trigger) {
        return switch (trigger) {
            case "application_attach" -> "冷启动 · 从 Application attach 入口计时";
            case "warm_activity_create" -> "暖启动重建页面 · 从 Activity.onCreate 入口计时";
            case "warm_foreground" -> "暖启动回到前台 · 从 Activity.onStart 入口计时";
            case "control_restart" -> "诊断命令重新记录 · 从接收命令计时";
            default -> "手动记录 · 从点击时间计时";
        };
    }

    private static String windows(int[] cache, int[] callback, int[] current) {
        String[] labels = {"0–200 ms", "200 ms–2 s", "2 s 至会话结束"};
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            if (i != 0) text.append('\n');
            text.append(labels[i]).append(" · 缓存 ").append(cache[i]).append(" · 监听 ")
                .append(callback[i]).append(" · 单次 ").append(current[i]);
        }
        return text.toString();
    }

    private static String fallback(String value) { return value == null ? "尚未观察到定位" : value; }

    private void copyReport() {
        recorder.copy(copy -> {
            if (!visible) return;
            if (copy.error() != null) {
                notice = "复制失败 · copy_report · " + copy.error() + "。报告仍保存在应用文件目录。";
                render();
                return;
            }
            try {
                ClipData clip = ClipData.newPlainText(copy.label(), copy.data());
                if (Build.VERSION.SDK_INT >= 33) {
                    PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                    clip.getDescription().setExtras(extras);
                }
                getSystemService(ClipboardManager.class).setPrimaryClip(clip);
                notice = copy.complete() ? "当前报告已复制。" : "已复制未结束快照。完整报告需等记录结束。";
            } catch (RuntimeException failure) {
                notice = "复制失败 · copy_report · " + failure.getClass().getName() + "。报告仍保存在应用文件目录。";
            }
            render();
        });
    }
}
