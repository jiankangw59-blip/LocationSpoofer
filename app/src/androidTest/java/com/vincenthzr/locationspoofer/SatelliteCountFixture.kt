package com.vincenthzr.locationspoofer

import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.security.MessageDigest

internal fun Instrumentation.satelliteCountFixture(
    phase: String?,
    runId: String?,
    expectedPayloadSha256: String?
): Bundle = runBlocking {
    runCatching {
        withFixtureConfig(runId) { fixture ->
            if (phase == "restore") {
                return@withFixtureConfig fixture.restore().apply {
                    if (getString("snapshot_restored_exactly") == "SKIPPED_USER_CHANGED") {
                        putString("result", "SUPERSEDED")
                    }
                }
            }
            val state = when (phase) {
                "prepare" -> fixture.prepareSatelliteCount(
                    requireNotNull(expectedPayloadSha256) { "expected_payload_sha256 is required" }
                )
                "status" -> fixture.status()
                else -> error("Unknown satellite-count fixture phase")
            }
            val payload = state.payload()
            val snapshot = JSONObject(checkNotNull(state.snapshot))
            val payloadText = snapshot.getString("payload")
            check(payloadText == state.desired) { "Desired and inline remote configuration differ" }
            val hash = MessageDigest.getInstance("SHA-256").digest(payloadText.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            Bundle().apply {
                putString("result", "PASS")
                putString("phase", phase)
                putString("fixture_run_id", fixture.runId)
                putBoolean("fixture_owned", state.owner == fixture.runId)
                putString("snapshot_id", snapshot.getString("id"))
                putString("payload_sha256", hash)
                putBoolean("active", payload.optBoolean("active"))
                if (payload.has("satellite_count")) putInt("satellite_count", payload.getInt("satellite_count"))
            }
        }
    }.getOrElse { error ->
        Bundle().apply {
            putString("phase", phase)
            putString("result", "FAIL: ${error.stackTraceToString()}")
        }
    }
}
