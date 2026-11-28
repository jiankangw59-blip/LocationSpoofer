package com.vincenthzr.locationspoofer.ui.screen

/** The native map SDKs report programmatic animations through the same move/idle callbacks
 * as a finger drag. Observe input without consuming it; only complete an actual drag once.
 * A camera can go idle just before the final finger is released, or after a fling. */
internal class PointMapGesture {
    private var pressed = false
    private var dragged = false
    private var settled: Pair<Double, Double>? = null

    fun down() { reset(); pressed = true }
    fun drag() { if (pressed) dragged = true }
    fun move(): Boolean { settled = null; return dragged }
    fun idle(lat: Double, lng: Double): Boolean {
        if (!dragged) return false
        if (pressed) {
            settled = lat to lng
            return false
        }
        reset()
        return true
    }
    fun up(): Pair<Double, Double>? {
        pressed = false
        val point = settled
        if (point != null || !dragged) reset()
        return point
    }
    fun reset() { pressed = false; dragged = false; settled = null }
}
