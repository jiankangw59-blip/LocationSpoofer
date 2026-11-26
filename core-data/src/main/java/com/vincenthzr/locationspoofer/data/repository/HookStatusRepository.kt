package com.vincenthzr.locationspoofer.data.repository

import com.vincenthzr.locationspoofer.data.model.HookStatusReport
import com.vincenthzr.locationspoofer.data.model.HookConfigCheck
import com.vincenthzr.locationspoofer.utils.FrameworkConfigChannel
import com.vincenthzr.locationspoofer.utils.SystemFileCommands
import com.vincenthzr.locationspoofer.utils.RootManager
import com.vincenthzr.locationspoofer.utils.XposedModuleStatus
import com.vincenthzr.locationspoofer.vendor.HookStatusFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 通过 root 读取全局方案各系统进程写出的 Hook 运行状态报告 */
class HookStatusRepository(private val rootManager: RootManager) {

    data class Diagnostic(val reports: Map<String, HookStatusReport?>, val config: HookConfigCheck)

    /** 按进程顺序返回；某个进程没有报告（从未生效或文件不可读）时对应项为 null */
    suspend fun readAll(): Map<String, HookStatusReport?> = withContext(Dispatchers.IO) { readReports() }

    suspend fun diagnose(): Diagnostic = withContext(Dispatchers.IO) {
        val service = XposedModuleStatus.mService
        val prefs = try { service?.getRemotePreferences(FrameworkConfigChannel.GROUP) }
        catch (_: Exception) { null }
        fun currentId(): String? = try {
            prefs?.getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)?.let { text ->
                val envelope = JSONObject(text)
                require(envelope.getInt("version") == FrameworkConfigChannel.VERSION)
                (envelope.opt("id") as? String)?.takeIf(String::isNotBlank)
            }
        } catch (_: Exception) { null }
        val before = currentId()
        val reports = readReports()
        val running = try {
            if (service == null || service.apiVersion < 102) null else {
                val names = mapOf("system" to "SYSTEM_SERVER", "system_server" to "SYSTEM_SERVER",
                    "com.android.phone" to "PHONE", "com.android.bluetooth" to "BLUETOOTH")
                service.runningTargets.mapNotNull { target ->
                    names[target.processName]?.let { it to target.pid }
                }.groupBy({ it.first }, { it.second }).mapNotNull { (host, pids) ->
                    pids.distinct().singleOrNull()?.takeIf { it > 0 }?.let { host to it }
                }.toMap()
            }
        } catch (_: Exception) { null }
        val after = currentId().takeIf { XposedModuleStatus.mService === service }
        Diagnostic(reports, HookConfigCheck.compare(before, after, running, reports))
    }

    private fun readReports(): Map<String, HookStatusReport?> =
        HookStatusFiles.PATHS.mapValues { (process, paths) ->
            val output = rootManager.executeCommand(SystemFileCommands.read(paths))
            HookStatusReport.parse(output)?.takeIf { it.process == process }
        }
}
