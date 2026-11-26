package com.codex.locationstartup;

import android.Manifest;
import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.json.JSONException;
import org.json.JSONObject;

public final class ShellLocationReferenceMain {
    private static final int SHELL_UID = 2000;
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final String GPS = LocationManager.GPS_PROVIDER;
    private static final long REQUEST_WINDOW_NS = TimeUnit.SECONDS.toNanos(30);

    private ShellLocationReferenceMain() {}

    public static void main(String[] args) {
        Stamp started = Stamp.now();
        Report report = new Report(started);
        boolean request = args.length == 1 && "--request".equals(args[0]);
        String phase = "arguments";
        try {
            report.emit("reference_start", started, json("mode", request ? "request" : "cache_only",
                "adds_gnss_demand", request, "uid", Process.myUid(), "pid", Process.myPid(),
                "sdk_int", Build.VERSION.SDK_INT, "build_fingerprint", Build.FINGERPRINT));
            if (args.length != 0 && !request) throw new IllegalArgumentException("Expected --request or no arguments");
            phase = "shell_identity";
            Context shell = shellContext(report);
            phase = "gps_cache";
            LocationManager locations = shell.getSystemService(LocationManager.class);
            if (locations == null) throw new IllegalStateException("Location service is unavailable");
            boolean enabled = call(report, "isLocationEnabled", locations::isLocationEnabled).value;
            boolean gpsEnabled = call(report, "isProviderEnabled", () -> locations.isProviderEnabled(GPS)).value;
            report.emit("reference_provider_state", Stamp.now(), json("requested_provider", GPS,
                "location_enabled", enabled, "provider_enabled", gpsEnabled));
            Read<Location> cached = call(report, "getLastKnownLocation", () -> locations.getLastKnownLocation(GPS));
            report.location("cache", cached.returned, cached.value, Long.MAX_VALUE);
            if (request) {
                phase = "gps_request";
                requestGps(report, locations);
            }
        } catch (Exception | LinkageError failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (!(failure instanceof ReadFailed)) report.error(phase, failure);
        } finally {
            report.end();
        }
        System.exit(report.failed() ? 1 : 0);
    }

    private static Context shellContext(Report report)
            throws ReflectiveOperationException, PackageManager.NameNotFoundException {
        if (Process.myUid() != SHELL_UID) throw new SecurityException("Shell UID required");
        if (Looper.myLooper() == null) {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            else Looper.prepare();
        }
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context shell = system.createPackageContext(SHELL_PACKAGE, 0);
        String[] packages = shell.getPackageManager().getPackagesForUid(SHELL_UID);
        int packageUid = shell.getApplicationInfo().uid;
        AttributionSource attribution = shell.getAttributionSource();
        boolean uidOwnsPackage = packages != null && Arrays.asList(packages).contains(SHELL_PACKAGE);
        report.emit("reference_identity", Stamp.now(), json("package_name", shell.getPackageName(),
            "package_uid", packageUid, "uid_owns_package", uidOwnsPackage,
            "op_package_name", shell.getOpPackageName(),
            "attribution_package_name", attribution.getPackageName(), "attribution_uid", attribution.getUid(),
            "fine_permission", shell.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            "coarse_permission", shell.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)));
        if (!SHELL_PACKAGE.equals(shell.getPackageName()) || packageUid != SHELL_UID
                || !uidOwnsPackage || attribution.getUid() != SHELL_UID) {
            throw new SecurityException("Shell package ownership could not be confirmed");
        }
        return shell;
    }

    private static void requestGps(Report report, LocationManager locations) throws InterruptedException {
        Stamp opened = Stamp.now();
        long deadline = opened.elapsedNs + REQUEST_WINDOW_NS;
        report.emit("reference_request_window", opened, json("deadline_elapsed_realtime_ns", deadline,
            "requested_provider", GPS));
        CancellationSignal cancellation = new CancellationSignal();
        Executor callbacks = Runnable::run;
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                report.location("callback", Stamp.now(), location, deadline);
            }

            @Override public void onLocationChanged(List<Location> locations) {
                Stamp received = Stamp.now();
                for (Location location : locations) report.location("callback", received, location, deadline);
            }
        };
        try {
            call(report, "requestLocationUpdates", () -> {
                LocationRequest request = new LocationRequest.Builder(1000L)
                    .setDurationMillis(remainingMillis(deadline)).setMaxUpdates(30).build();
                locations.requestLocationUpdates(GPS, request, callbacks, listener);
                return null;
            });
            call(report, "getCurrentLocation", () -> {
                LocationRequest request = new LocationRequest.Builder(0L)
                    .setDurationMillis(remainingMillis(deadline)).build();
                locations.getCurrentLocation(GPS, request, cancellation, callbacks,
                    location -> report.location("current_location", Stamp.now(), location, deadline));
                return null;
            });
            long remaining = deadline - SystemClock.elapsedRealtimeNanos();
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
        } finally {
            cleanup(report, "cancelCurrentLocation", cancellation::cancel);
            cleanup(report, "removeUpdates", () -> locations.removeUpdates(listener));
        }
    }

    private static long remainingMillis(long deadline) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - SystemClock.elapsedRealtimeNanos());
        if (remaining <= 0) throw new IllegalStateException("Request window elapsed");
        return remaining;
    }

    private static void cleanup(Report report, String operation, Runnable action) {
        try {
            call(report, operation, () -> { action.run(); return null; });
        } catch (ReadFailed ignored) {
            // The SDK error was emitted. The other cleanup call should still run.
        }
    }

    private static <T> Read<T> call(Report report, String operation, Supplier<T> action) {
        Stamp begin = Stamp.now();
        T result;
        try {
            result = action.get();
        } catch (RuntimeException failure) {
            report.sdkCall(operation, begin, Stamp.now(), failure);
            throw new ReadFailed(failure);
        }
        Stamp returned = Stamp.now();
        report.sdkCall(operation, begin, returned, null);
        return new Read<>(result, returned);
    }

    private static final class ReadFailed extends RuntimeException {
        ReadFailed(RuntimeException cause) { super(cause); }
    }

    private record Stamp(long wallMs, long elapsedNs) {
        static Stamp now() { return new Stamp(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()); }
    }

    private record Read<T>(T value, Stamp returned) {}

    private static JSONObject json(Object... values) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < values.length; i += 2) put(result, (String) values[i], values[i + 1]);
        return result;
    }

    private static void put(JSONObject object, String name, Object value) {
        try {
            object.put(name, value == null ? JSONObject.NULL : value);
        } catch (JSONException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String errorClass(Throwable failure) {
        if (failure instanceof InvocationTargetException target && target.getCause() != null) {
            return target.getCause().getClass().getName();
        }
        return failure.getClass().getName();
    }

    private static final class Report {
        private final PrintWriter output = new PrintWriter(new OutputStreamWriter(
            new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8), true);
        private final String sessionId;
        private int sequence;
        private int locationCount;
        private int errorCount;
        private boolean ended;

        Report(Stamp started) {
            sessionId = "shell-" + started.wallMs + "-" + Process.myPid() + "-" + started.elapsedNs;
        }

        synchronized void emit(String event, Stamp received, JSONObject row) {
            if (ended) return;
            put(row, "event", event);
            put(row, "sequence", ++sequence);
            put(row, "session_id", sessionId);
            put(row, "received_wall_ms", received.wallMs);
            put(row, "received_elapsed_realtime_ns", received.elapsedNs);
            Stamp serialization = Stamp.now();
            put(row, "serialization_wall_ms", serialization.wallMs);
            put(row, "serialization_elapsed_realtime_ns", serialization.elapsedNs);
            output.println(row.toString());
        }

        synchronized void sdkCall(String operation, Stamp begin, Stamp end, RuntimeException failure) {
            if (failure != null) errorCount++;
            emit("sdk_call", end, json("operation", operation, "requested_provider", GPS,
                "query_begin_wall_ms", begin.wallMs, "query_begin_elapsed_realtime_ns", begin.elapsedNs,
                "query_end_wall_ms", end.wallMs, "query_end_elapsed_realtime_ns", end.elapsedNs,
                "result", failure == null ? "returned" : "error",
                "exception_class", failure == null ? null : errorClass(failure)));
        }

        synchronized void location(String origin, Stamp received, Location value, long deadline) {
            if (ended) return;
            JSONObject row = json("origin", origin, "requested_provider", GPS,
                "after_deadline", received.elapsedNs >= deadline);
            if (value == null) {
                emit("location_absent", received, row);
                return;
            }
            try {
                JSONObject fields = LocationFields.snapshot(value);
                for (Iterator<String> names = fields.keys(); names.hasNext();) {
                    String name = names.next();
                    put(row, name, fields.opt(name));
                }
                emit("location", received, row);
                locationCount++;
            } catch (RuntimeException failure) {
                error(origin, failure, received);
            }
        }

        void error(String phase, Throwable failure) { error(phase, failure, Stamp.now()); }

        private synchronized void error(String phase, Throwable failure, Stamp received) {
            errorCount++;
            emit("reference_error", received, json("phase", phase, "exception_class", errorClass(failure)));
        }

        synchronized void end() {
            emit("reference_end", Stamp.now(), json("location_count", locationCount, "error_count", errorCount,
                "status", failed() ? "error" : locationCount == 0 ? "no_fix" : "ok"));
            ended = true;
        }

        synchronized boolean failed() {
            return errorCount != 0 || output.checkError();
        }
    }
}
