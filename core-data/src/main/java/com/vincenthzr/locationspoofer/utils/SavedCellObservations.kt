package com.vincenthzr.locationspoofer.utils

/** In-memory evidence for ONE explicit local capture. Call only after writing the exact row's storage key.
 * Equal-time conflicting reports do not corroborate either value; never splice MCC/MNC from different rows.
 */
class SavedCellObservations {
    private val savedTimes = mutableMapOf<String, Long>()
    private val savedOperators = mutableMapOf<String, CellOperatorEvidence>()
    val times: Map<String, Long> get() = savedTimes
    val operators: Map<String, CellOperatorEvidence> get() = savedOperators

    fun accept(key: String, time: Long, operator: CellOperatorEvidence?) {
        if (time <= 0 || key.isBlank()) return
        val previous = savedTimes[key]
        if (previous == null || time > previous) {
            savedTimes[key] = time
            if (operator == null) savedOperators.remove(key) else savedOperators[key] = operator
        } else if (time == previous && savedOperators[key] != operator) {
            savedOperators.remove(key)
        }
    }
}
