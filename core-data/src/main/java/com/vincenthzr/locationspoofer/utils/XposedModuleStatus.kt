package com.vincenthzr.locationspoofer.utils

import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ModuleConnectionState { CONNECTING, CONNECTED, UNAVAILABLE }

/**
 * LSPosed 模块连接状态的全局持有者，从 Application 子类中拆出来，
 * 避免 utils 包反向依赖 :app 模块。由 LocationApp 在收到
 * XposedServiceHelper 回调时更新，供 LSPosedManager / ViewModel 读取。
 */
object XposedModuleStatus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectionTimeout: Job? = null
    private val _connectionState = MutableStateFlow(ModuleConnectionState.CONNECTING)
    val connectionState: StateFlow<ModuleConnectionState> = _connectionState

    private val _isModuleActive = MutableStateFlow(false)
    val isModuleActive: StateFlow<Boolean> = _isModuleActive

    private val _service = MutableStateFlow<XposedService?>(null)
    val service: StateFlow<XposedService?> = _service
    val mService: XposedService? get() = _service.value

    @Synchronized
    fun beginConnecting() {
        if (_service.value != null) return
        connectionTimeout?.cancel()
        _connectionState.value = ModuleConnectionState.CONNECTING
        connectionTimeout = scope.launch {
            delay(15_000)
            connectionFailed()
        }
    }

    @Synchronized
    fun connectionFailed() {
        if (_service.value != null) return
        _connectionState.value = ModuleConnectionState.UNAVAILABLE
    }

    @Synchronized
    fun update(service: XposedService) {
        connectionTimeout?.cancel()
        connectionTimeout = null
        _service.value = service
        _isModuleActive.value = true
        _connectionState.value = ModuleConnectionState.CONNECTED
    }

    @Synchronized
    fun clear(service: XposedService? = null) {
        if (service != null && _service.value !== service) return
        _service.value = null
        _isModuleActive.value = false
        connectionTimeout?.cancel()
        connectionTimeout = null
        _connectionState.value = ModuleConnectionState.UNAVAILABLE
    }
}
