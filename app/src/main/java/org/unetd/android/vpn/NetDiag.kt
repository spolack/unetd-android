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

    /**
     * [protect] exempts a socket from the VPN the way unetd's and WireGuard's
     * sockets are exempted; null runs the UDP probes on plain sockets only.
     */
    fun run(context: Context, protect: ((java.net.DatagramSocket) -> Boolean)? = null) {
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

        val url = "https://$host/generate_204"
        val t1 = System.currentTimeMillis()
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            c.disconnect()
            out.append("  https $host -> $code in ${System.currentTimeMillis() - t1} ms\n")
        } catch (e: Exception) {
            out.append("  https $host FAILED after ${System.currentTimeMillis() - t1} ms: $e\n")
        }
        // TCP by address, no DNS and no TLS involved (a raw socket, so the
        // platform's cleartext policy does not apply).
        val t2 = System.currentTimeMillis()
        try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("34.107.221.82", 80), 5000)
                out.append("  tcp 34.107.221.82:80 connected in ${System.currentTimeMillis() - t2} ms\n")
            }
        } catch (e: Exception) {
            out.append("  tcp 34.107.221.82:80 FAILED after ${System.currentTimeMillis() - t2} ms: $e\n")
        }

        // UDP: is it UDP in general, or the BitTorrent DHT port, or our socket?
        // A DNS query to a public resolver (UDP/53), then a DHT ping to the
        // bootstrap routers unet-dht uses (UDP/6881), each from a plain socket
        // and, when offered, from a protect()ed one like unetd's.
        udpProbe(out, "dns 8.8.8.8:53", "8.8.8.8", 53, dnsQuery(), null)
        for ((router, port) in listOf("router.bittorrent.com" to 6881, "router.utorrent.com" to 6881, "dht.transmissionbt.com" to 6881, "dht.libtorrent.org" to 25401, "dht.aelitis.com" to 6881)) {
            udpProbe(out, "dht $router:$port", router, port, dhtPing(), null)
            if (protect != null) udpProbe(out, "dht $router:$port protected", router, port, dhtPing(), protect)
        }
        // NAT behaviour: the same socket asks two STUN servers for its mapped
        // address. Equal ports mean endpoint-independent mapping, which hole
        // punching can get through; different ports mean a symmetric NAT,
        // which it cannot. Once plain, once protect()ed like unetd's sockets.
        natProbe(out, "plain", null)
        if (protect != null) natProbe(out, "protected", protect)
        AppLog.line(out.toString().trimEnd())
    }

    private val stunServers = listOf("stun.l.google.com" to 19302, "stun.cloudflare.com" to 3478)

    private fun natProbe(out: StringBuilder, label: String, protect: ((java.net.DatagramSocket) -> Boolean)?) {
        try {
            java.net.DatagramSocket().use { s ->
                if (protect != null && !protect(s)) {
                    out.append("  nat ($label): protect() REFUSED\n")
                    return
                }
                s.soTimeout = 3000
                val mapped = stunServers.map { (host, port) ->
                    val m = runCatching { stunMappedAddress(s, host, port) }.getOrNull()
                    out.append("  stun $host:$port ($label): ${m ?: "NO REPLY"}\n")
                    m
                }
                val ok = mapped.filterNotNull()
                val verdict = when {
                    ok.size < 2 -> "undetermined (${ok.size} of ${mapped.size} servers answered)"
                    ok.toSet().size == 1 -> {
                        val port = ok[0].substringAfterLast(':').toIntOrNull()
                        "endpoint-independent mapping, hole punching possible" +
                            if (port == s.localPort) ", port preserved" else " (local port ${s.localPort} mapped to $port)"
                    }
                    else -> "endpoint-dependent mapping (symmetric NAT): hole punching will NOT work"
                }
                out.append("  nat ($label): $verdict\n")
            }
        } catch (e: Exception) {
            out.append("  nat ($label): FAILED: $e\n")
        }
    }

    /** One RFC 5389 binding request; returns "ip:port" from (XOR-)MAPPED-ADDRESS. */
    private fun stunMappedAddress(s: java.net.DatagramSocket, host: String, port: Int): String? {
        val addr = InetAddress.getAllByName(host).firstOrNull { it is java.net.Inet4Address } ?: return null
        val tid = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val req = java.nio.ByteBuffer.allocate(20)
            .putShort(0x0001).putShort(0).putInt(0x2112A442).put(tid).array()
        s.send(java.net.DatagramPacket(req, req.size, addr, port))
        val buf = ByteArray(1500)
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val pkt = java.net.DatagramPacket(buf, buf.size)
            s.receive(pkt)
            val b = java.nio.ByteBuffer.wrap(buf, 0, pkt.length)
            if (pkt.length < 20 || b.getShort(0).toInt() != 0x0101) continue
            if (!buf.copyOfRange(8, 20).contentEquals(tid)) continue
            var off = 20
            while (off + 4 <= pkt.length) {
                val type = b.getShort(off).toInt() and 0xffff
                val len = b.getShort(off + 2).toInt() and 0xffff
                if (off + 4 + len > pkt.length) break
                if ((type == 0x0020 || type == 0x0001) && len >= 8 && buf[off + 5].toInt() == 1) {
                    var p = b.getShort(off + 6).toInt() and 0xffff
                    var ip = b.getInt(off + 8)
                    if (type == 0x0020) { p = p xor 0x2112; ip = ip xor 0x2112A442 }
                    return "${(ip ushr 24) and 255}.${(ip ushr 16) and 255}.${(ip ushr 8) and 255}.${ip and 255}:$p"
                }
                off += 4 + ((len + 3) and 3.inv())
            }
            return null
        }
        return null
    }

    private fun udpProbe(
        out: StringBuilder, label: String, host: String, port: Int, payload: ByteArray,
        protect: ((java.net.DatagramSocket) -> Boolean)?,
    ) {
        val t = System.currentTimeMillis()
        try {
            val addr = InetAddress.getAllByName(host).firstOrNull { it is java.net.Inet4Address } ?: InetAddress.getByName(host)
            java.net.DatagramSocket().use { s ->
                if (protect != null && !protect(s)) {
                    out.append("  udp $label: protect() REFUSED\n")
                    return
                }
                s.soTimeout = 3000
                s.send(java.net.DatagramPacket(payload, payload.size, addr, port))
                val buf = ByteArray(1500)
                val reply = java.net.DatagramPacket(buf, buf.size)
                s.receive(reply)
                out.append("  udp $label (${addr.hostAddress}): reply ${reply.length} bytes in ${System.currentTimeMillis() - t} ms\n")
            }
        } catch (e: java.net.SocketTimeoutException) {
            out.append("  udp $label: NO REPLY in ${System.currentTimeMillis() - t} ms\n")
        } catch (e: Exception) {
            out.append("  udp $label: FAILED: $e\n")
        }
    }

    /** A minimal DNS A query for example.com, transaction id 0x1234. */
    private fun dnsQuery(): ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
        0x00, 0x01, 0x00, 0x01,
    )

    /** A bencoded BitTorrent DHT ping (BEP 5) with a random node id. */
    private fun dhtPing(): ByteArray {
        val id = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
        return "d1:ad2:id20:".toByteArray(Charsets.ISO_8859_1) + id + "e1:q4:ping1:t2:aa1:y1:qe".toByteArray(Charsets.ISO_8859_1)
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
