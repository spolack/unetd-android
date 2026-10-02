package org.unetd.android.nativebridge

/**
 * The embedded unetd control plane (native/core, via native/jni/unetd_jni.c).
 *
 * unetd's uloop runs on a thread owned by the native side; every call here is
 * marshalled onto it and returns when it has run. [Callbacks] are invoked *on*
 * that thread, so they must never call back into this object synchronously --
 * that would wait for the thread they are running on.
 */
object Unetd {

    init {
        System.loadLibrary("unet-android")
    }

    interface Callbacks {
        /**
         * A socket that must bypass the tunnel; return VpnService.protect(fd).
         * False makes unetd close the socket and fail what needed it: a refused
         * global PEX socket fails [start] with -EPERM.
         */
        fun protectSocket(fd: Int): Boolean

        /**
         * The interface update unetd would otherwise hand to update-cmd, as JSON:
         * `{"ifname","link-up","ipaddr":[],"ip6addr":[],"routes":[],"routes6":[]}`.
         */
        fun onNetworkUpdate(json: String)

        /**
         * A state change ([EV_PEER_UP] ...); [status] is already refreshed when
         * this runs. [peer] is set for the peer events only.
         */
        fun onEvent(kind: Int, network: String?, peer: String?)
    }

    const val EV_PEER_UP = 0
    const val EV_PEER_DOWN = 1
    const val EV_NETWORK_RELOAD = 2
    const val EV_STUN_PORT = 3

    /** Errors [start] returns, as negative errno values. */
    const val EALREADY = -114
    const val EADDRINUSE = -98
    const val EPERM = -1
    const val ETIMEDOUT = -110

    /** Returns 0 on success, a negative errno otherwise (see [EALREADY], [EADDRINUSE], [EPERM]). */
    fun start(
        dataDir: String,
        socketDir: String,
        unixSocket: String?,
        pexPort: Int,
        debug: Boolean,
        callbacks: Callbacks,
    ): Int = nativeStart(dataDir, socketDir, unixSocket, pexPort, debug, callbacks)

    /** 0, or [ETIMEDOUT] when the loop is stuck; it then finishes on its own. */
    fun stop(): Int = nativeStop()
    val isRunning: Boolean get() = nativeRunning()

    /** The same JSON as unetd's -N option. 0 on success. */
    fun networkAdd(json: String): Int = nativeNetworkAdd(json)
    fun networkRemove(name: String): Int = nativeNetworkRemove(name)

    /** The latest status snapshot of every network, or null when unetd is not running. Never blocks. */
    fun status(): String? = nativeStatus()

    fun startLogCapture() = nativeStartLogCapture()
    fun logTail(maxLines: Int = 400): String = nativeLogTail(maxLines) ?: ""

    /**
     * The log lines since sequence number [since] (0: everything the ring still
     * holds), at most [maxLines], and the sequence number to ask for next time.
     */
    fun logSince(since: Long, maxLines: Int = 500): Pair<Long, List<String>> {
        val text = nativeLogSince(since, maxLines) ?: return since to emptyList()
        val nl = text.indexOf('\n')
        if (nl < 0) return since to emptyList()
        val next = text.substring(0, nl).toLongOrNull() ?: since
        val body = text.substring(nl + 1)
        return next to if (body.isEmpty()) emptyList() else body.removeSuffix("\n").split('\n')
    }

    /** One line (or several, newline-separated) into the log ring, i.e. into the Log screen. */
    fun log(line: String) = nativeLog(line)

    /** A fresh Curve25519 key pair as (private, public), base64. */
    fun generateKey(): Pair<String, String>? =
        nativeGenerateKey()?.let { if (it.size == 2) it[0] to it[1] else null }

    fun publicKey(privateKey: String): String? = nativePublicKey(privateKey)

    private external fun nativeStart(
        dataDir: String, socketDir: String, unixSocket: String?, pexPort: Int,
        debug: Boolean, callbacks: Callbacks,
    ): Int
    private external fun nativeStop(): Int
    private external fun nativeRunning(): Boolean
    private external fun nativeNetworkAdd(json: String): Int
    private external fun nativeNetworkRemove(name: String): Int
    private external fun nativeStatus(): String?
    private external fun nativeStartLogCapture()
    private external fun nativeLogTail(maxLines: Int): String?
    private external fun nativeLogSince(since: Long, maxLines: Int): String?
    private external fun nativeLog(line: String)
    private external fun nativeGenerateKey(): Array<String>?
    private external fun nativePublicKey(privateKey: String): String?
}
