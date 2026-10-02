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
 * unprivileged app can never hold CAP_NET_RAW, so unetd cannot forge packets
 * from the WireGuard port; see design-review.md, section 3, for what remains.
 */
data class Capabilities(
    val pex: Boolean,
    val stun: Boolean,
    val dht: Boolean,
    /** False on every unrooted device. */
    val rawSockets: Boolean,
    /**
     * What STUN learned: the NAT's outside port for the WireGuard port, null
     * until a server answered. Measured by taking the port over for the query
     * while no peer is connected yet.
     */
    val stunExternalPort: Int? = null,
    /** Same for the peer-exchange port, which STUN can query at any time. */
    val stunAuthExternalPort: Int? = null,
)

/** The DHT node's progress, from unet-dht running on unetd's loop. */
data class DhtState(
    val running: Boolean,
    /** Attached to unetd's control socket, i.e. able to send and receive. */
    val connected: Boolean,
    /** Enough good nodes to search; the searches then run. */
    val ready: Boolean,
    val goodNodes: Int,
    val dubiousNodes: Int,
    val incomingNodes: Int,
    /** Peers announced for our networks that the node found so far. */
    val nodesFound: Int,
) {
    /** One line for the home screen. */
    val summary: String
        get() = when {
            !running -> "not running"
            !connected -> "waiting for unetd's control socket"
            nodesFound > 0 -> "found $nodesFound announced peer${if (nodesFound == 1) "" else "s"}"
            ready -> "ready, searching for the network"
            goodNodes + dubiousNodes > 0 -> "bootstrapping, $goodNodes good nodes so far"
            else -> "bootstrapping, no node answered yet"
        }
}

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
    /** Something the user should know about the current state, or null. */
    val message: String? = null,
    /** This device's public key, so it can be added to the network with unet-cli. */
    val localPublicKey: String? = null,
    /** How often a gateway refused our request for network data: our key is not in it. */
    val updateRefused: Int = 0,
    /** The DHT node, when it has been started in this session. */
    val dht: DhtState? = null,
) {
    val directPeerCount: Int get() = peers.count { it.link != PeerLink.Indirect }
    val onlinePeerCount: Int get() = peers.count { it.connected }
}
