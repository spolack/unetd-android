package org.unetd.android.nativebridge

/**
 * wireguard-go, built from source as libwg-go.so (native/libwg-go).
 *
 * Differences from the wireguard-android backend this mirrors: the UAPI socket
 * is named after the unet network so unetd finds it, the socket directory is
 * set at runtime, and every UDP socket is protect()ed at bind time through
 * [wgSetProtector] -- including after the rebinds unetd triggers by writing
 * listen_port.
 */
object WgGo {

    init {
        System.loadLibrary("wg-go")
    }

    /** Any object with `boolean protect(int fd)`; in practice the VpnService. Null clears it. */
    external fun wgSetProtector(protector: Any?)

    /** Directory for `<name>.sock`; must match what unetd is given. */
    external fun wgSetSocketDirectory(dir: String)

    /** Returns a handle >= 0, or -1. Takes ownership of [tunFd]. */
    external fun wgTurnOn(uapiName: String, tunFd: Int, settings: String): Int
    external fun wgTurnOff(handle: Int)
    external fun wgGetSocketV4(handle: Int): Int
    external fun wgGetSocketV6(handle: Int): Int
    external fun wgGetConfig(handle: Int): String?
    external fun wgVersion(): String?
}
