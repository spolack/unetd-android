package org.unetd.android.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.unetd.android.data.Capabilities
import org.unetd.android.data.NetworkStatus
import org.unetd.android.data.TunnelState

/**
 * The service's view of the tunnel, shared with the UI. Service and activity live
 * in the same process, so a process-wide StateFlow is all the IPC needed.
 */
object TunnelRuntime {

    val empty = NetworkStatus(
        name = "",
        localAddress = "—",
        ulaPrefix = "",
        state = TunnelState.Disconnected,
        connectedSinceMillis = null,
        peers = emptyList(),
        capabilities = Capabilities(pex = false, stun = false, dht = false, rawSockets = false),
    )

    private val _status = MutableStateFlow(empty)
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    fun set(status: NetworkStatus) {
        _status.value = status
    }

    fun update(transform: (NetworkStatus) -> NetworkStatus) {
        _status.value = transform(_status.value)
    }

    fun setState(state: TunnelState, message: String? = null) {
        update { current ->
            if (state == TunnelState.Disconnected) {
                empty.copy(name = current.name, message = message)
            } else {
                current.copy(state = state, message = message)
            }
        }
    }
}
