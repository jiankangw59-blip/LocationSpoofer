package com.codex.locationstartup;

import android.Manifest;
import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.location.OnNmeaMessageListener;
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
    private static final String GPS = LocationManager.GPS_PROVIDER;
    private static final int DEFAULT_DURATION_MS = 30_000;
    private static final int MAX_DURATION_MS = 180_000;
    private static final int NMEA_SENTENCES_PER_90_SECONDS = 4_096;
    private static final int BASE_STATUS_FRAMES = 180;
    private static final int MAX_NMEA_CHARS = 4_096;
    private static final int MAX_SATELLITES_PER_FRAME = GnssStatusSnapshot.MAX_SATELLITES_PER_FRAME;
    private static final int MAX_GNSS_LIFECYCLE_EVENTS = 64;

    private ShellLocationReferenceMain() {}

    public static void main(String[] args) {
        Stamp started = Stamp.now();
        Options options = options(args);
        int durationMs = options.durationMs;
        Identity identity = options.identity;
        boolean request = durationMs > 0;
        Report report = new Report(started, identity, request ? (durationMs + 999) / 1000 : 0);
        String phase = "arguments";
        try {
            report.emit("reference_start", started, json("mode", durationMs < 0 ? "invalid" : request ? "request" : "cache_only",
                "duration_ms", durationMs < 0 ? null : durationMs, "capture_limits", report.limits(),
                "identity", identity.label, "identity_scope", identity.scope, "required_uid", identity.uid,
                "adds_gnss_demand", request, "uid", Process.myUid(), "pid", Process.myPid(),
                "sdk_int", Build.VERSION.SDK_INT, "build_fingerprint", Build.FINGERPRINT));
            if (durationMs < 0) throw new IllegalArgumentException(
                "Expected [--request [--duration-ms 30000..180000]] [--identity system]");
            phase = "reference_identity";
            Context context = referenceContext(report, identity);
            phase = "gps_cache";
            LocationManager locations = context.getSystemService(LocationManager.class);
            if (locations == null) throw new IllegalStateException("Location service is unavailable");
            boolean enabled = call(report, "isLocationEnabled", locations::isLocationEnabled).value;
            boolean gpsEnabled = call(report, "isProviderEnabled", () -> locations.isProviderEnabled(GPS)).value;
            report.emit("reference_provider_state", Stamp.now(), json("requested_provider", GPS,
                "location_enabled", enabled, "provider_enabled", gpsEnabled));
            Read<Location> cached = call(report, "getLastKnownLocation", () -> locations.getLastKnownLocation(GPS));
            report.location("cache", cached.returned, cached.value, Long.MAX_VALUE);
            if (request) {
                phase = "gps_request";
                requestGps(report, locations, durationMs);
            }
        } catch (Exception | LinkageError failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (!(failure instanceof ReadFailed)) report.error(phase, failure);
        } finally {
            report.end();
        }
        System.exit(report.failed() ? 1 : 0);
    }

    private enum Identity {
        SHELL("shell", "adb_shell_uid_client", 2000, "com.android.shell"),
        SYSTEM("system", "independent_system_uid_client", 1000, "android");

        final String label;
        final String scope;
        final int uid;
        final String packageName;

        Identity(String label, String scope, int uid, String packageName) {
            this.label = label;
            this.scope = scope;
            this.uid = uid;
            this.packageName = packageName;
        }
    }

    private record Options(Identity identity, int durationMs) {}

    private static Options options(String[] args) {
        int end = args.length;
        if (end >= 2 && "--identity".equals(args[end - 2]) && "system".equals(args[end - 1])) {
            return new Options(Identity.SYSTEM, duration(Arrays.copyOf(args, end - 2)));
        }
        return new Options(Identity.SHELL, duration(args));
    }

    private static int duration(String[] args) {
        if (args.length == 0) return 0;
        if (!"--request".equals(args[0])) return -1;
        if (args.length == 1) return DEFAULT_DURATION_MS;
        if (args.length != 3 || !"--duration-ms".equals(args[1]) || !args[2].matches("[1-9][0-9]{4,5}")) return -1;
        int duration = Integer.parseInt(args[2]);
        return duration >= DEFAULT_DURATION_MS && duration <= MAX_DURATION_MS ? duration : -1;
    }

    private static Context referenceContext(Report report, Identity identity)
            throws ReflectiveOperationException, PackageManager.NameNotFoundException {
        if (Process.myUid() != identity.uid) throw new SecurityException("Requested UID required");
        if (Looper.myLooper() == null) {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            else Looper.prepare();
        }
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context context = identity == Identity.SYSTEM ? system : system.createPackageContext(identity.packageName, 0);
        PackageManager packageManager = context.getPackageManager();
        String[] packages = packageManager.getPackagesForUid(identity.uid);
        int packageUid = packageManager.getPackageUid(identity.packageName, 0);
        int contextApplicationUid = context.getApplicationInfo().uid;
        AttributionSource attribution = context.getAttributionSource();
        boolean uidOwnsPackage = packages != null && Arrays.asList(packages).contains(identity.packageName);
        report.emit("reference_identity", Stamp.now(), json("identity", identity.label, "identity_scope", identity.scope,
            "uid", Process.myUid(), "pid", Process.myPid(), "package_name", context.getPackageName(),
            "package_uid", packageUid, "package_uid_source", "package_manager_get_package_uid",
            "context_application_uid", contextApplicationUid, "uid_owns_package", uidOwnsPackage,
            "op_package_name", context.getOpPackageName(),
            "attribution_package_name", attribution.getPackageName(), "attribution_uid", attribution.getUid(),
            "fine_permission", context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            "coarse_permission", context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)));
        if (!identity.packageName.equals(context.getPackageName()) || packageUid != identity.uid
                || !uidOwnsPackage || attribution.getUid() != identity.uid
                || (identity == Identity.SYSTEM && !identity.packageName.equals(attribution.getPackageName()))) {
            throw new SecurityException("Reference package ownership could not be confirmed");
        }
        return context;
    }

    private static void requestGps(Report report, LocationManager locations, int durationMs) throws InterruptedException {
        Stamp opened = Stamp.now();
        long deadline = opened.elapsedNs + TimeUnit.MILLISECONDS.toNanos(durationMs);
        report.emit("reference_request_window", opened, json("deadline_elapsed_realtime_ns", deadline,
            "duration_ms", durationMs, "requested_provider", GPS));
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
        GnssStatus.Callback satellites = new GnssStatus.Callback() {
            @Override public void onStarted() {
                report.gnssLifecycle("started", Stamp.now(), null, deadline);
            }

            @Override public void onStopped() {
                report.gnssLifecycle("stopped", Stamp.now(), null, deadline);
            }

            @Override public void onFirstFix(int ttffMillis) {
                report.gnssLifecycle("first_fix", Stamp.now(), ttffMillis, deadline);
            }

            @Override public void onSatelliteStatusChanged(GnssStatus status) {
                report.gnssStatus(Stamp.now(), status, deadline);
            }
        };
        OnNmeaMessageListener nmea = (message, timestamp) -> report.nmea(Stamp.now(), message, timestamp, deadline);
        try {
            attempt(report, "registerGnssStatusCallback", () -> {
                remainingMillis(deadline);
                return locations.registerGnssStatusCallback(callbacks, satellites);
            });
            attempt(report, "addNmeaListener", () -> {
                remainingMillis(deadline);
                return locations.addNmeaListener(callbacks, nmea);
            });
            attempt(report, "requestLocationUpdates", () -> {
                LocationRequest request = new LocationRequest.Builder(1000L)
                    .setDurationMillis(remainingMillis(deadline)).setMaxUpdates(report.gpsCallbacks.limit).build();
                locations.requestLocationUpdates(GPS, request, callbacks, listener);
                return null;
            });
            attempt(report, "getCurrentLocation", () -> {
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
            cleanup(report, "unregisterGnssStatusCallback", () -> locations.unregisterGnssStatusCallback(satellites));
            cleanup(report, "removeNmeaListener", () -> locations.removeNmeaListener(nmea));
        }
    }

    private static long remainingMillis(long deadline) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - SystemClock.elapsedRealtimeNanos());
        if (remaining <= 0) throw new IllegalStateException("Request window elapsed");
        return remaining;
    }

    private static void cleanup(Report report, String operation, Runnable action) {
        attempt(report, operation, () -> { action.run(); return null; });
    }

    private static void attempt(Report report, String operation, Supplier<?> action) {
        try {
            call(report, operation, action);
        } catch (ReadFailed ignored) {
            // Each SDK result is reported; continue the independent registration and cleanup calls.
        }
    }

    private static <T> Read<T> call(Report report, String operation, Supplier<T> action) {
        Stamp begin = Stamp.now();
        T result;
        try {
            result = action.get();
        } catch (RuntimeException | LinkageError failure) {
            report.sdkCall(operation, begin, Stamp.now(), null, failure);
            throw new ReadFailed(failure);
        }
        Stamp returned = Stamp.now();
        report.sdkCall(operation, begin, returned, result instanceof Boolean ? result : null, null);
        return new Read<>(result, returned);
    }

    private static final class ReadFailed extends RuntimeException {
        ReadFailed(Throwable cause) { super(cause); }
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

    /** Counters are owned by the synchronized report, including events excluded from the raw archive. */
    private static final class CaptureCount {
        final int limit;
        long received;
        int accepted;
        int written;
        long late;
        long truncated;
        long oversized;

        CaptureCount(int limit) { this.limit = limit; }

        boolean accept(Stamp received, long deadline) {
            return accept(received, deadline, false);
        }

        boolean accept(Stamp received, long deadline, boolean retainLate) {
            this.received++;
            if (received.elapsedNs >= deadline) {
                late++;
                if (!retainLate) return false;
            }
            if (accepted >= limit) {
                truncated++;
                return false;
            }
            accepted++;
            return true;
        }

        JSONObject snapshot() {
            return json("limit", limit, "received", received, "accepted", accepted, "written", written, "late", late,
                "truncated", truncated, "oversized", oversized);
        }
    }

    private static final class Report {
        private final PrintWriter output = new PrintWriter(new OutputStreamWriter(
            new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8), true);
        private final String sessionId;
        private final CaptureCount gpsCallbacks;
        private final CaptureCount currentLocation = new CaptureCount(1);
        private final CaptureCount nmeaSentences;
        private final CaptureCount statusFrames;
        private final CaptureCount gnssEvents = new CaptureCount(MAX_GNSS_LIFECYCLE_EVENTS);
        private int sequence;
        private int locationCount;
        private int cacheFixes;
        private int liveFixes;
        private int errorCount;
        private boolean nmeaObserved;
        private boolean ended;

        Report(Stamp started, Identity identity, int maxUpdates) {
            sessionId = identity.label + "-" + started.wallMs + "-" + Process.myPid() + "-" + started.elapsedNs;
            gpsCallbacks = new CaptureCount(maxUpdates);
            statusFrames = new CaptureCount(Math.max(BASE_STATUS_FRAMES, 2 * maxUpdates));
            nmeaSentences = new CaptureCount(Math.max(NMEA_SENTENCES_PER_90_SECONDS,
                (NMEA_SENTENCES_PER_90_SECONDS * maxUpdates + 89) / 90));
        }

        JSONObject limits() {
            return json("gps_callbacks", gpsCallbacks.limit, "current_location_callbacks", currentLocation.limit,
                "nmea_sentences", nmeaSentences.limit, "nmea_sentence_chars", MAX_NMEA_CHARS,
                "gnss_status_frames", statusFrames.limit, "satellites_per_frame", MAX_SATELLITES_PER_FRAME,
                "gnss_lifecycle_events", gnssEvents.limit);
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

        synchronized void sdkCall(String operation, Stamp begin, Stamp end, Object returned, Throwable failure) {
            if (failure != null) errorCount++;
            emit("sdk_call", end, json("operation", operation, "requested_provider", GPS,
                "query_begin_wall_ms", begin.wallMs, "query_begin_elapsed_realtime_ns", begin.elapsedNs,
                "query_end_wall_ms", end.wallMs, "query_end_elapsed_realtime_ns", end.elapsedNs,
                "result", failure == null ? "returned" : "error",
                "sdk_return", returned,
                "exception_class", failure == null ? null : errorClass(failure)));
        }

        synchronized void location(String origin, Stamp received, Location value, long deadline) {
            if (ended) return;
            CaptureCount count = origin.equals("callback") ? gpsCallbacks
                : origin.equals("current_location") ? currentLocation : null;
            if (count != null && !count.accept(received, deadline, true)) return;
            JSONObject row = json("origin", origin, "requested_provider", GPS,
                "after_deadline", received.elapsedNs >= deadline);
            if (value == null) {
                emit("location_absent", received, row);
                if (count != null) count.written++;
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
                if (count == null) {
                    if (GPS.equals(value.getProvider())) cacheFixes++;
                } else {
                    count.written++;
                    if (received.elapsedNs < deadline && GPS.equals(value.getProvider())) liveFixes++;
                }
            } catch (RuntimeException failure) {
                error(origin, failure, received);
            }
        }

        synchronized void nmea(Stamp received, String raw, long timestamp, long deadline) {
            if (ended || !nmeaSentences.accept(received, deadline)) return;
            if (raw != null && raw.length() > MAX_NMEA_CHARS) {
                nmeaSentences.oversized++;
                return;
            }
            try {
                emit("nmea_sentence", received, NmeaSnapshot.snapshot(raw, timestamp));
                nmeaSentences.written++;
                if (raw != null && !raw.trim().isEmpty()) nmeaObserved = true;
            } catch (RuntimeException failure) {
                error("nmea_sentence", failure, received);
            }
        }

        synchronized void gnssStatus(Stamp received, GnssStatus status, long deadline) {
            if (ended || !statusFrames.accept(received, deadline)) return;
            try {
                int count = status.getSatelliteCount();
                if (count > MAX_SATELLITES_PER_FRAME) {
                    statusFrames.oversized++;
                    return;
                }
                emit("gnss_status", received, GnssStatusSnapshot.snapshot(status, count));
                statusFrames.written++;
            } catch (RuntimeException failure) {
                error("gnss_status", failure, received);
            }
        }

        synchronized void gnssLifecycle(String event, Stamp received, Integer ttff, long deadline) {
            // A stop can be delivered during cleanup. It is retained with its actual out-of-window time.
            if (ended || !gnssEvents.accept(received, deadline, true)) return;
            emit("gnss_lifecycle", received, json("state", event, "ttff_ms", ttff,
                "after_deadline", received.elapsedNs >= deadline));
            gnssEvents.written++;
        }

        void error(String phase, Throwable failure) { error(phase, failure, Stamp.now()); }

        private synchronized void error(String phase, Throwable failure, Stamp received) {
            errorCount++;
            emit("reference_error", received, json("phase", phase, "exception_class", errorClass(failure)));
        }

        synchronized void end() {
            emit("reference_end", Stamp.now(), json("location_count", locationCount, "error_count", errorCount,
                "status", failed() ? "error" : locationCount == 0 ? "no_fix" : "ok",
                "coverage", json("cache_gps_fix", observation(cacheFixes > 0), "gps_fix", observation(liveFixes > 0),
                    "nmea", observation(nmeaObserved), "gnss_status", observation(statusFrames.written > 0)),
                "capture_counts", json("gps_callbacks", gpsCallbacks.snapshot(), "current_location", currentLocation.snapshot(),
                    "nmea", nmeaSentences.snapshot(), "gnss_status", statusFrames.snapshot(),
                    "gnss_lifecycle", gnssEvents.snapshot())));
            ended = true;
        }

        private static String observation(boolean observed) { return observed ? "OBSERVED" : "UNOBSERVED"; }

        synchronized boolean failed() {
            return errorCount != 0 || output.checkError();
        }
    }
}
