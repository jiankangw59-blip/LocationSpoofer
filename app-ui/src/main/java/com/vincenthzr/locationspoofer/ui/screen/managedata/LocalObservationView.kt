package com.vincenthzr.locationspoofer.ui.screen.managedata

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.utils.LocalObservationJournal
import com.vincenthzr.locationspoofer.utils.LocalObservationMark
import com.vincenthzr.locationspoofer.utils.LocalObservationSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal sealed interface HistoryRead {
    object Loading : HistoryRead
    object Unavailable : HistoryRead
    data class Ready(val snapshot: LocalObservationSnapshot) : HistoryRead
}

// One off-main read for the opened point, not one database read per device or recomposition.
@Composable
internal fun rememberLocalHistory(id: Long, pointTimestamp: Long): HistoryRead {
    val context = LocalContext.current.applicationContext
    return key(context, id, pointTimestamp) {
        produceState<HistoryRead>(HistoryRead.Loading) {
            try {
                val journal = withContext(Dispatchers.IO) { LocalObservationJournal.get(context) }
                // The StateFlow can conflate changes at several points into one notification.
                // While this dialog is open, reread only its point even if the last event names another.
                journal.changedPoint.collect {
                    value = withContext(Dispatchers.IO) { HistoryRead.Ready(journal.snapshot(id, pointTimestamp)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                value = HistoryRead.Unavailable
            }
        }.value
    }
}

@Composable
internal fun ObservationTimeText(mark: LocalObservationMark?) {
    val locale = LocalConfiguration.current.locales[0]
    val label = if (mark == null) stringResource(R.string.obs_unknown_time) else {
        val time = remember(mark.lastSeenAtMs, locale) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale).format(Date(mark.lastSeenAtMs))
        }
        stringResource(if (mark.inLatestRound) R.string.obs_latest_time else R.string.obs_prior_time, time)
    }
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
