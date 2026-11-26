package com.codex.locationstartup;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.IntSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class PolicyCapture {
    private static final int MAX_REPORTS = 20;

    private PolicyCapture() {}

    static String write(Context context, StartupRecorder.Stamp received) throws IOException, JSONException {
        int pid = Process.myPid();
        int uid = Process.myUid();
        String pkg = context.getPackageName();
        JSONObject report = new JSONObject();
        report.put("schema_version", 1);
        report.put("pid", pid);
        report.put("uid", uid);
        putTime(report, "received", received);

        JSONArray calls = new JSONArray();
        for (String permission : new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}) {
            calls.put(capture("checkSelfPermission", "permission", permission,
                () -> context.checkSelfPermission(permission)));
        }
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        for (String op : new String[]{AppOpsManager.OPSTR_FINE_LOCATION, AppOpsManager.OPSTR_COARSE_LOCATION}) {
            calls.put(capture("unsafeCheckOpNoThrow", "op", op,
                () -> appOps.unsafeCheckOpNoThrow(op, uid, pkg)));
            calls.put(capture("unsafeCheckOpRawNoThrow", "op", op,
                () -> appOps.unsafeCheckOpRawNoThrow(op, uid, pkg)));
        }
        report.put("calls", calls);

        File directory = new File(context.getFilesDir(), "location-startup");
        Files.createDirectories(directory.toPath());
        File[] reports = directory.listFiles((parent, name) -> name.matches("policy-[0-9]+-[0-9]+-[0-9]+\\.json"));
        if (reports == null) throw new IOException("Cannot list policy reports");
        Arrays.sort(reports, Comparator.comparingLong(File::lastModified).thenComparing(File::getName).reversed());
        for (int i = MAX_REPORTS - 1; i < reports.length; i++) Files.delete(reports[i].toPath());

        String filename = "policy-" + received.wallMs() + "-" + received.elapsedNs() + "-" + pid + ".json";
        File temporary = new File(directory, "policy-writing.tmp");
        Files.write(temporary.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), new File(directory, filename).toPath(), StandardCopyOption.ATOMIC_MOVE);
        return filename;
    }

    private static JSONObject capture(String method, String argument, String target, IntSupplier action)
            throws JSONException {
        StartupRecorder.Stamp begin = StartupRecorder.Stamp.now();
        Integer result = null;
        String error = null;
        try {
            result = action.getAsInt();
        } catch (RuntimeException failure) {
            error = failure.getClass().getName();
        }
        StartupRecorder.Stamp end = StartupRecorder.Stamp.now();

        JSONObject call = new JSONObject();
        call.put("method", method);
        call.put(argument, target);
        putTime(call, "begin", begin);
        putTime(call, "end", end);
        call.put("result", result == null ? JSONObject.NULL : result);
        call.put("exception_class", error == null ? JSONObject.NULL : error);
        return call;
    }

    private static void putTime(JSONObject row, String prefix, StartupRecorder.Stamp stamp) throws JSONException {
        row.put(prefix + "_wall_time_ms", stamp.wallMs());
        row.put(prefix + "_elapsed_realtime_ns", stamp.elapsedNs());
    }
}
