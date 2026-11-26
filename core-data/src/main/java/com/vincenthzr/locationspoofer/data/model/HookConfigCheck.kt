package com.vincenthzr.locationspoofer.data.model

import com.vincenthzr.locationspoofer.vendor.HookStatusFiles

data class HookConfigCheck(
    val state: State,
    val hosts: Map<String, HostState> = emptyMap()
) {
    enum class State { UNAVAILABLE, CHANGING, COMPARED }

    enum class HostState { MATCHED, NO_RUNNING_TARGET, NO_REPORT, OLD_PROCESS, NO_SNAPSHOT_ID, DIFFERENT_SNAPSHOT }

    companion object {
        internal fun compare(
            before: String?,
            after: String?,
            runningPids: Map<String, Int>?,
            reports: Map<String, HookStatusReport?>
        ): HookConfigCheck {
            if (before == null || after == null || runningPids == null) return HookConfigCheck(State.UNAVAILABLE)
            if (before != after) return HookConfigCheck(State.CHANGING)
            val hosts = HookStatusFiles.PATHS.keys.associateWith { host ->
                val report = reports[host]?.takeIf { it.process == host }
                val live = runningPids[host]
                when {
                    live == null -> HostState.NO_RUNNING_TARGET
                    report == null -> HostState.NO_REPORT
                    report.pid != null && report.pid != live -> HostState.OLD_PROCESS
                    report.pid == null || report.snapshotId == null -> HostState.NO_SNAPSHOT_ID
                    report.snapshotId != after -> HostState.DIFFERENT_SNAPSHOT
                    else -> HostState.MATCHED
                }
            }
            return HookConfigCheck(State.COMPARED, hosts)
        }
    }
}
