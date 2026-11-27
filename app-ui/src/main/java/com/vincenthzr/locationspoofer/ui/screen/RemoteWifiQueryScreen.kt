package com.vincenthzr.locationspoofer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import com.vincenthzr.locationspoofer.data.model.RemoteCellRadio
import com.vincenthzr.locationspoofer.data.model.RemoteWifiMetadataStatus
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.viewmodel.RemoteWifiError
import com.vincenthzr.locationspoofer.viewmodel.RemoteWifiViewModel
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RemoteWifiQueryScreen(
    initialLatitude: Double?,
    initialLongitude: Double?,
    onBack: () -> Unit,
    onImported: (Long) -> Unit,
    viewModel: RemoteWifiViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val importCells = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importOfflineCellIndex { context.contentResolver.openInputStream(uri) }
    }
    var showCells by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { viewModel.initialize(initialLatitude, initialLongitude) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.remote_wifi_title), style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "query_form") {
                Column(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(selected = !showCells, onClick = { showCells = false }, label = { Text("Wi-Fi") })
                        FilterChip(selected = showCells, onClick = { showCells = true }, label = { Text("LTE / NR") })
                    }
                    Text(stringResource(if (showCells) R.string.remote_cell_scope else R.string.remote_wifi_scope), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.latitude,
                        onValueChange = viewModel::setLatitude,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.remote_wifi_latitude)) },
                        enabled = !state.busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.longitude,
                        onValueChange = viewModel::setLongitude,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.remote_wifi_longitude)) },
                        enabled = !state.busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RemoteWifiRegion.values().forEach { region ->
                            FilterChip(
                                selected = state.region == region,
                                onClick = { viewModel.setRegion(region) },
                                enabled = !state.busy,
                                label = {
                                    Text(stringResource(
                                        if (region == RemoteWifiRegion.MAINLAND_CHINA) R.string.remote_wifi_china
                                        else R.string.remote_wifi_global
                                    ))
                                }
                            )
                        }
                    }
                    if (!showCells) {
                        Button(onClick = viewModel::query, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.remote_wifi_query))
                        }
                    }
                }
            }
            if (showCells) item(key = "cell_query_form") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.remote_cell_title), style = MaterialTheme.typography.titleMedium)
                        Text(state.offlineCellIndex?.let { stringResource(R.string.remote_cell_offline_available, it.mcc, it.rows) }
                            ?: stringResource(R.string.remote_cell_offline_absent))
                        Text(stringResource(R.string.remote_cell_offline_scope), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { importCells.launch(arrayOf("application/octet-stream", "application/vnd.sqlite3", "application/x-sqlite3")) },
                            enabled = !state.busy) { Text(stringResource(R.string.remote_cell_offline_import)) }
                        if (state.offlineCellImportError) Text(stringResource(R.string.remote_cell_offline_error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { uriHandler.openUri("https://opencellid.org/") }) {
                            Text(stringResource(R.string.remote_cell_offline_attribution))
                        }
                        FilterChip(selected = state.manualSeed, onClick = { viewModel.setManualSeed(!state.manualSeed) },
                            enabled = !state.busy, label = { Text(stringResource(R.string.remote_cell_manual)) })
                        if (!state.manualSeed) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilterChip(selected = state.preferredRadio == null, onClick = { viewModel.setPreferredRadio(null) },
                                    enabled = !state.busy, label = { Text(stringResource(R.string.remote_cell_auto)) })
                                RemoteCellRadio.values().forEach { radio ->
                                    FilterChip(selected = state.preferredRadio == radio, onClick = { viewModel.setPreferredRadio(radio) },
                                        enabled = !state.busy, label = { Text(radio.name) })
                                }
                            }
                        }
                        if (state.manualSeed) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RemoteCellRadio.values().forEach { radio ->
                                    FilterChip(selected = state.radio == radio, onClick = { viewModel.setRadio(radio) },
                                        enabled = !state.busy, label = { Text(radio.name) })
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(state.mcc, viewModel::setMcc, Modifier.weight(1f), label = { Text("MCC") },
                                    enabled = !state.busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                OutlinedTextField(state.mnc, viewModel::setMnc, Modifier.weight(1f), label = { Text("MNC") },
                                    enabled = !state.busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            }
                            OutlinedTextField(state.tac, viewModel::setTac, Modifier.fillMaxWidth(), label = { Text("TAC") },
                                enabled = !state.busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(state.cellId, viewModel::setCellId, Modifier.fillMaxWidth(),
                                label = { Text(if (state.radio == RemoteCellRadio.NR) "NCI" else "Cell ID") },
                                enabled = !state.busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        OutlinedButton(viewModel::queryCells, Modifier.fillMaxWidth(), enabled = !state.busy) {
                            Text(stringResource(R.string.remote_cell_query))
                        }
                        state.cellError?.let { error ->
                            val message = when (error) {
                                RemoteWifiError.NO_SEED -> stringResource(R.string.remote_cell_error_seed)
                                RemoteWifiError.INVALID_SEED -> stringResource(R.string.remote_cell_error_invalid_seed)
                                RemoteWifiError.NO_COVERAGE -> stringResource(R.string.remote_cell_error_coverage)
                                RemoteWifiError.HTTP -> stringResource(R.string.remote_wifi_error_http, state.cellHttpStatus ?: 0)
                                RemoteWifiError.INVALID_COORDINATES -> stringResource(R.string.remote_wifi_error_coordinates)
                                RemoteWifiError.CACHE_READ -> stringResource(R.string.remote_wifi_error_cache_read)
                                RemoteWifiError.CACHE_WRITE -> stringResource(R.string.remote_wifi_error_cache_write)
                                RemoteWifiError.RESPONSE -> stringResource(R.string.remote_cell_error_response)
                                RemoteWifiError.IMPORT -> stringResource(R.string.remote_environment_error_import)
                                else -> stringResource(R.string.remote_wifi_error_network)
                            }
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (state.busy) {
                item(key = "loading") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.remote_wifi_loading))
                    }
                }
            }
            if (!showCells) state.error?.let { error ->
                item(key = "error") {
                    val message = when (error) {
                        RemoteWifiError.INVALID_COORDINATES -> stringResource(R.string.remote_wifi_error_coordinates)
                        RemoteWifiError.NO_COVERAGE -> stringResource(R.string.remote_wifi_error_no_coverage)
                        RemoteWifiError.SEARCH_LIMIT -> stringResource(R.string.remote_wifi_error_limit)
                        RemoteWifiError.HTTP -> stringResource(R.string.remote_wifi_error_http, state.httpStatus ?: 0)
                        RemoteWifiError.NETWORK -> stringResource(R.string.remote_wifi_error_network)
                        RemoteWifiError.RESPONSE -> stringResource(R.string.remote_wifi_error_response)
                        RemoteWifiError.CACHE_READ -> stringResource(R.string.remote_wifi_error_cache_read)
                        RemoteWifiError.CACHE_WRITE -> stringResource(R.string.remote_wifi_error_cache_write)
                        RemoteWifiError.IMPORT -> stringResource(R.string.remote_environment_error_import)
                        else -> stringResource(R.string.remote_cell_error_seed)
                    }
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
            if (!showCells) state.searchReport?.let { report ->
                item(key = "search_summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(when {
                            report.acceptedRecords > 0 -> R.string.remote_wifi_status_near
                            report.outcome == "INCOMPLETE" || report.outcome == "CANCELLED" -> R.string.remote_wifi_status_incomplete
                            report.outcome == "NO_NEARBY_RECORDS" -> R.string.remote_wifi_status_no_match
                            else -> R.string.remote_wifi_status_failed
                        }))
                        Text(stringResource(R.string.remote_wifi_search_summary, report.tileRequests,
                            report.wigleRequests, report.wlocRequests, report.acceptedRecords))
                        if (report.wigleFilterViolations > 0) Text(stringResource(R.string.remote_wifi_status_filter, report.wigleFilterViolations))
                        report.nearestRecordedMeters?.let { distance ->
                            Text(stringResource(R.string.remote_wifi_nearest_record, distance.toInt()))
                        }
                    }
                }
            }
            if (!showCells) state.snapshot?.let { snapshot ->
                item(key = "result_summary") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.remote_wifi_result_source,
                            if (snapshot.source == com.vincenthzr.locationspoofer.data.model.RemoteWifiSource.APPLE_WLOC) "Apple WLOC" else "Apple Tile"))
                        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(snapshot.fetchedAt))
                        Text(
                            stringResource(R.string.remote_wifi_result_count, snapshot.points.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(
                                if (state.fromCache) R.string.remote_wifi_cached else R.string.remote_wifi_saved,
                                date
                            )
                        )
                        Text(snapshot.region.host, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.remote_wifi_result_center, snapshot.latitude, snapshot.longitude))
                        val names = snapshot.points.count { it.metadata?.ssid != null }
                        val frequencies = snapshot.points.count { it.metadata?.frequencyMHz != null }
                        Text(stringResource(R.string.remote_wifi_metadata_count, names, frequencies, snapshot.points.size),
                            style = MaterialTheme.typography.bodySmall)
                        val metadataMessage = when (snapshot.metadataStatus) {
                            RemoteWifiMetadataStatus.NOT_REQUESTED -> stringResource(R.string.remote_wifi_metadata_pending)
                            RemoteWifiMetadataStatus.NOT_CONFIGURED -> stringResource(R.string.remote_wifi_metadata_no_key)
                            RemoteWifiMetadataStatus.MATCHED -> stringResource(R.string.remote_wifi_metadata_matched)
                            RemoteWifiMetadataStatus.NO_MATCH -> stringResource(R.string.remote_wifi_metadata_no_match)
                            RemoteWifiMetadataStatus.UNAVAILABLE -> when (snapshot.metadataHttpStatus) {
                                401, 403 -> stringResource(R.string.remote_wifi_metadata_auth, snapshot.metadataHttpStatus ?: 401)
                                null -> stringResource(R.string.remote_wifi_metadata_unavailable)
                                else -> stringResource(R.string.remote_wifi_metadata_http, snapshot.metadataHttpStatus ?: 0)
                            }
                        }
                        Text(metadataMessage, style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(R.string.remote_wifi_missing_fields),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(stringResource(R.string.remote_environment_import_note), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { viewModel.importWifi(onImported) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.remote_environment_import)) }
                    }
                }
                items(snapshot.points, key = { it.bssid }) { point ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(point.bssid, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.remote_wifi_point_coordinates, point.latitude, point.longitude))
                            Text(stringResource(R.string.remote_wifi_point_distance, point.distanceMeters))
                            val wifiMetadata = point.metadata
                            if (wifiMetadata?.channel == null && wifiMetadata?.frequencyMHz == null) {
                                point.reportedChannel?.let {
                                    Text(stringResource(R.string.remote_wifi_apple_wloc_channel, it))
                                }
                            }
                            wifiMetadata?.let { metadata ->
                                metadata.ssid?.let { ssid ->
                                    Text(if (ssid.isEmpty()) stringResource(R.string.remote_wifi_empty_ssid)
                                        else stringResource(R.string.remote_wifi_ssid, ssid))
                                }
                                val frequency = metadata.frequencyMHz
                                if (frequency != null) {
                                    Text(stringResource(R.string.remote_wifi_frequency, frequency, metadata.channel ?: 0))
                                } else metadata.channel?.let { Text(stringResource(R.string.remote_wifi_channel_unknown_band, it)) }
                                metadata.encryption?.let { Text(stringResource(R.string.remote_wifi_encryption, it), style = MaterialTheme.typography.bodySmall) }
                                metadata.observedAt?.let {
                                    Text(stringResource(R.string.remote_wifi_metadata_observed,
                                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it))),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(metadata.fetchedAt))
                                Text(stringResource(R.string.remote_wifi_metadata_time, time), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (showCells) state.cellSnapshot?.let { snapshot ->
                item(key = "cell_result_summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.remote_cell_result_count, snapshot.points.size), style = MaterialTheme.typography.titleMedium)
                        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(snapshot.fetchedAt))
                        Text(stringResource(if (state.cellFromCache) R.string.remote_wifi_cached else R.string.remote_wifi_saved, date))
                        Text(stringResource(R.string.remote_wifi_result_center, snapshot.latitude, snapshot.longitude))
                        Text("${snapshot.seed.radio} · ${snapshot.seed.mcc}/${snapshot.seed.mnc} · TAC ${snapshot.seed.tac} · ID ${snapshot.seed.id}")
                        Text(stringResource(R.string.remote_environment_import_note), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { viewModel.importCells(onImported) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.remote_environment_import)) }
                    }
                }
                items(snapshot.points, key = { "cell_${it.identity}" }) { point ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${point.identity.radio} · ${point.identity.mcc}/${point.identity.mnc} · TAC ${point.identity.tac} · ID ${point.identity.id}")
                            Text(stringResource(R.string.remote_wifi_point_coordinates, point.latitude, point.longitude))
                            Text(stringResource(R.string.remote_wifi_point_distance, point.distanceMeters))
                        }
                    }
                }
            }
        }
    }
}
