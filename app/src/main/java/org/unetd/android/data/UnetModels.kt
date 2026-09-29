package org.unetd.android.data

/**
 * UI-facing model of what unetd knows.
 *
 * These mirror the fields unetd exposes per peer in its status dump (see
 * `__network_dump()` in upstream ubus.c, which the Android build re-exposes
 * without ubus): connected, endpoint, rx_bytes, tx_bytes, idle,
 * last_handshake_sec, address.
 */

enum class TunnelState { Disconnected, Connecting, Connected }

/**
 * How a peer is reached.
 *
 * unetd marks a host `indirect` when it must be reached through a gateway; such
 * hosts get no WireGuard peer entry of their own and are folded into the
 * gateway's AllowedIPs instead.
 */
enum class PeerLink { Direct, ViaGateway, Indirect }

data class Peer(
    val name: String,
    val address: String,
    val link: PeerLink,
    val connected: Boolean,
    /** Seconds since the last completed handshake, or null if there has never been one. */
    val lastHandshakeSec: Long?,
    val rxBytes: Long,
    val txBytes: Long,
    /** Outer endpoint as WireGuard currently sees it, or null while unknown. */
    val endpoint: String?,
)

/**
 * Which discovery mechanisms are actually live.
 *
 * Worth surfacing in the UI rather than hiding: on Android several of these
 * degrade for reasons the user cannot influence but should be able to see. An
 * unprivileged app can never hold CAP_NET_RAW, so unetd's raw-socket NAT punch
 * is replaced by sending from wireguard-go's own UDP socket.
 */
data class Capabilities(
    val pex: Boolean,
    val stun: Boolean,
    val dht: Boolean,
    /** False on every unrooted device; the punch goes via the WireGuard socket instead. */
    val rawSockets: Boolean,
)

data class NetworkStatus(
    val name: String,
    /** This host's address, derived from its public key rather than assigned. */
    val localAddress: String,
    /** The whole network's prefix. Routed once, so peer churn never re-establishes the tunnel. */
    val ulaPrefix: String,
    val state: TunnelState,
    val connectedSinceMillis: Long?,
    val peers: List<Peer>,
    val capabilities: Capabilities,
) {
    val directPeerCount: Int get() = peers.count { it.link != PeerLink.Indirect }
    val onlinePeerCount: Int get() = peers.count { it.connected }
}
