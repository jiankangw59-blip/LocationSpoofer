package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import com.vincenthzr.locationspoofer.xposed.hooks.getCurrentSpoofedMotion
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.SystemHookUtils
import com.vincenthzr.locationspoofer.xposed.hooks.SatelliteProfile
import com.vincenthzr.locationspoofer.xposed.hooks.FINE_LOCATION_PERMISSION_LEVEL
import com.vincenthzr.locationspoofer.xposed.hooks.cachedGeoidAltitudeForLocation
import com.vincenthzr.locationspoofer.xposed.hooks.resolveGeoidAltitudeOnWorker
import com.vincenthzr.locationspoofer.xposed.utils.RouteEngine
import com.vincenthzr.locationspoofer.xposed.utils.SpoofedMotion
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemClassLocator
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import com.vincenthzr.locationspoofer.xposed.utils.CallScope
import com.vincenthzr.locationspoofer.xposed.utils.DemandTimer
import com.vincenthzr.locationspoofer.xposed.utils.demandTimer
import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge
import com.vincenthzr.locationspoofer.xposed.utils.getJitteredAccuracy
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.WeakHashMap
import java.util.function.Function
import java.util.function.Predicate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ColorOS 16 (Android 16) adapter. Validation scope is documented in docs/adaptation/coloros16.md.
 * Only per-request copies enter the existing framework delivery path. Provider caches,
 * Binder permission checks, coarse-location filtering and cancellation remain Android's job.
 */
internal class ColorOs16LocationDelivery(
    private val module: LocationHooker,
    private val loader: ClassLoader
) : AutoCloseable {
    private val managers = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
    private val lastLocationScope = CallScope<Any>()
    private var policyKey: String? = null
    private var updateRegistrations: Method? = null
    private val hooks = mutableListOf<XposedInterface.HookHandle>()
    @Volatile private var enabled = false
    private var output: DemandTimer? = null
    private var configObservation: AutoCloseable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val reconciliationQueued = AtomicBoolean(false)
    private val reconcileTask = Runnable {
        reconciliationQueued.set(false)
        if (enabled && !module.retiringGeneration) guarded("demand reconciliation") { reconcileDemand() }
    }
    private lateinit var registrationClass: Class<*>
    private lateinit var continuousRegistrationClass: Class<*>
    private lateinit var getIdentity: Method
    private var getRegistrationPermissionLevel: Method? = null
    private lateinit var getUid: Method
    private lateinit var getPackageName: Method
    private lateinit var acceptLocation: Method
    private lateinit var deliverToListeners: Method
    private lateinit var wrapResult: Method
    private lateinit var deepCopyResult: Method
    private lateinit var resultAsList: Method
    private lateinit var nameField: Field
    private lateinit var internalField: Field
    private lateinit var ownerField: Field
    private lateinit var isProvider: Method
    private lateinit var registrationsField: Field
    private lateinit var multiplexerLockField: Field
    private lateinit var registrationIsActive: Method

    fun install(): Boolean {
        try {
            val manager = SystemClassLocator.locate(SystemComponent.LOCATION_PROVIDER_MANAGER, loader)
                ?: error("Location provider manager unavailable")
            val managerName = manager.name
            registrationClass = Class.forName("$managerName\$Registration", false, loader)
            val continuous = Class.forName("$managerName\$LocationRegistration", false, loader)
            continuousRegistrationClass = continuous
            val current = Class.forName("$managerName\$GetCurrentLocationListenerRegistration", false, loader)
            val baseRegistration = Class.forName("com.android.server.location.listeners.ListenerRegistration", false, loader)
            val identity = Class.forName("android.location.util.identity.CallerIdentity", false, loader)
            val result = Class.forName("android.location.LocationResult", false, loader)
            val request = Class.forName("android.location.LastLocationRequest", false, loader)

            // Probe all required signatures before installing anything. Do not silently fall
            // back to the legacy global cache mutation when a different ROM changes them.
            getIdentity = registrationClass.getDeclaredMethod("getIdentity").accessible()
            getRegistrationPermissionLevel = optionalMslAltitude {
                registrationClass.getDeclaredMethod("getPermissionLevel").accessible()
                    .takeIf { it.returnType == Int::class.javaPrimitiveType }
            }
            getUid = identity.getMethod("getUid").accessible()
            getPackageName = identity.getMethod("getPackageName").accessible()
            acceptLocation = registrationClass.getDeclaredMethod("acceptLocationChange", result).accessible()
            deliverToListeners = findMethod(manager, "deliverToListeners", Function::class.java)
            updateRegistrations = findMethod(manager, "updateRegistrations", Predicate::class.java)
            wrapResult = result.getMethod("wrap", Array<Location>::class.java).accessible()
            deepCopyResult = result.getMethod("deepCopy").accessible()
            resultAsList = result.getMethod("asList").accessible()
            nameField = findField(manager, "mName")
            internalField = findField(manager, "mLocationManagerInternal")
            ownerField = findField(registrationClass, "this\$0")
            isProvider = internalField.type.getMethod("isProvider", String::class.java, identity).accessible()
            registrationsField = findField(manager, "mRegistrations")
            multiplexerLockField = findField(manager, "mMultiplexerLock")
            registrationIsActive = findMethod(baseRegistration, "isActive")
            val activeChanged = findMethod(manager, "onRegistrationActiveChanged", baseRegistration)
            val added = manager.getDeclaredMethod("onRegistrationAdded", Any::class.java, registrationClass)
            val reported = manager.getDeclaredMethod("onReportLocation", result)
            val getLast = manager.getDeclaredMethod("getLastLocation", request, identity, Int::class.javaPrimitiveType)
            val getLastUnsafe = manager.getDeclaredMethod(
                "getLastLocationUnsafe", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
            val deliveries = listOf(continuous, current).map {
                it.getDeclaredMethod("acceptLocationChange", result)
            }
            val active = manager.getDeclaredMethod("isActive", Boolean::class.javaPrimitiveType, identity)
            hook(continuous.getDeclaredMethod("onProviderEnabledChanged", String::class.java,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)) { chain ->
                val args = chain.args.toTypedArray()
                val registration = chain.thisObject!!
                val config = module.readConfig()
                if (config != null && config.optBoolean("force_location_enabled", false) &&
                    isTarget(ownerField.get(registration), getIdentity.invoke(registration), config)) args[2] = true
                chain.proceed(args)
            }
            // PLQ110 calls the typed isActive through a generated bridge from the
            // private multiplexer dispatcher. Deoptimizing only the typed overload
            // leaves an already compiled/inlined caller able to bypass the hook.
            for (entry in manager.declaredMethods.filter { it.name == "isActive" }) {
                check(module.deoptimize(entry)) { "Unable to deoptimize provider activation ${entry.parameterTypes.toList()}" }
            }
            for (entry in activeChanged.declaringClass.declaredMethods.filter {
                it.name in setOf("onRegistrationActiveChanged", "replaceRegistration", "putRegistration",
                    "updateRegistration", "updateRegistrations", "removeRegistration")
            }) {
                check(module.deoptimize(entry)) { "Unable to deoptimize registration dispatcher ${entry.name}" }
            }
            for (entry in manager.declaredMethods.filter { it.name == "registerLocationRequest" }) {
                check(module.deoptimize(entry)) { "Unable to deoptimize provider registration" }
            }

            hook(added) { chain ->
                val original = chain.proceed(chain.args.toTypedArray())
                chain.thisObject?.let { managers.add(it) }
                HookStatus.workEvent("coloros_location_registration_added")
                requestReconciliation()
                original
            }
            val registrationEvents = manager.declaredMethods.filter {
                it.name in setOf("onRegistrationRemoved", "onRegistrationActive", "onRegistrationInactive") &&
                    it.parameterTypes.any { p -> p == registrationClass }
            }
            check(registrationEvents.any { it.name == "onRegistrationRemoved" }) {
                "Registration removal event unavailable"
            }
            for (event in registrationEvents) hook(event) { chain ->
                val original = chain.proceed(chain.args.toTypedArray())
                chain.thisObject?.let { managers.add(it) }
                HookStatus.workEvent("coloros_location_registration_removed")
                requestReconciliation()
                original
            }
            // This ROM has no onRegistrationActive/Inactive manager overrides.
            // This private dispatcher is the actual add/remove/permission/power-state
            // activation path. Keep the original decision and only observe its result.
            hook(activeChanged) { chain ->
                val relevant = chain.thisObject != null && manager.isInstance(chain.thisObject)
                val registration = chain.args.firstOrNull()
                val before = if (relevant && registration != null) registrationIsActive.invoke(registration) else null
                val original = chain.proceed(chain.args.toTypedArray())
                if (relevant) {
                    chain.thisObject?.let { managers.add(it) }
                    HookStatus.workTick("coloros_location_active_event")
                    val after = registration?.let { registrationIsActive.invoke(it) }
                    if (before != after) HookStatus.workEvent("coloros_location_active_transition")
                    requestReconciliation()
                }
                original
            }
            hook(reported) { chain ->
                // Observe existing managers after a hot reload; never rewrite provider input.
                if (chain.thisObject?.let { managers.add(it) } == true) requestReconciliation()
                chain.proceed(chain.args.toTypedArray())
            }
            hook(getLast) { chain ->
                val owner = chain.thisObject
                val config = module.readConfig()
                val caller = chain.args[1]
                val target = owner != null && caller != null && config != null &&
                    isTarget(owner, caller, config)
                val original = lastLocationScope.withValue(if (target) owner else null) {
                    // All original enabled-state, AppOps and coarse-location checks still run.
                    chain.proceed(chain.args.toTypedArray())
                }
                original
            }
            hook(getLastUnsafe) { chain ->
                val owner = chain.thisObject
                if (owner != null && lastLocationScope.current === owner) {
                    val fake = guarded("last location") {
                        makeLocation(owner, chain.args.getOrNull(1) as? Int)
                    }
                    if (fake != null) return@hook fake
                }
                chain.proceed(chain.args.toTypedArray())
            }
            for (delivery in deliveries) {
                hook(delivery) { chain ->
                    val registration = chain.thisObject
                    val originalResult = chain.args[0]
                    // A null single-shot result means cancellation/timeout/inactive: keep it null.
                    val replacement = if (registration != null && originalResult != null) {
                        guarded("location delivery") {
                            val owner = ownerField.get(registration)
                            val config = module.readConfig()
                            val caller = getIdentity.invoke(registration)
                            if (config != null && isTarget(owner, caller, config)) {
                                val permissionLevel = optionalMslAltitude {
                                    getRegistrationPermissionLevel?.invoke(registration) as? Int
                                }
                                copyResult(originalResult, config, permissionLevel)
                            } else null
                        }
                    } else null
                    val args = chain.args.toTypedArray()
                    if (replacement != null) args[0] = replacement
                    chain.proceed(args)
                }
            }

            // getLastLocationUnsafe may already be inlined into this system-server method.
            // Its caller must run through the hook for the scoped replacement to take effect.
            check(module.deoptimize(getLast)) { "Unable to deoptimize getLastLocation" }

            enabled = true
            output = module.demandTimer("coloros_location", 1000L) {
                if (enabled) guarded("location output") { tick() }
            }
            // Existing registrations survive hot reload. Discover managers once; subsequent
            // demand changes come from the framework's own registration lifecycle hooks.
            guarded("existing providers") {
                val service = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java)
                    .invoke(null, "location") ?: return@guarded
                val existing = findField(service.javaClass, "mProviderManagers").get(service)
                (existing as? Collection<*>)?.filterNotNull()?.forEach { managers.add(it) }
            }
            configObservation = module.observeConfigChanges { requestReconciliation() }
            return true
        } catch (error: Throwable) {
            HookStatus.error("ColorOS16 location delivery", error)
            close()
            XposedBridge.log("[ColorOS16] Adapter disabled: incompatible framework or hook failure: $error")
            return false
        }
    }

    private fun requestReconciliation() {
        if (!enabled || module.retiringGeneration || !reconciliationQueued.compareAndSet(false, true)) return
        if (!handler.post(reconcileTask)) reconciliationQueued.set(false)
    }

    /** Called only for configuration and registration events, never by a periodic timer. */
    private fun reconcileDemand() {
        HookStatus.workTick("coloros_location_reconcile")
        val config = module.readConfig()
        val snapshot = synchronized(managers) { managers.toList() }
        val key = listOf(config?.optBoolean("active"), config?.optBoolean("force_location_enabled", false),
            config?.optBoolean("system_hook_global_mode"), config?.optJSONArray("system_hook_packages")).joinToString("|")
        if (key != policyKey) {
            policyKey = key
            snapshot.forEach { owner -> guarded("activation refresh") {
                updateRegistrations?.invoke(owner, Predicate<Any> { true })
            } }
        }
        var wanted = false
        if (config != null && config.optBoolean("active", false)) {
            for (owner in snapshot) guarded("active demand") {
                // Copy only immutable identity/state under the native lock. In particular,
                // target checks can use PackageManager/Binder and must happen after release.
                val registrations = registrationSnapshot(owner) { registration ->
                    Pair(getIdentity.invoke(registration), registrationIsActive.invoke(registration) == true)
                }
                for ((identity, active) in registrations) {
                        if (!isTarget(owner, identity, config)) continue

                        HookStatus.workTick("coloros_location_target_seen")
                        if (active) {
                            wanted = true
                            HookStatus.workTick("coloros_location_target_active")
                        } else HookStatus.workTick("coloros_location_target_inactive")
                }
            }
        }
        output?.setRequired(enabled && !module.retiringGeneration && wanted)
    }

    fun registeredTargetUids(userId: Int): Set<Int> {
        if (!enabled || module.retiringGeneration) return emptySet()
        val config = module.readConfig() ?: return emptySet()
        if (!config.optBoolean("active", false)) return emptySet()
        val getUserId = getIdentity.returnType.getMethod("getUserId")
        val snapshot = synchronized(managers) { managers.toList() }
        return buildSet {
            for (owner in snapshot) {
                val identities = registrationSnapshot(owner) { registration ->
                    if (continuousRegistrationClass.isInstance(registration)) getIdentity.invoke(registration)
                    else null
                }
                for (identity in identities) {
                    if (getUserId.invoke(identity) == userId && isTarget(owner, identity, config)) {
                        add(getUid.invoke(identity) as Int)
                    }
                }
            }
        }
    }

    private fun <T : Any> registrationSnapshot(owner: Any, read: (Any) -> T?): List<T> =
        synchronized(multiplexerLockField.get(owner)) {
            val registrations = registrationsField.get(owner) as Map<*, *>
            registrations.values.filterNotNull().mapNotNull(read)
        }

    private fun tick() {
        val config = module.readConfig() ?: return
        if (!config.optBoolean("active", false)) return
        // Let the resolver limit asset reads. A busy converter can be tried on the next demand tick.
        optionalMslAltitude<Unit> {
            val motion = module.getCurrentSpoofedMotion("WGS-84") ?: return@optionalMslAltitude
            module.resolveGeoidAltitudeOnWorker(config, motion, RouteEngine.realisticAltitude(config))
        }
        if (!enabled || module.retiringGeneration || module.readConfig() !== config) return
        val snapshot = synchronized(managers) { managers.toList() }
        for (owner in snapshot) {
            guarded("provider heartbeat") {
                // Use Android's multiplexer lock, reentrancy guard and active-registration
                // filter. Android performs expiration, AppOps, throttling, removal and IPC.
                val operation = Function<Any, Any?> { registration ->
                    guarded("registration heartbeat") registration@{
                        if (!enabled) return@registration null
                        val currentConfig = module.readConfig() ?: return@registration null
                        val caller = getIdentity.invoke(registration)
                        if (!isTarget(owner, caller, currentConfig)) return@registration null
                        val location = makeLocation(owner) ?: return@registration null
                        val result = wrapResult.invoke(null, arrayOf(location) as Any)
                        acceptLocation.invoke(registration, result)
                    }
                }
                deliverToListeners.invoke(owner, operation)
            }
        }
    }

    private fun isTarget(owner: Any, caller: Any, config: JSONObject): Boolean =
        guarded("caller identity") {
            if (!config.optBoolean("active", false)) return@guarded false
            val uid = getUid.invoke(caller) as Int
            val pkg = getPackageName.invoke(caller) as String
            if (!SystemHookUtils.isTargetCaller(owner, pkg, config, uid)) return@guarded false
            // Protect whatever provider the ROM actually binds, in addition to known packages.
            val locationInternal = internalField.get(owner) ?: return@guarded false
            isProvider.invoke(locationInternal, null, caller) != true
        } ?: false


    private fun makeLocation(owner: Any, permissionLevel: Int? = null): Location? {
        val config = module.readConfig() ?: return null
        if (!config.optBoolean("active", false)) return null
        val motion = module.getCurrentSpoofedMotion("WGS-84") ?: return null
        val provider = nameField.get(owner) as String
        val altitude = RouteEngine.realisticAltitude(config)
        return (SystemHookUtils.buildFakeLocation(
            loader, provider, motion, altitude, module.getJitteredAccuracy()
        ) as? Location)?.also {
            applyMslAltitude(it, config, permissionLevel, motion, altitude)
            applySatelliteExtras(it, config)
        }
    }

    private fun copyResult(original: Any, config: JSONObject, permissionLevel: Int?): Any? {
        val motion = module.getCurrentSpoofedMotion("WGS-84") ?: return null
        // Deep-copy both Location and extras. Never mutate data shared with another registration.
        val copy = deepCopyResult.invoke(original)
        val locations = resultAsList.invoke(copy) as List<*>
        for (item in locations) {
            val location = item as Location
            val altitude = RouteEngine.realisticAltitude(config)
            applyRetimedLocationFields(
                location, motion, altitude, module.getJitteredAccuracy()
            )
            location.removeMslAltitude()
            location.removeMslAltitudeAccuracy()
            applyMslAltitude(location, config, permissionLevel, motion, altitude)
            applySatelliteExtras(location, config)
        }
        return copy
    }

    private fun applyMslAltitude(
        location: Location,
        config: JSONObject,
        permissionLevel: Int?,
        motion: SpoofedMotion,
        ellipsoidMeters: Double
    ) {
        if (permissionLevel != FINE_LOCATION_PERMISSION_LEVEL || !enabled ||
            module.retiringGeneration || module.readConfig() !== config) return
        optionalMslAltitude<Unit> {
            // Field rewriting can fail locally. Only project a completed target sample.
            if (!location.hasAltitude() || location.latitude != motion.lat ||
                location.longitude != motion.lng || location.altitude != ellipsoidMeters) return@optionalMslAltitude
            val projection = cachedGeoidAltitudeForLocation(
                permissionLevel, location.latitude, location.longitude, location.altitude
            ) ?: return@optionalMslAltitude
            val verticalAccuracy = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null
            val accuracy = projection.locationAccuracyMeters(verticalAccuracy)
            if (!enabled || module.retiringGeneration || module.readConfig() !== config) return@optionalMslAltitude
            location.mslAltitudeMeters = projection.meanSeaLevelMeters
            accuracy?.let { location.mslAltitudeAccuracyMeters = it }
        }
    }

    private inline fun <T> optionalMslAltitude(action: () -> T): T? = try {
        action()
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun applySatelliteExtras(location: Location, config: JSONObject) {
        location.extras = android.os.Bundle(location.extras ?: android.os.Bundle()).apply {
            SatelliteProfile.apply(this, SatelliteProfile.count(config))
        }
    }

    private fun hook(method: Method, interceptor: (XposedInterface.Chain) -> Any?) {
        val installed = XposedHelpers.hookAllMethods(method.declaringClass, method.name) { chain, hooked ->
            if (!enabled || hooked != method) chain.proceed(chain.args.toTypedArray())
            else interceptor(chain)
        }
        hooks.addAll(installed)
        check(installed.isNotEmpty()) { "Unable to hook $method" }
    }

    private fun <T> guarded(operation: String, block: () -> T): T? = try {
        block()
    } catch (error: Throwable) {
        XposedBridge.logOpenCellIdEvery("ColorOS16-$operation", "$operation failed: $error", 10000L)
        null
    }

    override fun close() {
        enabled = false
        configObservation?.close()
        configObservation = null
        handler.removeCallbacks(reconcileTask)
        reconciliationQueued.set(false)
        output?.close()
        output = null
        hooks.asReversed().forEach { handle -> runCatching { handle.unhook() } }
        hooks.clear()
        synchronized(managers) { managers.toList() }.forEach { owner -> guarded("restore activation") {
            updateRegistrations?.invoke(owner, Predicate<Any> { true })
        } }
        managers.clear()
    }

    private fun Method.accessible() = apply { isAccessible = true }

    private fun findMethod(type: Class<*>, name: String, vararg args: Class<*>): Method {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *args).accessible()
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        throw NoSuchMethodException("${type.name}.$name")
    }

    private fun findField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("${type.name}.$name")
    }
}
