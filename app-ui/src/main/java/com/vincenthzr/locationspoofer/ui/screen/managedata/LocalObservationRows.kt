package com.vincenthzr.locationspoofer.ui.screen.managedata

import com.vincenthzr.locationspoofer.utils.LocalObservationMark
import com.vincenthzr.locationspoofer.utils.LocalObservationSnapshot
import com.vincenthzr.locationspoofer.utils.ObservationKind
import java.util.Locale

// Keep the stored precision when the formatted editor text was left untouched.
internal fun storedOrEditedCoordinate(text: String, stored: Double): Double =
    if (text == String.format(Locale.US, "%.6f", stored)) stored else text.toDoubleOrNull() ?: stored

internal class ObservationRows<T>(
    val shown: List<T>,
    val marks: Map<String, LocalObservationMark>
)

internal fun <T> observationRows(
    records: List<T>, snapshot: LocalObservationSnapshot?, kind: ObservationKind,
    latestOnly: Boolean, id: (T) -> String
): ObservationRows<T> {
    return projectObservations(records, latestOnly, id) { snapshot?.lookup(kind, it) }
}

internal fun <T> projectObservations(
    records: List<T>, latestOnly: Boolean, id: (T) -> String, lookup: (String) -> LocalObservationMark?
): ObservationRows<T> {
    val marks = records.mapNotNull { row ->
        val key = id(row)
        lookup(key)?.let { key to it }
    }.toMap()
    return ObservationRows(if (latestOnly) records.filter { marks[id(it)]?.inLatestRound == true } else records, marks)
}
