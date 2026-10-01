package org.unetd.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.unetd.android.config.TunnelConfig

/**
 * Where the network is configured. Everything needed to join an existing unetd
 * network as a "dynamic" host: this device's private key (generated here), the
 * network's public key, and where to fetch the signed network data from.
 *
 * [derivePublicKey] is injected so previews need no native library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    initial: TunnelConfig,
    derivePublicKey: (String) -> String?,
    generateKey: () -> Pair<String, String>?,
    onSave: (TunnelConfig) -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var privateKey by rememberSaveable { mutableStateOf(initial.privateKey) }
    var authKey by rememberSaveable { mutableStateOf(initial.authKey) }
    var gateways by rememberSaveable { mutableStateOf(initial.gateways.joinToString("\n")) }
    var keepalive by rememberSaveable { mutableStateOf(initial.keepalive.toString()) }
    var dht by rememberSaveable { mutableStateOf(initial.dht) }
    var debug by rememberSaveable { mutableStateOf(initial.debug) }
    var rawJson by rememberSaveable { mutableStateOf(initial.rawJson) }
    var showAdvanced by rememberSaveable { mutableStateOf(initial.rawJson.isNotBlank()) }

    val publicKey = remember(privateKey) { if (privateKey.isBlank()) null else derivePublicKey(privateKey.trim()) }
    val context = LocalContext.current

    fun current() = TunnelConfig(
        name = name.trim(),
        privateKey = privateKey.trim(),
        authKey = authKey.trim(),
        gateways = gateways.lines().map { it.trim() }.filter { it.isNotEmpty() },
        keepalive = keepalive.trim().toIntOrNull() ?: 10,
        dht = dht,
        debug = debug,
        rawJson = if (showAdvanced) rawJson.trim() else "",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network setup") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Join an existing unetd network as a dynamic host. Generate a key here, " +
                    "then add this device's public key to the network with unet-cli on " +
                    "the router that manages it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Network name") },
                supportingText = { Text("The name unetd uses for this network, e.g. unet or net0") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This device's key", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text("Private key (base64)") },
                        singleLine = true,
                        isError = privateKey.isNotBlank() && publicKey == null,
                        supportingText = {
                            if (privateKey.isNotBlank() && publicKey == null) Text("Not a valid Curve25519 key")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { generateKey()?.let { privateKey = it.first } }) {
                            Text(if (privateKey.isBlank()) "Generate key" else "Generate new key")
                        }
                    }
                    if (publicKey != null) {
                        Text("Public key — add this to the network:", style = MaterialTheme.typography.labelMedium)
                        Text(publicKey, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { copyToClipboard(context, "unetd public key", publicKey) }) { Text("Copy") }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "unet-cli <net> add-host <name> key=\"$publicKey\" …",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = authKey,
                onValueChange = { authKey = it },
                label = { Text("Network public key (auth_key)") },
                supportingText = { Text("From the router: unet-tool -P -K <network key file>, or the auth_key in its unetd config") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = gateways,
                onValueChange = { gateways = it },
                label = { Text("Gateways (one per line)") },
                supportingText = {
                    Text("host or host:port reachable from the internet; the network data is fetched from here. Port defaults to 51819.")
                },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = keepalive,
                onValueChange = { keepalive = it.filter(Char::isDigit) },
                label = { Text("Keepalive (seconds)") },
                supportingText = { Text("Needed behind NAT. unetd only connects to peers when this is set; 10 is a good start.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            ToggleRow("Find peers through the DHT", "Runs unet-dht alongside; useful when gateways are behind NAT or move.", dht) { dht = it }
            ToggleRow("Verbose unetd log", "Keep on while testing; the Log screen shows it.", debug) { debug = it }

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced" else "Advanced: raw unetd network JSON")
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = rawJson,
                    onValueChange = { rawJson = it },
                    label = { Text("unetd network JSON (used verbatim when set)") },
                    supportingText = {
                        Text("Exactly what unetd takes with -N on a router: {\"name\":…,\"type\":\"dynamic\",\"key\":…,\"auth_key\":…,\"auth_connect\":[…]}")
                    },
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val cfg = current()
            Button(
                onClick = { onSave(cfg) },
                enabled = cfg.isComplete && (cfg.rawJson.isNotBlank() || publicKey != null),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

internal fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
