package org.unetd.android.nativebridge

/**
 * wireguard-go, built from source as libwg-go.so (native/libwg-go).
 *
 * Differences from the wireguard-android backend this mirrors: the UAPI socket
 * is named after the unet network and created in a directory given per call,
 * so unetd finds it, and every UDP socket is protect()ed when wireguard-go
 * opens it, through [wgSetProtector] -- including after the rebinds unetd
 * triggers by writing listen_port. A refused protect() fails that bind.
 */
object WgGo {

    init {
        System.loadLibrary("wg-go")
    }

    /** Any object with `boolean protect(int fd)`; in practice the VpnService. Null clears it. */
    external fun wgSetProtector(protector: Any?)

    /**
     * Serves the UAPI as `<socketDir>/<uapiName>.sock`, which must match what
     * unetd is given. Returns a handle >= 0, or -1. Takes ownership of [tunFd].
     */
    external fun wgTurnOn(socketDir: String, uapiName: String, tunFd: Int): Int
    external fun wgTurnOff(handle: Int)
    external fun wgVersion(): String?
}
