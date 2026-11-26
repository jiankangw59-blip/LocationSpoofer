package com.vincenthzr.locationspoofer

import android.app.Instrumentation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.vincenthzr.locationspoofer.data.repository.HookStatusRepository
import com.vincenthzr.locationspoofer.utils.FrameworkConfigChannel
import com.vincenthzr.locationspoofer.utils.RootManager
import com.vincenthzr.locationspoofer.utils.XposedModuleStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Checks framework configuration and system reports without changing simulation state. */
class SystemFilesInstrumentation : Instrumentation() {
    private var environmentRefresh = false
    private var moduleRuntime = false
    private var verifyConfigStatus = false
    private var moduleHotReload = false
    private var moduleHotReloadActive = false
    private var moduleHotReloadPreserveState = false
    private var remoteWifiQuery = false
    private var remoteCellQuery = false
    private var remoteWifiMetadata = false
    private var remoteOutput: String? = null
    private var locationNoiseOperation: String? = null
    private var locationNoiseSamples: String? = null
    private var importWigleCredential = false
    private var wigleCapabilities = false
    private var refreshPreviews = false
    private var mapStartup = false
    private var stabilityOperation: String? = null
    private var currentSourceDiagnosis = false
    private var diagnoseNeighborTiles = false
    private var appleTileMatrix = false
    private var appleTileSpiral = false
    private var appleWifiWloc = false
    private var appleWifiWlocControl = false
    private var wigleCurrentArea = false
    private var wigleNetworkIntegrity = false
    private var wigleCurrentPages = false
    private var appleWifiWlocAdditional = false
    private var openCellGrid = false
    private var wifiColdStart = false
    private var remotePreviewTarget = false
    private var lookupArguments: Bundle? = null
    private var wiglePagingDiagnosis = false
    private var lookupStatusRegression = false
    private var offlineIndexRegression = false
    private var offlineSourceDiagnosis = false
    private var fixtureRunId: String? = null
    private var fixtureTransactionRegression = false
    private var nativeRegistrationCleanup = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        lookupArguments = arguments?.let { Bundle(it) }
        fixtureRunId = arguments?.getString("fixture_run_id")
        fixtureTransactionRegression = arguments?.getString("check") == "fixture-transaction-regression"
        nativeRegistrationCleanup = arguments?.getString("check") == "native-registration-cleanup"
        wiglePagingDiagnosis = arguments?.getString("check") == "wigle-paging-diagnose"
        lookupStatusRegression = arguments?.getString("check") == "lookup-status-regression"
        offlineIndexRegression = arguments?.getString("check") == "offline-index-regression"
        offlineSourceDiagnosis = arguments?.getString("check") == "offline-source-diagnose"
        remotePreviewTarget = arguments?.getString("check") == "remote-preview-target"
        wifiColdStart = arguments?.getString("check") == "wifi-cold-start"
        openCellGrid = arguments?.getString("check") == "opencell-grid-current"
        wigleNetworkIntegrity = arguments?.getString("check") == "wigle-network-integrity"
        wigleCurrentPages = arguments?.getString("check") == "wigle-current-pages"
        wigleCurrentArea = arguments?.getString("check") == "wigle-current-area" || wigleCurrentPages
        appleWifiWlocControl = arguments?.getString("check") == "apple-wifi-wloc-control"
        appleWifiWlocAdditional = arguments?.getString("check") == "apple-wifi-wloc-additional"
        appleWifiWloc = arguments?.getString("check") == "apple-wifi-wloc" || appleWifiWlocControl || appleWifiWlocAdditional
        appleTileSpiral = arguments?.getString("check") == "apple-tile-spiral"
        appleTileMatrix = arguments?.getString("check") == "apple-tile-matrix" || appleTileSpiral
        environmentRefresh = arguments?.getString("check") == "environment-refresh"
        moduleRuntime = arguments?.getString("check") == "module-runtime"
        verifyConfigStatus = arguments?.getString("check") == "module-config-status"
        moduleHotReloadActive = arguments?.getString("check") == "module-hot-reload-active-probe"
        moduleHotReloadPreserveState = arguments?.getString("check") == "module-hot-reload-preserve-state"
        moduleHotReload = arguments?.getString("check") == "module-hot-reload" ||
            moduleHotReloadActive || moduleHotReloadPreserveState
        remoteWifiQuery = arguments?.getString("check") == "remote-wifi-query"
        remoteCellQuery = arguments?.getString("check") == "remote-cell-query"
        remoteWifiMetadata = arguments?.getString("check") == "remote-wifi-metadata"
        locationNoiseOperation = arguments?.getString("check")?.takeIf { it.startsWith("location-noise-") || it.startsWith("gnss-integrity-") || it.startsWith("ble-output-") }
        locationNoiseSamples = arguments?.getString("fixes")
        importWigleCredential = arguments?.getString("check") == "wigle-credential-import"
        wigleCapabilities = arguments?.getString("check") == "wigle-radio-capabilities"
        refreshPreviews = arguments?.getString("check") == "remote-preview-refresh"
        mapStartup = arguments?.getString("check") == "map-startup"
        stabilityOperation = arguments?.getString("check")?.takeIf { it.startsWith("stability-") }
        diagnoseNeighborTiles = arguments?.getString("check") == "current-source-neighbors"
        currentSourceDiagnosis = arguments?.getString("check") == "current-source-diagnose" || diagnoseNeighborTiles
        remoteOutput = arguments?.getString("check")?.takeIf {
            it == "remote-output-prepare" || it == "remote-output-restore" || it == "remote-output-nr-prepare" || it == "remote-output-full-fixture-prepare" || it == "remote-output-metadata-prepare"
        }
        start()
    }

    override fun onStart() {
        if (nativeRegistrationCleanup) {
            val output = verifyNativeRegistrationCleanup()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (fixtureTransactionRegression) {
            val output = verifyFixtureTransaction()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (offlineIndexRegression || offlineSourceDiagnosis) {
            val output = if (offlineIndexRegression) verifyOfflineCellIndex() else diagnoseOfflineCells(lookupArguments)
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (lookupStatusRegression) {
            val output = verifyLookupStatus()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (wiglePagingDiagnosis) {
            val output = diagnoseWiglePaging(lookupArguments)
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (remotePreviewTarget) {
            val output = verifyRemotePreviewTarget()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (wifiColdStart) {
            val output = verifyWifiColdStart(lookupArguments)
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (openCellGrid) {
            val output = diagnoseOpenCellGrid()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (wigleNetworkIntegrity) {
            val output = diagnoseWigleNetworkIntegrity()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (wigleCurrentArea) {
            val output = diagnoseWigleCurrentArea(wigleCurrentPages)
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (appleWifiWloc) {
            val output = diagnoseAppleWifiWloc(appleWifiWlocControl, appleWifiWlocAdditional)
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (appleTileMatrix) {
            val output = diagnoseAppleTileMatrix(appleTileSpiral)
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (currentSourceDiagnosis) {
            val output = diagnoseCurrentSources(diagnoseNeighborTiles)
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        stabilityOperation?.let { operation ->
            val output = runCatching { stabilityRegression(operation, fixtureRunId) }.getOrElse { error ->
                Bundle().apply { putString("result", "FAIL: ${error.stackTraceToString()}") }
            }
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (mapStartup) {
            val output = verifyMapStartup()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (refreshPreviews) {
            val output = refreshRemotePreviews()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (wigleCapabilities) {
            val output = verifyWigleCapabilities()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (importWigleCredential) {
            val output = Bundle()
            val file = java.io.File(targetContext.cacheDir, "wigle-credential-import")
            var stage = "private_file"
            try {
                check(file.length() in 1..512)
                stage = "read"
                val token = file.readText(Charsets.US_ASCII).trim()
                stage = "decode"
                val decoded = java.util.Base64.getDecoder().decode(token).toString(Charsets.UTF_8)
                check(decoded.substringBefore(':').isNotBlank() && decoded.substringAfter(':', "").isNotBlank())
                check(decoded.none { it == '\r' || it == '\n' })
                stage = "settings"
                check(targetContext.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                    .edit().putString("wigle_api_token", token).commit())
                output.putString("result", "PASS")
            } catch (error: Exception) { output.putString("result", "FAIL: $stage/${error.javaClass.simpleName}") }
            finally { file.delete() }
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        locationNoiseOperation?.let { operation ->
            val output = locationNoiseRegression(operation, locationNoiseSamples, fixtureRunId)
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (remoteWifiMetadata) {
            val output = verifyRemoteWifiMetadata()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        remoteOutput?.let { operation ->
            val output = runCatching { remoteEnvironmentOutput(operation == "remote-output-restore",
                operation == "remote-output-nr-prepare" || operation == "remote-output-full-fixture-prepare",
                operation == "remote-output-full-fixture-prepare" || operation == "remote-output-metadata-prepare",
                fixtureRunId)
            }.getOrElse { error -> Bundle().apply { putString("result", "FAIL: ${error.stackTraceToString()}") } }
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (remoteCellQuery) {
            val output = verifyRemoteCellQuery()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (remoteWifiQuery) {
            val output = verifyRemoteWifiQuery()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (moduleHotReload) {
            val output = verifyModuleHotReload(moduleHotReloadActive, fixtureRunId, moduleHotReloadPreserveState)
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (moduleRuntime || verifyConfigStatus) {
            val output = verifyModuleRuntime(verifyConfigStatus, fixtureRunId)
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (environmentRefresh) {
            val output = verifyEnvironmentRefresh()
            finish(if (output.getString("result")?.startsWith("PASS") == true) -1 else 0, output)
            return
        }
        val output = Bundle()
        try {
            val service = runBlocking {
                repeat(150) {
                    XposedModuleStatus.mService?.let { return@runBlocking it }
                    delay(100)
                }
                error("Framework did not connect; enable the module and restart the device")
            }
            val text = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
                .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
                ?: error("No framework configuration; start a simulation first")
            val envelope = JSONObject(text)
            check(envelope.getInt("version") == FrameworkConfigChannel.VERSION)
            val file = envelope.optString("file")
            val payload = if (file.isEmpty()) envelope.getString("payload") else {
                check(FrameworkConfigChannel.isConfigFile(file))
                ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(file))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            check(JSONObject(payload).has("active"))
            val root = RootManager()
            val noLegacyTmp = root.executeCommand("""
                for name in locationspoofer_config.json locationspoofer_config_tmp.json .lsp_selinux_probe .lsp_sepolicy_apply.sh; do
                    test ! -e /proc/1/root/data/local/tmp/${'$'}name || exit 1
                done
            """.trimIndent())
            check(noLegacyTmp != "ERROR") { "Legacy tmp artifacts remain or root access is unavailable" }
            if (BuildConfig.GLOBAL_SCHEME) {
                val reports = runBlocking {
                    val repository = HookStatusRepository(root)
                    // Reconnecting the app may republish its snapshot; status files are debounced.
                    // Wait for that publication instead of comparing an immediately stale report.
                    repeat(40) {
                        val current = repository.readAll()
                        val latest = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
                            .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
                            ?.let { JSONObject(it).getLong("published_at") }
                        if (latest != null && current.values.all {
                                it?.configPath?.startsWith("libxposed:") == true && it.configModified == latest
                            }) return@runBlocking current
                        delay(250)
                    }
                    error("System processes have not converged on the latest publication; retry while coordinates are stationary")
                }
                output.putString("receivers", reports.keys.joinToString())
            }
            output.putString("transport", if (file.isEmpty()) "remote-preferences" else "remote-file")
            output.putString("result", "PASS")
            finish(-1, output)
        } catch (error: Throwable) {
            output.putString("result", "FAIL: ${error.stackTraceToString()}")
            finish(0, output)
        }
    }
}
