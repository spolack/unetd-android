package org.unetd.android

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.unetd.android.config.ConfigStore
import org.unetd.android.config.TunnelConfig
import org.unetd.android.data.TunnelState
import org.unetd.android.vpn.TunnelRuntime
import org.unetd.android.vpn.UnetVpnService
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * The tunnel on an emulator, driven the way a user drives it, against a unetd
 * "router" running on the machine that hosts the emulator
 * (tests/emulator/router.sh). Runs in the app's own process, so every socket
 * opened here sits inside the VPN exactly like another app's would.
 *
 * Instrumentation arguments (all optional; without `routerAddress` the
 * data-path test is skipped):
 *   gatewayHost    where to fetch the network data from (default 10.0.2.2, the host)
 *   authKey        the network's public key
 *   phoneKey       this device's private key, whose public key is in the signed data
 *   routerAddress  the router's in-tunnel IPv4 address, serving HTTP on :8080
 *   probeUrl       an internet URL that answers 204 (default: Google's connectivity check)
 *
 * The VPN consent dialog cannot be clicked here; CI pre-grants it with
 * `adb shell appops set org.unetd.android ACTIVATE_VPN allow`, which is the
 * same app-op the dialog sets.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TunnelEmulatorTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val gatewayHost get() = args.getString("gatewayHost") ?: "10.0.2.2"
    private val probeUrl get() = args.getString("probeUrl") ?: "https://connectivitycheck.gstatic.com/generate_204"
    private val routerAddress get() = args.getString("routerAddress")

    @Test
    fun t1_baselineInternet() {
        assertNoVpn("no VPN should be up before the test starts")
        assertInternet("before the VPN")
    }

    @Test
    fun t2_splitTunnelKeepsInternet() {
        val authKey = args.getString("authKey") ?: ""
        val phoneKey = args.getString("phoneKey") ?: ""
        assumeTrue("authKey and phoneKey instrumentation arguments required", authKey.isNotBlank() && phoneKey.isNotBlank())

        ConfigStore.save(
            context,
            TunnelConfig(
                name = NETWORK,
                privateKey = phoneKey,
                authKey = authKey,
                gateways = listOf(gatewayHost),
                keepalive = 10,
                dht = false,
                debug = true,
            ),
        )

        assertNull("VPN consent must be pre-granted (adb shell appops set <pkg> ACTIVATE_VPN allow)", VpnService.prepare(context))

        // A visible activity keeps the foreground-service start unrestricted.
        ActivityScenario.launch(MainActivity::class.java)
        context.startForegroundService(Intent(context, UnetVpnService::class.java))

        waitFor("a VPN network to appear", 30_000) { vpnNetwork() != null }
        // The reported bug: the placeholder tun routes nothing, and still every
        // other destination has to keep working.
        assertInternet("with the VPN up (placeholder tun, no routes)")
    }

    /** UDP from inside the app to the host, with the VPN up: the path unetd's PEX requests take. */
    @Test
    fun t2b_udpReachesHost() {
        val reply = udpEcho(gatewayHost, 51999, "unetd-android probe")
        println("udp echo to $gatewayHost:51999 -> $reply")
        assertEquals("UDP echo from the host", "unetd-android probe", reply)
    }

    @Test
    fun t3_tunnelCarriesTraffic() {
        val router = routerAddress
        assumeTrue("routerAddress instrumentation argument required", !router.isNullOrBlank())
        assumeTrue("t2 did not bring the VPN up", vpnNetwork() != null)

        waitFor("unetd to fetch the network data and connect to the router", 120_000) {
            val s = TunnelRuntime.status.value
            s.state == TunnelState.Connected && s.peers.any { it.connected }
        }
        assertInternet("with the real routes installed")

        // Plain HTTP over a raw socket: the platform's cleartext policy would
        // refuse HttpURLConnection, and what matters here is the tunnel.
        val t0 = System.currentTimeMillis()
        val reply = rawHttpGet(router!!, 8080, "/")
        assertTrue("HTTP through the tunnel to the router, got: $reply", reply.startsWith("HTTP/1.0 200") || reply.startsWith("HTTP/1.1 200"))
        assertTrue("the router's page came through the tunnel", reply.contains("hello from the router"))
        println("tunnel round trip: ${System.currentTimeMillis() - t0} ms")
    }

    @Test
    fun t4_disconnectRemovesVpn() {
        assumeTrue("nothing to disconnect", vpnNetwork() != null)
        context.startService(Intent(context, UnetVpnService::class.java).setAction(UnetVpnService.ACTION_DISCONNECT))
        waitFor("the VPN network to disappear after disconnect", 20_000) { vpnNetwork() == null }
        assertInternet("after disconnecting")
    }

    // ---- helpers ------------------------------------------------------------------

    private fun vpnNetwork(): android.net.Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun assertNoVpn(message: String) {
        assertNull(message, vpnNetwork())
    }

    private fun assertInternet(stage: String) {
        val host = URL(probeUrl).host
        val resolved = try {
            InetAddress.getAllByName(host).joinToString { it.hostAddress ?: "?" }
        } catch (e: Exception) {
            fail("DNS for $host failed $stage: $e"); ""
        }
        val code = try {
            httpGet(probeUrl)
        } catch (e: Exception) {
            fail("HTTP $probeUrl failed $stage ($host -> $resolved): $e"); -1
        }
        assertTrue("HTTP $probeUrl answered $code $stage", code in 200..299)
    }

    private fun rawHttpGet(host: String, port: Int, path: String): String {
        java.net.Socket().use { socket ->
            socket.soTimeout = 10_000
            socket.connect(java.net.InetSocketAddress(host, port), 10_000)
            socket.getOutputStream().write("GET $path HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
    }

    private fun udpEcho(host: String, port: Int, payload: String): String? {
        java.net.DatagramSocket().use { socket ->
            socket.soTimeout = 3_000
            val bytes = payload.toByteArray()
            repeat(3) {
                socket.send(java.net.DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
                try {
                    val buf = ByteArray(2048)
                    val p = java.net.DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    return String(p.data, 0, p.length)
                } catch (_: java.net.SocketTimeoutException) {
                }
            }
        }
        return null
    }

    private fun httpGet(url: String): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.instanceFollowRedirects = false
        return try {
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    private fun waitFor(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(500)
        }
        fail("timed out after ${timeoutMs / 1000}s waiting for $what; status=${TunnelRuntime.status.value}")
    }

    private companion object {
        const val NETWORK = "wgci"
    }
}
