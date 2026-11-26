package com.vincenthzr.locationspoofer.xposed.hooks

import android.Manifest
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.hooks.network.buildFakeCellInfoList
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

private data class CellRequest(val callback: Any, val pkg: String, val uid: Int) {
    var death: IBinder.DeathRecipient? = null
}
private val requests = ConcurrentHashMap<IBinder, CellRequest>()
private val callbackTypes = ConcurrentHashMap.newKeySet<Class<*>>()

internal fun hasPendingCellInfoRequests(): Boolean = requests.isNotEmpty()

private fun forget(binder: IBinder, request: CellRequest) {
    if (requests.remove(binder, request)) request.death?.let { runCatching { binder.unlinkToDeath(it, 0) } }
}

/** Transform only successful native responses, after PhoneInterfaceManager's access checks. */
internal fun LocationHooker.routeCellInfoRequest(chain: XposedInterface.Chain, loader: ClassLoader): Any? {
    val config = readConfig()
    val pkg = SystemHookUtils.extractPackageName(chain.args)
    val uid = Binder.getCallingUid()
    if (config == null || pkg == null || !SystemHookUtils.isTargetCaller(chain.thisObject, pkg, config, uid))
        return chain.proceed(chain.args.toTypedArray())
    val callback = chain.args.firstOrNull { it != null && LocationHooker.hasTypeByName(it.javaClass, "android.telephony.ICellInfoCallback") }
        ?: return chain.proceed(chain.args.toTypedArray())
    val binder = (callback as? IInterface)?.asBinder() ?: return chain.proceed(chain.args.toTypedArray())
    val request = CellRequest(callback, pkg, uid)
    requests.put(binder, request)?.let { previous -> previous.death?.let { runCatching { binder.unlinkToDeath(it, 0) } } }
    val recipient = object : IBinder.DeathRecipient { override fun binderDied() { forget(binder, request) } }
    request.death = recipient
    try { binder.linkToDeath(recipient, 0) }
    catch (_: Throwable) { forget(binder, request) }
    if (callbackTypes.add(callback.javaClass)) {
        for (name in listOf("onCellInfo", "onError")) XposedHelpers.hookAllMethods(callback.javaClass, name) { delivery, _ ->
            val key = (delivery.thisObject as? IInterface)?.asBinder()
            val current = key?.let { requests[it] }
            if (key == null || current == null) return@hookAllMethods delivery.proceed(delivery.args.toTypedArray())
            val args = delivery.args.toTypedArray()
            try {
                if (name == "onCellInfo") {
                    val snapshot = readConfig()
                    if (snapshot != null && registeredPackageEnabled(snapshot, current.pkg, current.uid)) {
                        val context = SystemHookUtils.getSystemContext()
                        val allowed = context != null && context.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, -1, current.uid) == PackageManager.PERMISSION_GRANTED &&
                            context.getSystemService(AppOpsManager::class.java)?.noteOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, current.uid, current.pkg) == AppOpsManager.MODE_ALLOWED
                        val observed = args.firstOrNull() as? List<*>
                        args[0] = if (allowed && observed?.isNotEmpty() == true && snapshot.optBoolean("mock_cell", true))
                            try { buildFakeCellInfoList(loader, snapshot.optDouble("lat"), snapshot.optDouble("lng"), snapshot) }
                            catch (error: Throwable) { HookStatus.error("Async CellInfo transformation", error); emptyList<Any>() }
                        else emptyList<Any>()
                    }
                }
                delivery.proceed(args)
            } finally { forget(key, current) }
        }
    }
    return try { chain.proceed(chain.args.toTypedArray()) }
    catch (error: Throwable) { forget(binder, request); throw error }
}
