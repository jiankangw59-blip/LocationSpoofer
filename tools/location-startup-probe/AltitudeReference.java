package com.codex.locationstartup;

import android.content.Context;
import android.location.Location;
import android.location.altitude.AltitudeConverter;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

final class AltitudeReference {
    private static final String TAG = "LocationStartupProbe";
    private static final int MAX_REPORTS = 20;
    private static final AtomicBoolean busy = new AtomicBoolean();

    private AltitudeReference() {}

    static String enqueue(Context context, StartupRecorder.Stamp received, String lat, String lon, String alt) {
        double latitude = finite(lat);
        double longitude = finite(lon);
        double altitude = finite(alt);
        if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw new IllegalArgumentException("Coordinates are outside WGS84 bounds");
        }
        if (!busy.compareAndSet(false, true)) return null;
        String filename = "altitude-reference-" + received.wallMs() + "-" + received.elapsedNs()
            + "-" + Process.myPid() + ".json";
        try {
            Thread worker = new Thread(() -> {
                try {
                    write(context, filename, convert(context, received, latitude, longitude, altitude));
                } catch (IOException | RuntimeException | LinkageError failure) {
                    Log.w(TAG, "Altitude reference report " + filename + " " + failure.getClass().getName());
                } finally {
                    busy.set(false);
                }
            }, "LocationAltitudeReference");
            worker.start();
            return filename;
        } catch (RuntimeException | Error failure) {
            busy.set(false);
            throw failure;
        }
    }

    private static double finite(String text) {
        if (text == null || text.length() > 64) throw new IllegalArgumentException("Missing or oversized number");
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("A finite number is required");
        return value;
    }

    private static JSONObject convert(Context context, StartupRecorder.Stamp received,
                                      double latitude, double longitude, double altitude) {
        StartupRecorder.Stamp begin = StartupRecorder.Stamp.now();
        Location input = new Location("altitude_reference");
        input.setLatitude(latitude);
        input.setLongitude(longitude);
        input.setAltitude(altitude);
        input.setTime(received.wallMs());
        input.setElapsedRealtimeNanos(received.elapsedNs());
        Location output = new Location(input);
        output.setVerticalAccuracyMeters(0.0f);

        boolean attempted = Build.VERSION.SDK_INT >= 34;
        String status = "unsupported";
        String exception = null;
        Double separation = null;
        Float mslAccuracy = null;
        if (attempted) {
            try {
                Api34.convert(context, output);
                if (!Api34.hasFiniteMsl(output)) throw new IllegalStateException("MSL altitude is unavailable");
                double delta = input.getAltitude() - Api34.msl(output);
                if (!Double.isFinite(delta)) throw new IllegalStateException("MSL separation is not finite");
                mslAccuracy = Api34.mslAccuracy(output);
                separation = delta;
                status = "ok";
            } catch (IOException | RuntimeException | LinkageError failure) {
                status = "error";
                exception = failure.getClass().getName();
            }
        }
        StartupRecorder.Stamp end = StartupRecorder.Stamp.now();
        JSONObject report = new JSONObject();
        LocationFields.put(report, "schema_version", 1);
        LocationFields.put(report, "input_kind", "synthetic_model_reference");
        LocationFields.put(report, "sdk_int", Build.VERSION.SDK_INT);
        LocationFields.put(report, "android_release", Build.VERSION.RELEASE);
        LocationFields.put(report, "build_fingerprint", Build.FINGERPRINT);
        LocationFields.put(report, "pid", Process.myPid());
        LocationFields.put(report, "uid", Process.myUid());
        putTime(report, "received", received);
        putTime(report, "begin", begin);
        putTime(report, "end", end);
        LocationFields.put(report, "conversion_attempted", attempted);
        LocationFields.put(report, "status", status);
        LocationFields.put(report, "exception_class", exception);
        LocationFields.put(report, "input", LocationFields.snapshot(input));
        LocationFields.put(report, "output", attempted ? LocationFields.snapshot(output) : null);
        LocationFields.put(report, "separation_m", separation);
        LocationFields.put(report, "native_msl_altitude_accuracy_m", mslAccuracy);
        return report;
    }

    private static void putTime(JSONObject report, String prefix, StartupRecorder.Stamp stamp) {
        LocationFields.put(report, prefix + "_wall_time_ms", stamp.wallMs());
        LocationFields.put(report, prefix + "_elapsed_realtime_ns", stamp.elapsedNs());
    }

    private static void write(Context context, String filename, JSONObject report) throws IOException {
        File directory = new File(context.getFilesDir(), "location-startup");
        Files.createDirectories(directory.toPath());
        File[] reports = directory.listFiles((parent, name) ->
            name.matches("altitude-reference-[0-9]+-[0-9]+-[0-9]+\\.json"));
        if (reports == null) throw new IOException("Cannot list altitude reference reports");
        Arrays.sort(reports, Comparator.comparingLong(File::lastModified).thenComparing(File::getName).reversed());
        for (int i = MAX_REPORTS - 1; i < reports.length; i++) Files.delete(reports[i].toPath());
        File temporary = new File(directory, "altitude-reference-writing.tmp");
        Files.write(temporary.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), new File(directory, filename).toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    private static final class Api34 {
        static void convert(Context context, Location location) throws IOException {
            new AltitudeConverter().addMslAltitudeToLocation(context, location);
        }

        static boolean hasFiniteMsl(Location location) {
            return location.hasMslAltitude() && Double.isFinite(msl(location));
        }

        static double msl(Location location) { return location.getMslAltitudeMeters(); }

        static Float mslAccuracy(Location location) {
            if (!location.hasMslAltitudeAccuracy()) return null;
            float accuracy = location.getMslAltitudeAccuracyMeters();
            return Float.isFinite(accuracy) && accuracy >= 0 ? accuracy : null;
        }
    }
}
