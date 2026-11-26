package com.vincenthzr.locationspoofer

import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.AtomicFile
import com.vincenthzr.locationspoofer.utils.ConfigManager
import com.vincenthzr.locationspoofer.utils.FrameworkConfigChannel
import com.vincenthzr.locationspoofer.utils.XposedModuleStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

private const val RUN_FIELD = "_instrumentation_fixture_run"

internal data class FixtureConfigState(val desired: String?, val snapshot: String?, val owner: String? = null) {
    fun payload() = JSONObject(checkNotNull(snapshot)).let {
        check(it.optString("file").isEmpty()) { "Fixture requires an inline configuration" }
        JSONObject(it.getString("payload"))
    }
}

internal interface FixtureConfigStore {
    fun readDesired(): Pair<String?, String?>
    fun readSnapshot(): String?
    fun writeDesired(value: String?, owner: String?, userConfig: String? = null)
    fun confirmDesiredDurable()
    fun writeSnapshot(value: String?)
}

private class PreferencesFixtureStore(
    private val local: SharedPreferences, private val remote: SharedPreferences
) : FixtureConfigStore {
    override fun readDesired() = local.getString(ConfigManager.DESIRED_CONFIG, null) to
        local.getString(ConfigManager.FIXTURE_OWNER, null)

    override fun readSnapshot() = remote.getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)

    override fun writeDesired(value: String?, owner: String?, userConfig: String?) {
        check(local.edit().putString(ConfigManager.DESIRED_CONFIG, value)
            .putString(ConfigManager.FIXTURE_OWNER, owner)
            .putString(ConfigManager.FIXTURE_USER_CONFIG, userConfig).commit()) { "Desired config commit failed" }
    }

    override fun confirmDesiredDurable() {
        // A failed Android synchronous commit can still update SharedPreferences memory.
        // Retry syncing the current generation without replaying stale values.
        check(local.edit().commit()) { "Desired configuration did not persist" }
    }

    override fun writeSnapshot(value: String?) {
        val edit = remote.edit()
        if (value == null) edit.remove(FrameworkConfigChannel.SNAPSHOT_KEY)
        else edit.putString(FrameworkConfigChannel.SNAPSHOT_KEY, value)
        check(edit.commit()) { "Snapshot commit failed" }
    }
}

/** Call while holding the app's config write lock. The journal precedes every possible commit. */
internal class FixtureConfigJournal(private val file: AtomicFile, private val store: FixtureConfigStore, val runId: String) {
    init {
        require(UUID.fromString(runId).toString() == runId) { "Invalid fixture run ID" }
    }

    private fun readJournal(): JSONObject? =
        if (file.baseFile.isFile) JSONObject(String(file.readFully(), Charsets.UTF_8)) else null

    private val cancellation = File(file.baseFile.parentFile, "${file.baseFile.name}.closed-$runId")

    private fun closeRun() {
        if (!cancellation.exists()) {
            cancellation.outputStream().use { it.fd.sync() }
        }
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        cancellation.parentFile?.listFiles()?.forEach { marker ->
            if (marker.name.startsWith("${file.baseFile.name}.closed-") &&
                marker != cancellation && marker.lastModified() < cutoff) marker.delete()
        }
    }

    private fun ownedJournal(): JSONObject = checkNotNull(readJournal()) { "No fixture to update" }.also {
        check(it.getString("run_id") == runId) { "Fixture belongs to another run" }
    }

    private fun persist(journal: JSONObject) {
        val bytes = journal.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
        file.finishWrite(stream)
        check(file.readFully().contentEquals(bytes)) { "Fixture journal did not persist" }
    }

    private fun JSONObject.original(key: String) = if (isNull(key)) null else getString(key)

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun recordedOwner(value: String?): String? =
        value?.let { runCatching { JSONObject(it).optString(RUN_FIELD) }.getOrNull() }

    private fun owns(value: String?, journal: JSONObject): Boolean {
        if (value == null || recordedOwner(value) != runId) return false
        val hash = fingerprint(value)
        return hash == journal.optString("expected_payload_sha256") || hash == journal.optString("previous_payload_sha256")
    }

    private fun snapshotPayload(snapshot: String?): String? =
        snapshot?.let { runCatching { JSONObject(it).getString("payload") }.getOrNull() }

    fun read(): FixtureConfigState {
        val (desired, owner) = store.readDesired()
        return FixtureConfigState(desired, store.readSnapshot(), owner)
    }

    fun prepare(baseline: FixtureConfigState, payload: JSONObject) {
        check(!cancellation.exists()) { "Fixture run was already cancelled" }
        check(readJournal() == null) { "Previous fixture requires recovery" }
        val current = read()
        check(current.owner == null && baseline.owner == null && current.desired == baseline.desired &&
            snapshotPayload(current.snapshot) == snapshotPayload(baseline.snapshot)) {
            "User configuration changed before fixture publication"
        }
        check(!current.payload().optBoolean("active")) { "Preserve the active user simulation" }
        val journal = JSONObject().put("run_id", runId)
            .put("original_desired", current.desired ?: JSONObject.NULL)
            .put("original_snapshot", current.snapshot ?: JSONObject.NULL)
        publish(journal, payload)
    }

    fun requireCurrent(): FixtureConfigState = current(ownedJournal())

    private fun current(journal: JSONObject): FixtureConfigState {
        val state = read()
        val remotePayload = snapshotPayload(state.snapshot)
        check(state.owner == runId && state.desired == remotePayload &&
            owns(remotePayload, journal) && fingerprint(checkNotNull(remotePayload)) == journal.optString("expected_payload_sha256")) {
            "Fixture was superseded or only partially published"
        }
        return state
    }

    fun update(change: JSONObject.() -> Unit) {
        val journal = ownedJournal()
        val payload = current(journal).payload().apply(change)
        publish(journal, payload)
    }

    private fun publish(journal: JSONObject, payload: JSONObject) {
        val desired = payload.put(RUN_FIELD, runId).toString()
        check(desired.toByteArray(Charsets.UTF_8).size <= FrameworkConfigChannel.INLINE_LIMIT_BYTES) {
            "Fixture exceeds the inline transport limit"
        }
        val snapshot = JSONObject().put("version", FrameworkConfigChannel.VERSION)
            .put("id", UUID.randomUUID().toString()).put("published_at", System.currentTimeMillis())
            .put("payload", desired).toString()
        journal.put("previous_payload_sha256", journal.optString("expected_payload_sha256"))
        journal.put("expected_payload_sha256", fingerprint(desired))
        persist(journal)
        store.writeDesired(desired, runId, journal.original("original_desired"))
        check(store.readDesired() == (desired to runId)) { "Desired config readback changed" }
        store.writeSnapshot(snapshot)
        check(store.readSnapshot() == snapshot) { "Snapshot readback changed" }
    }

    fun restore(): Bundle {
        closeRun()
        val journal = readJournal() ?: return Bundle().apply {
            putString("snapshot_restored_exactly", "NO_PENDING_FIXTURE")
            putString("result", "PASS")
        }
        check(journal.getString("run_id") == runId) { "Fixture belongs to another run" }
        val desired = journal.original("original_desired")
        val snapshot = journal.original("original_snapshot")
        val desiredResult = runCatching {
            val (current, owner) = store.readDesired()
            val status = when {
                current == desired && owner == null -> "original"
                owner == runId && owns(current, journal) -> {
                    store.writeDesired(desired, null)
                    check(store.readDesired() == (desired to null)) { "Desired config did not restore" }
                    "restored"
                }
                owner == runId -> error("Owned desired value does not match the recovery journal")
                else -> "superseded"
            }
            store.confirmDesiredDurable()
            status
        }
        val snapshotResult = runCatching {
            val current = store.readSnapshot()
            val payload = snapshotPayload(current)
            when {
                owns(payload, journal) -> {
                    // The libxposed editor changes its client cache before acknowledging the Binder write.
                    // Persist the retry intent before we can observe a restored value only in that cache.
                    journal.put("snapshot_restore_pending", true)
                    persist(journal)
                    store.writeSnapshot(snapshot)
                    check(store.readSnapshot() == snapshot) { "Snapshot did not restore" }
                    "restored"
                }
                recordedOwner(payload) == runId -> error("Owned snapshot does not match the recovery journal")
                current == snapshot -> {
                    if (journal.optBoolean("snapshot_restore_pending")) {
                        store.writeSnapshot(current)
                        check(store.readSnapshot() == current) { "Snapshot did not restore" }
                    }
                    "original"
                }
                else -> "superseded"
            }
        }
        val failure = listOfNotNull(desiredResult.exceptionOrNull(), snapshotResult.exceptionOrNull()).firstOrNull()
        if (failure != null) throw IllegalStateException("Recovery journal retained", failure)
        file.delete()
        check(!file.baseFile.exists()) { "Recovered fixture journal could not be removed" }
        return Bundle().apply {
            val statuses = listOf(desiredResult.getOrThrow(), snapshotResult.getOrThrow())
            putString("desired_config_recovery", statuses[0])
            putString("remote_snapshot_recovery", statuses[1])
            putString("snapshot_restored_exactly", if ("superseded" in statuses) "SKIPPED_USER_CHANGED" else "PASS")
            putString("result", "PASS")
        }
    }
}

internal fun Instrumentation.verifyFixtureTransaction(): Bundle {
    val output = Bundle()
    val original = """{"active":false,"value":"baseline"}"""
    fun envelope(payload: String) = JSONObject().put("payload", payload).toString()
    val userUpdate = """{"active":false,"value":"current"}"""

    class Store : FixtureConfigStore {
        var desired: String? = original
        var owner: String? = null
        var snapshot: String? = envelope(original)
        var beforeRemoteWrite: () -> Unit = {}
        var afterRemoteMemoryWrite: () -> Unit = {}
        var afterRemoteWrite: () -> Unit = {}
        var afterLocalMemoryWrite: () -> Unit = {}
        var durableDesired: Pair<String?, String?> = original to null
        var durableSnapshot: String? = snapshot

        override fun readDesired() = desired to owner
        override fun readSnapshot() = snapshot
        override fun writeDesired(value: String?, owner: String?, userConfig: String?) {
            desired = value
            this.owner = owner
            afterLocalMemoryWrite()
            confirmDesiredDurable()
        }
        override fun confirmDesiredDurable() {
            durableDesired = desired to owner
        }
        fun restartFromDisk() {
            desired = durableDesired.first
            owner = durableDesired.second
            snapshot = durableSnapshot
        }
        override fun writeSnapshot(value: String?) {
            beforeRemoteWrite()
            snapshot = value
            afterRemoteMemoryWrite()
            durableSnapshot = value
            afterRemoteWrite()
        }
    }

    fun verify(name: String, scenario: (Store, () -> FixtureConfigJournal) -> Unit) {
        val file = AtomicFile(File(targetContext.noBackupFilesDir, "fixture-regression-${UUID.randomUUID()}.json"))
        val store = Store()
        val runId = UUID.randomUUID().toString()
        try {
            scenario(store) { FixtureConfigJournal(file, store, runId) }
            check(!file.baseFile.exists()) { "Journal remains after $name" }
            output.putString(name, "PASS")
        } finally {
            file.delete()
            File(file.baseFile.parentFile, "${file.baseFile.name}.closed-$runId").delete()
        }
    }

    try {
        verify("lost_prepare_ack") { store, open ->
            val first = open()
            first.prepare(first.read(), JSONObject().put("active", true))
            check(store.desired != original && store.snapshot != envelope(original))
            check(open().restore().getString("result") == "PASS")
            check(store.desired == original && store.snapshot == envelope(original))
            check(open().restore().getString("snapshot_restored_exactly") == "NO_PENDING_FIXTURE")
            val late = open()
            check(runCatching { late.prepare(late.read(), JSONObject().put("active", true)) }.isFailure)
        }
        verify("partial_commit") { store, open ->
            val fixture = open()
            store.beforeRemoteWrite = { error("Injected failure before remote commit") }
            check(runCatching { fixture.prepare(fixture.read(), JSONObject().put("active", true)) }.isFailure)
            check(store.desired != original && store.snapshot == envelope(original))
            store.beforeRemoteWrite = { error("An untouched snapshot must not be rewritten") }
            check(open().restore().getString("result") == "PASS")
            check(store.desired == original && store.owner == null)
        }
        verify("ambiguous_commit_and_retry") { store, open ->
            val fixture = open()
            store.afterRemoteWrite = { error("Injected failure after remote commit") }
            check(runCatching { fixture.prepare(fixture.read(), JSONObject().put("active", true)) }.isFailure)
            check(store.snapshot != envelope(original))
            store.afterRemoteWrite = {}
            store.beforeRemoteWrite = { error("Injected failure during recovery") }
            check(runCatching { open().restore() }.isFailure)
            check(store.desired == original && store.snapshot != envelope(original))
            store.beforeRemoteWrite = {}
            check(open().restore().getString("result") == "PASS")
            check(store.snapshot == envelope(original))
        }
        verify("preserve_new_user_config") { store, open ->
            val first = open()
            val baseline = first.read()
            store.desired = userUpdate
            store.snapshot = envelope(userUpdate)
            check(runCatching { first.prepare(baseline, JSONObject().put("active", true)) }.isFailure)
            check(store.desired == userUpdate && store.snapshot == envelope(userUpdate))
            first.prepare(first.read(), JSONObject().put("active", true))
            store.writeDesired(original, null)
            val recovery = open().restore()
            check(recovery.getString("desired_config_recovery") == "superseded")
            check(store.desired == original && store.snapshot == envelope(userUpdate))
        }
        verify("preserve_superseding_snapshot") { store, open ->
            val fixture = open()
            fixture.prepare(fixture.read(), JSONObject().put("active", true))
            store.writeSnapshot(envelope(userUpdate))
            store.beforeRemoteWrite = { error("Recovery tried to write the user's snapshot") }
            val recovery = open().restore()
            check(recovery.getString("remote_snapshot_recovery") == "superseded")
            check(recovery.getString("snapshot_restored_exactly") == "SKIPPED_USER_CHANGED")
            check(store.desired == original && store.durableSnapshot == envelope(userUpdate))
        }
        verify("preserve_snapshot_after_recovery_failure") { store, open ->
            val fixture = open()
            fixture.prepare(fixture.read(), JSONObject().put("active", true))
            store.afterRemoteMemoryWrite = { error("Injected failure after client cache update") }
            check(runCatching { open().restore() }.isFailure)
            store.afterRemoteMemoryWrite = {}
            store.writeSnapshot(envelope(userUpdate))
            store.beforeRemoteWrite = { error("Retry tried to write the user's snapshot") }
            val recovery = open().restore()
            check(recovery.getString("remote_snapshot_recovery") == "superseded")
            check(store.desired == original && store.durableSnapshot == envelope(userUpdate))
        }
        verify("retry_local_durability") { store, open ->
            val fixture = open()
            fixture.prepare(fixture.read(), JSONObject().put("active", true))
            val persistedFixture = store.durableDesired
            store.afterLocalMemoryWrite = { error("Injected local disk failure after memory commit") }
            check(runCatching { open().restore() }.isFailure)
            check(store.desired == original && store.durableDesired == persistedFixture)
            store.afterLocalMemoryWrite = {}
            check(open().restore().getString("result") == "PASS")
            store.restartFromDisk()
            check(store.readDesired() == (original to null))
            check(store.durableDesired == (original to null))
        }
        verify("retry_remote_durability") { store, open ->
            val fixture = open()
            fixture.prepare(fixture.read(), JSONObject().put("active", true))
            val persistedFixture = store.durableSnapshot
            store.afterRemoteMemoryWrite = { error("Injected Binder failure after client cache update") }
            check(runCatching { open().restore() }.isFailure)
            check(store.snapshot == envelope(original) && store.durableSnapshot == persistedFixture)
            store.afterRemoteMemoryWrite = {}
            check(open().restore().getString("result") == "PASS")
            store.restartFromDisk()
            check(store.snapshot == envelope(original))
        }
        runBlocking { verifyReloadObservation() }
        output.putString("preserve_updates_during_reload", "PASS")
        output.putString("result", "PASS")
    } catch (failure: Throwable) {
        output.putString("result", "FAIL: ${failure.stackTraceToString()}")
    }
    return output
}

internal suspend fun <T> Instrumentation.withFixtureConfig(runId: String?, action: (FixtureConfigJournal) -> T): T {
    val owner = requireNotNull(runId) { "fixture_run_id is required for configuration changes" }
    withTimeout(20_000) {
        while (XposedModuleStatus.mService == null) delay(100)
    }
    val manager = GlobalContext.get().get<ConfigManager>()
    return manager.withFixtureConfigAccess {
        val service = checkNotNull(XposedModuleStatus.mService) { "Framework disconnected" }
        val legacy = listOf("location-noise-original.json", "location-noise-transport-original.json",
            "remote-environment-output-original.json", "remote-environment-output-transport.json")
        check(legacy.none { File(targetContext.cacheDir, it).exists() }) { "A legacy fixture backup requires recovery" }
        val journal = AtomicFile(File(targetContext.noBackupFilesDir, "instrumentation-config-fixture.json"))
        val local = targetContext.getSharedPreferences(ConfigManager.TRANSPORT_PREFS, Context.MODE_PRIVATE)
        val remote = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
        action(FixtureConfigJournal(journal, PreferencesFixtureStore(local, remote), owner))
    }
}
