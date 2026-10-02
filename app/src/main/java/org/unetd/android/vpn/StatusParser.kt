package org.unetd.android.vpn

import org.json.JSONObject
import org.unetd.android.config.Ip
import org.unetd.android.data.Capabilities
import org.unetd.android.data.DhtState
import org.unetd.android.data.NetworkStatus
import org.unetd.android.data.Peer
import org.unetd.android.data.PeerLink
import org.unetd.android.data.TunnelState

/**
 * Turns the status JSON of the native core (network_dump_status() plus a few
 * fields of our own) into the UI model.
 */
object StatusParser {

    fun parse(json: String, networkName: String, dhtEnabled: Boolean, previous: NetworkStatus): NetworkStatus? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val pexSocket = root.optBoolean("pex_socket", false)
        val dht = root.optJSONObject("dht")?.let { d ->
            DhtState(
                running = d.optBoolean("running", false),
                connected = d.optBoolean("connected", false),
                ready = d.optBoolean("ready", false),
                goodNodes = d.optInt("good", 0),
                dubiousNodes = d.optInt("dubious", 0),
                incomingNodes = d.optInt("incoming", 0),
                nodesFound = d.optInt("nodes_found", 0),
            )
        }?.takeIf { it.running || previous.dht != null }
        val net = root.optJSONObject("networks")?.optJSONObject(networkName)
            ?: return previous.copy(
                name = networkName,
                state = TunnelState.Connecting,
                message = "unetd has no network \"$networkName\"",
                capabilities = previous.capabilities.copy(pex = pexSocket),
                dht = dht,
            )

        val localAddress = net.optString("local_address", "")
        val hasLocalHost = localAddress.isNotEmpty()
        val updateRefused = net.optInt("update_refused", 0)
        val indirect = net.optJSONArray("indirect_peers")?.let { arr ->
            buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
        } ?: emptySet()

        val peers = mutableListOf<Peer>()
        net.optJSONObject("peers")?.let { p ->
            for (name in p.keys()) {
                val e = p.getJSONObject(name)
                val connected = e.optBoolean("connected", false)
                peers += Peer(
                    name = name,
                    address = e.optString("address", ""),
                    link = if (name in indirect) PeerLink.ViaGateway else PeerLink.Direct,
                    connected = connected,
                    lastHandshakeSec = if (connected && e.has("last_handshake_sec")) e.getLong("last_handshake_sec") else null,
                    rxBytes = e.optLong("rx_bytes", 0),
                    txBytes = e.optLong("tx_bytes", 0),
                    endpoint = if (connected) e.optString("endpoint", "").ifEmpty { null } else null,
                )
            }
        }
        peers.sortWith(compareByDescending<Peer> { it.connected }.thenBy { it.name })

        val connectedSince = when {
            !hasLocalHost -> null
            previous.connectedSinceMillis != null -> previous.connectedSinceMillis
            else -> System.currentTimeMillis()
        }

        return NetworkStatus(
            name = networkName,
            localAddress = if (hasLocalHost) localAddress else "not yet known",
            ulaPrefix = if (hasLocalHost) "${Ip.prefixOf(localAddress, 64)}/64" else "",
            state = if (hasLocalHost) TunnelState.Connected else TunnelState.Connecting,
            connectedSinceMillis = connectedSince,
            peers = peers,
            capabilities = Capabilities(
                pex = pexSocket,
                stun = net.optBoolean("stun", false),
                dht = dhtEnabled && pexSocket,
                rawSockets = false,
                stunExternalPort = net.optInt("stun_port_ext", 0).takeIf { it > 0 },
                stunAuthExternalPort = net.optInt("stun_auth_port_ext", 0).takeIf { it > 0 },
            ),
            message = when {
                !pexSocket -> "Global PEX socket could not be opened (port ${root.optInt("pex_port", 51819)}); network data cannot be fetched."
                !hasLocalHost && net.optBoolean("no_local_host", false) ->
                    "This device's key is not part of the network data. Add its public key with unet-cli."
                !hasLocalHost && updateRefused > 0 ->
                    "A gateway answered but refused this device ($updateRefused×): its public key is not " +
                        "in the signed network data. Add it with unet-cli add-host and re-sign."
                !hasLocalHost -> "Waiting for the signed network data from a gateway…"
                else -> null
            },
            localPublicKey = net.optString("local_pubkey", "").ifEmpty { null },
            updateRefused = updateRefused,
            dht = dht,
        )
    }
}
