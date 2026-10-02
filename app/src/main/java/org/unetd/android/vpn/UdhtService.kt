package org.unetd.android.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import org.unetd.android.nativebridge.Udht
import org.unetd.android.nativebridge.Unetd

/**
 * Runs unet-dht in the app's `:dht` process.
 *
 * It has to be a separate process because libubox's uloop is a process-wide
 * singleton that unetd already occupies in the main process -- which is also
 * why upstream ships unet-dht as its own daemon. It has no network socket of
 * its own: everything is relayed through unetd's global PEX socket over a unix
 * socket, so nothing here needs protect().
 *
 * The main service "pings" this one every 30 s while the tunnel is up; a ping
 * while the node is running is a no-op, otherwise it (re)starts it.
 */
class UdhtService : Service() {

    private var worker: Thread? = null
    private var mirror: Thread? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        if (intent.action == ACTION_STOP) {
            stopping = true
            Udht.requestStop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY

        val unixSocket = intent.getStringExtra(EXTRA_UNIX_SOCKET) ?: return START_NOT_STICKY
        val idString = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
        val nodeFile = intent.getStringExtra(EXTRA_NODE_FILE)
        val keys = intent.getStringArrayExtra(EXTRA_AUTH_KEYS) ?: emptyArray()
        val bootstrap = intent.getStringArrayExtra(EXTRA_BOOTSTRAP) ?: emptyArray()
        val debug = intent.getBooleanExtra(EXTRA_DEBUG, false)

        stopping = false
        worker = Thread({
            Unetd.startLogCapture() // same library, this process: lines go to logcat as "unetd"
            while (!stopping) {
                val rc = Udht.run(unixSocket, idString, nodeFile, keys, bootstrap, debug)
                Log.i(TAG, "unet-dht returned $rc")
                if (stopping) break
                // unetd not up yet, or it went away: try again shortly.
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
            }
            stopSelf()
        }, "unet-dht").also { it.start() }
        // Mirror this process's log ring to a file for the UI in the main process.
        mirror = Thread({
            while (worker?.isAlive == true) {
                DhtLog.write(this, Unetd.logTail(400))
                try { Thread.sleep(2_000) } catch (_: InterruptedException) { break }
            }
            DhtLog.write(this, Unetd.logTail(400))
        }, "dht-log-mirror").also { it.isDaemon = true; it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        Udht.requestStop()
        worker?.join(3_000)
        mirror?.interrupt()
        DhtLog.write(this, Unetd.logTail(400))
        super.onDestroy()
        // The DHT library keeps file-scope state; a fresh process next time is the
        // simplest way to be sure none of it carries over.
        Process.killProcess(Process.myPid())
    }

    companion object {
        private const val TAG = "UdhtService"
        private const val ACTION_STOP = "org.unetd.android.DHT_STOP"
        private const val EXTRA_UNIX_SOCKET = "unix_socket"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NODE_FILE = "node_file"
        private const val EXTRA_AUTH_KEYS = "auth_keys"
        private const val EXTRA_BOOTSTRAP = "bootstrap"
        private const val EXTRA_DEBUG = "debug"

        fun startIntent(
            context: Context,
            unixSocket: String,
            idString: String,
            nodeFile: String?,
            authKeys: List<String>,
            bootstrap: List<String>,
            debug: Boolean,
        ): Intent = Intent(context, UdhtService::class.java)
            .putExtra(EXTRA_UNIX_SOCKET, unixSocket)
            .putExtra(EXTRA_ID, idString)
            .putExtra(EXTRA_NODE_FILE, nodeFile)
            .putExtra(EXTRA_AUTH_KEYS, authKeys.toTypedArray())
            .putExtra(EXTRA_BOOTSTRAP, bootstrap.toTypedArray())
            .putExtra(EXTRA_DEBUG, debug)

        fun stopIntent(context: Context): Intent =
            Intent(context, UdhtService::class.java).setAction(ACTION_STOP)
    }
}
