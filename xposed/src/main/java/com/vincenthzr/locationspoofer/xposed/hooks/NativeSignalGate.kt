package com.vincenthzr.locationspoofer.xposed.hooks

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

internal data class NativeSignalRegistration(
    val listener: Any, val packageName: String, val uid: Int, val active: Boolean,
    val registrationIdentity: Any
)

/** Follow Android's accepted and active GNSS registrations, including live AppOps changes.
 * No permission replicas, new permission grants, or periodic discovery are involved.
 */
internal class NativeSignalGate(
    private val module: LocationHooker,
    private val loader: ClassLoader,
    private val changed: () -> Unit
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val queued = AtomicBoolean()
    private val hooks = mutableListOf<XposedInterface.HookHandle>()
    private val snapshots = NativeSignalSnapshots<IBinder, NativeSignalRegistration>()
    private lateinit var statusType: Class<*>
    private lateinit var nmeaType: Class<*>
    private lateinit var registrations: Field
    private lateinit var listenerField: Field
    private lateinit var lock: Field
    private lateinit var active: Method
    private val notifyTask = Runnable {
        queued.set(false)
        if (!snapshots.closed && !module.retiringGeneration) {
            refresh()
            if (!snapshots.closed && !module.retiringGeneration) changed()
        }
    }

    fun install(): Boolean = try {
        val multiplexer = Class.forName("com.android.server.location.listeners.ListenerMultiplexer", false, loader)
        val registration = Class.forName("com.android.server.location.listeners.ListenerRegistration", false, loader)
        statusType = Class.forName("com.android.server.location.gnss.GnssStatusProvider", false, loader)
        nmeaType = Class.forName("com.android.server.location.gnss.GnssNmeaProvider", false, loader)
        registrations = field(multiplexer, "mRegistrations")
        listenerField = field(registration, "mListener")
        lock = field(multiplexer, "mMultiplexerLock")
        active = registration.getDeclaredMethod("isActive").apply { isAccessible = true }
        val event = multiplexer.getDeclaredMethod("onRegistrationActiveChanged", registration)
        for (method in multiplexer.declaredMethods.filter { it.name in setOf(
            "onRegistrationActiveChanged", "replaceRegistration", "putRegistration",
            "updateRegistration", "updateRegistrations", "removeRegistration"
        ) }) check(module.deoptimize(method)) { "Cannot deoptimize GNSS registration ${method.name}" }
        hooks += XposedHelpers.hookAllMethods(multiplexer, event.name) { chain, hooked ->
            val result = chain.proceed(chain.args.toTypedArray())
            val owner = chain.thisObject
            if (hooked == event && !snapshots.closed && owner != null &&
                (statusType.isInstance(owner) || nmeaType.isInstance(owner))) {
                // The framework already holds this reentrant lock. Publish only immutable
                // native state here; target/PackageManager checks run after releasing it.
                try { refreshProvider(owner) }
                catch (error: Throwable) {
                    HookStatus.error("GNSS native registration event", error)
                }
                HookStatus.workTick("gnss_native_registration_event")
                if (queued.compareAndSet(false, true) && !handler.post(notifyTask)) queued.set(false)
            }
            result
        }
        check(hooks.isNotEmpty()) { "GNSS registration event unavailable" }
        refresh()
        true
    } catch (error: Throwable) {
        HookStatus.error("GNSS native registration gate", error)
        close()
        false
    }

    /** Also called after registration and once on hot reload, never from an output timer. */
    fun refresh(service: Any? = null) {
        if (snapshots.closed || !::registrations.isInitialized) return
        try {
            val owner = service ?: Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java).invoke(null, "location") ?: return
            val manager = field(owner.javaClass, "mGnssManagerService").get(owner) ?: return
            val statusProvider = field(manager.javaClass, "mGnssStatusProvider").get(manager)
            val nmeaProvider = field(manager.javaClass, "mGnssNmeaProvider").get(manager)
            statusProvider?.let(::refreshProvider)
            nmeaProvider?.let(::refreshProvider)
        } catch (error: Throwable) {
            snapshots.clear()
            HookStatus.error("GNSS native registration snapshot", error)
        }
    }

    fun allows(binder: IBinder, nmea: Boolean): Boolean =
        entries(nmea)[binder]?.active == true

    fun entries(nmea: Boolean): Map<IBinder, NativeSignalRegistration> =
        snapshots.entries(nmea)

    private fun refreshProvider(provider: Any) {
        snapshots.refresh(requireNotNull(lock.get(provider)), nmeaType.isInstance(provider)) {
            (registrations.get(provider) as Map<*, *>).entries.mapNotNull { (key, value) ->
                if (key !is IBinder || value == null) return@mapNotNull null
                val listener = listenerField.get(value) ?: return@mapNotNull null
                val identity = method(value.javaClass, "getIdentity").invoke(value)
                key to NativeSignalRegistration(listener,
                    method(identity.javaClass, "getPackageName").invoke(identity) as String,
                    method(identity.javaClass, "getUid").invoke(identity) as Int,
                    active.invoke(value) == true, value)
            }.toMap()
        }
    }

    override fun close() {
        snapshots.close()
        handler.removeCallbacks(notifyTask)
        hooks.asReversed().forEach { runCatching { it.unhook() } }
        hooks.clear()
    }

    private fun field(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try { return current.getDeclaredField(name).apply { isAccessible = true } }
            catch (_: NoSuchFieldException) { current = current.superclass }
        }
        throw NoSuchFieldException("${type.name}.$name")
    }

    private fun method(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            try { return current.getDeclaredMethod(name).apply { isAccessible = true } }
            catch (_: NoSuchMethodException) { current = current.superclass }
        }
        throw NoSuchMethodException("${type.name}.$name")
    }
}
