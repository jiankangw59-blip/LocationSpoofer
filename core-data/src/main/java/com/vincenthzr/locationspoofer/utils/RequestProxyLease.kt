package com.vincenthzr.locationspoofer.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID

interface RequestProxyLease {
    val proxy: Proxy
    suspend fun release()
}

fun interface RequestProxyLeaseProvider {
    suspend fun acquire(): RequestProxyLease?
}

/** Optional adapter for an already installed FeedMeBoost broker; never enables Surfing or installs a service. */
class FeedMeProxyLeaseProvider(context: Context, private val root: RootManager) : RequestProxyLeaseProvider {
    private val apk = context.applicationInfo.sourceDir

    private suspend fun command(operation: String): JSONObject = withContext(Dispatchers.IO) {
        val quotedApk = "'" + apk.replace("'", "'\"'\"'") + "'"
        val result = root.executeCommand("CLASSPATH=$quotedApk /system/bin/app_process /system/bin " +
            "com.vincenthzr.locationspoofer.utils.FeedMeProxyCommand $operation")
        // app_process can print runtime notices before the single JSON result.
        result.lineSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.lastOrNull()
            ?: JSONObject().put("ok", false)
    }

    override suspend fun acquire(): RequestProxyLease? {
        val response = command("acquire")
        if (!response.optBoolean("ok") || response.optInt("port") != 18765) return null
        val id = response.optString("lease")
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) return null
        return object : RequestProxyLease {
            override val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 18765))
            override suspend fun release() {
                check(command("release $id").optBoolean("ok")) { "Private proxy release unavailable" }
            }
        }
    }

    /** Read-only acceptance diagnostic, without exposing any account or sync API. */
    suspend fun status(): JSONObject = command("status")
}
