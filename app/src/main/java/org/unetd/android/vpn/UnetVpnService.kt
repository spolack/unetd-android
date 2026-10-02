package org.unetd.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import org.unetd.android.MainActivity
import org.unetd.android.R
import org.unetd.android.config.ConfigStore
import org.unetd.android.config.TunSettings
import org.unetd.android.data.TunnelState

/**
 * The Android host of one connection: the VPN interface, protect(), the
 * foreground notification. The tunnel itself, unetd and wireguard-go, is
 * driven by [TunnelController], which outlives any service instance.
 */
class UnetVpnService : VpnService(), TunnelController.Host {

    private var channelCreated = false

    override fun onCreate() {
        super.onCreate()
        TunnelRuntime.observeProcessLifecycle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            TunnelController.stop(null)
            // The hosting instance is told when the tunnel is down; any other
            // instance has nothing to wait for.
            if (!TunnelController.isHostedBy(this)) stopSelf()
            return START_NOT_STICKY
        }

        // startForegroundService() was used to get here, so the service must
        // go foreground promptly even when it has nothing to do.
        startForegroundCompat(buildNotification("Connecting…"))
        val cfg = ConfigStore.load(this)
        if (cfg == null || !cfg.isComplete) {
            TunnelRuntime.setState(TunnelState.Disconnected, "Not configured yet")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AppLog.line("always-on=$isAlwaysOn lockdown=$isLockdownEnabled")
        }
        TunnelController.start(this, cfg)
        // Restarted by the system after being killed: reconnect from the saved config.
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        TunnelController.stop("VPN permission was revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        TunnelController.detach(this)
        super.onDestroy()
    }

    // ---- TunnelController.Host --------------------------------------------------

    override val context: Context get() = this
    override val protector: Any get() = this

    override fun protectSocket(socket: java.net.DatagramSocket): Boolean = protect(socket)

    override fun protectSocket(fd: Int): Boolean {
        // Every socket that must bypass the tunnel (unetd's global PEX socket,
        // its STUN socket) comes through here; wireguard-go's go through
        // protect() directly. A false here fails the operation that needed the
        // socket: with lockdown on, its traffic would otherwise go nowhere.
        val ok = protect(fd)
        AppLog.line("protect(fd=$fd) -> $ok")
        return ok
    }

    override fun establish(tun: TunSettings): ParcelFileDescriptor? {
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

    override fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onFinished(error: String?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        if (!channelCreated) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tunnel", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the unetd tunnel is up"
                    setShowBadge(false)
                },
            )
            channelCreated = true
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
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

        const val ACTION_DISCONNECT = "org.unetd.android.DISCONNECT"
    }
}
