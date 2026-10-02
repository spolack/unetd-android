package org.unetd.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
 * The native layer's recent output, line by line: unetd's diagnostics and
 * debug trace, wireguard-go's log, the DHT node and the app's own lines, in
 * the order they were logged. Only new lines are fetched (once a second while
 * the screen is visible), and the view follows the tail only while it is at
 * the tail, so one can scroll up and read while lines keep arriving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(readSince: (Long, Int) -> Pair<Long, List<String>>, onBack: () -> Unit) {
    val lines = remember { mutableStateListOf<String>() }
    var seq by remember { mutableLongStateOf(0L) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val (next, fresh) = readSince(seq, BATCH)
                seq = next
                if (fresh.isNotEmpty()) {
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    val atTail = lines.isEmpty() || lastVisible == null || lastVisible >= lines.lastIndex
                    lines.addAll(fresh)
                    if (lines.size > KEEP) lines.removeRange(0, lines.size - KEEP)
                    if (atTail) listState.scrollToItem(lines.lastIndex)
                }
                if (fresh.size < BATCH) delay(1000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { copyToClipboard(context, "unetd log", lines.joinToString("\n")) }) { Text("Copy") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).horizontalScroll(rememberScrollState())) {
            if (lines.isEmpty()) {
                Text(
                    "Nothing logged yet. Connect to start unetd.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                itemsIndexed(lines) { _, line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, softWrap = false)
                }
            }
        }
    }
}

private const val BATCH = 500
private const val KEEP = 2000
