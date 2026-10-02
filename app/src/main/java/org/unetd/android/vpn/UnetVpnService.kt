package org.unetd.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.unetd.android.BuildConfig
import org.unetd.android.MainActivity
import org.unetd.android.R
import org.unetd.android.config.ConfigStore
import org.unetd.android.config.TunSettings
import org.unetd.android.config.TunnelConfig
import org.unetd.android.data.TunnelState
import org.unetd.android.nativebridge.Unetd
import org.unetd.android.nativebridge.WgGo
import java.io.File

/**
 * The VPN data plane and the home of the embedded control plane.
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
 * network data) is the tunnel re-established.
 *
 * Threading: everything that drives the natives runs on [control], a single
 * HandlerThread. unetd's callbacks arrive on its uloop thread and only post
 * work here -- a synchronous call back into unetd from one of them would wait
 * for the thread it is running on.
 */
class UnetVpnService : VpnService(), Unetd.Callbacks {

    private lateinit var controlThread: HandlerThread
    private lateinit var control: Handler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var config: TunnelConfig? = null
    private var active = false
    private var wgHandle = -1
    private var currentTun: TunSettings? = null
    private var statusJob: Job? = null
    private var dhtPing: Runnable? = null
    private var dhtRunning = false
    @Volatile private var lastPeerConnectedAt = 0L
    private var lastNotificationText: String? = null

    private val dataDir get() = File(filesDir, "unetd").absolutePath
    private val runDir get() = File(filesDir, "run")
    private val socketDir get() = File(runDir, "wireguard").absolutePath
    private val unixSocket get() = File(runDir, "unetd.sock").absolutePath

    override fun onCreate() {
        super.onCreate()
        controlThread = HandlerThread("unetd-control").also { it.start() }
        control = Handler(controlThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            control.post { stopTunnel(null) }
            return START_NOT_STICKY
        }

        val cfg = ConfigStore.load(this)
        if (cfg == null || !cfg.isComplete) {
            TunnelRuntime.setState(TunnelState.Disconnected, "Not configured yet")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification("Connecting…"))
        control.post { startTunnel(cfg) }
        // Restarted by the system after being killed: reconnect from the saved config.
        return START_STICKY
    }

    // ---- bring-up ----------------------------------------------------------

    private fun startTunnel(cfg: TunnelConfig) {
        if (active) return
        active = true
        config = cfg
        runDir.mkdirs()
        Unetd.startLogCapture()
        // First line of every connection in the Log screen: which build this is.
        Log.i(TAG, "unetd-android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) starting; wireguard-go ${WgGo.wgVersion()}")
        AppLog.line(
            "unetd-android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) connecting; " +
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "always-on=$isAlwaysOn lockdown=$isLockdownEnabled" else "",
        )
        TunnelRuntime.set(TunnelRuntime.empty.copy(name = cfg.effectiveName(), state = TunnelState.Connecting))

        val tun = ConfigStore.loadLastTun(this) ?: TunSettings.placeholder()
        val error = bringUp(cfg, tun)
        if (error != null) {
            stopTunnel(error)
            return
        }

        val name = cfg.effectiveName()
        statusJob = scope.launch { pollStatus(name, cfg.dht) }
        if (cfg.dht) scheduleDhtPing(cfg)
        scope.launch { delay(3_000); NetDiag.run(this@UnetVpnService) { protect(it) } }
        Log.i(TAG, "tunnel up: wireguard-go ${WgGo.wgVersion()}, network $name")
    }

    /** Steps 1-3 above against [tun]. Returns a user-facing error, or null. */
    private fun bringUp(cfg: TunnelConfig, tun: TunSettings): String? {
        val name = cfg.effectiveName()

        val pfd = establish(tun) ?: return "Could not establish the VPN interface (permission revoked, or another VPN is active)"

        WgGo.wgSetProtector(this)
        WgGo.wgSetSocketDirectory(socketDir)
        wgHandle = WgGo.wgTurnOn(name, pfd.detachFd(), "")
        if (wgHandle < 0) return "wireguard-go failed to start; see the log"

        if (!Unetd.isRunning) {
            val rc = Unetd.start(dataDir, socketDir, unixSocket, PEX_PORT, cfg.debug, this)
            if (rc != 0) {
                teardownWg()
                return "unetd failed to start ($rc)"
            }
        }

        val rc = Unetd.networkAdd(cfg.unetdJson())
        if (rc != 0) {
            teardownWg()
            return "unetd rejected the network configuration ($rc); check the keys and the log"
        }
        currentTun = tun
        return null
    }

    private fun establish(tun: TunSettings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(SESSION_NAME)
            // wireguard-android's default; unetd never sets one. 1280 is the IPv6
            // minimum, so it survives any further encapsulation.
            .setMtu(1280)
            .setBlocking(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        // Split tunnel by routing: only unetd's prefixes go in, never a default
        // route. Android adds a rule on top of routing, though: a family (IPv4 or
        // IPv6) for which the VPN adds no address, route or DNS server is BLOCKED
        // for every app using the VPN, not passed through. The placeholder tun has
        // only an IPv6 address, and a host without an IPv4 ipaddr would be in the
        // same position permanently, so both families are allowed explicitly and
        // unrouted destinations leave via the underlying network.
        builder.allowFamily(OsConstants.AF_INET)
        builder.allowFamily(OsConstants.AF_INET6)

        // Deliberately no addDisallowedApplication(packageName): that would exclude
        // this whole UID from the tunnel, including unetd's per-network PEX socket,
        // which is bound to the in-tunnel address and must go through it. Sockets
        // that need to bypass are protect()ed individually.
        return try {
            for (a in tun.addresses) {
                val (addr, prefix) = a.split('/')
                builder.addAddress(addr, prefix.toInt())
            }
            for (r in tun.routes) {
                val (addr, prefix) = r.split('/')
                builder.addRoute(addr, prefix.toInt())
            }
            AppLog.line("tun: addresses=${tun.addresses} routes=${tun.routes} (split tunnel, both families allowed)")
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed for $tun", e)
            null
        }
    }

    // ---- unetd callbacks (uloop thread) -----------------------------------------

    override fun protectSocket(fd: Int): Boolean {
        // Every socket that must bypass the tunnel (unetd's global PEX socket,
        // wireguard-go's UDP sockets) comes through here. A false here means the
        // socket's traffic goes into the tunnel or, with lockdown on, nowhere.
        val ok = protect(fd)
        AppLog.line("protect(fd=$fd) -> $ok")
        return ok
    }

    override fun onNetworkUpdate(json: String) {
        Log.d(TAG, "interface update: $json")
        TunnelRuntime.requestRefresh()
        val desired = TunSettings.fromUpdate(json) ?: return // link down: nothing to do
        control.post { applyTunSettings(desired) }
    }

    /** Re-establishes the tunnel if unetd wants different addresses or routes. */
    private fun applyTunSettings(desired: TunSettings) {
        val cfg = config ?: return
        if (!active || desired == currentTun) return
        Log.i(TAG, "addresses/routes changed, re-establishing: $desired")
        ConfigStore.saveLastTun(this, desired)

        val name = cfg.effectiveName()
        Unetd.networkRemove(name)
        teardownWg()

        val pfd = establish(desired)
        if (pfd == null) {
            stopTunnel("Could not re-establish the VPN interface")
            return
        }
        wgHandle = WgGo.wgTurnOn(name, pfd.detachFd(), "")
        if (wgHandle < 0) {
            stopTunnel("wireguard-go failed to restart; see the log")
            return
        }
        val rc = Unetd.networkAdd(cfg.unetdJson())
        if (rc != 0) {
            stopTunnel("unetd rejected the network configuration after re-establishing ($rc)")
            return
        }
        currentTun = desired
        scope.launch { delay(3_000); NetDiag.run(this@UnetVpnService) { protect(it) } }
    }

    // ---- status ----------------------------------------------------------------

    /**
     * Polls unetd's status every second while the tunnel is still settling or
     * the UI is visible, and every 30 s once a peer is connected and nothing is
     * watching (the notification only changes on state changes anyway). An
     * interface update or the UI coming up wakes it early. Staying responsive
     * until a peer connects keeps establishment quick and the status fresh.
     */
    private suspend fun pollStatus(name: String, dht: Boolean) {
        while (scope.isActive) {
            val json = Unetd.status()
            if (json != null) {
                StatusParser.parse(json, name, dht, TunnelRuntime.status.value)?.let { status ->
                    TunnelRuntime.set(status)
                    if (status.peers.any { it.connected }) lastPeerConnectedAt = System.currentTimeMillis()
                    val text = when (status.state) {
                        TunnelState.Connected -> "${status.onlinePeerCount} of ${status.peers.size} peers reachable"
                        else -> status.message ?: "Connecting…"
                    }
                    if (text != lastNotificationText) {
                        lastNotificationText = text
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
                    }
                }
            }
            val settled = TunnelRuntime.status.value.let { it.state == TunnelState.Connected && it.peers.any { p -> p.connected } }
            val interval = if (TunnelRuntime.uiVisible.value || !settled) 1_000L else 30_000L
            withTimeoutOrNull(interval) {
                merge(TunnelRuntime.refresh, TunnelRuntime.uiVisible.drop(1).filter { it }.map { }).first()
            }
        }
    }

    // ---- DHT (separate process) --------------------------------------------------

    /**
     * The DHT node runs only while it is needed: until the network data is
     * here and a peer is connected, and again after the tunnel has had no
     * connected peer for [DHT_IDLE_GRACE_MS]. While a peer is up it would only
     * keep the radio busy: searches restart as soon as they finish, the
     * routing table is maintained, and the announced port draws traffic.
     */
    private fun scheduleDhtPing(cfg: TunnelConfig) {
        val tick = object : Runnable {
            override fun run() {
                if (!active) return
                val status = TunnelRuntime.status.value
                val peerUp = status.peers.any { it.connected }
                val idleFor = System.currentTimeMillis() - lastPeerConnectedAt
                val wanted = status.state != TunnelState.Connected || (!peerUp && idleFor > DHT_IDLE_GRACE_MS)
                if (wanted) {
                    val pubkey = Unetd.publicKey(cfg.effectivePrivateKey()) ?: cfg.effectiveName()
                    // Idempotent on the other side; this also revives the process if Android reclaimed it.
                    startService(
                        UdhtService.startIntent(
                            this@UnetVpnService,
                            unixSocket = unixSocket,
                            idString = pubkey,
                            nodeFile = File(filesDir, "dht-nodes.bin").absolutePath,
                            authKeys = cfg.authKeys(),
                            bootstrap = cfg.dhtBootstrap,
                            debug = cfg.debug,
                        ),
                    )
                    if (!dhtRunning) AppLog.line("dht: node started (no connected peer)")
                    dhtRunning = true
                } else if (dhtRunning) {
                    AppLog.line("dht: node stopped, a peer is connected; it restarts after ${DHT_IDLE_GRACE_MS / 1000} s without one")
                    startService(UdhtService.stopIntent(this@UnetVpnService, "a peer is connected"))
                    dhtRunning = false
                }
                control.postDelayed(this, 15_000)
            }
        }
        dhtPing = tick
        dhtRunning = false
        lastPeerConnectedAt = 0L
        DhtLog.clear(this) // a stale file from the last run would mislead the UI
        // Give unetd a moment to bind the control socket the DHT connects to.
        control.postDelayed(tick, 2_000)
    }

    private fun stopDht() {
        dhtPing?.let { control.removeCallbacks(it) }
        dhtPing = null
        dhtRunning = false
        startService(UdhtService.stopIntent(this, "tunnel closed"))
    }

    // ---- teardown ----------------------------------------------------------------

    private fun teardownWg() {
        if (wgHandle >= 0) {
            WgGo.wgTurnOff(wgHandle) // also closes the tun fd it owns
            wgHandle = -1
        }
    }

    private fun stopTunnel(error: String?) {
        if (error != null) Log.e(TAG, error)
        active = false
        statusJob?.cancel()
        statusJob = null
        if (config?.dht == true) stopDht()
        // The tun goes first: closing it is what ends the VPN for Android, and it
        // must not wait on unetd, whose event loop can sit in a blocking DNS
        // lookup for a gateway (seconds to minutes when the network is unusable).
        teardownWg()
        AppLog.line("tunnel closed, stopping unetd")
        Unetd.stop()
        WgGo.wgSetProtector(null)
        currentTun = null
        TunnelRuntime.setState(TunnelState.Disconnected, error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        control.post { stopTunnel("VPN permission was revoked") }
        super.onRevoke()
    }

    override fun onDestroy() {
        control.post { stopTunnel(null) }
        controlThread.quitSafely()
        super.onDestroy()
    }

    // ---- notification ------------------------------------------------------------

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Tunnel", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the unetd tunnel is up"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "UnetVpnService"
        /** How long the tunnel may be without a connected peer before the DHT node is started again. */
        private const val DHT_IDLE_GRACE_MS = 60_000L
        private const val CHANNEL_ID = "unetd.tunnel"
        private const val NOTIFICATION_ID = 1
        private const val SESSION_NAME = "unetd"
        /** unetd's default global PEX port; peers and the DHT expect it. */
        private const val PEX_PORT = 51819

        const val ACTION_DISCONNECT = "org.unetd.android.DISCONNECT"
    }
}
