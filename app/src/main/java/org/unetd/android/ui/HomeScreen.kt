package org.unetd.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.unetd.android.data.Capabilities
import org.unetd.android.data.SampleData
import org.unetd.android.data.NetworkStatus
import org.unetd.android.data.Peer
import org.unetd.android.data.PeerLink
import org.unetd.android.data.TunnelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    status: NetworkStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    configured: Boolean = true,
    onOpenSetup: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    dhtSummary: String? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("unetd", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            status.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenLog) { Text("Log") }
                    TextButton(onClick = onOpenSetup) { Text("Setup") }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!configured) {
                item { SetupPrompt(onOpenSetup) }
            }
            item { StatusCard(status, onConnect, onDisconnect, configured) }
            item { CapabilityCard(status.capabilities, dhtSummary) }
            item {
                Text(
                    "Peers",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            items(status.peers, key = { it.name }) { PeerRow(it) }
            item { RoutingFootnote(status.ulaPrefix) }
        }
    }
}

@Composable
private fun SetupPrompt(onOpenSetup: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("No network configured", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Generate a key for this device, enter the network's public key and a gateway, " +
                    "and add the device to the network with unet-cli.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSetup) { Text("Set up") }
        }
    }
}

@Composable
private fun StatusCard(
    status: NetworkStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    configured: Boolean,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    when (status.state) {
                        TunnelState.Connected -> StatusColors.up
                        TunnelState.Connecting -> StatusColors.pending
                        TunnelState.Disconnected -> StatusColors.down
                    },
                    size = 12,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    when (status.state) {
                        TunnelState.Connected -> "Connected"
                        TunnelState.Connecting -> "Connecting"
                        TunnelState.Disconnected -> "Not connected"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(14.dp))
            Mono(status.localAddress, 14)
            Text(
                "this device",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.localPublicKey?.let {
                Spacer(Modifier.height(6.dp))
                Mono(it, 11, MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "public key",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status.message?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.state == TunnelState.Disconnected) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (status.state == TunnelState.Connected) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Stat("${status.onlinePeerCount}/${status.peers.size}", "peers up")
                    Stat("${status.directPeerCount}", "direct")
                    Stat(formatBytes(status.peers.sumOf { it.rxBytes }), "received")
                }
            }

            Spacer(Modifier.height(18.dp))
            when (status.state) {
                TunnelState.Connected -> OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Disconnect") }

                TunnelState.Connecting -> OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting — tap to cancel")
                }

                TunnelState.Disconnected -> Button(
                    onClick = onConnect,
                    enabled = configured,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect") }
            }
        }
    }
}

@Composable
private fun CapabilityCard(caps: Capabilities, dhtSummary: String?) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Discovery",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("PEX", caps.pex)
                Chip("STUN", caps.stun)
                Chip("DHT", caps.dht)
            }
            if (caps.stun) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "STUN: " + when {
                        caps.stunExternalPort != null -> "WireGuard port seen from outside as ${caps.stunExternalPort}"
                        caps.stunAuthExternalPort != null -> "peer-exchange port seen from outside as ${caps.stunAuthExternalPort}"
                        else -> "servers configured, no answer yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (caps.dht) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "DHT node: " + (dhtSummary ?: "not started yet"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!caps.rawSockets) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Raw sockets unavailable on Android, so NAT hole punching is sent " +
                        "from WireGuard's own socket instead. This is expected, not a fault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (peer.connected) StatusColors.up else StatusColors.down, size = 9)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        peer.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    if (peer.link != PeerLink.Direct) {
                        Spacer(Modifier.width(8.dp))
                        LinkBadge(peer.link)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Mono(peer.address, 12, MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    peerDetail(peer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun peerDetail(peer: Peer): String = when {
    !peer.connected -> "offline"
    peer.link == PeerLink.ViaGateway ->
        "via gateway · ↓ ${formatBytes(peer.rxBytes)} ↑ ${formatBytes(peer.txBytes)}"
    else -> buildString {
        append(formatHandshake(peer.lastHandshakeSec))
        append(" · ↓ ${formatBytes(peer.rxBytes)} ↑ ${formatBytes(peer.txBytes)}")
        peer.endpoint?.let { append(" · $it") }
    }
}

/**
 * [sec] is unetd's age of the last handshake. A freshly re-created peer (after a
 * roam, say) has no handshake recorded in wireguard-go yet, and unetd then
 * reports the age as the current epoch; show that as pending rather than "56
 * years ago".
 */
private fun formatHandshake(sec: Long?): String = when {
    sec == null -> "no handshake yet"
    sec > 10L * 365 * 24 * 3600 -> "handshake pending"
    sec < 2 -> "handshake just now"
    sec < 60 -> "handshake ${sec}s ago"
    sec < 3600 -> "handshake ${sec / 60}m ago"
    sec < 86400 -> "handshake ${sec / 3600}h ago"
    else -> "handshake ${sec / 86400}d ago"
}

@Composable
private fun RoutingFootnote(prefix: String) {
    Text(
        "Routing $prefix — every peer address is derived from its public key, so one " +
            "route covers the whole network and peers can come and go without " +
            "re-establishing the tunnel.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun LinkBadge(link: PeerLink) {
    val label = when (link) {
        PeerLink.Direct -> "direct"
        PeerLink.ViaGateway -> "gateway"
        PeerLink.Indirect -> "indirect"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Chip(label: String, on: Boolean) {
    Surface(
        color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (on) StatusColors.up else StatusColors.down, size = 7)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusDot(color: Color, size: Int) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

@Composable
private fun Mono(text: String, sizeSp: Int, color: Color = Color.Unspecified) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = sizeSp.sp,
        color = color,
    )
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1e3)
    else -> "$bytes B"
}

@Preview(name = "Connected", showBackground = true, heightDp = 900)
@Composable
private fun PreviewConnected() {
    UnetdTheme(dynamicColor = false, darkTheme = false) {
        HomeScreen(SampleData.connected, {}, {})
    }
}

@Preview(name = "Connected (dark)", showBackground = true, heightDp = 900)
@Composable
private fun PreviewConnectedDark() {
    UnetdTheme(dynamicColor = false, darkTheme = true) {
        HomeScreen(SampleData.connected, {}, {})
    }
}

@Preview(name = "Disconnected", showBackground = true, heightDp = 900)
@Composable
private fun PreviewDisconnected() {
    UnetdTheme(dynamicColor = false, darkTheme = false) {
        HomeScreen(SampleData.disconnected, {}, {})
    }
}
