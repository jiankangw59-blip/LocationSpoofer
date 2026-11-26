package com.vincenthzr.locationspoofer.xposed.hooks

import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.utils.installHookHandles
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.lang.reflect.Method

/** Brief unavailable results while old query hooks are replaced; never blocks Binder threads. */
internal class SynchronousQueryHandoff(
    module: LocationHooker, methods: List<Method>, config: JSONObject
) : AutoCloseable {
    private val handles = installHookHandles(
        if (config.optBoolean("active", false)) methods.distinct() else emptyList(),
        { HookStatus.error("synchronous query handoff unhook", it) }
    ) { method ->
        val handle = module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // Preserve native exceptions, permissions and AppOps even during replacement.
                    val appOps = method.name in MOCK_OPERATION_QUERIES || method.name in MOCK_NOTE_OPERATIONS
                    val original = if (appOps) proceedNativeAppOps(chain) else chain.proceed(chain.args.toTypedArray())
                    if (method.name in MOCK_NOTE_OPERATIONS) {
                        return if (chain.args.firstOrNull() == MOCK_LOCATION_OP && isAppOpsBinderRequest() &&
                            SystemHookUtils.isTargetCaller(chain.thisObject, null, config)) projectMockNotedOp(original) else original
                    }
                    val mockOp = method.name in MOCK_OPERATION_QUERIES &&
                        method.returnType == Int::class.javaPrimitiveType
                    if (mockOp) {
                        return if (chain.args.firstOrNull() == MOCK_LOCATION_OP && original == 0 && isAppOpsBinderRequest() &&
                            SystemHookUtils.isTargetCaller(chain.thisObject, null, config)) 1 else original
                    }
                    if (method.declaringClass.name == "com.android.providers.settings.SettingsProvider") {
                        val target = SystemHookUtils.isTargetCaller(chain.thisObject, null, config)
                        return when {
                            method.name == "call" && original is android.os.Bundle && SystemMockSettings.isAdaptedCall(chain.args) ->
                                SystemMockSettings.adaptCall(original, target)
                            method.name == "query" -> SystemMockSettings.adaptQuery(original, chain.args, target)
                            else -> original
                        }
                    }
                    if (!SystemHookUtils.isTargetCaller(chain.thisObject,
                            SystemHookUtils.extractPackageName(chain.args), config)) return original
                    val type = method.returnType
                    return when {
                        type == Int::class.javaPrimitiveType -> 0 // NETWORK_TYPE_UNKNOWN
                        List::class.java.isAssignableFrom(type) -> arrayListOf<Any>()
                        type.name.endsWith("ParceledListSlice") -> type.getConstructor(List::class.java).newInstance(emptyList<Any>())
                        else -> null
                    }
                }
        })
        AutoCloseable { handle.unhook() }
    }

    override fun close() {
        handles.close()
    }
}
