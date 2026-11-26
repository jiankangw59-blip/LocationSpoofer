package com.vincenthzr.locationspoofer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import com.vincenthzr.locationspoofer.data.model.RemoteWifiSnapshot
import com.vincenthzr.locationspoofer.data.model.RemoteCellSnapshot
import com.vincenthzr.locationspoofer.data.model.RemoteCellRadio
import com.vincenthzr.locationspoofer.data.model.RemoteCellIdentity
import com.vincenthzr.locationspoofer.data.repository.RemoteCellRepository
import com.vincenthzr.locationspoofer.data.repository.RemoteEnvironmentImporter
import com.vincenthzr.locationspoofer.utils.RemoteCellNoSeedException
import com.vincenthzr.locationspoofer.utils.RemoteCellNoCoverageException
import com.vincenthzr.locationspoofer.data.repository.RemoteWifiCacheException
import com.vincenthzr.locationspoofer.data.repository.RemoteWifiRepository
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.utils.AppleWifiSearchReport
import com.vincenthzr.locationspoofer.utils.OfflineCellSeedIndex
import com.vincenthzr.locationspoofer.utils.OfflineCellIndexInfo
import com.vincenthzr.locationspoofer.utils.RemoteWifiTimeoutException
import com.vincenthzr.locationspoofer.utils.RemoteWifiSearchIncompleteException
import com.vincenthzr.locationspoofer.utils.RemoteWifiNoCoverageException
import com.vincenthzr.locationspoofer.utils.RemoteWifiQueryException
import com.vincenthzr.locationspoofer.utils.RemoteWifiResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.io.InputStream

enum class RemoteWifiError {
    INVALID_COORDINATES, NO_COVERAGE, HTTP, NETWORK, RESPONSE, CACHE_READ, CACHE_WRITE, NO_SEED, IMPORT, INVALID_SEED, SEARCH_LIMIT
}

data class RemoteWifiUiState(
    val latitude: String = "",
    val longitude: String = "",
    val region: RemoteWifiRegion = RemoteWifiRegion.MAINLAND_CHINA,
    val busy: Boolean = true,
    val snapshot: RemoteWifiSnapshot? = null,
    val fromCache: Boolean = false,
    val error: RemoteWifiError? = null,
    val httpStatus: Int? = null,
    val cellHttpStatus: Int? = null,
    val cellSnapshot: RemoteCellSnapshot? = null,
    val cellFromCache: Boolean = false,
    val cellError: RemoteWifiError? = null,
    val manualSeed: Boolean = false,
    val preferredRadio: RemoteCellRadio? = null,
    val radio: RemoteCellRadio = RemoteCellRadio.LTE,
    val mcc: String = "460",
    val mnc: String = "0",
    val tac: String = "",
    val cellId: String = "",
    val searchReport: AppleWifiSearchReport? = null,
    val offlineCellIndex: OfflineCellIndexInfo? = null,
    val offlineCellImportError: Boolean = false
)

class RemoteWifiViewModel(
    private val repository: RemoteWifiRepository,
    private val cellRepository: RemoteCellRepository,
    private val importer: RemoteEnvironmentImporter,
    private val offlineCells: OfflineCellSeedIndex? = null
) : ViewModel() {
    private val _state = MutableStateFlow(RemoteWifiUiState())
    val state = _state.asStateFlow()
    private var initialized = false

    fun initialize(gcjLatitude: Double?, gcjLongitude: Double?) {
        if (initialized) return
        initialized = true
        val initial = if (gcjLatitude != null && gcjLongitude != null) {
            CoordinateUtils.gcj02ToWgs84(gcjLatitude, gcjLongitude)
        } else null
        _state.update {
            it.copy(
                latitude = initial?.lat?.let { lat -> String.format(Locale.US, "%.7f", lat) } ?: "",
                longitude = initial?.lng?.let { lng -> String.format(Locale.US, "%.7f", lng) } ?: "",
                region = if (initial != null && initial.lat in 18.0..54.0 && initial.lng in 73.0..135.0) {
                    RemoteWifiRegion.MAINLAND_CHINA
                } else RemoteWifiRegion.GLOBAL
            )
        }
        viewModelScope.launch {
            try {
                val cached = repository.loadLatest()
                _state.update {
                    if (cached == null || initial != null && GeoMath.distance(initial,
                            CoordinateUtils.LatLng(cached.latitude, cached.longitude)) > 2.0) it else it.copy(
                        latitude = if (initial == null) cached.latitude.toString() else it.latitude,
                        longitude = if (initial == null) cached.longitude.toString() else it.longitude,
                        region = if (initial == null) cached.region else it.region,
                        snapshot = cached,
                        fromCache = true,
                        busy = true
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(error = RemoteWifiError.CACHE_READ) }
            }
            try {
                val cached = cellRepository.loadLatest()
                _state.update {
                    val matched = cached?.takeIf { cell -> matchesTarget(it, cell.latitude, cell.longitude, cell.region) }
                    it.copy(cellSnapshot = matched, cellFromCache = matched != null)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.update { it.copy(cellError = RemoteWifiError.CACHE_READ) } }
            try {
                val current = _state.value
                val lat = current.latitude.toDoubleOrNull(); val lon = current.longitude.toDoubleOrNull()
                if (lat != null && lon != null) {
                    val report = repository.loadSearchReport(lat, lon, current.region)
                    _state.update { if (matchesTarget(it, lat, lon, current.region)) it.copy(searchReport = report) else it }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.update { it.copy(error = RemoteWifiError.CACHE_READ) } }
            try {
                val info = offlineCells?.status()
                _state.update { it.copy(offlineCellIndex = info) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.update { it.copy(offlineCellImportError = true) } }
            _state.update { it.copy(busy = false) }
        }
    }

    fun importOfflineCellIndex(open: () -> InputStream?) {
        val index = offlineCells ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, offlineCellImportError = false) }
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { checkNotNull(open()).use { index.install(it) } }
                _state.update { it.copy(offlineCellIndex = imported) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.update { it.copy(offlineCellImportError = true) } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    fun setLatitude(value: String) { _state.update { it.copy(latitude = value, error = null, cellError = null, snapshot = null, cellSnapshot = null, fromCache = false, cellFromCache = false, searchReport = null) } }
    fun setLongitude(value: String) { _state.update { it.copy(longitude = value, error = null, cellError = null, snapshot = null, cellSnapshot = null, fromCache = false, cellFromCache = false, searchReport = null) } }
    fun setRegion(value: RemoteWifiRegion) { _state.update { it.copy(region = value, error = null, cellError = null, snapshot = null, cellSnapshot = null, fromCache = false, cellFromCache = false, searchReport = null) } }
    fun setManualSeed(value: Boolean) { _state.update { it.copy(manualSeed = value, cellError = null) } }
    fun setRadio(value: RemoteCellRadio) { _state.update { it.copy(radio = value, cellError = null) } }
    fun setPreferredRadio(value: RemoteCellRadio?) { _state.update { it.copy(preferredRadio = value, cellError = null) } }
    fun setMcc(value: String) { _state.update { it.copy(mcc = value, cellError = null) } }
    fun setMnc(value: String) { _state.update { it.copy(mnc = value, cellError = null) } }
    fun setTac(value: String) { _state.update { it.copy(tac = value, cellError = null) } }
    fun setCellId(value: String) { _state.update { it.copy(cellId = value, cellError = null) } }

    fun queryCells() {
        val current = _state.value
        if (current.busy) return
        val lat = current.latitude.trim().toDoubleOrNull()
        val lng = current.longitude.trim().toDoubleOrNull()
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite() ||
            lat !in -85.05112878..85.05112878 || lng !in -180.0..180.0) {
            _state.update { it.copy(cellError = RemoteWifiError.INVALID_COORDINATES) }; return
        }
        val seed = if (current.manualSeed) {
            try {
                RemoteCellIdentity(current.radio, current.mcc.trim().toInt(), current.mnc.trim().toInt(),
                    current.tac.trim().toInt(), current.cellId.trim().toLong()).also { it.validate() }
            } catch (_: IllegalArgumentException) {
                _state.update { it.copy(cellError = RemoteWifiError.INVALID_SEED) }; return
            }
        } else null
        _state.update { it.copy(busy = true, cellError = null, cellHttpStatus = null) }
        viewModelScope.launch {
            try {
                val snapshot = cellRepository.query(lat, lng, current.region, seed,
                    if (current.manualSeed) current.radio else current.preferredRadio)
                _state.update { if (matchesTarget(it, lat, lng, current.region))
                    it.copy(cellSnapshot = snapshot, cellFromCache = false, busy = false) else it.copy(busy = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val problem = when (error) {
                    is RemoteCellNoSeedException -> RemoteWifiError.NO_SEED
                    is RemoteCellNoCoverageException -> RemoteWifiError.NO_COVERAGE
                    is RemoteWifiQueryException -> RemoteWifiError.HTTP
                    is RemoteWifiResponseException -> RemoteWifiError.RESPONSE
                    is RemoteWifiCacheException -> RemoteWifiError.CACHE_WRITE
                    else -> RemoteWifiError.NETWORK
                }
                _state.update { it.copy(busy = false, cellError = problem, cellHttpStatus = (error as? RemoteWifiQueryException)?.httpStatus) }
            }
        }
    }

    fun importWifi(onImported: (Long) -> Unit) = importResult(false, onImported)
    fun importCells(onImported: (Long) -> Unit) = importResult(true, onImported)

    private fun importResult(cells: Boolean, onImported: (Long) -> Unit) {
        val current = _state.value
        if (current.busy || (if (cells) current.cellSnapshot == null else current.snapshot == null)) return
        val valid = if (cells) current.cellSnapshot!!.let { matchesTarget(current, it.latitude, it.longitude, it.region) }
            else current.snapshot!!.let { matchesTarget(current, it.latitude, it.longitude, it.region) }
        if (!valid) return
        _state.update { it.copy(busy = true, error = null, cellError = null) }
        viewModelScope.launch {
            try {
                val id = if (cells) importer.importCells(checkNotNull(current.cellSnapshot))
                    else importer.importWifi(checkNotNull(current.snapshot))
                _state.update { it.copy(busy = false) }
                onImported(id)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.update { if (cells) it.copy(busy = false, cellError = RemoteWifiError.IMPORT)
                else it.copy(busy = false, error = RemoteWifiError.IMPORT) } }
        }
    }

    fun query() {
        val current = _state.value
        if (current.busy) return
        val latitude = current.latitude.trim().toDoubleOrNull()
        val longitude = current.longitude.trim().toDoubleOrNull()
        if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -85.05112878..85.05112878 || longitude !in -180.0..180.0) {
            _state.update { it.copy(error = RemoteWifiError.INVALID_COORDINATES) }
            return
        }
        _state.update { it.copy(busy = true, error = null, httpStatus = null, searchReport = null) }
        viewModelScope.launch {
            try {
                val snapshot = repository.query(latitude, longitude, current.region)
                _state.update { if (matchesTarget(it, latitude, longitude, current.region))
                    it.copy(snapshot = snapshot, fromCache = false, busy = false, searchReport = repository.lastSearchReport)
                    else it.copy(busy = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val problem = when (error) {
                    is RemoteWifiNoCoverageException -> RemoteWifiError.NO_COVERAGE
                    is RemoteWifiTimeoutException, is RemoteWifiSearchIncompleteException -> RemoteWifiError.SEARCH_LIMIT
                    is RemoteWifiQueryException -> RemoteWifiError.HTTP
                    is RemoteWifiResponseException -> RemoteWifiError.RESPONSE
                    is RemoteWifiCacheException -> RemoteWifiError.CACHE_WRITE
                    else -> RemoteWifiError.NETWORK
                }
                _state.update {
                    it.copy(busy = false, error = problem, httpStatus = (error as? RemoteWifiQueryException)?.httpStatus,
                        searchReport = repository.lastSearchReport)
                }
            }
        }
    }

    private fun matchesTarget(state: RemoteWifiUiState, latitude: Double, longitude: Double, region: RemoteWifiRegion): Boolean {
        val lat = state.latitude.toDoubleOrNull() ?: return false
        val lon = state.longitude.toDoubleOrNull() ?: return false
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 || region != state.region) return false
        return GeoMath.distance(CoordinateUtils.LatLng(lat, lon), CoordinateUtils.LatLng(latitude, longitude)) <= 2.0
    }
}
