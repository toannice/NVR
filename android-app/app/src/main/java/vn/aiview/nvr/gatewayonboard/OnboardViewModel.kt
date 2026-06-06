package vn.aiview.nvr.gatewayonboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.aiview.nvr.gatewayonboard.net.AgentClient
import vn.aiview.nvr.gatewayonboard.net.Gateway
import vn.aiview.nvr.gatewayonboard.net.Identity
import vn.aiview.nvr.gatewayonboard.net.LanScanner

/** Screens, navigated by a simple state machine (the flow is linear: scan → detail → add). */
sealed interface Screen {
    data object Scan : Screen
    data class Detail(val gateway: Gateway) : Screen
    data class Add(val gateway: Gateway) : Screen
}

class OnboardViewModel : ViewModel() {
    private val agent = AgentClient()
    private val scanner = LanScanner(agent)

    var screen by mutableStateOf<Screen>(Screen.Scan)
        private set

    val gateways = mutableStateListOf<Gateway>()

    var scanning by mutableStateOf(false)
        private set
    var localIp by mutableStateOf<String?>(null)
        private set
    var scanError by mutableStateOf<String?>(null)
        private set

    var saving by mutableStateOf(false)
        private set
    var submitError by mutableStateOf<String?>(null)
        private set
    var submitted by mutableStateOf<Identity?>(null)
        private set

    init {
        localIp = scanner.localIpv4()
    }

    fun scan() {
        if (scanning) return
        scanning = true
        scanError = null
        gateways.clear()
        localIp = scanner.localIpv4()
        viewModelScope.launch {
            if (localIp == null) {
                scanError = "Không tìm thấy mạng LAN. Hãy nối Wi-Fi cùng mạng với gateway."
                scanning = false
                return@launch
            }
            try {
                scanner.scan(onFound = { gw ->
                    withContext(Dispatchers.Main) {
                        if (gateways.none { it.host == gw.host }) gateways.add(gw)
                    }
                })
            } catch (e: Exception) {
                scanError = e.message ?: "Quét thất bại"
            } finally {
                scanning = false
            }
        }
    }

    fun open(gw: Gateway) {
        screen = Screen.Detail(gw)
    }

    fun startAdd(gw: Gateway) {
        submitError = null
        submitted = null
        screen = Screen.Add(gw)
    }

    fun back() {
        screen = when (val s = screen) {
            is Screen.Add -> Screen.Detail(s.gateway)
            is Screen.Detail -> Screen.Scan
            Screen.Scan -> Screen.Scan
        }
    }

    fun finishToScan() {
        submitted = null
        submitError = null
        screen = Screen.Scan
    }

    fun submitLabel(gw: Gateway, name: String, installer: String, location: String) {
        if (saving) return
        saving = true
        submitError = null
        viewModelScope.launch {
            val port = gw.identity.agentPort.takeIf { it > 0 } ?: AgentClient.DEFAULT_PORT
            val res = agent.label(gw.host, port, name, installer, location)
            saving = false
            res.onSuccess { id ->
                submitted = id
                val idx = gateways.indexOfFirst { it.host == gw.host }
                if (idx >= 0) {
                    gateways[idx] = gw.copy(
                        identity = gw.identity.copy(
                            name = id.name,
                            installer = id.installer,
                            location = id.location,
                        ),
                    )
                }
            }.onFailure { submitError = it.message ?: "Gửi thất bại" }
        }
    }
}
