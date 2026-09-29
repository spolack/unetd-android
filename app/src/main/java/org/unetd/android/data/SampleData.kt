package org.unetd.android.data

/**
 * A representative network, used by [FakeUnetRepository] and by the Compose
 * previews so the two cannot drift apart.
 *
 * Deliberately covers the cases that are easy to get wrong in layout: a peer
 * reachable only through a gateway, one that has never completed a handshake,
 * and one that is offline entirely.
 */
object SampleData {

    private const val PREFIX = "fd57:3484:101:a0ca"

    val connected = NetworkStatus(
        name = "net0",
        localAddress = "$PREFIX:0c0d:9f84:c794:e4dc",
        ulaPrefix = "$PREFIX::/64",
        state = TunnelState.Connected,
        connectedSinceMillis = null,
        capabilities = Capabilities(
            pex = true,
            stun = true,
            dht = true,
            // Always false on an unrooted device; surfaced rather than hidden.
            rawSockets = false,
        ),
        peers = listOf(
            Peer(
                name = "gateway",
                address = "$PREFIX:19aa:4d8a:d332:a0ae",
                link = PeerLink.Direct,
                connected = true,
                lastHandshakeSec = 14,
                rxBytes = 48_219_904,
                txBytes = 7_733_248,
                endpoint = "203.0.113.24:51820",
            ),
            Peer(
                name = "workshop-ap",
                address = "$PREFIX:63d1:f1eb:566b:ddfc",
                link = PeerLink.Direct,
                connected = true,
                lastHandshakeSec = 47,
                rxBytes = 2_402_304,
                txBytes = 1_156_096,
                endpoint = "198.51.100.77:51820",
            ),
            Peer(
                name = "nas",
                address = "$PREFIX:8c02:44b1:0e9f:3311",
                link = PeerLink.ViaGateway,
                connected = true,
                lastHandshakeSec = null,
                rxBytes = 884_736,
                txBytes = 401_408,
                endpoint = null,
            ),
            Peer(
                name = "laptop",
                address = "$PREFIX:2f71:9ab0:c6d4:5520",
                link = PeerLink.Direct,
                connected = false,
                lastHandshakeSec = null,
                rxBytes = 0,
                txBytes = 0,
                endpoint = null,
            ),
        ),
    )

    val disconnected = connected.copy(
        state = TunnelState.Disconnected,
        connectedSinceMillis = null,
        peers = connected.peers.map {
            it.copy(
                connected = false,
                lastHandshakeSec = null,
                endpoint = null,
                rxBytes = 0,
                txBytes = 0,
            )
        },
    )
}
