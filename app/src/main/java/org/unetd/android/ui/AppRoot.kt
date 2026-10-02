package org.unetd.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.unetd.android.config.ConfigStore
import org.unetd.android.config.TunnelConfig
import org.unetd.android.data.NetworkStatus
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.unetd.android.nativebridge.Unetd
import org.unetd.android.vpn.DhtLog

private enum class Screen { Home, Setup, Log }

/** Three screens, no navigation library: home, setup, log. */
@Composable
fun AppRoot(status: NetworkStatus, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var config by remember { mutableStateOf(ConfigStore.load(context)) }

    // The DHT node lives in another process; its progress reaches us through a file.
    var dhtSummary by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(status.capabilities.dht, status.state) {
        // Only while the activity is started: no file reads from the background.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                dhtSummary = if (status.capabilities.dht) DhtLog.summary(DhtLog.read(context)) else null
                delay(2000)
            }
        }
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (screen) {
        Screen.Home -> HomeScreen(
            status = if (status.name.isEmpty() && config != null) status.copy(name = config!!.effectiveName()) else status,
            configured = config?.isComplete == true,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onOpenSetup = { screen = Screen.Setup },
            onOpenLog = { screen = Screen.Log },
            dhtSummary = dhtSummary,
        )

        Screen.Setup -> SetupScreen(
            initial = config ?: TunnelConfig(),
            derivePublicKey = { Unetd.publicKey(it) },
            generateKey = { Unetd.generateKey() },
            onSave = { cfg ->
                ConfigStore.save(context, cfg)
                config = cfg
                screen = Screen.Home
            },
            onBack = { screen = Screen.Home },
        )

        Screen.Log -> LogScreen(
            readLog = {
                val dht = DhtLog.read(context)
                if (dht.isBlank()) Unetd.logTail()
                else Unetd.logTail() + "\n\n--- unet-dht (:dht process) ---\n" + dht
            },
            onBack = { screen = Screen.Home },
        )
    }
}
