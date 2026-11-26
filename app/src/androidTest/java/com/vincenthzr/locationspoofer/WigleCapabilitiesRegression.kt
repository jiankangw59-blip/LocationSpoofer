package com.vincenthzr.locationspoofer

import android.os.Bundle
import com.vincenthzr.locationspoofer.data.model.RemoteCellRadio
import com.vincenthzr.locationspoofer.data.model.RemoteCellIdentity
import com.vincenthzr.locationspoofer.data.repository.SettingsRepository
import com.vincenthzr.locationspoofer.utils.*
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.koin.core.context.GlobalContext

/** Only counts, protocol types and field names leave the test, never device IDs, names or credentials. */
internal fun SystemFilesInstrumentation.verifyWigleCapabilities(): Bundle = runBlocking {
    val output = Bundle()
    withTimeout(20_000) { while (XposedModuleStatus.mService == null) delay(100) }
    val koin = GlobalContext.get()
    val settings = koin.get<SettingsRepository>()
    val transport = koin.get<WigleTransport>()
    val token = settings.getWigleApiToken().trim()
    suspend fun inspect(label: String, path: String, lat: Double, lng: Double, span: Double, nrOnly: Boolean = false) {
        try {
            val builder = ("https://api.wigle.net/api/v2/" + path + "/search").toHttpUrl().newBuilder()
                .addQueryParameter("latrange1", (lat-span).toString()).addQueryParameter("latrange2", (lat+span).toString())
                .addQueryParameter("longrange1", (lng-span).toString()).addQueryParameter("longrange2", (lng+span).toString())
                .addQueryParameter("resultsPerPage", if (nrOnly) "100" else "5")
            if (nrOnly) for (radio in listOf("Nr", "Lte", "Gsm", "Cdma", "Wcdma")) builder.addQueryParameter("show$radio", (radio == "Nr").toString())
            val request = Request.Builder().url(builder.build()).header("Authorization", "Basic $token").header("Accept", "application/json").build()
            val json = JSONObject(transport.fetch(request).toString(Charsets.UTF_8))
            output.putBoolean(label + "_success", json.optBoolean("success"))
            val records = json.optJSONArray("results")
            var withinBox = 0
            val types = linkedSetOf<String>()
            for (index in 0 until (records?.length() ?: 0)) {
                val entry = records!!.getJSONObject(index)
                if (entry.optDouble("trilat", Double.NaN) in (lat-span)..(lat+span) &&
                    entry.optDouble("trilong", Double.NaN) in (lng-span)..(lng+span)) withinBox++
                types.add(entry.optString("gentype", entry.optString("type", "unknown")))
            }
            output.putInt(label + "_within_requested_box", withinBox)
            output.putString(label + "_radio_types", types.joinToString(","))
            if (nrOnly) {
                // Official WiGLE Android uses PLMN_TAC_NCI and "NR;operator".
                // Generic GSM labels, MACs, and SSIDs containing "5G" establish no NR seed.
                val valid = (0 until (records?.length() ?: 0)).count { index ->
                    val entry = records!!.getJSONObject(index)
                    val key = entry.optString("id").split('_')
                    if (entry.optString("attributes").substringBefore(';') != "NR" || key.size != 3 || key[0].length !in 5..6) false
                    else runCatching {
                        RemoteCellIdentity(RemoteCellRadio.NR, key[0].take(3).toInt(), key[0].drop(3).toInt(),
                            key[1].toInt(), key[2].toLong()).validate()
                    }.isSuccess
                }
                output.putInt(label + "_valid_seed_candidates", valid)
            }
            if (label == "target_nr" || label == "target_bluetooth")
                java.io.File(targetContext.cacheDir, "wigle-capability-$label.json").writeText(json.toString())
            output.putInt(label + "_records", records?.length() ?: 0)
            output.putLong(label + "_total", json.optLong("totalResults", -1))
            val first = records?.optJSONObject(0)
            if (first != null) {
                output.putString(label + "_fields", first.keys().asSequence().sorted().joinToString(","))
                output.putBoolean(label + "_has_frequency", first.optInt("frequency", 0) > 0)
            }
        } catch (error: Exception) {
            output.putString(label + "_failure", error.javaClass.simpleName)
            (error as? RemoteWifiQueryException)?.let { output.putInt(label + "_http", it.httpStatus) }
        }
    }
    try {
        inspect("reference_wifi", "network", 37.7749, -122.4194, 0.002)
        inspect("target_bluetooth", "bluetooth", 30.6544979, 113.1366294, 0.005)
        if (output.containsKey("target_bluetooth_failure"))
            inspect("target_bluetooth_retry", "bluetooth", 30.6544979, 113.1366294, 0.002)
        inspect("reference_bluetooth", "bluetooth", 37.7749, -122.4194, 0.002)
        inspect("target_nr", "cell", 30.6544979, 113.1366294, 0.05, true)
        try {
            val seed = koin.get<AppleCellClient>().findSeed(30.6544979, 113.1366294,
                settings.getOpencellidApiToken(), RemoteCellRadio.NR)
            output.putBoolean("opencellid_nr_seed_found", seed != null)
        } catch (error: Exception) {
            output.putString("opencellid_nr_failure", error.javaClass.simpleName)
            (error as? RemoteWifiQueryException)?.let { output.putInt("opencellid_nr_http", it.httpStatus) }
        }
        output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: ${error.javaClass.simpleName}") }
    output
}
