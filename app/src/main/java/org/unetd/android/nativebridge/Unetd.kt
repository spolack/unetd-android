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
        /** A socket that must bypass the tunnel; return VpnService.protect(fd). */
        fun protectSocket(fd: Int): Boolean

        /**
         * The interface update unetd would otherwise hand to update-cmd, as JSON:
         * `{"ifname","link-up","ipaddr":[],"ip6addr":[],"routes":[],"routes6":[]}`.
         */
        fun onNetworkUpdate(json: String)
    }

    /** Returns 0 on success, a negative errno otherwise. */
    fun start(
        dataDir: String,
        socketDir: String,
        unixSocket: String?,
        pexPort: Int,
        debug: Boolean,
        callbacks: Callbacks,
    ): Int = nativeStart(dataDir, socketDir, unixSocket, pexPort, debug, callbacks)

    fun stop() = nativeStop()
    val isRunning: Boolean get() = nativeRunning()

    /** The same JSON as unetd's -N option. 0 on success. */
    fun networkAdd(json: String): Int = nativeNetworkAdd(json)
    fun networkRemove(name: String): Int = nativeNetworkRemove(name)

    /** Status of every network, or null when unetd is not running. */
    fun status(): String? = nativeStatus()

    fun startLogCapture() = nativeStartLogCapture()
    fun logTail(maxLines: Int = 400): String = nativeLogTail(maxLines) ?: ""

    /** A fresh Curve25519 key pair as (private, public), base64. */
    fun generateKey(): Pair<String, String>? =
        nativeGenerateKey()?.let { if (it.size == 2) it[0] to it[1] else null }

    fun publicKey(privateKey: String): String? = nativePublicKey(privateKey)

    private external fun nativeStart(
        dataDir: String, socketDir: String, unixSocket: String?, pexPort: Int,
        debug: Boolean, callbacks: Callbacks,
    ): Int
    private external fun nativeStop()
    private external fun nativeRunning(): Boolean
    private external fun nativeNetworkAdd(json: String): Int
    private external fun nativeNetworkRemove(name: String): Int
    private external fun nativeStatus(): String?
    private external fun nativeStartLogCapture()
    private external fun nativeLogTail(maxLines: Int): String?
    private external fun nativeGenerateKey(): Array<String>?
    private external fun nativePublicKey(privateKey: String): String?
}
