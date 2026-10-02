package org.unetd.android.vpn

import android.content.Context
import java.io.File

/**
 * The DHT node's log, carried from the `:dht` process to the UI.
 *
 * unet-dht runs in its own process, so its output is not in the main process's
 * log ring. [UdhtService] mirrors its ring into a file every couple of
 * seconds; the Log screen appends that file and the home screen shows a
 * one-line reading of it, so a phone without adb still tells where DHT
 * discovery stops.
 */
object DhtLog {
    fun file(context: Context): File = File(context.filesDir, "dht.log")

    fun read(context: Context): String = runCatching { file(context).readText() }.getOrDefault("")

    fun clear(context: Context) {
        file(context).delete()
    }

    /** Atomic replace, so a reader never sees a half-written file. */
    fun write(context: Context, text: String) {
        val f = file(context)
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(f)) f.writeText(text)
        }
    }

    /** One line for the home screen: how far the DHT node got. Null if nothing is known. */
    fun summary(text: String): String? {
        if (text.isBlank()) return null
        val lines = text.lines()
        val found = lines.lastOrNull { it.startsWith("Node: ") }
        if (found != null) return "found a peer at ${found.removePrefix("Node: ").trim()}"
        for (line in lines.asReversed()) {
            when {
                line.startsWith("Start search for network") -> return "ready, searching for the network"
                line.startsWith("DHT is ready") -> return "ready, search starts shortly"
                line.startsWith("DHT status: ") -> {
                    val good = Regex("good=(\\d+)").find(line)?.groupValues?.get(1) ?: "?"
                    return "bootstrapping, $good good nodes so far"
                }
                line.startsWith("Pong!") -> return "a bootstrap node answered"
                line.startsWith("Ping node ") -> return "pinging bootstrap node ${line.removePrefix("Ping node ").trim()}, no answer yet"
                line.startsWith("Failed to connect to unetd") -> return "cannot reach unetd's control socket"
                line.startsWith("DHT connected") -> return "connected to unetd, resolving bootstrap nodes"
            }
        }
        return "running, no bootstrap contact yet"
    }
}
