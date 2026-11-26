package com.vincenthzr.locationspoofer.xposed.hooks

import android.content.ContentProvider
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** Adapts the provider response without writing Settings or installing hooks in client apps. */
internal class SystemMockSettings(private val owner: LocationHooker, loader: ClassLoader) : AutoCloseable {
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val installed = mutableSetOf<Class<*>>()
    private var closed = false

    init {
        // Startup: observe provider attachment. Hot reload: recover the already attached instance.
        handles += XposedHelpers.hookAllMethods(ContentProvider::class.java, "attachInfo") { chain, _ ->
            val result = chain.proceed(chain.args.toTypedArray())
            if (chain.thisObject?.javaClass?.name == PROVIDER) install(chain.thisObject!!)
            result
        }
        runCatching {
            val threadClass = XposedHelpers.findClass("android.app.ActivityThread", loader)
            val thread = XposedHelpers.callStaticMethod(threadClass, "currentActivityThread") ?: return@runCatching
            val providers = XposedHelpers.getObjectField(thread, "mProviderMap") as? Map<*, *> ?: return@runCatching
            synchronized(providers) { providers.values.toList() }.forEach { record ->
                if (record != null) {
                    val provider = XposedHelpers.getObjectField(record, "mLocalProvider")
                    if (provider?.javaClass?.name == PROVIDER) install(provider)
                }
            }
        }.onFailure { HookStatus.error("SettingsProvider discovery", it) }
    }

    @Synchronized private fun install(provider: Any) {
        if (closed) return
        val clazz = provider.javaClass
        if (!installed.add(clazz)) return
        HookStatus.classFound(SystemComponent.SETTINGS_PROVIDER, clazz, "ActivityThread.localProvider")
        handles += XposedHelpers.hookAllMethods(clazz, "call") { chain, method ->
            val original = chain.proceed(chain.args.toTypedArray())
            if (method !is Method || method.returnType != Bundle::class.java ||
                chain.args.getOrNull(0) != "GET_secure" || chain.args.getOrNull(1) !in KEYS || original !is Bundle) {
                return@hookAllMethods original
            }
            adaptCall(original, isTarget(chain.thisObject))
        }
        handles += XposedHelpers.hookAllMethods(clazz, "query") { chain, _ ->
            val original = chain.proceed(chain.args.toTypedArray())
            adaptQuery(original, chain.args, isTarget(chain.thisObject))
        }
        // Clear entries cached before this generation. Stored values and permissions do not change.
        runCatching { invalidateExistingCaches(provider) }
            .onFailure { HookStatus.error("SettingsProvider cache invalidation", it) }
    }

    private fun isTarget(service: Any?): Boolean = owner.readConfig()?.let {
        SystemHookUtils.isTargetCaller(service, null, it)
    } == true

    private fun invalidateExistingCaches(provider: Any) {
        val context = (provider as ContentProvider).context ?: return
        val lock = XposedHelpers.getObjectField(provider, "mLock") ?: return
        val registry = XposedHelpers.getObjectField(provider, "mSettingsRegistry") ?: return
        val generations = XposedHelpers.getObjectField(registry, "mGenerationRegistry") ?: return
        val state = Class.forName("com.android.providers.settings.SettingsState", false, provider.javaClass.classLoader)
        val type = XposedHelpers.getStaticObjectField(state, "SETTINGS_TYPE_SECURE") as Int
        val users = context.getSystemService(UserManager::class.java)?.userProfiles.orEmpty()
        synchronized(lock) {
            for (user in users) {
                val userId = XposedHelpers.callMethod(user, "getIdentifier") as Int
                val key = XposedHelpers.callStaticMethod(state, "makeKey", type, userId) as Int
                for (name in KEYS) XposedHelpers.callMethod(generations, "incrementGeneration", key, name)
                XposedHelpers.callMethod(generations, "incrementGenerationForUnsetSettings", key)
            }
        }
    }

    @Synchronized override fun close() {
        closed = true
        // Framework-owned hooks stay until the next generation installs its query barrier.
        // super.onHotReloaded removes them; eagerly unhooking here opens a native-data window.
        handles.clear()
        installed.clear()
    }

    private class MockSettingsCursor(cursor: Cursor, private val selected: String?) : CursorWrapper(cursor) {
        private val nameColumn = cursor.getColumnIndex("name")
        private val valueColumn = cursor.getColumnIndex("value")
        private fun adapted(column: Int): Boolean = column == valueColumn && column >= 0 &&
            (if (nameColumn >= 0) super.getString(nameColumn) else selected) in KEYS
        override fun getString(columnIndex: Int): String? = if (adapted(columnIndex)) "0" else super.getString(columnIndex)
        override fun getType(columnIndex: Int): Int = if (adapted(columnIndex)) Cursor.FIELD_TYPE_STRING else super.getType(columnIndex)
        override fun isNull(columnIndex: Int): Boolean = if (adapted(columnIndex)) false else super.isNull(columnIndex)
        override fun getInt(columnIndex: Int): Int = if (adapted(columnIndex)) 0 else super.getInt(columnIndex)
        override fun getLong(columnIndex: Int): Long = if (adapted(columnIndex)) 0L else super.getLong(columnIndex)
        override fun getShort(columnIndex: Int): Short = if (adapted(columnIndex)) 0 else super.getShort(columnIndex)
        override fun getFloat(columnIndex: Int): Float = if (adapted(columnIndex)) 0f else super.getFloat(columnIndex)
        override fun getDouble(columnIndex: Int): Double = if (adapted(columnIndex)) 0.0 else super.getDouble(columnIndex)
    }

    companion object {
        private const val PROVIDER = "com.android.providers.settings.SettingsProvider"
        private val KEYS = setOf("mock_location", "allow_mock_location")

        internal fun isAdaptedCall(args: List<Any?>): Boolean =
            args.getOrNull(0) == "GET_secure" && args.getOrNull(1) in KEYS

        internal fun adaptCall(original: Bundle, target: Boolean): Bundle = Bundle(original).apply {
            // A cached 0 must not survive switching simulation off or excluding a caller.
            remove("_track_generation")
            remove("_generation_index")
            remove("_generation")
            if (target) putString("value", "0")
        }

        internal fun adaptQuery(original: Any?, args: List<Any?>, target: Boolean): Any? {
            val uri = args.firstOrNull() as? Uri
            if (!target || original !is Cursor || uri?.authority?.substringAfter('@') != "settings" ||
                uri.pathSegments.firstOrNull() != "secure") return original
            val selected = uri.pathSegments.getOrNull(1) ?: run {
                val where = args.getOrNull(2) as? String
                @Suppress("UNCHECKED_CAST")
                val selectionArgs = args.getOrNull(3) as? Array<String>
                if (where?.replace(" ", "") in setOf("name=?", "(name=?)")) selectionArgs?.singleOrNull() else null
            }
            return MockSettingsCursor(original, selected)
        }
    }
}
