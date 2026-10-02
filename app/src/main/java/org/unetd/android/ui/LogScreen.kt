package org.unetd.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * The native layer's recent output: unetd's diagnostics and debug trace, and
 * wireguard-go's log, in order, followed by the DHT process's log (mirrored to
 * a file by UdhtService, see DhtLog). Refreshed every second.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(readLog: () -> String, onBack: () -> Unit) {
    var text by remember { mutableStateOf(readLog()) }
    val vertical = rememberScrollState()
    val context = LocalContext.current

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val fresh = readLog()
                if (fresh != text) {
                    text = fresh
                    vertical.scrollTo(vertical.maxValue)
                }
                delay(1000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = { copyToClipboard(context, "unetd log", text) }) { Text("Copy") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(vertical)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                if (text.isEmpty()) "Nothing logged yet. Connect to start unetd." else text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                softWrap = false,
            )
        }
    }
}
