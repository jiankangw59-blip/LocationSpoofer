package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemClassLocator
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import android.Manifest
import android.app.AppOpsManager
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Handler
import android.os.SystemClock
import android.os.WorkSource
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.hooks.network.buildScanRecordBytes
import com.vincenthzr.locationspoofer.xposed.hooks.network.createBluetoothDevice
import com.vincenthzr.locationspoofer.xposed.hooks.network.createScanResult
import com.vincenthzr.locationspoofer.xposed.hooks.network.normalizeMacAddress
import com.vincenthzr.locationspoofer.xposed.utils.CallScope
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import com.vincenthzr.locationspoofer.xposed.utils.DemandTimer
import com.vincenthzr.locationspoofer.xposed.utils.demandTimer
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Predicate

/** ColorOS moved BLE scanning out of GattService into le_scan.TransitionalScanHelper. */
internal class ColorOs16BleDelivery(module: LocationHooker, loader: ClassLoader) :
    ColorOs16HookSupport(module, loader, "BLE") {
    private class Owner {
        val queued = AtomicBoolean()
        val dirty = AtomicBoolean()
        val emit = AtomicBoolean()
        @Volatile var demanded = false
        @Volatile var handler: Handler? = null
        @Volatile var pending: Runnable? = null
        // Accessed only on ScanManager's own handler. Keys are the actual ScanClient objects.
        val scans = WeakHashMap<Any, BleDeliveryState<ScanResult>>()
    }
    private data class Registration(val who: ColorOsCaller, val helper: WeakReference<Any>, val app: WeakReference<Any>)
    private val callbacks = Collections.synchronizedMap(WeakHashMap<Any, Registration>())
    private val callbackClasses = Collections.synchronizedSet(mutableSetOf<Class<*>>())
    private val deliveryHandles = mutableListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
    @Volatile private var retiringRecipients: Set<IBinder> = emptySet()
    private val owners = Collections.synchronizedMap(WeakHashMap<Any, Owner>())
    private val synthetic = CallScope<Boolean>()
    private val explicitFlush = CallScope<Boolean>()
    private val flushRequests = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    @Volatile private var ready = false
    private var output: DemandTimer? = null
    private var configObservation: AutoCloseable? = null
    private var observedAppOps: AppOpsManager? = null
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, pkg ->
        val config = module.readConfig()
        val relevant = pkg == null || synchronized(callbacks) { callbacks.values.any { it.who.pkg == pkg } }
        if (ready && live && relevant && config?.optBoolean("active") == true && config.optBoolean("mock_bluetooth", true)) {
            HookStatus.workTick("ble_permission_changed")
            val snapshot = synchronized(owners) { owners.entries.map { it.key to it.value } }
            snapshot.forEach { (helper, state) -> scheduleOwner(helper, state) }
        }
    }
    private lateinit var scannerInterface: Class<*>
    private lateinit var permission: Method
    private lateinit var matches: Method
    private lateinit var sendBatch: Method
    private lateinit var sendIntent: Method
    private lateinit var dead: Method

    fun install() = section("TransitionalScanHelper") {
        val helper = SystemClassLocator.locate(SystemComponent.BLUETOOTH_SCAN_SERVICE, loader)
            ?: error("BLE scan helper unavailable")
        val client = helper.declaredMethods.first { it.name == "hasScanResultPermission" }.parameterTypes[0]
        val app = helper.declaredMethods.first { it.name == "sendBatchScanResults" }.parameterTypes[0]
        val intentInfo = type(helper.name + "\$PendingIntentInfo")
        scannerInterface = type("android.bluetooth.le.IScannerCallback")
        permission = method(helper, "hasScanResultPermission", client)
        matches = method(helper, "matchesFilters", client, ScanResult::class.java)
        sendBatch = method(helper, "sendBatchScanResults", app, client, ArrayList::class.java)
        sendIntent = method(helper, "sendResultsByPendingIntent", intentInfo, ArrayList::class.java, Int::class.javaPrimitiveType!!)
        dead = method(helper, "handleDeadScanClient", client)
        val manager = field(helper, "mScanManager").type
        field(manager, "mHandler") // Probe before installing the callback filters.
        method(manager, "getRegularScanQueue")
        method(manager, "getBatchScanQueue")

        // ContextMap.add is reached only after the platform accepted scanner registration.
        val mapClass = field(helper, "mScannerMap").type
        val add = mapClass.methods.first { it.name == "add" && it.parameterTypes.lastOrNull() == helper }
        hook(add) { chain ->
            val uid = Binder.getCallingUid()
            val registered = chain.proceed(chain.args.toTypedArray())
            if (registered != null) guard("register") {
                val helperObject = chain.args.last()!!
                scheduleOwner(helperObject, owner(helperObject))
                val callback = get(registered, "callback")
                if (callback != null) {
                    callbacks[callback] = Registration(ColorOsCaller(uid, get(registered, "name") as? String),
                        WeakReference(helperObject), WeakReference(registered))
                    hookCallback(callback.javaClass)
                    module.deliveryHandoff?.release(callback)
                }
            }
            registered
        }
        helper.declaredMethods.filter { it.name in setOf("startScan", "registerPiAndStartScan", "continuePiStartScan") }
            .forEach { entry -> hook(entry) { chain ->
                val result = chain.proceed(chain.args.toTypedArray())
                chain.thisObject?.let { scheduleOwner(it, owner(it)) }
                result
            } }
        hook(sendIntent) { chain ->
            val info = chain.args[0]!!
            val who = ColorOsCaller(get(info, "callingUid") as Int, get(info, "callingPackage") as? String)
            val config = module.readConfig()
            if (ready && synthetic.current != true && config != null && target(who, config, chain.thisObject)) null
            else chain.proceed(chain.args.toTypedArray())
        }
        // Only an app-requested flush may bypass reportDelay. Hardware thresholds and
        // periodic controller flushes share the same handler and must not shorten it.
        hook(method(helper, "flushPendingBatchResults", Int::class.javaPrimitiveType!!, AttributionSource::class.java)) { chain ->
            val source = chain.args[1] as AttributionSource
            explicitFlush.withValue(source.uid >= 10000 && source.uid == Binder.getCallingUid()) {
                chain.proceed(chain.args.toTypedArray())
            }
        }
        hook(method(manager, "flushBatchScanResults", client)) { chain ->
            if (explicitFlush.current == true) flushRequests[chain.args[0]!!] = true
            chain.proceed(chain.args.toTypedArray())
        }
        // Preserve permission checking and ordering on ScanManager's own handler.
        val handlerClass = type(manager.name + "\$ClientHandler")
        check(module.deoptimize(method(handlerClass, "handleMessage", android.os.Message::class.java))) {
            "Cannot deoptimize scan event dispatch"
        }
        // The Binder entry merely queues a start/stop. Reconcile after ScanManager has
        // processed that event, so a stopped client cannot leave an output timer alive.
        val demandEvents = setOf("handleStartScan", "handleStopScan", "handleSuspendScans", "handleResumeScans",
            "handleSuspendScan", "handleResumeScan", "updateRegularScanClientsScreenOff",
            "updateRegularScanClientsScreenOn", "updateBatchScanToRegularScanClients", "updateRegularScanToBatchScanClients")
        handlerClass.declaredMethods.filter { it.name in demandEvents }.forEach { event -> hook(event) { chain ->
            val result = chain.proceed(chain.args.toTypedArray())
            guard("scan demand event") {
                val scanManager = get(chain.thisObject!!, "this\$0")!!
                val helperObject = get(scanManager, "mScanHelper")!!
                scheduleOwner(helperObject, owner(helperObject))
            }
            result
        } }
        check(handlerClass.declaredMethods.any { it.name == "handleStopScan" }) { "Scan stop event unavailable" }
        hook(method(manager, "cleanup")) { chain ->
            val result = chain.proceed(chain.args.toTypedArray())
            guard("scan manager cleanup") {
                val helperObject = get(chain.thisObject!!, "mScanHelper")!!
                owners.remove(helperObject)?.let { cancelPending(it) }
                reconcileOutput()
            }
            result
        }
        for (name in listOf("cleanup", "stop", "stopAllScans", "unregisterScanner", "handleDeadScanClient")) {
            helper.declaredMethods.filter { it.name == name }.forEach { event -> hook(event) { chain ->
                val result = chain.proceed(chain.args.toTypedArray())
                chain.thisObject?.let { helperObject ->
                    if (name == "cleanup" || name == "stop") {
                        owners.remove(helperObject)?.let { cancelPending(it) }
                        reconcileOutput()
                    } else scheduleOwner(helperObject, owner(helperObject))
                }
                result
            } }
        }
        hook(method(handlerClass, "handleFlushBatchResults", client)) { chain ->
            val forced = flushRequests.remove(chain.args[0]) == true
            val result = chain.proceed(chain.args.toTypedArray())
            guard("flush") {
                val scanManager = get(chain.thisObject!!, "this\$0")!!
                val helperObject = get(scanManager, "mScanHelper")!!
                tick(helperObject, owner(helperObject), get(chain.args[0]!!, "scannerId") as Int, forced)
            }
            result
        }
        helper.declaredMethods.filter { it.name in setOf("registerScanner", "registerAndStartScan", "flushPendingBatchResults", "onScanResultInternal", "onBatchScanReportsInternal",
            "sendBatchScanResults", "sendResultByPendingIntent", "onTrackAdvFoundLost") }.forEach {
            check(module.deoptimize(it)) { "Cannot deoptimize BLE delivery ${it.name}" }
        }
        output = module.demandTimer("coloros_ble", 1000L) {
            val snapshot = synchronized(owners) { owners.entries.map { it.key to it.value } }
            for ((helperObject, state) in snapshot) {
                if (state.demanded) scheduleOwner(helperObject, state, emit = true)
            }
        }
        ready = true
        // The inspected ROM leaves ScanManager.sInstance null even during active scans.
        // Recover the real helper from AdapterService's current ScanController instead.
        try {
            val adapterType = field(helper, "mAdapterService").type
            val adapter = method(adapterType, "getAdapterService").invoke(null)
            val controller = adapter?.let { get(it, "mScanController") }
            // The framework feature flag chooses ScanController or the older Gatt owner.
            val gatt = adapter?.let { get(it, "mGattService") }
            val helperObject = controller?.let { get(it, "mTransitionalScanHelper") }
                ?: gatt?.let { get(it, "mTransitionalScanHelper") }
            HookStatus.workEvent(if (helperObject == null) "ble_existing_helper_absent" else "ble_existing_helper_found")
            if (helperObject != null) scheduleOwner(helperObject, owner(helperObject))
        } catch (error: Throwable) {
            HookStatus.error("BLE existing registration discovery", error)
        }
        configObservation = module.observeConfigChanges {
            if (ready && live) {
                val snapshot = synchronized(owners) { owners.entries.map { it.key to it.value } }
                snapshot.forEach { (helperObject, state) -> scheduleOwner(helperObject, state) }
                reconcileOutput()
            }
        }
    }

    private fun owner(helper: Any): Owner {
        observePermissions(helper)
        return synchronized(owners) { owners.getOrPut(helper) { Owner() } }
    }

    @Synchronized private fun observePermissions(helper: Any) {
        if (observedAppOps != null) return
        val ops = (get(helper, "mContext") as Context).getSystemService(AppOpsManager::class.java) ?: return
        observedAppOps = ops
        try {
            for (op in listOf(AppOpsManager.permissionToOp(Manifest.permission.BLUETOOTH_SCAN),
                AppOpsManager.OPSTR_FINE_LOCATION, AppOpsManager.OPSTR_COARSE_LOCATION).filterNotNull())
                ops.startWatchingMode(op, null, AppOpsManager.WATCH_FOREGROUND_CHANGES, permissionListener)
        } catch (error: Throwable) {
            ops.stopWatchingMode(permissionListener)
            observedAppOps = null
            HookStatus.error("BLE permission events", error)
        }
    }

    private fun reconcileOutput() {
        val config = module.readConfig()
        output?.setRequired(ready && live && !module.retiringGeneration && config != null &&
            config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true) &&
            (config.optJSONArray("bluetooth_json")?.length() ?: 0) > 0 &&
            synchronized(owners) { owners.values.any { it.demanded } })
    }

    private fun scheduleOwner(helper: Any, state: Owner, emit: Boolean = false) {
        if (!ready || !live || module.retiringGeneration) return
        state.dirty.set(true)
        if (emit) state.emit.set(true)
        if (!state.queued.compareAndSet(false, true)) return
        val handler = guard("scan handler") { get(get(helper, "mScanManager")!!, "mHandler") as Handler }
        val task = Runnable {
            state.dirty.set(false)
            try {
                state.demanded = guard("scan demand") {
                    tick(helper, state, emit = state.emit.getAndSet(false))
                } == true
            } finally {
                state.pending = null
                state.queued.set(false)
                reconcileOutput()
                // Only a real event that arrived during inspection requests another pass.
                if (state.dirty.get()) scheduleOwner(helper, state)
            }
        }
        state.handler = handler
        state.pending = task
        if (handler == null || !handler.post(task)) {
            state.pending = null
            state.queued.set(false)
            state.demanded = false
            reconcileOutput()
        }
    }

    private fun cancelPending(state: Owner) {
        state.pending?.let { state.handler?.removeCallbacks(it) }
        state.pending = null
        state.handler = null
        state.dirty.set(false)
        state.emit.set(false)
        state.queued.set(false)
        state.demanded = false
    }

    private fun tick(helper: Any, state: Owner, flushId: Int? = null, forceFlush: Boolean = false, emit: Boolean = true): Boolean {
        HookStatus.workTick("ble_scan_reconciliation")
        if (!ready || !live || module.retiringGeneration) return false
        val config = module.readConfig()
        if (config == null || !config.optBoolean("active")) {
            state.scans.clear()
            return false
        }
        val hasFixtures = config.optBoolean("mock_bluetooth", true) && (config.optJSONArray("bluetooth_json")?.length() ?: 0) > 0
        if (!hasFixtures) state.scans.clear()
        val manager = get(helper, "mScanManager") ?: return false
        val map = get(helper, "mScannerMap") ?: return false
        val regular = (call(manager, "getRegularScanQueue") as Collection<*>).filterNotNull()
        val batch = (call(manager, "getBatchScanQueue") as Collection<*>).filterNotNull()
        val all = regular + batch
        all.forEach { HookStatus.workTick("ble_registered_scan_seen") }
        state.scans.keys.retainAll(all.toSet())
        val fixtures = if (emit) results(config) else emptyList()
        var demanded = false
        for (client in all) guard("scan") {
            val id = get(client, "scannerId") as Int
            if (flushId != null && (id != flushId || client !in batch)) return@guard
            if (get(client, "appDied") == true) { state.scans.remove(client); return@guard }
            val app = method(map.javaClass, "getById", Int::class.javaPrimitiveType!!).invoke(map, id) ?: return@guard
            val callback = get(app, "callback")
            val info = get(app, "info")
            if (callback != null && callbacks[callback] == null) {
                // Registrations are platform-owned and survive a module hot reload.
                callbacks[callback] = Registration(ColorOsCaller(get(client, "appUid") as Int,
                    get(client, "callingPackage") as? String ?: get(app, "name") as? String),
                    WeakReference(helper), WeakReference(app))
                hookCallback(callback.javaClass)
                module.deliveryHandoff?.release(callback)
            }
            val who = if (callback != null) callbacks[callback]?.who?.let {
                it.copy(pkg = get(client, "callingPackage") as? String ?: it.pkg)
            } ?: run {
                val uid = get(client, "appUid") as? Int
                val pkg = get(client, "callingPackage") as? String ?: get(app, "name") as? String
                if (uid != null) ColorOsCaller(uid, pkg) else null
            } else info?.let {
                ColorOsCaller(get(it, "callingUid") as Int, get(it, "callingPackage") as? String)
            }
            if (who == null || !target(who, config, helper) || !permitted(helper, client, who)) {
                state.scans.remove(client); return@guard
            }
            val settings = get(client, "settings") as ScanSettings
            if (settings.scanMode == ScanSettings.SCAN_MODE_OPPORTUNISTIC && all.none {
                (get(it, "settings") as ScanSettings).scanMode != ScanSettings.SCAN_MODE_OPPORTUNISTIC
            }) return@guard
            // Empty remote environments still need native-callback filtering, including
            // registrations that survived hot reload. They need no periodic output.
            if (!hasFixtures) return@guard
            demanded = true
            if (!emit) return@guard
            @Suppress("UNCHECKED_CAST")
            val denylist = get(helper, "mLocationDenylistPredicate") as Predicate<ScanResult>
            val matched = fixtures.filter {
                matches.invoke(helper, client, it) == true &&
                    (get(client, "hasDisavowedLocation") != true || !denylist.test(it))
            }
            val now = SystemClock.elapsedRealtime()
            val tracker = state.scans.getOrPut(client) { BleDeliveryState(now) }
            try {
                synthetic.withValue(true) {
                    if (client in batch) {
                        if (tracker.batchDue(now, settings.reportDelayMillis, forceFlush) && matched.isNotEmpty()) {
                            sendBatch.invoke(helper, app, client, ArrayList(matched))
                        }
                    } else if (settings.callbackType == 1 || settings.callbackType == 8) {
                        if (callback != null) {
                            matched.forEach { scannerInterface.getMethod("onScanResult", ScanResult::class.java).invoke(callback, it) }
                        } else if (info != null && matched.isNotEmpty()) sendIntent.invoke(helper, info, ArrayList(matched), 1)
                    } else {
                        val changes = tracker.track(matched.associateBy { it.device.address }, now)
                        for ((found, values) in listOf(true to changes.found, false to changes.lost)) {
                            val flag = if (found) 2 else 4
                            if (settings.callbackType and flag != 0) {
                                if (callback != null) values.forEach {
                                    scannerInterface.getMethod("onFoundOrLost", Boolean::class.javaPrimitiveType, ScanResult::class.java)
                                        .invoke(callback, found, it)
                                } else if (info != null && values.isNotEmpty()) sendIntent.invoke(helper, info, ArrayList(values), flag)
                            }
                        }
                    }
                }
            } catch (error: java.lang.reflect.InvocationTargetException) {
                val cause = error.targetException
                if (cause is android.os.RemoteException || cause is android.app.PendingIntent.CanceledException) {
                    dead.invoke(helper, client); state.scans.remove(client)
                } else throw cause
            }
        }
        return demanded
    }

    private fun hookCallback(type: Class<*>) {
        if (!callbackClasses.add(type)) return
        type.declaredMethods.map { it.name }.distinct().filter { it in setOf("onScanResult", "onBatchScanResults", "onFoundOrLost") }
            .forEach { name -> deliveryHandles += XposedHelpers.hookAllMethods(type, name) { chain, _ ->
                val recipient = (chain.thisObject as? IInterface)?.asBinder() ?: chain.thisObject as? IBinder
                if (module.retiringGeneration && recipient in retiringRecipients) return@hookAllMethods null
                val registration = callbacks[chain.thisObject]
                val config = module.readConfig()
                val suppress = if (ready && synthetic.current != true && registration != null && config != null) guard("filter") {
                    val helper = registration.helper.get() ?: return@guard false
                    val app = registration.app.get() ?: return@guard false
                    val map = get(helper, "mScannerMap") ?: return@guard false
                    val current = method(map.javaClass, "getById", Int::class.javaPrimitiveType!!)
                        .invoke(map, get(app, "id"))
                    current === app && target(registration.who, config, helper)
                } == true else false
                if (suppress) null else chain.proceed(chain.args.toTypedArray())
            } }
    }

    internal fun prepareReloadHandoff(): Array<Any> {
        val config = module.readConfig() ?: return emptyArray()
        val snapshot = synchronized(callbacks) { callbacks.entries.map { it.key to it.value } }
        val nativeCallbacks = snapshot.filter { (_, registration) ->
            guard("reload recipient") {
                val helper = registration.helper.get() ?: return@guard false
                val app = registration.app.get() ?: return@guard false
                val map = get(helper, "mScannerMap") ?: return@guard false
                val current = method(map.javaClass, "getById", Int::class.javaPrimitiveType!!).invoke(map, get(app, "id"))
                current === app && target(registration.who, config, helper)
            } == true
        }.map { it.first }
        retiringRecipients = nativeCallbacks.mapNotNull { (it as? IInterface)?.asBinder() ?: it as? IBinder }.toSet()
        return nativeCallbacks.toTypedArray()
    }

    private fun permitted(helper: Any, client: Any, who: ColorOsCaller): Boolean {
        if (permission.invoke(helper, client) != true) return false
        val context = get(helper, "mContext") as Context
        val pkg = who.pkg ?: return false
        val ops = context.getSystemService(AppOpsManager::class.java)
        // Recheck revocation while a session is already running; no fabricated denied result.
        if (context.checkPermission(Manifest.permission.BLUETOOTH_SCAN, -1, who.uid) != PackageManager.PERMISSION_GRANTED) return false
        val scanOp = AppOpsManager.permissionToOp(Manifest.permission.BLUETOOTH_SCAN) ?: return false
        if (ops.noteOpNoThrow(scanOp, who.uid, pkg) != AppOpsManager.MODE_ALLOWED) return false
        if (get(client, "hasDisavowedLocation") != true && get(client, "hasScanWithoutLocationPermission") != true &&
            get(client, "hasNetworkSettingsPermission") != true && get(client, "hasNetworkSetupWizardPermission") != true) {
            val fine = get(client, "isQApp") == true
            val perm = if (fine) Manifest.permission.ACCESS_FINE_LOCATION else Manifest.permission.ACCESS_COARSE_LOCATION
            val op = if (fine) AppOpsManager.OPSTR_FINE_LOCATION else AppOpsManager.OPSTR_COARSE_LOCATION
            if (context.checkPermission(perm, -1, who.uid) != PackageManager.PERMISSION_GRANTED ||
                ops.noteOpNoThrow(op, who.uid, pkg) != AppOpsManager.MODE_ALLOWED) return false
        }
        return true
    }

    private fun results(config: JSONObject): List<ScanResult> {
        if (!config.optBoolean("mock_bluetooth", true)) return emptyList()
        val entries = config.optJSONArray("bluetooth_json") ?: return emptyList()
        val parse = method(type("android.bluetooth.le.ScanRecord"), "parseFromBytes", ByteArray::class.java)
        return (0 until entries.length().coerceAtMost(200)).mapNotNull { index -> guard("fixture") {
            val data = entries.optJSONObject(index) ?: return@guard null
            val address = normalizeMacAddress(data.optString("address"))
            if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) return@guard null
            val device = createBluetoothDevice(loader, address) ?: return@guard null
            val bytes = buildScanRecordBytes(data.optString("name"),
                data.optString("scanRecordHex", data.optString("rawBytes")), address)
            val record = parse.invoke(null, bytes)
            createScanResult(loader, device, record, data.optInt("rssi", -65).coerceIn(-127, 20),
                SystemClock.elapsedRealtimeNanos()) as? ScanResult
        } }.distinctBy { it.device.address }
    }

    override fun close() {
        ready = false
        observedAppOps?.let { runCatching { it.stopWatchingMode(permissionListener) } }
        observedAppOps = null
        configObservation?.close()
        configObservation = null
        output?.close()
        output = null
        synchronized(owners) { owners.values.toList() }.forEach { cancelPending(it) }
        super.close()
        if (!module.retiringGeneration) {
            deliveryHandles.forEach { runCatching { it.unhook() } }
            retiringRecipients = emptySet()
        }
        deliveryHandles.clear()
        owners.clear(); callbacks.clear(); callbackClasses.clear(); flushRequests.clear()
    }
}
