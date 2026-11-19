package com.rfsentinel.app.sense

import com.rfsentinel.app.detect.Advert
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * WiFi Sense: device-free motion sensing from the disturbance a moving body makes in a WiFi signal.
 * Receive-only, like the rest of RF Sentinel — it transmits nothing.
 *
 * Two kinds of source feed one view ([Source]):
 *  - the phone/adapter's own RSSI for one armed access point ([onWifiAdvert]); coarse — a "something
 *    moved" indicator, limited by how often that AP transmits (beacons ~10/s);
 *  - ESP32 CSI nodes ([com.rfsentinel.app.sense.CsiNodes]); true per-subcarrier CSI, finer.
 *
 * Each source carries a motion score 0..100 against its own quiet baseline, so RSSI and CSI sources
 * sit on the same scale in the view even though CSI is far more sensitive.
 */
object WifiSense {

    const val MOTION_MIN_SAMPLES = 6
    private const val WINDOW_MS = 4_000L
    private const val KEEP_MS = 30_000L
    /** RSSI std, in dB, that reads as full-scale motion above the quiet baseline. */
    private const val RSSI_FULL_SCALE_DB = 5.0
    /** The quiet baseline re-learns upward this fast (dB-std per update) so a room that settles reads calm again. */
    private const val BASELINE_RELEARN = 0.01

    data class Point(val t: Long, val motion: Float)

    /** One motion signal in the view: the armed AP's RSSI, or a CSI node. */
    class Source(val id: String, @Volatile var label: String, val kind: Kind) {
        enum class Kind { RSSI, CSI }
        val history = ArrayDeque<Point>()
        @Volatile var motion: Float = 0f          // 0..100, −1 until it has enough data
        @Volatile var lastUpdate: Long = 0L
        @Volatile var detail: String = ""         // e.g. "-58 dBm" or "ch 6 · 3 nodes"
        internal var baseline = Double.NaN
        internal val rssi = ArrayDeque<Pair<Long, Int>>()   // RSSI sources only

        @Synchronized fun push(t: Long, motion: Float, detail: String) {
            this.motion = motion; this.lastUpdate = t; this.detail = detail
            history.addLast(Point(t, motion.coerceAtLeast(0f)))
            while (history.isNotEmpty() && t - history.first.t > KEEP_MS) history.removeFirst()
            Listeners.fire()
        }
        @Synchronized fun points(): List<Point> = history.toList()
    }

    @Volatile private var armedBssid: String? = null
    @Volatile var armedChannel: Int = 0; private set
    private val rssiSource = Source("rssi", "This phone", Source.Kind.RSSI)

    /** Every active source, RSSI first then nodes, for the view. */
    fun sources(): List<Source> =
        (if (armedBssid != null) listOf(rssiSource) else emptyList()) + CsiNodes.sources()

    val isArmed get() = armedBssid != null

    /** Start RSSI sensing on one access point; [channel] lets a CSI node be told which channel to lock to. */
    @Synchronized
    fun arm(bssid: String, channel: Int, label: String) {
        armedBssid = bssid.lowercase(); armedChannel = channel
        rssiSource.label = label
        rssiSource.rssi.clear(); rssiSource.history.clear(); rssiSource.baseline = Double.NaN
        rssiSource.motion = -1f; rssiSource.detail = ""
        CsiNodes.onArmedChannel(channel)
        Listeners.fire()
    }

    @Synchronized
    fun disarm() { armedBssid = null; armedChannel = 0; Listeners.fire() }

    /** Fed from the scan service for every WiFi advert. Cheap no-op unless this AP is the armed one. */
    fun onWifiAdvert(a: Advert) {
        val bssid = armedBssid ?: return
        if (!a.mac.equals(bssid, ignoreCase = true)) return
        val s = rssiSource
        synchronized(s) {
            val t = a.timestamp
            s.rssi.addLast(t to a.rssi)
            while (s.rssi.isNotEmpty() && t - s.rssi.first.first > WINDOW_MS) s.rssi.removeFirst()
            if (s.rssi.size < MOTION_MIN_SAMPLES) { s.motion = -1f; s.detail = "${a.rssi} dBm · listening"; Listeners.fire(); return }
            val vals = s.rssi.map { it.second }
            val mean = vals.average()
            val std = sqrt(vals.sumOf { (it - mean) * (it - mean) } / vals.size)
            s.baseline = if (s.baseline.isNaN()) std else minOf(s.baseline + BASELINE_RELEARN, std).coerceAtLeast(0.0)
            val motion = (((std - s.baseline) / RSSI_FULL_SCALE_DB) * 100).coerceIn(0.0, 100.0).toFloat()
            s.push(t, motion, "${a.rssi} dBm")
        }
    }

    /** UI subscribes here; called on any source update. */
    object Listeners {
        private val cbs = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        fun add(cb: () -> Unit) = cbs.add(cb)
        fun remove(cb: () -> Unit) = cbs.remove(cb)
        fun fire() = cbs.forEach { runCatching { it() } }
    }
}
