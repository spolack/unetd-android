package org.unetd.android.config

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the user enters on the setup screen, and how it becomes unetd's network
 * configuration -- the same JSON unetd takes with `-N` on a router.
 *
 * The common case is a "dynamic" network: this device holds its own private key
 * and the network's public key (`auth_key`), and fetches the signed network data
 * from the gateways listed in `auth_connect` (or found through the DHT). The
 * network admin adds this device's public key with `unet-cli`.
 */
data class TunnelConfig(
    /** Also the name of the WireGuard UAPI socket, so keep it short and plain. */
    val name: String = "unet",
    val privateKey: String = "",
    /** The network's public key, base64. */
    val authKey: String = "",
    /** host[:port] of gateways to fetch the network data from; port defaults to 51819. */
    val gateways: List<String> = emptyList(),
    /**
     * Seconds between WireGuard keepalives. unetd only attempts connections at all
     * when this is non-zero, so 0 is "never connect" rather than "no keepalive".
     */
    val keepalive: Int = 10,
    val dht: Boolean = true,
    val debug: Boolean = true,
    /** Advanced: a complete unetd network JSON used verbatim instead of the fields above. */
    val rawJson: String = "",
) {
    val isComplete: Boolean
        get() = rawJson.isNotBlank() ||
            (name.isNotBlank() && privateKey.isNotBlank() && authKey.isNotBlank())

    fun effectiveName(): String =
        if (rawJson.isNotBlank()) runCatching { JSONObject(rawJson).getString("name") }.getOrDefault(name) else name

    fun effectivePrivateKey(): String =
        if (rawJson.isNotBlank()) runCatching { JSONObject(rawJson).getString("key") }.getOrDefault("") else privateKey

    /** Public keys of the networks the DHT should search for. */
    fun authKeys(): List<String> =
        if (rawJson.isNotBlank()) runCatching { listOf(JSONObject(rawJson).getString("auth_key")) }.getOrDefault(emptyList())
        else listOf(authKey).filter { it.isNotBlank() }

    fun unetdJson(): String {
        if (rawJson.isNotBlank()) return rawJson
        return JSONObject().apply {
            put("name", name)
            put("type", "dynamic")
            put("key", privateKey)
            put("auth_key", authKey)
            if (gateways.isNotEmpty()) put("auth_connect", JSONArray(gateways))
            put("keepalive", keepalive)
        }.toString()
    }

    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("privateKey", privateKey)
        put("authKey", authKey)
        put("gateways", JSONArray(gateways))
        put("keepalive", keepalive)
        put("dht", dht)
        put("debug", debug)
        put("rawJson", rawJson)
    }.toString()

    companion object {
        fun fromJson(json: String): TunnelConfig? = runCatching {
            val o = JSONObject(json)
            val gw = o.optJSONArray("gateways")
            TunnelConfig(
                name = o.optString("name", "unet"),
                privateKey = o.optString("privateKey", ""),
                authKey = o.optString("authKey", ""),
                gateways = if (gw == null) emptyList() else List(gw.length()) { gw.getString(it) },
                keepalive = o.optInt("keepalive", 10),
                dht = o.optBoolean("dht", true),
                debug = o.optBoolean("debug", true),
                rawJson = o.optString("rawJson", ""),
            )
        }.getOrNull()
    }
}

/**
 * Addresses and routes the tun was (or will be) established with. Android fixes
 * these at establish() time, so they are compared against unetd's interface
 * update to decide whether the tunnel must be re-established.
 */
data class TunSettings(
    val addresses: List<String>,   // "addr/prefix"
    val routes: List<String>,      // "prefix/len"
) {
    fun toJson(): String = JSONObject().apply {
        put("addresses", JSONArray(addresses))
        put("routes", JSONArray(routes))
    }.toString()

    companion object {
        /**
         * Before unetd has the network data nothing is known. Any address lets
         * establish() succeed; no route means nothing is sent into the tun.
         */
        fun placeholder() = TunSettings(addresses = listOf("fd00::1/128"), routes = emptyList())

        fun fromJson(json: String): TunSettings? = runCatching {
            val o = JSONObject(json)
            fun list(key: String): List<String> {
                val a = o.getJSONArray(key)
                return List(a.length()) { a.getString(it) }
            }
            TunSettings(list("addresses"), list("routes"))
        }.getOrNull()

        /**
         * From unetd's interface update. Returns null for a link-down update.
         *
         * The local address arrives as a /64 and the policy is: the address goes on
         * the tun as a /128, the /64 it lives in is added as a route. That prefix is
         * derived from the network id, so it covers every present and future peer
         * and never changes while the network exists.
         */
        fun fromUpdate(json: String): TunSettings? = runCatching {
            val o = JSONObject(json)
            if (!o.optBoolean("link-up", false)) return null
            val addresses = sortedSetOf<String>()
            val routes = sortedSetOf<String>()

            o.optJSONArray("ipaddr")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    addresses += "${e.getString("ipaddr")}/${e.optString("mask", "32").toInt()}"
                }
            }
            o.optJSONArray("ip6addr")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val addr = e.getString("ipaddr")
                    val mask = e.optString("mask", "128").toInt()
                    addresses += "$addr/128"
                    if (mask < 128) routes += "${Ip.prefixOf(addr, mask)}/$mask"
                }
            }
            for (key in listOf("routes", "routes6")) {
                o.optJSONArray(key)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        val len = e.getString("netmask").toInt()
                        routes += "${Ip.prefixOf(e.getString("target"), len)}/$len"
                    }
                }
            }
            TunSettings(addresses.toList(), routes.toList())
        }.getOrNull()
    }
}

object Ip {
    /** The network address of [addr] with the low bits beyond [prefixLen] cleared, in canonical text form. */
    fun prefixOf(addr: String, prefixLen: Int): String {
        val bytes = java.net.InetAddress.getByName(addr).address
        for (i in bytes.indices) {
            val bitsLeft = prefixLen - i * 8
            bytes[i] = when {
                bitsLeft >= 8 -> bytes[i]
                bitsLeft <= 0 -> 0
                else -> (bytes[i].toInt() and (0xff shl (8 - bitsLeft))).toByte()
            }
        }
        return java.net.InetAddress.getByAddress(bytes).hostAddress ?: addr
    }
}

object ConfigStore {
    private const val PREFS = "unetd"
    private const val KEY_CONFIG = "config"
    private const val KEY_LAST_TUN = "last_tun"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): TunnelConfig? =
        prefs(context).getString(KEY_CONFIG, null)?.let { TunnelConfig.fromJson(it) }

    fun save(context: Context, config: TunnelConfig) {
        prefs(context).edit {
            putString(KEY_CONFIG, config.toJson())
            remove(KEY_LAST_TUN)
        }
    }

    fun loadLastTun(context: Context): TunSettings? =
        prefs(context).getString(KEY_LAST_TUN, null)?.let { TunSettings.fromJson(it) }

    fun saveLastTun(context: Context, tun: TunSettings) {
        prefs(context).edit { putString(KEY_LAST_TUN, tun.toJson()) }
    }
}
