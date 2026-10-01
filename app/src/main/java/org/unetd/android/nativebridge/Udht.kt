package org.unetd.android.nativebridge

/**
 * unet-dht, the BitTorrent-DHT rendezvous helper, running as a library in the
 * app's `:dht` process (it needs a uloop of its own, and unetd owns the one in
 * the main process). It has no network socket: it relays through unetd's global
 * PEX socket over [unixSocket].
 */
object Udht {

    init {
        System.loadLibrary("unet-android")
    }

    /**
     * Blocks until the node disconnects from unetd or [requestStop] is called.
     * [authKeys] are the networks' public keys; [idString] seeds the node id;
     * [bootstrap] (`host[:port]`) replaces the public bootstrap routers when
     * non-empty, for a private DHT or a test.
     */
    fun run(
        unixSocket: String,
        idString: String,
        nodeFile: String?,
        authKeys: Array<String>,
        bootstrap: Array<String>,
        debug: Boolean,
    ): Int = nativeRun(unixSocket, idString, nodeFile, authKeys, bootstrap, debug)

    fun requestStop() = nativeRequestStop()

    private external fun nativeRun(
        unixSocket: String, idString: String, nodeFile: String?, authKeys: Array<String>,
        bootstrap: Array<String>, debug: Boolean,
    ): Int
    private external fun nativeRequestStop()
}
