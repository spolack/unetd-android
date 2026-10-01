package org.unetd.android.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Writes what Android thinks of the VPN into the log, and whether this app can
 * reach the internet through it. Diagnostics only; nothing depends on it.
 *
 * This app is inside the VPN like every other app (nothing is excluded), so a
 * plain HTTP connection from here behaves like one from a browser.
 */
object NetDiag {

    fun run(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val out = StringBuilder("netdiag:\n")

        try {
            val active = cm.activeNetwork
            out.append("  active network: $active ${describe(cm, active)}\n")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(n)
                out.append("  vpn network: $n iface=${lp?.interfaceName} ${describe(cm, n)}\n")
                out.append("    addresses: ${lp?.linkAddresses}\n")
                out.append("    routes: ${lp?.routes}\n")
                out.append("    dns: ${lp?.dnsServers}\n")
            }
        } catch (e: Exception) {
            out.append("  (network info failed: $e)\n")
        }

        val host = "connectivitycheck.gstatic.com"
        val t0 = System.currentTimeMillis()
        try {
            val addrs = InetAddress.getAllByName(host)
            out.append("  dns $host -> ${addrs.joinToString { it.hostAddress ?: "?" }} in ${System.currentTimeMillis() - t0} ms\n")
        } catch (e: Exception) {
            out.append("  dns $host FAILED: $e\n")
        }

        for (url in listOf("https://$host/generate_204", "http://34.107.221.82/generate_204")) {
            val t1 = System.currentTimeMillis()
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.instanceFollowRedirects = false
                val code = c.responseCode
                c.disconnect()
                out.append("  http $url -> $code in ${System.currentTimeMillis() - t1} ms\n")
            } catch (e: Exception) {
                out.append("  http $url FAILED after ${System.currentTimeMillis() - t1} ms: $e\n")
            }
        }
        System.err.print(out)
    }

    private fun describe(cm: ConnectivityManager, n: android.net.Network?): String {
        if (n == null) return ""
        val caps = cm.getNetworkCapabilities(n) ?: return "(no capabilities)"
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cell",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
            NetworkCapabilities.TRANSPORT_ETHERNET to "eth",
        ).filter { caps.hasTransport(it.first) }.joinToString("+") { it.second }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return "[$transports internet=$internet validated=$validated]"
    }
}
