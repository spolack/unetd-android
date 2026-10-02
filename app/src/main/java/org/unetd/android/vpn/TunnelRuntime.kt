package org.unetd.android.vpn

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.unetd.android.data.Capabilities
import org.unetd.android.data.NetworkStatus
import org.unetd.android.data.TunnelState

/**
 * The tunnel's state as the UI sees it. Service, controller and activity live
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

    /**
     * True while any activity of this process is started; the controller
     * polls the counters only then. Derived from the process lifecycle, so two
     * activities (the notification opens one) cannot clear it for each other.
     */
    private val _uiVisible = MutableStateFlow(false)
    val uiVisible: StateFlow<Boolean> = _uiVisible.asStateFlow()

    private var observing = false

    /** Idempotent; call from the main thread (an activity's or service's onCreate). */
    fun observeProcessLifecycle() {
        if (observing) return
        observing = true
        val install = Runnable {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { _uiVisible.value = true }
                override fun onStop(owner: LifecycleOwner) { _uiVisible.value = false }
            })
        }
        if (Looper.myLooper() == Looper.getMainLooper()) install.run() else Handler(Looper.getMainLooper()).post(install)
    }

    fun set(status: NetworkStatus) {
        _status.value = status
    }

    fun setState(state: TunnelState, message: String? = null) {
        _status.update { current ->
            if (state == TunnelState.Disconnected) {
                empty.copy(name = current.name, message = message)
            } else {
                current.copy(state = state, message = message)
            }
        }
    }
}
