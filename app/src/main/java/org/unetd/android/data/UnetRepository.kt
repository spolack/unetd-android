package org.unetd.android.data

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * The app's view of the unetd control plane.
 *
 * Two implementations are planned: [FakeUnetRepository], which lets the UI run
 * and be reviewed before any native code exists, and a native one backed by the
 * JNI facade over the embedded unetd. Keeping the boundary here means the UI
 * never learns whether unetd is real.
 */
interface UnetRepository {
    val status: StateFlow<NetworkStatus>
    suspend fun connect()
    suspend fun disconnect()
}

/**
 * The real thing: status comes from the VpnService through [TunnelRuntime], and
 * connect/disconnect start and stop that service. The caller must have obtained
 * the VPN permission (VpnService.prepare) before [connect].
 */
class NativeUnetRepository(private val context: Context) : UnetRepository {

    override val status: StateFlow<NetworkStatus> = org.unetd.android.vpn.TunnelRuntime.status

    override suspend fun connect() {
        context.startForegroundService(Intent(context, org.unetd.android.vpn.UnetVpnService::class.java))
    }

    override suspend fun disconnect() {
        context.startService(
            Intent(context, org.unetd.android.vpn.UnetVpnService::class.java)
                .setAction(org.unetd.android.vpn.UnetVpnService.ACTION_DISCONNECT),
        )
    }
}

/** Drives the UI from [SampleData], including the connect transition. */
class FakeUnetRepository : UnetRepository {

    private val _status = MutableStateFlow(SampleData.disconnected)
    override val status = _status.asStateFlow()

    override suspend fun connect() {
        _status.value = SampleData.disconnected.copy(state = TunnelState.Connecting)
        delay(1200)
        _status.value = SampleData.connected.copy(connectedSinceMillis = System.currentTimeMillis())
    }

    override suspend fun disconnect() {
        _status.value = SampleData.disconnected
    }

    /** Nudges counters and handshake ages so the UI can be seen updating. */
    suspend fun tick() {
        while (true) {
            delay(1000)
            val current = _status.value
            if (current.state != TunnelState.Connected) continue
            _status.value = current.copy(
                peers = current.peers.map { peer ->
                    if (!peer.connected) peer else peer.copy(
                        rxBytes = peer.rxBytes + Random.nextLong(200, 9_000),
                        txBytes = peer.txBytes + Random.nextLong(200, 6_000),
                        lastHandshakeSec = peer.lastHandshakeSec?.let { (it + 1) % 120 },
                    )
                },
            )
        }
    }
}
