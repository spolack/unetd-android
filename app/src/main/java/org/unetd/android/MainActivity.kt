package org.unetd.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.unetd.android.data.FakeUnetRepository
import org.unetd.android.ui.HomeScreen
import org.unetd.android.ui.UnetdTheme

class MainActivity : ComponentActivity() {

    // TODO(native): swap for the JNI-backed repository once unetd is embedded.
    // The UI is written against the UnetRepository interface so it does not
    // change when that happens.
    private val repository = FakeUnetRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch { repository.tick() }

        setContent {
            UnetdTheme {
                val status by repository.status.collectAsStateWithLifecycle()
                HomeScreen(
                    status = status,
                    onConnect = { lifecycleScope.launch { repository.connect() } },
                    onDisconnect = { lifecycleScope.launch { repository.disconnect() } },
                )
            }
        }
    }
}
