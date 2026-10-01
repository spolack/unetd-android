package org.unetd.android

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.unetd.android.data.NativeUnetRepository
import org.unetd.android.ui.AppRoot
import org.unetd.android.ui.UnetdTheme

class MainActivity : ComponentActivity() {

    private val repository by lazy { NativeUnetRepository(applicationContext) }

    /** The system's "allow this app to set up a VPN" dialog. */
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) lifecycleScope.launch { repository.connect() }
    }

    /** Only affects whether the tunnel's notification is shown; the service runs either way. */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            UnetdTheme {
                val status by repository.status.collectAsStateWithLifecycle()
                AppRoot(
                    status = status,
                    onConnect = ::connect,
                    onDisconnect = { lifecycleScope.launch { repository.disconnect() } },
                )
            }
        }
    }

    private fun connect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnPermission.launch(consent)
        } else {
            lifecycleScope.launch { repository.connect() }
        }
    }
}
