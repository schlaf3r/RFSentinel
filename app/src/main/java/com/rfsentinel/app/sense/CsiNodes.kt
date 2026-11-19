package com.rfsentinel.app.sense

import android.content.Context
import android.util.Log
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

/**
 * The ESP32 CSI nodes feeding WiFi Sense. Each node is a board on the network running CSI firmware
 * that streams newline-delimited lines over TCP; the phone connects out to each one. Add as many as
 * you like (Settings › WiFi Sense › CSI nodes). Each node runs its own reader and shows as its own
 * motion signal in the Sense view.
 *
 * Two line formats are accepted, auto-detected per line:
 *   SENSE,<seq>,<motion 0-100>[,<detail>]        node already computed a motion score (e.g. ESPectre)
 *   CSI_DATA,...,"[i,q,i,q,...]"                  raw esp-csi line; the phone computes motion ([CsiMotion])
 * Lines that are neither are ignored, so a chatty board doesn't break anything.
 */
object CsiNodes {

    private const val TAG = "CsiNodes"
    private const val PORT_DEFAULT = 3333
    private const val RECONNECT_MS = 3_000L
    private const val STALE_MS = 8_000L

    data class Node(val id: String, var name: String, var host: String, var port: Int, var enabled: Boolean)

    private class Live(val node: Node) {
        val source = WifiSense.Source("csi:${node.id}", node.name, WifiSense.Source.Kind.CSI)
        val motion = CsiMotion()
        var job: Job? = null
        @Volatile var connected = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val live = Collections.synchronizedMap(LinkedHashMap<String, Live>())
    private var appContext: Context? = null
    @Volatile private var channelHint: Int = 0

    /** Call once at startup; loads saved nodes and starts the enabled ones. */
    fun init(context: Context) {
        appContext = context.applicationContext
        load()
    }

    /** CSI sources for the Sense view, in the order the nodes were added. */
    fun sources(): List<WifiSense.Source> = synchronized(live) { live.values.map { it.source } }

    fun nodes(): List<Node> = synchronized(live) { live.values.map { it.node.copy() } }

    fun isConnected(id: String): Boolean = synchronized(live) { live[id]?.connected == true }

    /** When an AP is armed, tell nodes which channel to lock to (nodes that honor the command). */
    fun onArmedChannel(channel: Int) { channelHint = channel }

    fun addNode(context: Context, name: String, host: String, port: Int = PORT_DEFAULT) {
        val id = "n" + System.currentTimeMillis().toString(36)
        val n = Node(id, name.ifBlank { host }, host.trim(), port, enabled = true)
        synchronized(live) { live[id] = Live(n) }
        save(); start(id)
    }

    fun removeNode(id: String) {
        synchronized(live) { live.remove(id)?.let { it.job?.cancel(); } }
        save(); WifiSense.Listeners.fire()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(live) { live[id]?.node?.enabled = enabled }
        save()
        if (enabled) start(id) else synchronized(live) { live[id]?.let { it.job?.cancel(); it.connected = false; it.source.motion = 0f } }
        WifiSense.Listeners.fire()
    }

    private fun start(id: String) {
        val l = synchronized(live) { live[id] } ?: return
        if (!l.node.enabled) return
        l.job?.cancel()
        l.job = scope.launch { readLoop(l) }
    }

    private suspend fun readLoop(l: Live) {
        while (scope.isActive) {
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(l.node.host, l.node.port), 5_000)
                    sock.soTimeout = STALE_MS.toInt()
                    l.connected = true; l.source.detail = "connected"; WifiSense.Listeners.fire()
                    val reader = sock.getInputStream().bufferedReader()
                    // Greet with the armed channel so a node can lock to it (ignored by nodes that don't parse it).
                    runCatching { sock.getOutputStream().write("CH ${channelHint}\n".toByteArray()); sock.getOutputStream().flush() }
                    readLines(l, reader)
                }
            } catch (e: Exception) {
                Log.d(TAG, "${l.node.name}: ${e.javaClass.simpleName} ${e.message}")
            }
            l.connected = false
            l.source.push(System.currentTimeMillis(), 0f, "offline")
            if (!scope.isActive) break
            delay(RECONNECT_MS)
        }
    }

    private fun readLines(l: Live, reader: BufferedReader) {
        while (scope.isActive) {
            val line = reader.readLine() ?: break
            val t = System.currentTimeMillis()
            when {
                line.startsWith("SENSE,") -> parseSense(line)?.let { (m, d) -> l.source.push(t, m, d.ifBlank { "node motion" }) }
                line.startsWith("CSI_DATA") -> parseCsi(line)?.let { iq ->
                    val m = l.motion.pushIq(iq)
                    if (m >= 0f) l.source.push(t, m, "ch ${channelHint} · ${iq.size / 2} sc")
                }
            }
        }
    }

    /** SENSE,<seq>,<motion>[,detail] */
    internal fun parseSense(line: String): Pair<Float, String>? {
        val p = line.split(',')
        val m = p.getOrNull(2)?.trim()?.toFloatOrNull() ?: return null
        return m.coerceIn(0f, 100f) to p.drop(3).joinToString(",").trim()
    }

    /** The bracketed int array at the end of an esp-csi CSI_DATA line. */
    internal fun parseCsi(line: String): IntArray? {
        val open = line.indexOf('['); val close = line.lastIndexOf(']')
        if (open < 0 || close <= open) return null
        val body = line.substring(open + 1, close)
        if (body.isBlank()) return null
        val parts = body.split(',')
        val out = IntArray(parts.size)
        var n = 0
        for (s in parts) { val v = s.trim().toIntOrNull() ?: continue; out[n++] = v }
        return if (n >= 2) out.copyOf(n) else null
    }

    private fun load() {
        val ctx = appContext ?: return
        synchronized(live) {
            live.values.forEach { it.job?.cancel() }
            live.clear()
            runCatching {
                val arr = JSONArray(Prefs.senseNodes(ctx).ifBlank { "[]" })
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val n = Node(o.getString("id"), o.getString("name"), o.getString("host"),
                        o.optInt("port", PORT_DEFAULT), o.optBoolean("enabled", true))
                    live[n.id] = Live(n)
                }
            }
        }
        synchronized(live) { live.keys.toList() }.forEach { start(it) }
    }

    private fun save() {
        val ctx = appContext ?: return
        val arr = JSONArray()
        synchronized(live) {
            live.values.forEach {
                val n = it.node
                arr.put(JSONObject().put("id", n.id).put("name", n.name).put("host", n.host).put("port", n.port).put("enabled", n.enabled))
            }
        }
        Prefs.setSenseNodes(ctx, arr.toString())
    }
}
