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
import org.unetd.android.nativebridge.Unetd

private enum class Screen { Home, Setup, Log }

/** Three screens, no navigation library: home, setup, log. */
@Composable
fun AppRoot(status: NetworkStatus, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var config by remember { mutableStateOf(ConfigStore.load(context)) }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (screen) {
        Screen.Home -> HomeScreen(
            status = if (status.name.isEmpty() && config != null) status.copy(name = config!!.effectiveName()) else status,
            configured = config?.isComplete == true,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onOpenSetup = { screen = Screen.Setup },
            onOpenLog = { screen = Screen.Log },
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

        Screen.Log -> LogScreen(readLog = { Unetd.logTail() }, onBack = { screen = Screen.Home })
    }
}
