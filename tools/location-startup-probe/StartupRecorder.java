package com.codex.locationstartup;

import android.Manifest;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class StartupRecorder {
    private static final long MS = 1_000_000L;
    private static final long CAPTURE_NS = 30_000 * MS;
    private static final long BURST_STEP_NS = 50 * MS;
    private static final int BURST_SLOTS = 40;
    private static final int MAX_EVENTS = 4096;
    private static final int CLEANUP_RESERVE = 10;
    private static final int GNSS_FRAME_LIMIT = 60;
    private static final int GNSS_LIFECYCLE_LIMIT = 64;
    private static final long LATE_GNSS_DELAY_NS = 500 * MS;
    private static final long LATE_GNSS_MIN_REMAINING_NS = 5_000 * MS;
    private static final int LATE_GNSS_MIN_FRAMES = 3;
    private static final int MAX_REPORTS = 20;
    private static final long MAX_CLIPBOARD_BYTES = 300_000;
    private static final String[] PROVIDERS = {LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.FUSED_PROVIDER};
    private static final String[] CURRENT_PROVIDERS = {
        LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER
    };

    record Stamp(long wallMs, long elapsedNs) {
        static Stamp now() { return new Stamp(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()); }
    }

    record ViewState(boolean running, boolean complete, String file, String reason, String fileError, String trigger,
                     Integer locationEnabled, int count, int errors, int[] cached, int[] callbacks,
                     int[] currentLocations, long firstCallbackMs, long firstFocusDelayMs,
                     int[][] focusWindows, String first, String latest, String coverage) {
        static ViewState preparing() {
            return new ViewState(false, false, null, null, null, null, null, 0, 0, new int[3], new int[3],
                new int[3], -1, -1, new int[3][3], null, null, "准备阶段 · 无启动采样");
        }
    }

    record Copy(String label, String data, boolean complete, String error) {}
    private record StopRequest(long owner, long elapsedNs) {}

    private final Context context;
    private final Stamp attachEntry;
    private final Stamp attachReady;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object workLock = new Object();
    private HandlerThread thread;
    private Handler worker;
    private int pendingCommands;
    private volatile Session current;
    private volatile ViewState view = ViewState.preparing();
    private volatile long declaredOwner;
    private volatile StopRequest latestStop;
    private Runnable observer;
    private boolean uiUpdatePending;

    StartupRecorder(Context context, Stamp attachEntry, Stamp attachReady) {
        this.context = context;
        this.attachEntry = attachEntry;
        this.attachReady = attachReady;
    }

    boolean hasPermission() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean granted(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    ViewState view() { return view; }
    void observe(Runnable observer) { this.observer = observer; observer.run(); }
    void unobserve(Runnable observer) { if (this.observer == observer) this.observer = null; }

    void begin(String trigger, long owner, Stamp origin) {
        begin(trigger, owner, origin, true, false, false);
    }

    void begin(String trigger, long owner, Stamp origin, boolean includeNmea, boolean includeGnssStatus,
               boolean includeLateGnssStatus) {
        if (!hasPermission()) return;
        if (owner != 0) declaredOwner = owner;
        Session active = current;
        if (active != null && active.owner == owner) active.stopCutoffNs = origin.elapsedNs;
        enqueue(true, handler -> {
            Session previous = current;
            if (previous != null && previous.running) finish(previous, "manual_restart", origin);
            startOnWorker(new Session(handler, trigger, owner, origin, includeNmea, includeGnssStatus,
                includeLateGnssStatus), null);
        });
    }

    void ensureActiveSession(long owner, String event, String trigger, Stamp stamp) {
        if (!hasPermission()) return;
        declaredOwner = owner;
        enqueue(true, handler -> {
            if (declaredOwner != owner) return;
            Session session = current;
            if (session != null && session.running && stamp.elapsedNs >= session.deadline) {
                finish(session, "duration_elapsed", stamp);
            }
            if (session == null || !session.running) {
                startOnWorker(new Session(handler, trigger, owner, stamp, true, false, false), event);
                return;
            }
            if (session.owner != owner) {
                if (session.owner != 0) session.stopCutoffNs = Long.MAX_VALUE;
                session.owner = owner;
                applyLatestStop(session);
            }
            recordLifecycle(session, owner, event, stamp, null, true);
        });
    }

    void mark(long owner, String event, Stamp stamp, Boolean focused, boolean cache) {
        enqueue(false, handler -> recordLifecycle(current, owner, event, stamp, focused, cache));
    }

    private void recordLifecycle(Session session, long owner, String event, Stamp stamp, Boolean focused, boolean cache) {
        if (session == null || (owner != 0 && session.owner != owner) || !capturing(session, stamp)) return;
        boolean firstFocus = Boolean.TRUE.equals(focused) && session.firstFocusNs == 0;
        if (firstFocus) {
            session.firstFocusNs = stamp.elapsedNs;
            for (Observation observation : session.observations) countFocus(session, observation);
            session.observations.clear();
        }
        emit(session, event, stamp, json("focused", focused));
        if (cache) {
            Set<String> providers = state(session, event);
            registerAvailable(session, providers, event);
            readCache(session, providers, event, null, -1);
        }
        if (firstFocus) session.focusBurst = startBurst(session, "window_focus", stamp.elapsedNs);
        publish(session);
    }

    void stop(long owner, String reason, String event, Stamp stamp) {
        if (owner == declaredOwner) latestStop = new StopRequest(owner, stamp.elapsedNs);
        Session active = current;
        if (active != null && (active.owner == owner || (active.owner == 0 && owner == declaredOwner))) {
            active.stopCutoffNs = stamp.elapsedNs;
        }
        enqueue(false, handler -> {
            Session session = current;
            if (session == null || !session.running || session.owner != owner) return;
            if (event != null) emit(session, event, stamp, json());
            finish(session, reason, stamp);
        });
    }

    private void enqueue(boolean open, Consumer<Handler> command) {
        synchronized (workLock) {
            if (worker == null) {
                if (!open) return;
                thread = new HandlerThread("LocationStartupRecorder");
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            Handler target = worker;
            pendingCommands++;
            target.post(() -> {
                try {
                    command.accept(target);
                } catch (RuntimeException error) {
                    Session session = current;
                    if (session != null && session.running) {
                        failure(session, "recorder_command", null, error);
                        finish(session, "recorder_failure", Stamp.now());
                    }
                } finally {
                    synchronized (workLock) { pendingCommands--; }
                    releaseWorkerIfIdle();
                }
            });
        }
    }

    private void releaseWorkerIfIdle() {
        synchronized (workLock) {
            if (worker != null && pendingCommands == 0 && (current == null || !current.running)) {
                thread.quitSafely();
                worker = null;
                thread = null;
            }
        }
    }

    private void startOnWorker(Session session, String entryEvent) {
        current = session;
        applyLatestStop(session);
        File directory = new File(context.getFilesDir(), "location-startup");
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create report directory");
            session.file = new File(directory, "startup-" + session.origin.wallMs + "-" + Process.myPid()
                + "-" + session.origin.elapsedNs + ".jsonl");
            session.writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(session.file), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            session.errors++;
            session.fileError = error.getClass().getName();
            session.reason = "report_open_failed";
            publish(session);
            return;
        }
        session.running = true;
        session.timeout = () -> onTimeout(session);
        postAt(session, session.timeout, session.deadline);
        JSONObject start = json("trigger", session.trigger, "event_limit", MAX_EVENTS,
            "duration_ms", 30000, "deadline_elapsed_realtime_ns", session.deadline, "package", context.getPackageName(),
            "process", Application.getProcessName(), "pid", Process.myPid(), "uid", Process.myUid(),
            "process_start_elapsed_ms", Process.getStartElapsedRealtime(), "attach_entry_elapsed_ns", attachEntry.elapsedNs,
            "attach_entry_wall_ms", attachEntry.wallMs, "attach_context_ready_elapsed_ns", attachReady.elapsedNs,
            "fine_permission", granted(Manifest.permission.ACCESS_FINE_LOCATION),
            "coarse_permission", granted(Manifest.permission.ACCESS_COARSE_LOCATION));
        if (session.primaryGnss != null) set(start, "gnss_status_requested", true);
        if (session.lateGnss != null) {
            set(start, "late_gnss_status_requested", true);
            set(start, "late_gnss_status_delay_ms", LATE_GNSS_DELAY_NS / MS);
            set(start, "late_gnss_status_min_remaining_ms", LATE_GNSS_MIN_REMAINING_NS / MS);
        }
        emit(session, "session_start", session.origin, start);
        if (entryEvent != null) emit(session, entryEvent, session.origin, json("focused", null));
        publish(session);
        if (!capturing(session, Stamp.now())) return;
        session.manager = readSdk(session, "location_service", "startup", null,
            () -> context.getSystemService(LocationManager.class));
        if (session.manager == null) {
            failure(session, "location_service", null, new IllegalStateException("Location service unavailable"));
            finish(session, "location_manager_unavailable", Stamp.now());
            return;
        }
        boolean readGpsEarly = switch (session.trigger) {
            case "application_attach", "warm_activity_create", "warm_foreground" -> true;
            default -> false;
        };
        if (readGpsEarly) {
            readCache(session, Set.of(LocationManager.GPS_PROVIDER), "early_gps_before_receiver", null, -1);
        }
        registerReceiver(session);
        if (session.primaryGnss != null) registerGnssStatus(session, session.primaryGnss);
        Set<String> available = providers(session, "startup");
        Set<String> initialCacheProviders = available;
        if (readGpsEarly && available != null) {
            initialCacheProviders = new LinkedHashSet<>(available);
            initialCacheProviders.remove(LocationManager.GPS_PROVIDER);
        }
        readCache(session, initialCacheProviders, "initial_cache_before_registration", null, -1);
        registerAvailable(session, available, "startup");
        emit(session, "registration_checkpoint", Stamp.now(), json("receiver_ready", session.receiver != null,
            "listeners_ready", session.registrationCount, "listeners_attempted", session.listeners.size()));
        requestCurrentLocations(session, available);
        registerNmea(session);
        String initialPhase = entryEvent == null ? "startup" : entryEvent;
        state(session, initialPhase, available);
        session.initialLocationEnabled = session.locationEnabled;
        readCache(session, available, initialPhase, null, -1);
        String anchor = session.trigger.equals("application_attach") ? "attach" : "session_start";
        session.entryBurst = startBurst(session, anchor, session.origin.elapsedNs);
        retainReports(session, directory);
        publish(session);
    }

    private void applyLatestStop(Session session) {
        StopRequest stop = latestStop;
        if (stop != null && stop.elapsedNs >= session.origin.elapsedNs
                && (stop.owner == session.owner || (session.owner == 0 && stop.owner == declaredOwner))) {
            session.stopCutoffNs = Math.min(session.stopCutoffNs, stop.elapsedNs);
        }
    }

    private static void postAt(Session session, Runnable task, long elapsedNs) {
        long remaining = Math.max(0L, elapsedNs - SystemClock.elapsedRealtimeNanos());
        session.handler.postDelayed(task, (remaining + MS - 1) / MS);
    }

    private void onTimeout(Session session) {
        if (!session.running) return;
        Stamp now = Stamp.now();
        if (now.elapsedNs < session.deadline) {
            postAt(session, session.timeout, session.deadline);
            return;
        }
        finish(session, "duration_elapsed", now);
    }

    private Burst startBurst(Session session, String anchor, long origin) {
        Burst burst = new Burst(anchor, origin);
        burst.task = () -> burst(session, burst);
        session.handler.post(burst.task);
        return burst;
    }

    private void burst(Session session, Burst burst) {
        Stamp now = Stamp.now();
        if (!capturing(session, now) || burst.nextSlot >= BURST_SLOTS) return;
        int slot = (int) Math.min(BURST_SLOTS, Math.max(0L, (now.elapsedNs - burst.originNs) / BURST_STEP_NS));
        if (slot < burst.nextSlot) {
            postAt(session, burst.task, burst.originNs + burst.nextSlot * BURST_STEP_NS);
            return;
        }
        if (slot > burst.nextSlot) {
            int missed = slot - burst.nextSlot;
            burst.missed += missed;
            emit(session, "burst_slots_missed", now, json("first_slot", burst.nextSlot, "count", missed,
                "first_nominal_elapsed_ns", burst.originNs + burst.nextSlot * BURST_STEP_NS,
                "burst_anchor", burst.anchor, "burst_origin_elapsed_ns", burst.originNs));
        }
        burst.nextSlot = Math.min(BURST_SLOTS, slot + 1);
        if (slot >= BURST_SLOTS) return;
        long nominal = burst.originNs + slot * BURST_STEP_NS;
        boolean shared = session.lastBurstStartNs >= nominal && session.lastBurstStartNs < nominal + BURST_STEP_NS;
        emit(session, shared ? "burst_slot_shared" : "burst_slot", now, json("slot", slot,
            "nominal_elapsed_ns", nominal, "burst_anchor", burst.anchor, "burst_origin_elapsed_ns", burst.originNs,
            "shared_sample_elapsed_ns", shared ? session.lastBurstStartNs : null));
        if (!shared) {
            session.lastBurstStartNs = now.elapsedNs;
            readCache(session, session.knownProviders, "startup_burst", burst, slot);
        }
        if (burst.nextSlot < BURST_SLOTS && session.running) {
            postAt(session, burst.task, burst.originNs + burst.nextSlot * BURST_STEP_NS);
        }
    }

    private boolean capturing(Session session, Stamp stamp) {
        if (session == null || !session.running) return false;
        if (stamp.elapsedNs >= session.stopCutoffNs) return false;
        if (stamp.elapsedNs < session.deadline) return true;
        finish(session, "duration_elapsed", stamp);
        return false;
    }

    private Set<String> providers(Session session, String phase) {
        Set<String> result = readSdk(session, "all_providers", phase, null,
            () -> new LinkedHashSet<>(session.manager.getAllProviders()));
        if (result != null) session.knownProviders = result;
        if (!session.fusedAbsenceReported && (result == null || !result.contains(LocationManager.FUSED_PROVIDER))) {
            Stamp received = Stamp.now();
            if (capturing(session, received)) {
                session.fusedAbsenceReported = true;
                emit(session, "fused_provider_unavailable", received, json("phase", phase,
                    "requested_provider", LocationManager.FUSED_PROVIDER,
                    "reason", result == null ? "provider_list_unavailable" : "not_listed"));
            }
        }
        return result;
    }

    private Set<String> state(Session session, String phase) {
        return state(session, phase, providers(session, phase));
    }

    private Set<String> state(Session session, String phase, Set<String> known) {
        if (!capturing(session, Stamp.now())) return known;
        Boolean enabled = readSdk(session, "location_enabled", phase, null, session.manager::isLocationEnabled);
        session.locationEnabled = enabled;
        Set<String> names = new LinkedHashSet<>(Arrays.asList(PROVIDERS));
        if (known != null) names.addAll(known);
        JSONArray list = new JSONArray();
        for (String name : names) {
            Boolean available = known == null ? null : known.contains(name);
            Boolean providerEnabled = Boolean.TRUE.equals(available)
                ? readSdk(session, "provider_enabled", phase, name, () -> session.manager.isProviderEnabled(name)) : null;
            list.put(json("provider", name, "available", available, "enabled", providerEnabled));
        }
        emit(session, "provider_state", Stamp.now(), json("source", "location_manager_sdk", "phase", phase,
            "location_enabled", enabled, "providers", list));
        return known;
    }

    private <T> T readSdk(Session session, String operation, String phase, String provider, Supplier<T> call) {
        Stamp begin = Stamp.now();
        if (!capturing(session, begin)) return null;
        try {
            T result = call.get();
            Stamp end = Stamp.now();
            JSONObject data = json("operation", operation, "phase", phase, "requested_provider", provider,
                "outcome", "returned", "value", result instanceof Boolean ? result : null);
            timing(data, begin, end);
            emit(session, "sdk_read", end, data);
            return result;
        } catch (RuntimeException error) {
            sdkFailure(session, operation, phase, provider, begin, error);
            return null;
        }
    }

    private void readCache(Session session, Set<String> available, String phase, Burst burst, int slot) {
        if (available == null) return;
        for (String provider : PROVIDERS) {
            if (!available.contains(provider)) continue;
            Stamp begin = Stamp.now();
            if (!capturing(session, begin)) return;
            long slotEnd = burst == null ? Long.MAX_VALUE : burst.originNs + (slot + 1) * BURST_STEP_NS;
            if (begin.elapsedNs >= slotEnd) {
                emit(session, "burst_provider_missed", begin, json("slot", slot, "burst_anchor", burst.anchor,
                    "requested_provider", provider, "nominal_elapsed_ns", slotEnd - BURST_STEP_NS));
                continue;
            }
            try {
                Location location = session.manager.getLastKnownLocation(provider);
                Stamp end = Stamp.now();
                if (!capturing(session, end)) return;
                JSONObject source = json("origin", "cache", "phase", phase, "requested_provider", provider,
                    "slot", slot < 0 ? null : slot, "burst_anchor", burst == null ? null : burst.anchor,
                    "slot_completed_in_window", burst == null ? null : end.elapsedNs < slotEnd);
                timing(source, begin, end);
                if (location == null) emit(session, "cache_empty", end, source);
                else location(session, location, end, source, 0);
            } catch (RuntimeException error) {
                sdkFailure(session, "last_known", phase, provider, begin, error);
            }
        }
    }

    private void registerReceiver(Session session) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                Stamp received = Stamp.now();
                if (!capturing(session, received)) return;
                String action = intent.getAction();
                if (LocationManager.MODE_CHANGED_ACTION.equals(action)) session.modeBroadcasts++;
                JSONObject data = json("action", action);
                try {
                    set(data, "location_enabled_extra", optionalBoolean(intent, LocationManager.EXTRA_LOCATION_ENABLED));
                    set(data, "provider_name_extra", intent.getStringExtra(LocationManager.EXTRA_PROVIDER_NAME));
                    set(data, "provider_enabled_extra", optionalBoolean(intent, LocationManager.EXTRA_PROVIDER_ENABLED));
                } catch (RuntimeException error) {
                    session.errors++;
                    set(data, "extra_read_error", error.getClass().getName());
                }
                emit(session, "location_broadcast", received, data);
                Set<String> known = state(session, "broadcast");
                registerAvailable(session, known, "broadcast");
                readCache(session, known, "broadcast", null, -1);
                publish(session);
            }
        };
        Stamp begin = Stamp.now();
        if (!capturing(session, begin)) return;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, null, session.handler, Context.RECEIVER_NOT_EXPORTED);
            } else context.registerReceiver(receiver, filter, null, session.handler);
            session.receiver = receiver;
            Stamp end = Stamp.now();
            JSONObject data = json("result", "returned");
            timing(data, begin, end);
            emit(session, "broadcast_registration", end, data);
        } catch (RuntimeException error) {
            session.registrationFailed = true;
            sdkFailure(session, "register_location_broadcasts", "startup", null, begin, error);
        }
    }

    private void registerAvailable(Session session, Set<String> known, String phase) {
        if (known == null) return;
        for (String provider : PROVIDERS) {
            if (!known.contains(provider) || session.listeners.containsKey(provider)) continue;
            LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location value) {
                    Stamp received = Stamp.now();
                    if (!capturing(session, received)) return;
                    try {
                        location(session, value, received,
                            json("origin", "callback", "phase", "listener", "requested_provider", provider), 1);
                    } catch (RuntimeException error) {
                        failure(session, "location_callback", provider, error);
                    }
                }
                @Override public void onProviderEnabled(String name) { change(name, true); }
                @Override public void onProviderDisabled(String name) { change(name, false); }
                private void change(String name, boolean enabled) {
                    Stamp received = Stamp.now();
                    if (capturing(session, received)) emit(session, "listener_provider_state", received,
                        json("requested_provider", provider, "provider", name, "enabled", enabled));
                }
            };
            Stamp begin = Stamp.now();
            if (!capturing(session, begin)) return;
            session.listeners.put(provider, listener);
            try {
                session.manager.requestLocationUpdates(provider, 0L, 0f, listener, session.handler.getLooper());
                Stamp end = Stamp.now();
                session.registrationCount++;
                JSONObject data = json("phase", phase, "requested_provider", provider, "result", "returned");
                timing(data, begin, end);
                emit(session, "listener_registration", end, data);
            } catch (RuntimeException error) {
                session.registrationFailed = true;
                sdkFailure(session, "request_location_updates", phase, provider, begin, error);
            }
        }
    }

    private void requestCurrentLocations(Session session, Set<String> available) {
        if (available == null) return;
        for (String provider : CURRENT_PROVIDERS) {
            if (!available.contains(provider)) continue;
            Stamp begin = Stamp.now();
            if (!capturing(session, begin)) return;
            CancellationSignal cancel = new CancellationSignal();
            session.requests.put(provider, cancel);
            try {
                session.manager.getCurrentLocation(provider, cancel, session.handler::post, result -> {
                    Stamp received = Stamp.now();
                    if (!capturing(session, received)) return;
                    JSONObject data = json("origin", "current_location", "phase", "startup", "requested_provider", provider,
                        "request_begin_elapsed_ns", begin.elapsedNs);
                    try {
                        if (result == null) emit(session, "current_location_empty", received, data);
                        else location(session, result, received, data, 2);
                    } catch (RuntimeException error) {
                        failure(session, "current_location_callback", provider, error);
                    }
                });
                Stamp end = Stamp.now();
                JSONObject data = json("origin", "current_location", "phase", "startup", "requested_provider", provider,
                    "result", "returned");
                timing(data, begin, end);
                emit(session, "current_location_request", end, data);
            } catch (RuntimeException error) {
                sdkFailure(session, "get_current_location", "startup", provider, begin, error);
            }
        }
    }

    private void registerGnssStatus(Session session, GnssCapture capture) {
        Stamp begin = Stamp.now();
        if (!capturing(session, begin)) return;
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            signalSdkResult(session, capture.prefix + "status_registration", null, "permission_missing",
                null, null, capture);
            return;
        }
        GnssStatus.Callback callback = new GnssStatus.Callback() {
            @Override public void onStarted() { lifecycle("started", null); }
            @Override public void onStopped() { lifecycle("stopped", null); }
            @Override public void onFirstFix(int ttffMillis) { lifecycle("first_fix", ttffMillis); }

            private void lifecycle(String state, Integer ttff) {
                Stamp received = Stamp.now();
                if (!capturing(session, received)) return;
                boolean primaryOfPair = session.lateGnss != null && capture == session.primaryGnss;
                if ("stopped".equals(state)) {
                    capture.stopped = true;
                    if (primaryOfPair) cancelLateGnss(session, "primary_stopped");
                }
                capture.lifecycleReceived++;
                if (capture.lifecycleWritten >= GNSS_LIFECYCLE_LIMIT) {
                    capture.lifecycleTruncated++;
                    return;
                }
                JSONObject data = json("state", state, "ttff_ms", ttff);
                tagGnssObserver(session, capture, data);
                if (!emit(session, capture.prefix + "lifecycle", received, data)) return;
                capture.lifecycleWritten++;
                if (primaryOfPair && "first_fix".equals(state) && session.primaryFixNs == 0) {
                    session.primaryFixNs = received.elapsedNs;
                    scheduleLateGnss(session);
                }
            }

            @Override public void onSatelliteStatusChanged(GnssStatus status) {
                Stamp received = Stamp.now();
                if (!capturing(session, received)) return;
                capture.framesReceived++;
                if (capture.framesWritten >= GNSS_FRAME_LIMIT) {
                    capture.framesTruncated++;
                    return;
                }
                try {
                    int count = status.getSatelliteCount();
                    if (count > GnssStatusSnapshot.MAX_SATELLITES_PER_FRAME) {
                        capture.framesOversized++;
                        return;
                    }
                    JSONObject data = GnssStatusSnapshot.snapshot(status, count);
                    tagGnssObserver(session, capture, data);
                    if (emit(session, capture.prefix + "status", received, data)) {
                        capture.framesWritten++;
                        if (count > 0) {
                            capture.nonemptyFrames++;
                            capture.lastNonemptyFrameNs = received.elapsedNs;
                        }
                    }
                } catch (RuntimeException error) {
                    failure(session, capture.prefix + "status_callback", null, error);
                }
            }
        };
        begin = Stamp.now();
        if (!capturing(session, begin)) return;
        capture.callback = callback;
        try {
            capture.registered = session.manager.registerGnssStatusCallback(session.handler::post, callback);
            capture.registrationEndNs = Stamp.now().elapsedNs;
            signalSdkResult(session, capture.prefix + "status_registration", begin,
                capture.registered ? "registered" : "not_registered", capture.registered, null, capture);
        } catch (RuntimeException error) {
            signalSdkResult(session, capture.prefix + "status_registration", begin, "error", null, error, capture);
        }
    }

    private static void tagGnssObserver(Session session, GnssCapture capture, JSONObject data) {
        if (session.lateGnss != null) set(data, "observer", capture.observer);
    }

    private void scheduleLateGnss(Session session) {
        if (session.lateGnss == null || !session.primaryGnss.registered || session.primaryGnss.stopped
                || session.lateGnssTargetNs != 0 || session.primaryFixNs == 0 || session.ordinaryGpsNs == 0) return;
        long target = Math.max(session.primaryFixNs, session.ordinaryGpsNs) + LATE_GNSS_DELAY_NS;
        session.lateGnssTargetNs = target;
        if (target > session.deadline - LATE_GNSS_MIN_REMAINING_NS) {
            session.lateGnssReason = "insufficient_time_after_primary_evidence";
            return;
        }
        session.lateGnssTask = () -> {
            Stamp before = Stamp.now();
            if (current != session || !session.running || before.elapsedNs >= session.stopCutoffNs) {
                session.lateGnssTask = null;
                session.lateGnssReason = "session_ended_before_late_registration";
                return;
            }
            if (before.elapsedNs < target) {
                postAt(session, session.lateGnssTask, target);
                return;
            }
            session.lateGnssTask = null;
            if (before.elapsedNs > session.deadline - LATE_GNSS_MIN_REMAINING_NS) {
                session.lateGnssReason = "insufficient_time_when_task_ran";
                return;
            }
            registerGnssStatus(session, session.lateGnss);
        };
        postAt(session, session.lateGnssTask, target);
    }

    private static void cancelLateGnss(Session session, String reason) {
        if (session.lateGnssTask == null) return;
        session.handler.removeCallbacks(session.lateGnssTask);
        session.lateGnssTask = null;
        session.lateGnssReason = reason;
    }

    private void removeGnssStatus(Session session, GnssCapture capture) {
        if (capture == null || capture.callback == null) return;
        Stamp begin = Stamp.now();
        try {
            session.manager.unregisterGnssStatusCallback(capture.callback);
            capture.removed = true;
            signalSdkResult(session, capture.prefix + "status_removal", begin, "removed", null, null, capture);
        } catch (RuntimeException error) {
            signalSdkResult(session, capture.prefix + "status_removal", begin, "error", null, error, capture);
        } finally {
            capture.callback = null;
        }
    }

    private void registerNmea(Session session) {
        if (!capturing(session, Stamp.now())) return;
        if (!session.includeNmea) {
            signalSdkResult(session, "nmea_registration", null, "disabled_by_test", null, null);
            return;
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            signalSdkResult(session, "nmea_registration", null, "permission_missing", null, null);
            return;
        }
        OnNmeaMessageListener listener = (sentence, timestamp) -> {
            Stamp received = Stamp.now();
            if (!capturing(session, received)) return;
            try {
                if (emit(session, "nmea_sentence", received, NmeaSnapshot.snapshot(sentence, timestamp))) {
                    session.nmeaCount++;
                }
            } catch (RuntimeException error) {
                failure(session, "nmea_callback", null, error);
            }
        };
        Stamp begin = Stamp.now();
        if (!capturing(session, begin)) return;
        try {
            boolean registered = session.manager.addNmeaListener(session.handler::post, listener);
            if (registered) session.nmeaListener = listener;
            signalSdkResult(session, "nmea_registration", begin,
                registered ? "registered" : "not_registered", registered, null);
        } catch (RuntimeException error) {
            signalSdkResult(session, "nmea_registration", begin, "error", null, error);
        }
    }

    private void signalSdkResult(Session session, String event, Stamp begin, String result,
                                 Boolean returned, RuntimeException error) {
        signalSdkResult(session, event, begin, result, returned, error, null);
    }

    private void signalSdkResult(Session session, String event, Stamp begin, String result,
                                 Boolean returned, RuntimeException error, GnssCapture capture) {
        Stamp end = Stamp.now();
        if (error != null) session.errors++;
        JSONObject data = json("result", result, "sdk_attempted", begin != null, "sdk_return", returned,
            "exception_class", error == null ? null : error.getClass().getName());
        if (begin != null) timing(data, begin, end);
        if (capture != null) tagGnssObserver(session, capture, data);
        emit(session, event, end, data);
    }

    private void location(Session session, Location value, Stamp received, JSONObject data, int source) {
        LocationFields.putFields(value, data);
        if (data.has("extras_keys_error")) session.errors++;
        if (!emit(session, "location", received, data)) return;
        if (session.lateGnss != null && source == 1
                && LocationManager.GPS_PROVIDER.equals(data.optString("requested_provider"))
                && LocationManager.GPS_PROVIDER.equals(value.getProvider())) {
            session.latestOrdinaryGpsNs = received.elapsedNs;
            if (session.ordinaryGpsNs == 0) {
                session.ordinaryGpsNs = received.elapsedNs;
                scheduleLateGnss(session);
            }
        }
        long offset = received.elapsedNs - session.origin.elapsedNs;
        int window = window(offset);
        if (source == 0) session.cached[window]++;
        if (source == 1) session.callbacks[window]++;
        if (source == 2) session.currentLocations[window]++;
        if (source == 1 && session.firstCallbackNs < 0) session.firstCallbackNs = offset;
        Observation observation = new Observation(received.elapsedNs, source);
        if (session.firstFocusNs == 0) session.observations.add(observation);
        else countFocus(session, observation);
        String label = source == 0 ? "缓存" : source == 1 ? "监听回调" : "单次请求回调";
        String point = received.wallMs + " · " + label + " · " + value.getProvider()
            + "\n" + value.getLatitude() + ", " + value.getLongitude() + " · mock=" + value.isMock();
        if (session.first == null) session.first = point;
        session.latest = point;
        publish(session);
    }

    private static int window(long offset) {
        return offset < 200 * MS ? 0 : offset < 2000 * MS ? 1 : 2;
    }

    private static void countFocus(Session session, Observation observation) {
        long offset = observation.elapsedNs - session.firstFocusNs;
        if (offset >= 0) session.focusWindows[observation.source][window(offset)]++;
    }

    private record Observation(long elapsedNs, int source) {}

    private void finish(Session session, String reason, Stamp requested) {
        if (!session.running) return;
        session.running = false;
        session.reason = reason;
        session.handler.removeCallbacks(session.timeout);
        if (session.entryBurst != null) session.handler.removeCallbacks(session.entryBurst.task);
        if (session.focusBurst != null) session.handler.removeCallbacks(session.focusBurst.task);
        cancelLateGnss(session, "session_ended_before_late_registration");
        for (Map.Entry<String, CancellationSignal> request : session.requests.entrySet()) {
            try {
                request.getValue().cancel();
                emit(session, "current_location_cancel", Stamp.now(), json("requested_provider", request.getKey(), "result", "returned"));
            } catch (RuntimeException error) { failure(session, "cancel_current_location", request.getKey(), error); }
        }
        session.requests.clear();
        for (Map.Entry<String, LocationListener> entry : session.listeners.entrySet()) {
            try {
                session.manager.removeUpdates(entry.getValue());
                emit(session, "listener_removal", Stamp.now(), json("requested_provider", entry.getKey(), "result", "returned"));
            } catch (RuntimeException error) { failure(session, "remove_location_updates", entry.getKey(), error); }
        }
        session.listeners.clear();
        if (session.nmeaListener != null) {
            Stamp begin = Stamp.now();
            try {
                session.manager.removeNmeaListener(session.nmeaListener);
                signalSdkResult(session, "nmea_removal", begin, "removed", null, null);
            } catch (RuntimeException error) {
                signalSdkResult(session, "nmea_removal", begin, "error", null, error);
            }
            session.nmeaListener = null;
        }
        removeGnssStatus(session, session.primaryGnss);
        removeGnssStatus(session, session.lateGnss);
        if (session.receiver != null) {
            try {
                context.unregisterReceiver(session.receiver);
                emit(session, "broadcast_removal", Stamp.now(), json("result", "returned"));
            } catch (RuntimeException error) { failure(session, "unregister_location_broadcasts", null, error); }
            session.receiver = null;
        }
        JSONObject end = json("reason", reason, "errors", session.errors,
            "nmea_sentence_count", session.nmeaCount,
            "cached_windows", counts(session.cached), "callback_windows", counts(session.callbacks),
            "current_location_windows", counts(session.currentLocations),
            "focus_windows", session.firstFocusNs == 0 ? null : json("cache", counts(session.focusWindows[0]),
                "callback", counts(session.focusWindows[1]), "current_location", counts(session.focusWindows[2])),
            "first_callback_delay_ns", session.firstCallbackNs < 0 ? null : session.firstCallbackNs,
            "first_focus_elapsed_ns", session.firstFocusNs == 0 ? null : session.firstFocusNs,
            "observed_mode_broadcasts", session.modeBroadcasts, "missed_burst_slots", missedSlots(session),
            "coverage", coverage(session));
        if (session.primaryGnss != null) set(end, "gnss_status_capture", session.primaryGnss.summary());
        if (session.lateGnss != null) {
            GnssCapture late = session.lateGnss;
            String gap = lateGnssCoverageGap(session);
            JSONObject data = late.summary();
            set(data, "observer", late.observer);
            set(data, "coverage", gap == null ? "OBSERVED" : "UNOBSERVED");
            set(data, "coverage_gap", gap);
            set(data, "nonempty_frames", late.nonemptyFrames);
            set(data, "first_primary_fix_received_elapsed_ns", elapsedOrNull(session.primaryFixNs));
            set(data, "first_ordinary_gps_received_elapsed_ns", elapsedOrNull(session.ordinaryGpsNs));
            set(data, "late_registration_target_elapsed_ns", elapsedOrNull(session.lateGnssTargetNs));
            set(end, "late_gnss_status_capture", data);
        }
        session.complete = emit(session, "session_end", requested, end);
        if (session.writer != null) {
            try { session.writer.close(); }
            catch (IOException error) { session.fileError = error.getClass().getName(); session.errors++; }
            session.writer = null;
        }
        publish(session);
        releaseWorkerIfIdle();
    }

    private static JSONArray counts(int[] windows) {
        return new JSONArray().put(windows[0]).put(windows[1]).put(windows[2]);
    }

    private static Long elapsedOrNull(long elapsedNs) {
        return elapsedNs == 0 ? null : elapsedNs;
    }

    private static String lateGnssCoverageGap(Session session) {
        GnssCapture primary = session.primaryGnss;
        GnssCapture late = session.lateGnss;
        if (primary.stopped || late.stopped) return "gnss_stopped_during_window";
        if (session.lateGnssReason != null) return session.lateGnssReason;
        if (!primary.registered) return "primary_not_registered";
        if (session.primaryFixNs == 0) return "primary_first_fix_unobserved";
        if (session.ordinaryGpsNs == 0) return "ordinary_gps_unobserved";
        if (!late.registered) return "late_registration_unconfirmed";
        if (session.deadline - late.registrationEndNs < LATE_GNSS_MIN_REMAINING_NS)
            return "insufficient_time_after_registration";
        if (!primary.removed || !late.removed) return "cleanup_unconfirmed";
        if (!"duration_elapsed".equals(session.reason) || session.errors != 0
                || !primary.complete() || !late.complete()) return "session_or_stream_incomplete";
        if (late.nonemptyFrames < LATE_GNSS_MIN_FRAMES) return "insufficient_late_status_frames";
        if (primary.lastNonemptyFrameNs <= late.registrationEndNs
                || session.latestOrdinaryGpsNs <= late.registrationEndNs) return "overlap_unobserved";
        return null;
    }

    private static String coverage(Session session) {
        StringBuilder value = new StringBuilder();
        if (session.registrationFailed || session.registrationCount == 0) value.append("注册覆盖不足；");
        if (session.first == null) value.append("尚未观察到定位；");
        if (session.firstCallbackNs < 0) value.append("未观察到普通监听回调；");
        if (session.modeBroadcasts == 0) {
            if (Boolean.TRUE.equals(session.initialLocationEnabled)) value.append("初始定位已开启，");
            value.append("未观察到定位开关广播；");
        }
        if (session.firstFocusNs == 0) value.append("未观察到窗口获得焦点；");
        else if (session.firstFocusNs >= session.origin.elapsedNs + 2000 * MS) value.append("前 2 秒未观察到窗口焦点；");
        int missed = missedSlots(session);
        if (missed > 0) value.append("漏过缓存时点 ").append(missed).append(" 个；");
        if (session.primaryGnss != null) {
            if (session.primaryGnss.framesWritten == 0) value.append("未观察到逐星状态帧；");
            if (!session.primaryGnss.complete()) {
                value.append("逐星状态记录不完整；");
            }
        }
        if ("event_limit_reached".equals(session.reason)) value.append("达到记录上限，采样不完整；");
        if (session.fileError != null) value.append("日志 I/O 失败，采样不完整；");
        if (session.errors > 0) value.append("有异常，详见报告；");
        return value.length() == 0 ? "仅说明当前会话已观察到的记录" : value.toString();
    }

    private static int missedSlots(Session session) {
        return (session.entryBurst == null ? 0 : session.entryBurst.missed)
            + (session.focusBurst == null ? 0 : session.focusBurst.missed);
    }

    private void publish(Session session) {
        view = new ViewState(session.running, session.complete, session.file == null ? null : session.file.getName(),
            session.reason, session.fileError, session.trigger, session.locationEnabled == null ? null : session.locationEnabled ? 1 : 0,
            session.count, session.errors, session.cached.clone(), session.callbacks.clone(),
            session.currentLocations.clone(), session.firstCallbackNs < 0 ? -1 : session.firstCallbackNs / MS,
            session.firstFocusNs == 0 ? -1 : (session.firstFocusNs - session.origin.elapsedNs) / MS,
            new int[][]{session.focusWindows[0].clone(), session.focusWindows[1].clone(), session.focusWindows[2].clone()},
            session.first, session.latest, coverage(session));
        synchronized (ui) {
            if (uiUpdatePending) return;
            uiUpdatePending = true;
        }
        ui.post(() -> {
            synchronized (ui) { uiUpdatePending = false; }
            if (observer != null) observer.run();
        });
    }

    void copy(Consumer<Copy> callback) {
        enqueue(true, handler -> {
            Session session = current;
            Copy result;
            try {
                if (session == null || session.file == null) throw new IOException("No report available");
                if (session.file.length() > MAX_CLIPBOARD_BYTES) throw new IOException("Report exceeds clipboard limit");
                String data = new String(Files.readAllBytes(session.file.toPath()), StandardCharsets.UTF_8);
                boolean complete = session.complete && session.fileError == null;
                if (!complete) data = json("event", "clipboard_snapshot", "complete", false,
                    "captured_wall_ms", System.currentTimeMillis(), "captured_elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
                    + "\n" + data;
                result = new Copy((complete ? "" : "未结束快照 · ") + session.file.getName(), data, complete, null);
            } catch (IOException | RuntimeException error) {
                result = new Copy(null, null, false, error.getClass().getName());
            }
            Copy published = result;
            ui.post(() -> callback.accept(published));
        });
    }

    private void retainReports(Session session, File directory) {
        File[] reports = directory.listFiles((parent, name) -> name.matches("startup-[0-9]+-[0-9]+-[0-9]+\\.jsonl"));
        if (reports == null) {
            failure(session, "report_retention", null, new IllegalStateException("Cannot list reports"));
            return;
        }
        Arrays.sort(reports, Comparator.comparingLong(File::lastModified).thenComparing(File::getName).reversed());
        int retained = 1;
        for (File file : reports) if (!file.equals(session.file) && ++retained > MAX_REPORTS && !file.delete()) {
            failure(session, "report_retention", null, new IllegalStateException("Cannot remove old report"));
        }
    }

    private void failure(Session session, String phase, String provider, RuntimeException error) {
        session.errors++;
        emit(session, "error", Stamp.now(), json("phase", phase, "requested_provider", provider,
            "exception_class", error.getClass().getName()));
        publish(session);
    }

    private void sdkFailure(Session session, String operation, String phase, String provider, Stamp begin, RuntimeException error) {
        session.errors++;
        Stamp end = Stamp.now();
        JSONObject data = json("operation", operation, "phase", phase, "requested_provider", provider,
            "exception_class", error.getClass().getName());
        timing(data, begin, end);
        emit(session, "sdk_error", end, data);
        publish(session);
    }

    private boolean emit(Session session, String event, Stamp receive, JSONObject data) {
        if (session.writer == null || session.fileError != null) return false;
        int cleanupReserve = CLEANUP_RESERVE;
        if (session.primaryGnss != null && session.primaryGnss.callback != null) cleanupReserve++;
        if (session.lateGnss != null && session.lateGnss.callback != null) cleanupReserve++;
        if (session.running && session.count >= MAX_EVENTS - cleanupReserve) {
            finish(session, "event_limit_reached", receive);
            return false;
        }
        if (session.count >= MAX_EVENTS) return false;
        set(data, "event", event);
        set(data, "session_id", session.file.getName());
        set(data, "sequence", session.count + 1);
        set(data, "received_wall_ms", receive.wallMs);
        set(data, "received_elapsed_realtime_ns", receive.elapsedNs);
        Stamp serialized = Stamp.now();
        set(data, "serialization_wall_ms", serialized.wallMs);
        set(data, "serialization_elapsed_realtime_ns", serialized.elapsedNs);
        try {
            session.writer.write(data.toString());
            session.writer.newLine();
            session.writer.flush();
            session.count++;
            return true;
        } catch (IOException error) {
            session.fileError = error.getClass().getName();
            session.errors++;
            if (session.running) finish(session, "report_write_failed", Stamp.now());
            return false;
        }
    }

    private static void timing(JSONObject data, Stamp begin, Stamp end) {
        set(data, "sdk_begin_elapsed_ns", begin.elapsedNs);
        set(data, "sdk_end_elapsed_ns", end.elapsedNs);
    }

    private static Boolean optionalBoolean(Intent intent, String name) {
        return intent.hasExtra(name) ? intent.getBooleanExtra(name, false) : null;
    }

    private static JSONObject json(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) set(result, (String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static void set(JSONObject data, String key, Object value) {
        LocationFields.put(data, key, value);
    }

    private static final class Session {
        final Handler handler;
        final String trigger;
        final Stamp origin;
        final long deadline;
        final boolean includeNmea;
        final GnssCapture primaryGnss;
        final GnssCapture lateGnss;
        final Map<String, LocationListener> listeners = new LinkedHashMap<>();
        final Map<String, CancellationSignal> requests = new LinkedHashMap<>();
        final int[] cached = new int[3];
        final int[] callbacks = new int[3];
        final int[] currentLocations = new int[3];
        final int[][] focusWindows = new int[3][3];
        final List<Observation> observations = new ArrayList<>();
        volatile long owner;
        volatile long stopCutoffNs = Long.MAX_VALUE;
        volatile boolean running;
        File file;
        BufferedWriter writer;
        LocationManager manager;
        OnNmeaMessageListener nmeaListener;
        int nmeaCount;
        long primaryFixNs;
        long ordinaryGpsNs;
        long latestOrdinaryGpsNs;
        long lateGnssTargetNs;
        Runnable lateGnssTask;
        String lateGnssReason;
        boolean fusedAbsenceReported;
        Runnable timeout;
        Burst entryBurst;
        Burst focusBurst;
        BroadcastReceiver receiver;
        Set<String> knownProviders;
        Boolean initialLocationEnabled;
        Boolean locationEnabled;
        boolean registrationFailed;
        boolean complete;
        int count;
        int errors;
        int registrationCount;
        int modeBroadcasts;
        long lastBurstStartNs;
        long firstCallbackNs = -1;
        long firstFocusNs;
        String first;
        String latest;
        String reason;
        String fileError;

        Session(Handler handler, String trigger, long owner, Stamp origin, boolean includeNmea,
                boolean includeGnssStatus, boolean includeLateGnssStatus) {
            this.handler = handler;
            this.trigger = trigger;
            this.owner = owner;
            this.origin = origin;
            this.includeNmea = includeNmea;
            this.primaryGnss = includeGnssStatus ? new GnssCapture("primary", "gnss_") : null;
            this.lateGnss = includeLateGnssStatus ? new GnssCapture("late", "late_gnss_") : null;
            this.deadline = origin.elapsedNs + CAPTURE_NS;
        }
    }

    private static final class GnssCapture {
        final String observer;
        final String prefix;
        GnssStatus.Callback callback;
        boolean registered;
        boolean removed;
        boolean stopped;
        long registrationEndNs;
        long framesReceived;
        int framesWritten;
        int nonemptyFrames;
        long lastNonemptyFrameNs;
        long framesOversized;
        long framesTruncated;
        long lifecycleReceived;
        int lifecycleWritten;
        long lifecycleTruncated;

        GnssCapture(String observer, String prefix) {
            this.observer = observer;
            this.prefix = prefix;
        }

        boolean complete() {
            return framesReceived == framesWritten && lifecycleReceived == lifecycleWritten;
        }

        JSONObject summary() {
            return json("frames_received", framesReceived, "frames_written", framesWritten,
                "frames_oversized", framesOversized, "frames_truncated", framesTruncated,
                "frame_limit", GNSS_FRAME_LIMIT, "satellites_per_frame", GnssStatusSnapshot.MAX_SATELLITES_PER_FRAME,
                "lifecycle_received", lifecycleReceived, "lifecycle_written", lifecycleWritten,
                "lifecycle_truncated", lifecycleTruncated, "lifecycle_limit", GNSS_LIFECYCLE_LIMIT);
        }
    }

    private static final class Burst {
        final String anchor;
        final long originNs;
        Runnable task;
        int nextSlot;
        int missed;

        Burst(String anchor, long originNs) {
            this.anchor = anchor;
            this.originNs = originNs;
        }
    }
}
