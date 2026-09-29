package org.unetd.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import org.unetd.android.R

/**
 * The VPN data plane.
 *
 * This establishes the tun and will hand its descriptor to wireguard-go; unetd
 * then programs that device over its UAPI socket. The native side is not wired
 * up yet -- see the TODOs -- but the routing policy below is the part worth
 * pinning down early, because Android makes it expensive to get wrong.
 *
 * Routing policy: Android fixes routes at [Builder.establish] and changing them
 * means tearing the tunnel down and losing every peer's handshake. unetd derives
 * each host's address from its public key (0xfd || siphash(network id), host part
 * = siphash(pubkey)), so the network's /64 is known before any peer is. Routing
 * that one prefix once covers every present and future peer, and peer churn never
 * touches the tun. Only a change to the non-derivable routes -- the explicit
 * subnet[]/ipaddr[] entries in the signed network data -- should re-establish.
 */
class UnetVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stop()
                return START_NOT_STICKY
            }
            else -> start()
        }
        return START_STICKY
    }

    private fun start() {
        if (tun != null) return
        startForeground(NOTIFICATION_ID, buildNotification())

        // TODO(native): take these from unetd's network_do_update() callback
        // rather than hardcoding. That callback delivers exactly the payload the
        // upstream update-cmd script consumes: ipaddr[], ip6addr[], routes[],
        // routes6[]. fork+execvp is unusable on Android 10+ (W^X), so it becomes
        // an in-process callback into this service.
        val localAddress = PLACEHOLDER_LOCAL_ADDRESS
        val ulaPrefix = PLACEHOLDER_ULA_PREFIX

        val builder = Builder()
            .setSession(SESSION_NAME)
            // The address is a /128: we own exactly this one address. The whole
            // prefix is added as a route instead, so the tun does not claim a /64
            // it does not own.
            .addAddress(localAddress, 128)
            .addRoute(ulaPrefix, 64)
            // Matches wireguard-android's default. unetd itself never sets an MTU,
            // and 1280 is the IPv6 minimum, so it survives further encapsulation.
            .setMtu(1280)
            .setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Deliberately NOT addDisallowedApplication(packageName): that would
        // exclude this whole UID from the tunnel, including unetd's per-network
        // PEX socket, which is bound to the in-tunnel address and must go through
        // the tunnel. Sockets that need to bypass it are protect()ed individually
        // via protectSocket() below.

        tun = try {
            builder.establish()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "could not establish the tunnel", e)
            stop()
            return
        }

        if (tun == null) {
            // Permission was revoked, or another VPN took over.
            Log.w(TAG, "establish() returned null; VPN permission likely revoked")
            stop()
            return
        }

        Log.i(TAG, "tun established, fd=${tun?.fd}")

        // TODO(native): wgTurnOn(ifname, tun.detachFd(), "") against our own
        // build of libwg-go, then start unetd on its own thread pointed at
        // <cacheDir>/wireguard as the UAPI socket directory.
    }

    private fun stop() {
        // TODO(native): wgTurnOff(handle) and stop the unetd loop before this.
        tun?.close()
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        stop()
        super.onRevoke()
    }

    /**
     * Exempts a socket from the tunnel.
     *
     * Called from native code for the sockets that must reach the outside world
     * directly: unetd's global PEX socket, its STUN socket, and its local-address
     * probe. Explicitly *not* the per-network PEX socket, which belongs inside
     * the tunnel.
     *
     * Note for the native side: wireguard-go's own UDP sockets need this too, and
     * re-protecting them after the fact is not enough. Any UAPI `listen_port`
     * write makes wireguard-go rebind, replacing the descriptors, and unetd writes
     * that field whenever the local host changes and twice per STUN cycle. The
     * durable fix is to protect at bind time inside Go via conn.controlFns.
     */
    @Suppress("unused") // called from JNI
    fun protectSocket(fd: Int): Boolean = protect(fd)

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Tunnel", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the unetd tunnel is up"
                setShowBadge(false)
            },
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Tunnel active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "UnetVpnService"
        private const val CHANNEL_ID = "unetd.tunnel"
        private const val NOTIFICATION_ID = 1
        private const val SESSION_NAME = "unetd"

        const val ACTION_DISCONNECT = "org.unetd.android.DISCONNECT"

        // Stand-ins until the native control plane supplies the real values.
        private const val PLACEHOLDER_LOCAL_ADDRESS = "fd57:3484:101:a0ca:c0d:9f84:c794:e4dc"
        private const val PLACEHOLDER_ULA_PREFIX = "fd57:3484:101:a0ca::"
    }
}
