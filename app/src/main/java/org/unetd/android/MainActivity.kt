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

    override fun onStart() {
        super.onStart()
        org.unetd.android.vpn.TunnelRuntime.uiVisible.value = true
        org.unetd.android.vpn.TunnelRuntime.requestRefresh()
    }

    override fun onStop() {
        org.unetd.android.vpn.TunnelRuntime.uiVisible.value = false
        super.onStop()
    }

    private val repository by lazy { NativeUnetRepository(applicationContext) }

    /** The system's "allow this app to set up a VPN" dialog. */
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) lifecycleScope.launch { repository.connect() }
    }

    /** Only affects whether the tunnel's notification is shown; the service runs either way. */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * Android 17 blocks every packet to a local-network address (EPERM on
     * sendto) until this is granted. A gateway on the LAN, or the emulator's
     * host, is exactly such an address, so it is asked for before connecting.
     */
    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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

        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }

        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnPermission.launch(consent)
        } else {
            lifecycleScope.launch { repository.connect() }
        }
    }
}
