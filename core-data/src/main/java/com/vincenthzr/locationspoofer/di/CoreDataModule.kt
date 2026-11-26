package com.vincenthzr.locationspoofer.di

import com.vincenthzr.locationspoofer.data.db.AppDatabase
import com.vincenthzr.locationspoofer.data.motion.ConfigMotionSink
import com.vincenthzr.locationspoofer.data.motion.MotionController
import com.vincenthzr.locationspoofer.data.state.SpoofingState
import com.vincenthzr.locationspoofer.data.repository.LocationRepository
import com.vincenthzr.locationspoofer.data.repository.SettingsRepository
import com.vincenthzr.locationspoofer.data.repository.WifiRepository
import com.vincenthzr.locationspoofer.utils.ConfigManager
import com.vincenthzr.locationspoofer.utils.EnvironmentScanner
import com.vincenthzr.locationspoofer.utils.LSPosedManager
import com.vincenthzr.locationspoofer.utils.OpenCellIdClient
import com.vincenthzr.locationspoofer.utils.OpenCellSearchClient
import com.vincenthzr.locationspoofer.utils.OfflineCellSeedIndex
import com.vincenthzr.locationspoofer.utils.RootManager
import com.vincenthzr.locationspoofer.utils.SettingsManager
import com.vincenthzr.locationspoofer.utils.WigleClient
import com.vincenthzr.locationspoofer.utils.WigleTransport
import com.vincenthzr.locationspoofer.data.repository.HookStatusRepository
import com.vincenthzr.locationspoofer.data.repository.RemoteWifiRepository
import com.vincenthzr.locationspoofer.utils.AppleWifiTileClient
import com.vincenthzr.locationspoofer.utils.AppleCellClient
import com.vincenthzr.locationspoofer.utils.AppleWifiDiscovery
import com.vincenthzr.locationspoofer.utils.AppleWifiWlocClient
import com.vincenthzr.locationspoofer.utils.WigleSeedSource
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.data.db.EnvironmentDao
import com.vincenthzr.locationspoofer.utils.WifiMetadataEnricher
import com.vincenthzr.locationspoofer.utils.FeedMeProxyLeaseProvider
import com.vincenthzr.locationspoofer.data.repository.RemoteCellRepository
import com.vincenthzr.locationspoofer.data.repository.RemoteEnvironmentImporter
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val coreDataModule = module {
    single { RootManager() }
    single { ConfigManager(androidContext(), get()) }
    single { LSPosedManager() }
    single { SettingsManager(androidContext()) }
    single { EnvironmentScanner(androidContext()) }

    single { WigleTransport(proxyProvider = get<FeedMeProxyLeaseProvider>()) }
    single { WigleClient(get()) }
    single { OpenCellSearchClient() }
    single { OpenCellIdClient(get()) }
    single { WifiRepository(get(), get(), get()) }
    single { AppleWifiTileClient() }
    single { AppleWifiWlocClient() }
    single { WigleSeedSource(get()) }
    single {
        AppleWifiDiscovery(get(), get(), get(), get<SettingsRepository>()::getWigleApiToken,
            localSeeds = { lat, lon ->
                val point = CoordinateUtils.wgs84ToGcj02(lat, lon)
                get<EnvironmentDao>().getNearestLocations(point.lat, point.lng, 10)
                    .filter { GeoMath.distance(point, CoordinateUtils.LatLng(it.location.lat, it.location.lng)) <= 8_000 }
                    .flatMap { it.wifis }.map { it.device.bssid }.distinct().take(12)
            })
    }
    single { FeedMeProxyLeaseProvider(androidContext(), get()) }
    single { WifiMetadataEnricher(get<SettingsRepository>()::getWigleApiToken, transport = get()) }
    single { RemoteWifiRepository(get(), File(androidContext().filesDir, "remote_wifi_preview.json"), get(), get(),
        File(androidContext().filesDir, "remote_wifi_lookup_status.json")) }
    single { OfflineCellSeedIndex(File(androidContext().filesDir, "offline_lte_cells.sqlite")) }
    single { AppleCellClient(openCells = get(), offlineSeeds = get()) }
    single { RemoteCellRepository(get(), get(), get(), File(androidContext().filesDir, "remote_cell_preview.json")) }
    single { RemoteEnvironmentImporter(get()) }

    single { LocationRepository(get(), get(), get(), get(), get(), get()) }
    single { SettingsRepository(get()) }
    single { HookStatusRepository(get()) }
    single {
        val settings = get<SettingsManager>()
        MotionController(
            sink = ConfigMotionSink(get(), get()),
            realismParams = {
                (SpoofingState.realismLevel.takeIf { it >= 0 } ?: settings.realismLevel) to
                    (SpoofingState.speedFluctuationPct.takeIf { it >= 0 } ?: settings.speedFluctuationPct)
            }
        )
    }

    single { AppDatabase.getDatabase(androidContext()) }
    single { get<AppDatabase>().environmentDao() }
    single { get<AppDatabase>().savedRouteDao() }
}
