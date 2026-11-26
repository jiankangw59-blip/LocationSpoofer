package com.vincenthzr.locationspoofer.xposed.hooks

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** No fabricated pseudoranges/navigation bits: block hardware payloads for active targets only.
 * Capture accepted native subscriptions even while inactive, so activation needs no polling.
 */
internal class RawGnssDelivery(private val module: LocationHooker) : AutoCloseable {
    private enum class Kind(val payload: String, val provider: String) {
        MEASUREMENTS("onGnssMeasurementsReceived", "mGnssMeasurementsProvider"),
        NAVIGATION("onGnssNavigationMessageReceived", "mGnssNavigationMessageProvider")
    }
    private data class Key(val binder: IBinder, val kind: Kind)
    private data class Registration(val callback: Any, val pkg: String, val uid: Int) {
        var death: IBinder.DeathRecipient? = null
    }
    private val registrations = ConcurrentHashMap<Key, Registration>()
    private val hooked = ConcurrentHashMap.newKeySet<Pair<Class<*>, Kind>>()
    private var retiring: Set<Key> = emptySet()

    fun install(service: Class<*>) {
        for (kind in Kind.entries) {
            val adds = if (kind == Kind.MEASUREMENTS) listOf("addGnssMeasurementsListener", "registerGnssMeasurementsCallback")
                else listOf("addGnssNavigationMessageListener", "registerGnssNavigationMessageCallback")
            val removes = if (kind == Kind.MEASUREMENTS) listOf("removeGnssMeasurementsListener", "unregisterGnssMeasurementsCallback")
                else listOf("removeGnssNavigationMessageListener", "unregisterGnssNavigationMessageCallback")
            for (name in adds) XposedHelpers.hookAllMethods(service, name) { chain, _ -> register(chain, kind) }
            for (name in removes) XposedHelpers.hookAllMethods(service, name) { chain, _ ->
                // Preserve native errors and ownership checks before forgetting a registration.
                val result = chain.proceed(chain.args.toTypedArray())
                callback(chain.args, kind)?.let { value ->
                    binder(value)?.let { key -> registrations.remove(Key(key, kind))?.let { unlink(key, it) } }
                }
                result
            }
        }
        adoptNative(service.classLoader ?: ClassLoader.getSystemClassLoader())
    }

    private fun register(chain: XposedInterface.Chain, kind: Kind): Any? {
        val callback = callback(chain.args, kind) ?: return chain.proceed(chain.args.toTypedArray())
        val binder = binder(callback) ?: return chain.proceed(chain.args.toTypedArray())
        val pkg = SystemHookUtils.extractPackageName(chain.args) ?: return chain.proceed(chain.args.toTypedArray())
        val key = Key(binder, kind)
        val entry = Registration(callback, pkg, Binder.getCallingUid())
        val previous = registrations.put(key, entry)
        hookDelivery(callback, kind)
        fun rollback() {
            if (previous == null) registrations.remove(key, entry)
            else registrations.replace(key, entry, previous)
        }
        val result = try { chain.proceed(chain.args.toTypedArray()) }
        catch (error: Throwable) { rollback(); throw error }
        if (result == false) { rollback(); return result }
        previous?.let { unlink(binder, it) }
        watch(key, entry)
        return result
    }

    private fun hookDelivery(callback: Any, kind: Kind) {
        if (!hooked.add(callback.javaClass to kind)) return
        XposedHelpers.hookAllMethods(callback.javaClass, kind.payload) { chain, _ ->
            val recipient = binder(chain.thisObject)
            val key = recipient?.let { Key(it, kind) }
            if (module.retiringGeneration && key in retiring) return@hookAllMethods null
            val entry = key?.let { registrations[it] }
            val config = module.readConfig()
            if (entry != null && config != null && registeredPackageEnabled(config, entry.pkg, entry.uid)) null
            else chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun watch(key: Key, entry: Registration) {
        if (registrations[key] !== entry) return
        val death = object : IBinder.DeathRecipient {
            override fun binderDied() { registrations.remove(key, entry) }
        }
        entry.death = death
        try {
            key.binder.linkToDeath(death, 0)
            if (registrations[key] !== entry) unlink(key.binder, entry)
        }
        catch (_: Throwable) { registrations.remove(key, entry) }
    }

    private fun unlink(binder: IBinder, entry: Registration) {
        entry.death?.let { runCatching { binder.unlinkToDeath(it, 0) } }
        entry.death = null
    }

    /** One snapshot for subscriptions predating an upgrade. Native locks guard only native reads. */
    private fun adoptNative(loader: ClassLoader) {
        try {
            val owner = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java)
                .invoke(null, "location") ?: return
            val manager = field(owner.javaClass, "mGnssManagerService").get(owner) ?: return
            val multiplexer = Class.forName("com.android.server.location.listeners.ListenerMultiplexer", false, loader)
            val base = Class.forName("com.android.server.location.listeners.ListenerRegistration", false, loader)
            val lock = field(multiplexer, "mMultiplexerLock")
            val listeners = field(multiplexer, "mRegistrations")
            val listener = field(base, "mListener")
            for (kind in Kind.entries) {
                val provider = field(manager.javaClass, kind.provider).get(manager) ?: continue
                val snapshot = synchronized(lock.get(provider)) {
                    (listeners.get(provider) as Map<*, *>).entries.mapNotNull { (key, value) ->
                        if (key !is IBinder || value == null) return@mapNotNull null
                        val callback = listener.get(value) ?: return@mapNotNull null
                        val identity = method(value.javaClass, "getIdentity").invoke(value)
                        Key(key, kind) to Registration(callback,
                            method(identity.javaClass, "getPackageName").invoke(identity) as String,
                            method(identity.javaClass, "getUid").invoke(identity) as Int)
                    }
                }
                for ((key, entry) in snapshot) adopt(key, entry)
            }
        } catch (error: Throwable) { HookStatus.error("Raw GNSS subscription adoption", error) }
    }

    private fun adopt(key: Key, entry: Registration) {
        if (!key.binder.isBinderAlive) return
        if (registrations.putIfAbsent(key, entry) == null) {
            hookDelivery(entry.callback, key.kind)
            watch(key, entry)
        }
        module.deliveryHandoff?.release(entry.callback)
    }

    fun prepareReloadHandoff(): Array<Any> {
        val config = module.readConfig()
        val targets = registrations.filterValues { config != null && registeredPackageEnabled(config, it.pkg, it.uid) }
        retiring = targets.keys.toSet()
        return targets.values.map { it.callback }.toTypedArray()
    }

    fun save(): List<Array<Any>> = registrations.map { (key, entry) ->
        arrayOf(key.binder, entry.callback, entry.pkg, entry.uid, key.kind.name)
    }

    fun restore(rows: Any?) {
        (rows as? List<*>)?.filterIsInstance<Array<*>>()?.forEach { row ->
            adopt(Key(row[0] as IBinder, Kind.valueOf(row[4] as String)),
                Registration(row[1]!!, row[2] as String, row[3] as Int))
        }
    }

    override fun close() {
        registrations.forEach { (key, entry) -> unlink(key.binder, entry) }
        registrations.clear()
        // Framework hot reload owns hook removal; the retiring barrier remains until then.
    }

    private fun callback(args: List<Any?>, kind: Kind): Any? = args.firstOrNull { value ->
        value is IInterface && LocationHooker.hasTypeByName(value.javaClass,
            if (kind == Kind.MEASUREMENTS) "android.location.IGnssMeasurementsListener" else "android.location.IGnssNavigationMessageListener")
    }
    private fun binder(value: Any?): IBinder? = (value as? IInterface)?.asBinder() ?: value as? IBinder
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
