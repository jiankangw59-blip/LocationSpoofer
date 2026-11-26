package com.vincenthzr.locationspoofer.utils

import kotlin.math.pow

/** A bounded view of a source pool; indices refer to the caller's unchanged records. */
object VisibleCells {
    const val LIMIT = 12
    data class Candidate(val identity: String, val dbm: Int, val registered: Boolean, val distanceMeters: Double? = null)
    fun select(source: List<Candidate>, limit: Int = LIMIT): List<Int> {
        require(limit in 1..32)
        fun rank(index: Int): Double = source[index].distanceMeters?.takeIf { it.isFinite() && it >= 0 }
            ?: 10.0.pow((-55.0-source[index].dbm.coerceIn(-140,-40))/20.0)
        val order = compareBy<Int>(::rank).thenBy { source[it].identity }
        val unique = source.indices.groupBy { source[it].identity }.values.map { duplicates ->
            duplicates.filter { source[it].registered }.minWithOrNull(order) ?: duplicates.minWith(order)
        }
        val serving = unique.filter { source[it].registered }.minWithOrNull(order) ?: unique.minWithOrNull(order)
            ?: return emptyList()
        return listOf(serving) + unique.filter { it != serving }.sortedWith(order).take(limit-1)
    }
}
