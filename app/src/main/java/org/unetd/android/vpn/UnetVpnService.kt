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
        System.err.println("unetd-android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) connecting")
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
        scope.launch { delay(3_000); NetDiag.run(this@UnetVpnService) }
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
            System.err.println("tun: addresses=${tun.addresses} routes=${tun.routes} (split tunnel, both families allowed)")
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed for $tun", e)
            null
        }
    }

    // ---- unetd callbacks (uloop thread) -----------------------------------------

    override fun protectSocket(fd: Int): Boolean = protect(fd)

    override fun onNetworkUpdate(json: String) {
        Log.d(TAG, "interface update: $json")
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
        scope.launch { delay(3_000); NetDiag.run(this@UnetVpnService) }
    }

    // ---- status ----------------------------------------------------------------

    private suspend fun pollStatus(name: String, dht: Boolean) {
        while (scope.isActive) {
            val json = Unetd.status()
            if (json != null) {
                StatusParser.parse(json, name, dht, TunnelRuntime.status.value)?.let { status ->
                    TunnelRuntime.set(status)
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
            delay(1000)
        }
    }

    // ---- DHT (separate process) --------------------------------------------------

    private fun scheduleDhtPing(cfg: TunnelConfig) {
        val ping = object : Runnable {
            override fun run() {
                if (!active) return
                val pubkey = Unetd.publicKey(cfg.effectivePrivateKey()) ?: cfg.effectiveName()
                startService(
                    UdhtService.startIntent(
                        this@UnetVpnService,
                        unixSocket = unixSocket,
                        idString = pubkey,
                        nodeFile = File(filesDir, "dht-nodes.bin").absolutePath,
                        authKeys = cfg.authKeys(),
                        debug = cfg.debug,
                    ),
                )
                // Idempotent on the other side; this just revives the process if Android reclaimed it.
                control.postDelayed(this, 30_000)
            }
        }
        dhtPing = ping
        // Give unetd a moment to bind the control socket the DHT connects to.
        control.postDelayed(ping, 2_000)
    }

    private fun stopDht() {
        dhtPing?.let { control.removeCallbacks(it) }
        dhtPing = null
        startService(UdhtService.stopIntent(this))
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
        System.err.println("tunnel closed, stopping unetd")
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
        private const val CHANNEL_ID = "unetd.tunnel"
        private const val NOTIFICATION_ID = 1
        private const val SESSION_NAME = "unetd"
        /** unetd's default global PEX port; peers and the DHT expect it. */
        private const val PEX_PORT = 51819

        const val ACTION_DISCONNECT = "org.unetd.android.DISCONNECT"
    }
}
