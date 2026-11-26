package com.vincenthzr.locationspoofer.xposed.utils

import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import java.util.Timer
import java.util.TimerTask

/** A periodic output session exists only while an event-derived demand is true. */
internal class DemandTimer(
    private val schedule: (() -> Unit) -> AutoCloseable,
    private val changed: (Boolean) -> Unit = {},
    private val tick: () -> Unit = {},
    private val action: () -> Unit
) : AutoCloseable {
    private var registration: AutoCloseable? = null
    private var generation = 0L
    private var closed = false

    @Synchronized
    fun setRequired(required: Boolean) {
        if (closed || required == (registration != null)) return
        generation++
        if (!required) {
            val old = registration
            registration = null
            old?.close()
            changed(false)
        } else {
            val installedGeneration = generation
            registration = schedule {
                val current = synchronized(this) {
                    !closed && registration != null && generation == installedGeneration
                }
                if (current) {
                    tick()
                    action()
                }
            }
            changed(true)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        setRequired(false)
        closed = true
        generation++
    }
}

internal fun LocationHooker.demandTimer(name: String, interval: Long, fixedRate: Boolean = false, action: () -> Unit): DemandTimer {
    HookStatus.workActive(name, false)
    return DemandTimer(
        schedule = { callback ->
            val timer = ownReloadTimer(Timer("LocationSpoofer-$name", true))
            try {
                val task = object : TimerTask() {
                    override fun run() {
                        if (!retiringGeneration) runCatching { callback() }
                            .onFailure { HookStatus.error("$name output", it) }
                    }
                }
                if (fixedRate) timer.scheduleAtFixedRate(task, interval, interval)
                else timer.schedule(task, interval, interval)
            } catch (error: Throwable) {
                timer.cancel()
                releaseReloadTimer(timer)
                throw error
            }
            AutoCloseable { timer.cancel(); releaseReloadTimer(timer) }
        },
        changed = { HookStatus.workActive(name, it) },
        tick = { HookStatus.workTick(name) },
        action = action
    )
}
