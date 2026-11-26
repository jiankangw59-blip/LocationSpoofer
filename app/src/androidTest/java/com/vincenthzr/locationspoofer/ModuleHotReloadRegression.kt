package com.vincenthzr.locationspoofer

import android.app.Instrumentation
import android.os.Bundle
import com.vincenthzr.locationspoofer.data.model.HookConfigCheck
import com.vincenthzr.locationspoofer.data.repository.HookStatusRepository
import com.vincenthzr.locationspoofer.utils.FrameworkConfigChannel
import com.vincenthzr.locationspoofer.utils.XposedModuleStatus
import com.vincenthzr.locationspoofer.utils.RootManager
import com.vincenthzr.locationspoofer.vendor.HookStatusFiles
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

private suspend fun moduleService(): XposedService {
    repeat(150) {
        XposedModuleStatus.mService?.let { return it }
        delay(100)
    }
    error("Framework service unavailable")
}

private suspend fun readMatchingReloadReports(
    baseline: Map<String, JSONObject>,
    readSnapshotId: suspend () -> String,
    readReports: () -> Map<String, JSONObject>
): Map<String, JSONObject>? {
    val snapshotId = readSnapshotId()
    val current = readReports()
    return current.takeIf { reports ->
        reports.keys == baseline.keys && reports.all { (process, report) ->
            report.optString("generation").isNotBlank() &&
                report.optString("generation") != baseline.getValue(process).optString("generation") &&
                report.optJSONObject("config")?.optString("snapshot_id") == snapshotId
        } && readSnapshotId() == snapshotId
    }
}

internal suspend fun verifyReloadObservation() {
    val initial = "initial"
    var snapshotId = initial
    fun reports(generation: String, id: String) = mapOf(
        "SYSTEM_SERVER" to JSONObject().put("generation", generation)
            .put("config", JSONObject().put("snapshot_id", id))
    )
    val baseline = reports("first", initial)
    val duringWait = "updated-during-wait"
    check(readMatchingReloadReports(baseline, { snapshotId }) {
        snapshotId = duringWait
        reports("second", initial)
    } == null)
    val first = checkNotNull(readMatchingReloadReports(baseline, { snapshotId }) {
        reports("second", duringWait)
    })
    val betweenCycles = "updated-between-cycles"
    snapshotId = betweenCycles
    check(readMatchingReloadReports(first, { snapshotId }) { reports("third", duringWait) } == null)
    checkNotNull(readMatchingReloadReports(first, { snapshotId }) { reports("third", betweenCycles) })
    check(snapshotId == betweenCycles)
}

/** Module-scoped diagnostics; does not start or stop a simulation. */
internal fun Instrumentation.verifyModuleRuntime(verifyConfigStatus: Boolean = false, runId: String? = null): Bundle {
    val output = Bundle()
    try {
        val service = runBlocking { moduleService() }
        output.putString("framework", "${service.frameworkName} ${service.frameworkVersion} API ${service.apiVersion}")
        output.putString("scope", service.scope.sorted().joinToString())
        if (service.apiVersion >= 102) {
            output.putString("targets", service.runningTargets.joinToString { target ->
                "${target.processName}:pid=${target.pid},state=${target.state},version=${target.loadedVersionCode}"
            })
        }
        val text = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
            .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
        if (text != null) {
            val envelope = JSONObject(text)
            val payload = envelope.optString("payload")
            output.putString("configuration", "published=${envelope.optLong("published_at")},inline=${payload.isNotEmpty()},active=${if (payload.isNotEmpty()) JSONObject(payload).optBoolean("active") else "remote-file"}")
        }
        if (verifyConfigStatus) {
            val repository = HookStatusRepository(RootManager())
            val check = runBlocking {
                withTimeout(15_000) {
                    while (true) {
                        val before = runId?.let { withFixtureConfig(it) { fixture -> fixture.requireCurrent().snapshot } }
                        val result = repository.diagnose().config
                        val after = runId?.let { withFixtureConfig(it) { fixture -> fixture.requireCurrent().snapshot } }
                        if (before == after && result.state == HookConfigCheck.State.COMPARED &&
                            result.hosts.size == 3 && result.hosts.values.all { it == HookConfigCheck.HostState.MATCHED }) {
                            return@withTimeout result
                        }
                        delay(500)
                    }
                    error("Unreachable")
                }
            }
            output.putString("config_hosts", check.hosts.entries.joinToString { "${it.key}=${it.value}" })
        }
        output.putString("result", "PASS")
    } catch (error: Throwable) {
        output.putString("result", "FAIL: ${error.stackTraceToString()}")
    }
    return output
}

/** Exercises actual API 102 generation replacement, preserving scope and simulation payload. */
internal fun Instrumentation.verifyModuleHotReload(
    activeProbeOnly: Boolean = false,
    runId: String? = null,
    preserveState: Boolean = false
): Bundle {
    val output = Bundle()
    try {
        runBlocking {
            check(!preserveState || !activeProbeOnly)
            val service = moduleService()
            check(BuildConfig.GLOBAL_SCHEME && service.apiVersion >= 102)
            val scope = service.scope.sorted()
            val targets = service.runningTargets
            val pids = targets.associate { it.processName to it.pid }
            check(pids.keys == setOf("system", "com.android.phone", "com.android.bluetooth")) {
                "Unexpected targets: $pids"
            }
            val preferences = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
            val preservedSnapshot = if (preserveState) {
                checkNotNull(preferences.getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)) {
                    "No framework configuration snapshot to preserve"
                }
            } else null
            val preservedSnapshotId = preservedSnapshot?.let { JSONObject(it).getString("id") }
            suspend fun expectedSnapshotId(): String {
                val snapshot = if (activeProbeOnly) withFixtureConfig(runId) { it.requireCurrent().snapshot }
                    else preferences.getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
                if (preservedSnapshotId != null) {
                    check(snapshot == preservedSnapshot) { "Configuration snapshot changed during hot reload" }
                    return preservedSnapshotId
                }
                val envelope = JSONObject(checkNotNull(snapshot))
                check(envelope.optString("file").isEmpty())
                val payload = JSONObject(envelope.getString("payload"))
                check(payload.optBoolean("active") == activeProbeOnly) { "Simulation state changed during hot reload" }
                return envelope.getString("id")
            }
            expectedSnapshotId()
            val root = RootManager()
            fun reports(): Map<String, JSONObject> = HookStatusFiles.PATHS.mapValues { (_, paths) ->
                val command = paths.joinToString(" || ") { "cat '/proc/1/root$it' 2>/dev/null" }
                JSONObject(root.executeCommand(command))
            }
            fun hookCounts(report: JSONObject): Map<String, Int> = buildMap {
                val components = report.getJSONArray("components")
                repeat(components.length()) { index ->
                    val component = components.getJSONObject(index)
                    if (component.optBoolean("found")) {
                        val methods = component.getJSONObject("methods")
                        methods.keys().forEach { name -> put("${component.getString("name")}.$name", methods.getInt(name)) }
                    }
                }
            }
            var baseline = reports()
            repeat(2) { cycle ->
                for (target in service.runningTargets) {
                    if (preserveState) expectedSnapshotId()
                    val completion = CompletableDeferred<HotReloadResult>()
                    service.hotReloadModule(target, null) { _, result -> completion.complete(result) }
                    val result = withTimeout(30_000) {
                        while (preserveState && !completion.isCompleted) {
                            expectedSnapshotId()
                            delay(250)
                        }
                        completion.await()
                    }
                    if (preserveState) expectedSnapshotId()
                    check(result.status() == HotReloadResult.Status.SUCCEEDED) {
                        "${target.processName}: ${result.status()} ${result.message()}"
                    }
                }
                var after: Map<String, JSONObject>? = null
                withTimeout(20_000) {
                    while (after == null) {
                        delay(500)
                        after = readMatchingReloadReports(baseline, ::expectedSnapshotId, ::reports)
                    }
                }
                val verified = requireNotNull(after)
                verified.forEach { (process, report) ->
                    val target = when (process) {
                        "SYSTEM_SERVER" -> "system"
                        "PHONE" -> "com.android.phone"
                        else -> "com.android.bluetooth"
                    }
                    check(report.getInt("pid") == pids.getValue(target)) { "$process restarted" }
                    check(report.getString("load_kind") == "hot-reload")
                    check(report.getJSONArray("errors").length() == 0) { "$process: ${report.getJSONArray("errors")}" }
                    val counts = hookCounts(report)
                    check(counts.isNotEmpty() && hookCounts(baseline.getValue(process)).all { (method, count) ->
                        counts.getOrDefault(method, 0) >= count
                    }) { "$process lost hooks" }
                }
                check(service.scope.sorted() == scope)
                check(service.runningTargets.associate { it.processName to it.pid } == pids)
                output.putString("cycle_${cycle + 1}", "All three generations replaced; hooks intact; latest configuration received; PIDs unchanged")
                baseline = verified
            }
            if (preserveState) output.putString("preserved_snapshot_id", expectedSnapshotId())
            output.putString("pids", pids.toString())
            output.putString("scope", scope.joinToString())
        }
        output.putString("result", "PASS")
    } catch (error: Throwable) {
        output.putString("result", "FAIL: ${error.stackTraceToString()}")
    }
    return output
}
