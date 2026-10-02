package org.unetd.android.vpn

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.unetd.android.BuildConfig
import org.unetd.android.config.ConfigStore
import org.unetd.android.config.TunSettings
import org.unetd.android.config.TunnelConfig
import org.unetd.android.data.TunnelState
import org.unetd.android.nativebridge.Unetd
import org.unetd.android.nativebridge.WgGo
import java.io.File
import java.net.DatagramSocket

/**
 * The tunnel's one owner: a process-wide state machine on its own thread.
 *
 * The native singletons it drives (unetd's loop, wireguard-go's device
 * handles, the protector) are process-wide, so their owner is too. A
 * [UnetVpnService] instance is only the Android host for one connection: it
 * establishes the tun, lends its protect() and shows the notification. When
 * the system destroys the service under a running tunnel the controller
 * notices ([detach]) and stops; when it starts a new one, the controller
 * refuses until the old connection is gone.
 *
 * Bring-up order, each step depending on the one before:
 *   1. establish() the tun with the last known addresses and routes (a
 *      placeholder the very first time);
 *   2. wgTurnOn() hands wireguard-go that tun and makes it serve the UAPI socket
 *      `<socketDir>/<network>.sock`;
 *   3. unetd starts on its own thread, is pointed at the same socket directory,
 *      and the network is added -- unetd refuses a network whose UAPI socket
 *      does not exist yet, hence the order.
 *
 * Android fixes addresses and routes at establish() time. unetd derives every
 * host address from its public key, so one /64 route covers all peers and peer
 * churn never touches the tun; only when unetd's interface update differs from
 * what the tun was established with (first run, or a change to the signed
 * network data) is the tunnel re-established, new tun first.
 *
 * State reaches the UI as events: unetd reports peer up/down, a network
 * (re)load and STUN results, and every event refreshes [TunnelRuntime] from
 * the status snapshot. Only the counters (bytes, handshake age) are polled,
 * once a second and only while the UI is visible.
 *
 * Threading: everything that drives the natives runs on [control]. unetd's
 * callbacks arrive on its uloop thread and only post here -- a synchronous
 * call back into unetd from one of them would wait for the thread it is
 * running on.
 */
object TunnelController {

    /** What the controller needs from the Android side; the VpnService. */
    interface Host {
        val context: Context
        fun establish(tun: TunSettings): ParcelFileDescriptor?
        fun protectSocket(fd: Int): Boolean
        fun protectSocket(socket: DatagramSocket): Boolean
        /** The object wireguard-go calls `protect(int)` on; the VpnService itself. */
        val protector: Any
        fun notify(text: String)
        /** The connection is over; [error] is why, or null for a requested stop. */
        fun onFinished(error: String?)
    }

    private enum class State { Idle, Starting, Up, Stopping }

    private const val TAG = "TunnelController"
    /** unetd's default global PEX port; peers and the DHT expect it. */
    private const val PEX_PORT = 51819
    /** How long the tunnel may be without a connected peer before the DHT node is started again. */
    private const val DHT_IDLE_GRACE_MS = 60_000L

    private val thread = HandlerThread("unetd-control").also { it.start() }
    private val control = Handler(thread.looper)

    // All of the below belongs to the control thread.
    private var state = State.Idle
    @Volatile private var host: Host? = null
    private var cfg: TunnelConfig? = null
    private var wgHandle = -1
    private var currentTun: TunSettings? = null
    private var scope: CoroutineScope? = null
    private var dhtRunning = false
    private var dhtCheck: Runnable? = null
    private var lastPeerConnectedAt = 0L
    private var lastNotificationText: String? = null
    private lateinit var dataDir: String
    private lateinit var socketDir: String
    private lateinit var unixSocket: String

    fun start(host: Host, cfg: TunnelConfig) = control.post { doStart(host, cfg) }
    fun stop(reason: String?) = control.post { doStop(reason) }

    /** The host is going away; a tunnel it hosts cannot outlive it. */
    fun detach(host: Host) = control.post {
        if (this.host === host && state != State.Idle) doStop(null)
    }

    /** Whether [host] is the one hosting the current connection. */
    fun isHostedBy(host: Host): Boolean = this.host === host

    // ---- unetd callbacks (uloop thread) -----------------------------------------

    private val callbacks = object : Unetd.Callbacks {
        override fun protectSocket(fd: Int): Boolean = host?.protectSocket(fd) ?: false

        override fun onNetworkUpdate(json: String) {
            Log.d(TAG, "interface update: $json")
            val desired = TunSettings.fromUpdate(json) ?: return // link down: nothing to do
            control.post { applyTunSettings(desired) }
        }

        override fun onEvent(kind: Int, network: String?, peer: String?) {
            control.post { refresh(kind) }
        }
    }

    // ---- bring-up ----------------------------------------------------------

    private fun doStart(host: Host, cfg: TunnelConfig) {
        if (state != State.Idle) {
            Log.w(TAG, "start ignored: a tunnel is $state")
            if (this.host !== host) host.onFinished("A previous connection is still ${state.name.lowercase()}; try again in a moment")
            return
        }
        state = State.Starting
        this.host = host
        this.cfg = cfg
        val files = host.context.filesDir
        dataDir = File(files, "unetd").absolutePath
        val runDir = File(files, "run").also { it.mkdirs() }
        socketDir = File(runDir, "wireguard").absolutePath
        unixSocket = File(runDir, "unetd.sock").absolutePath

        Unetd.startLogCapture()
        // First line of every connection in the Log screen: which build this is.
        AppLog.line("unetd-android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) connecting; wireguard-go ${WgGo.wgVersion()}")
        TunnelRuntime.set(TunnelRuntime.empty.copy(name = cfg.effectiveName(), state = TunnelState.Connecting))

        val tun = ConfigStore.loadLastTun(host.context) ?: TunSettings.placeholder()
        val error = bringUp(host, cfg, tun)
        if (error != null) {
            doStop(error)
            return
        }
        state = State.Up
        currentTun = tun

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { s ->
            s.launch { pollWhileVisible() }
            if (cfg.debug) s.launch { delay(3_000); NetDiag.run(host.context) { host.protectSocket(it) } }
        }
        lastPeerConnectedAt = 0L
        if (cfg.dht) scheduleDhtCheck(0)
        refresh(null)
        Log.i(TAG, "tunnel up: wireguard-go ${WgGo.wgVersion()}, network ${cfg.effectiveName()}")
    }

    /** Steps 1-3 above against [tun]. Returns a user-facing error, or null. */
    private fun bringUp(host: Host, cfg: TunnelConfig, tun: TunSettings): String? {
        val name = cfg.effectiveName()

        val pfd = host.establish(tun) ?: return "Could not establish the VPN interface (permission revoked, or another VPN is active)"

        WgGo.wgSetProtector(host.protector)
        wgHandle = WgGo.wgTurnOn(socketDir, name, pfd.detachFd())
        if (wgHandle < 0) return "wireguard-go failed to start; see the log"

        val rc = Unetd.start(dataDir, socketDir, unixSocket, PEX_PORT, cfg.debug, callbacks)
        if (rc != 0) {
            teardownWg()
            return when (rc) {
                Unetd.EALREADY -> "unetd is still shutting down from the last connection; try again in a moment"
                Unetd.EADDRINUSE -> "The peer-exchange port $PEX_PORT is in use by another program"
                Unetd.EPERM -> "Android refused to exempt unetd's socket from the VPN (protect); with lockdown on, check the always-on settings"
                else -> "unetd failed to start ($rc)"
            }
        }

        val add = Unetd.networkAdd(cfg.unetdJson())
        if (add != 0) {
            teardownWg()
            Unetd.stop()
            return "unetd rejected the network configuration ($add); check the keys and the log"
        }
        return null
    }

    /** Re-establishes the tunnel if unetd wants different addresses or routes. */
    private fun applyTunSettings(desired: TunSettings) {
        val host = host ?: return
        val cfg = cfg ?: return
        if (state != State.Up || desired == currentTun) return
        Log.i(TAG, "addresses/routes changed, re-establishing: $desired")

        // The new tun first: Android replaces the VPN atomically on establish(),
        // so there is no moment without one. Only then is the old data plane
        // swapped out under unetd.
        val pfd = host.establish(desired)
        if (pfd == null) {
            doStop("Could not re-establish the VPN interface")
            return
        }
        val name = cfg.effectiveName()
        Unetd.networkRemove(name)
        teardownWg()
        wgHandle = WgGo.wgTurnOn(socketDir, name, pfd.detachFd())
        if (wgHandle < 0) {
            doStop("wireguard-go failed to restart; see the log")
            return
        }
        val rc = Unetd.networkAdd(cfg.unetdJson())
        if (rc != 0) {
            doStop("unetd rejected the network configuration after re-establishing ($rc)")
            return
        }
        currentTun = desired
        ConfigStore.saveLastTun(host.context, desired)
        if (cfg.debug) scope?.launch { delay(3_000); NetDiag.run(host.context) { host.protectSocket(it) } }
    }

    // ---- status ----------------------------------------------------------------

    /**
     * Reads the status snapshot (never blocks) into [TunnelRuntime], updates
     * the notification and the DHT policy. Runs on every unetd event, once a
     * second while the UI is visible, and at bring-up.
     */
    private fun refresh(eventKind: Int?) {
        val host = host ?: return
        val cfg = cfg ?: return
        if (state != State.Up) return
        val json = Unetd.status() ?: return
        val status = StatusParser.parse(json, cfg.effectiveName(), cfg.dht, TunnelRuntime.status.value) ?: return
        TunnelRuntime.set(status)

        val peerUp = status.peers.any { it.connected }
        if (peerUp) lastPeerConnectedAt = System.currentTimeMillis()

        val text = when (status.state) {
            TunnelState.Connected -> "${status.onlinePeerCount} of ${status.peers.size} peers reachable"
            else -> status.message ?: "Connecting…"
        }
        if (text != lastNotificationText) {
            lastNotificationText = text
            host.notify(text)
        }

        if (cfg.dht && (eventKind == Unetd.EV_PEER_UP || eventKind == Unetd.EV_PEER_DOWN || eventKind == Unetd.EV_NETWORK_RELOAD)) {
            scheduleDhtCheck(0)
        }
    }

    private suspend fun pollWhileVisible() {
        TunnelRuntime.uiVisible.collectLatest { visible ->
            if (!visible) return@collectLatest
            while (true) {
                control.post { refresh(null) }
                delay(1_000)
            }
        }
    }

    // ---- DHT policy ---------------------------------------------------------------

    /**
     * The DHT node runs only while it is needed: until the network data is
     * here and a peer is connected, and again after the tunnel has had no
     * connected peer for [DHT_IDLE_GRACE_MS]. While a peer is up it would only
     * keep the radio busy: searches restart as soon as they finish, the
     * routing table is maintained, and the announced port draws traffic.
     * Evaluated on peer events and, while no peer is up, when the grace period
     * runs out; no periodic tick.
     */
    private fun scheduleDhtCheck(delayMs: Long) {
        dhtCheck?.let { control.removeCallbacks(it) }
        val check = Runnable { evaluateDht() }
        dhtCheck = check
        control.postDelayed(check, delayMs)
    }

    private fun evaluateDht() {
        val cfg = cfg ?: return
        if (state != State.Up || !cfg.dht) return
        val status = TunnelRuntime.status.value
        val peerUp = status.peers.any { it.connected }
        val idleFor = System.currentTimeMillis() - lastPeerConnectedAt
        val wanted = status.state != TunnelState.Connected || (!peerUp && idleFor >= DHT_IDLE_GRACE_MS)

        if (wanted && !dhtRunning) {
            val pubkey = Unetd.publicKey(cfg.effectivePrivateKey()) ?: cfg.effectiveName()
            val rc = Unetd.dhtStart(
                idString = pubkey,
                nodeFile = File(host!!.context.filesDir, "dht-nodes.bin").absolutePath,
                authKeys = cfg.authKeys(),
                bootstrap = cfg.dhtBootstrap,
                debug = cfg.debug,
            )
            AppLog.line(if (rc == 0) "dht: node started (no connected peer)" else "dht: node failed to start ($rc)")
            dhtRunning = rc == 0
        } else if (!wanted && dhtRunning) {
            AppLog.line("dht: node stopped, a peer is connected; it restarts after ${DHT_IDLE_GRACE_MS / 1000} s without one")
            Unetd.dhtStop()
            dhtRunning = false
        }
        // Without a peer the grace period decides; check again when it runs out.
        if (!peerUp && !dhtRunning) scheduleDhtCheck(DHT_IDLE_GRACE_MS - idleFor.coerceIn(0, DHT_IDLE_GRACE_MS) + 1)
    }

    // ---- teardown ----------------------------------------------------------------

    private fun teardownWg() {
        if (wgHandle >= 0) {
            WgGo.wgTurnOff(wgHandle) // also closes the tun fd it owns
            wgHandle = -1
        }
    }

    private fun doStop(error: String?) {
        val host = host
        if (state == State.Idle) {
            // Nothing to stop; a disconnect request while idle still gets an answer.
            if (error != null) TunnelRuntime.setState(TunnelState.Disconnected, error)
            host?.onFinished(error)
            return
        }
        if (error != null) Log.e(TAG, error)
        state = State.Stopping
        dhtCheck?.let { control.removeCallbacks(it) }
        dhtCheck = null
        scope?.cancel()
        scope = null
        if (dhtRunning) Unetd.dhtStop()
        dhtRunning = false
        // The tun goes first: closing it is what ends the VPN for Android, and it
        // must not wait on unetd, whose event loop can sit in a blocking DNS
        // lookup for a gateway (seconds to minutes when the network is unusable).
        teardownWg()
        AppLog.line("tunnel closed, stopping unetd")
        val rc = Unetd.stop()
        if (rc != 0) AppLog.line("unetd did not stop in time ($rc); it finishes on its own")
        WgGo.wgSetProtector(null)
        currentTun = null
        lastNotificationText = null
        state = State.Idle
        this.host = null
        cfg = null
        TunnelRuntime.setState(TunnelState.Disconnected, error)
        host?.onFinished(error)
    }
}
