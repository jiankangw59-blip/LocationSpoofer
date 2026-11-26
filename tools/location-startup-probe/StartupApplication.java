package com.codex.locationstartup;

import android.app.Application;
import android.content.Context;

public final class StartupApplication extends Application {
    private StartupRecorder recorder;

    @Override protected void attachBaseContext(Context base) {
        StartupRecorder.Stamp entered = StartupRecorder.Stamp.now();
        super.attachBaseContext(base);
        recorder = new StartupRecorder(this, entered, StartupRecorder.Stamp.now());
        recorder.begin("application_attach", 0, entered);
    }

    @Override public void onCreate() {
        StartupRecorder.Stamp entered = StartupRecorder.Stamp.now();
        super.onCreate();
        recorder.mark(0, "application_on_create", entered, null, true);
    }

    StartupRecorder recorder() { return recorder; }
}
