package com.vincenthzr.locationspoofer.xposed.hooks

/** The clock belongs to the GPS producer. Listener registration never advances it. */
internal class GpsProductionSession<R> {
    enum class Kind { STARTED, FIRST_FIX, STOPPED }
    enum class Source { HEARTBEAT, PROVIDER_REPORT }

    class Epoch internal constructor(val startElapsedNs: Long) {
        internal var firstFixElapsedNs: Long? = null
        internal var missedAdmission = false
    }

    data class Event(
        val kind: Kind, val startElapsedNs: Long, val elapsedNs: Long, val source: Source? = null
    ) {
        val ttffMs: Int get() = ((elapsedNs - startElapsedNs) / 1_000_000L).toInt()
    }

    class Delivery<R>(val event: Event, val recipients: List<R>)
    data class Handoff<R>(val clock: LongArray, val unclaimed: List<Delivery<R>>)

    private var demand: Boolean? = null
    private var epoch: Epoch? = null
    private var paused = false
    private var closed = false
    private val pending = ArrayDeque<Delivery<R>>()

    @Synchronized
    fun updateDemand(required: Boolean, elapsedNs: Long): Event? {
        if (paused || closed || required == demand) return null
        val wasIdle = demand == false
        demand = required
        if (!required) {
            val previous = epoch
            epoch = null
            return previous?.let { Event(Kind.STOPPED, it.startElapsedNs, elapsedNs) }
        }
        if (!wasIdle) return null // Activity already existed when observation began.
        epoch = Epoch(elapsedNs)
        return Event(Kind.STARTED, elapsedNs, elapsedNs)
    }

    @Synchronized
    fun pendingFirstFix(): Epoch? =
        if (paused || closed) null else epoch?.takeIf { it.firstFixElapsedNs == null && !it.missedAdmission }

    @Synchronized
    fun needsFixObservation(): Boolean = !paused && !closed && (demand != true || pendingFirstFix() != null)

    @Synchronized
    fun missAdmission(expected: Epoch?) {
        if (paused || closed) return
        if (expected == null && demand != true) demand = true
        val current = pendingFirstFix()
        if (expected == null || expected === current) current?.missedAdmission = true
    }

    @Synchronized
    fun acceptFirstFix(expected: Epoch?, elapsedNs: Long, source: Source): Event? {
        if (paused || closed) return null
        val current = pendingFirstFix()
        if (current == null) {
            // A provider report can be accepted before the asynchronous demand scan.
            // That activity already has a fix with an unobserved session start.
            if (expected == null && demand != true) demand = true
            return null
        }
        if (expected != null && expected !== current) return null
        // Even when the return is later, an operation entered before start
        // cannot establish that this epoch acquired its own first fix.
        if (expected == null || elapsedNs < current.startElapsedNs) {
            current.missedAdmission = true
            return null
        }
        current.firstFixElapsedNs = elapsedNs
        return Event(Kind.FIRST_FIX, current.startElapsedNs, elapsedNs, source)
    }

    @Synchronized
    fun enqueue(event: Event, recipients: List<R>) {
        if (!paused && !closed) pending.addLast(Delivery(event, recipients))
    }

    @Synchronized
    fun nextDelivery(): Delivery<R>? = if (paused || closed) null else pending.firstOrNull()

    /** The successful caller now owns this delivery, including across a subsequent reload. */
    @Synchronized
    fun claim(expected: Delivery<R>): Boolean {
        if (nextDelivery() !== expected) return false
        pending.removeFirst()
        return true
    }

    @Synchronized
    fun pauseForReload(): Handoff<R> {
        paused = true
        val recorded = epoch
        val clock = when {
            demand == false -> longArrayOf(0)
            // Preserve a known start for stop semantics, without backdating an
            // unobserved first fix across the period in which publication is paused.
            recorded != null -> longArrayOf(1, recorded.startElapsedNs, recorded.firstFixElapsedNs ?: -1L)
            else -> longArrayOf()
        }
        return Handoff(clock, pending.toList())
    }

    @Synchronized
    fun cancelReload() {
        if (!paused || closed) return
        paused = false
        epoch?.let { if (it.firstFixElapsedNs == null) it.missedAdmission = true }
    }

    @Synchronized
    fun restore(saved: Any?, unclaimed: List<Delivery<R>> = emptyList(), pause: Boolean = false) {
        if (demand != null || paused || closed) return
        val values = saved as? LongArray ?: return
        pending.addAll(unclaimed)
        paused = pause
        if (!pause && values.size == 1 && values[0] == 0L) demand = false
        if (values.size == 3 && values[0] == 1L && values[1] >= 0L && (values[2] == -1L || values[2] >= values[1])) {
            demand = true
            epoch = Epoch(values[1]).apply {
                if (values[2] == -1L) missedAdmission = true
                else firstFixElapsedNs = values[2]
            }
        }
    }

    @Synchronized
    fun close() {
        closed = true
        epoch = null
        pending.clear()
    }
}

internal fun matchesGpsLifecyclePermit(permit: ThreadLocal<Array<Any>>, binder: Any?, method: String): Boolean {
    val current = permit.get() ?: return false
    return current[0] === binder && current[1] == method
}

/** Only the dispatcher which claimed this event may enter this callback scope. */
internal inline fun withGpsLifecyclePermit(
    permit: ThreadLocal<Array<Any>>, binder: Any, method: String, claimIdentity: Any, action: () -> Unit
) {
    val previous = permit.get()
    permit.set(arrayOf(binder, method, claimIdentity))
    try {
        action()
    } finally {
        if (previous == null) permit.remove() else permit.set(previous)
    }
}
