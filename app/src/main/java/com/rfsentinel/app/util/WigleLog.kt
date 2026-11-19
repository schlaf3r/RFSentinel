package com.rfsentinel.app.util

import android.content.Context
import android.location.Location
import android.os.Build
import android.util.Log
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.detect.Advert
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings > Data > Record a WiGLE log (off by default): while scanning, every WiFi access point this phone
 * and its hardware hear (the phone's own WiFi scan, a USB WiFi adapter in monitor mode, an ESP32 board),
 * and optionally Bluetooth LE devices, written with this phone's position to a WiGLE upload file
 * (WigleWifi-1.6 CSV, [WigleCsv]) in the app's private storage, ready to upload to wigle.net
 * ([com.rfsentinel.app.online.WigleUpload]) or share.
 *
 * Only with a fresh, precise fix (the one used to place devices on the map). Client devices (laptops,
 * phones) are never written, nor networks named *_nomap, nor anything in the user's whitelist. An
 * access point gets a new row when this phone has moved since its last one, or when it's heard much
 * louder, so WiGLE can work out where it is; at most one row per access point every few seconds.
 * One file per scan; Upload or Share closes the file being written and starts a new one.
 */
object WigleLog {

    private const val TAG = "WigleLog"
    private const val DIR = "wigle"
    private const val UPLOADED = "uploaded"
    /** A new row for an access point after this long, once this phone has moved [MOVED_M]... */
    private const val AGAIN_MS = 15_000L
    private const val MOVED_M = 20f
    /** ...or sooner when it's heard this much louder than its loudest row so far. */
    private const val LOUDER_DB = 6
    private const val LOUDER_AGAIN_MS = 5_000L
    private const val FLUSH_MS = 10_000L
    private const val FLUSH_ROWS = 50

    /** Settings > Data switches, read when the scan starts and changed live from Settings. */
    @Volatile var enabled = false
    @Volatile var bluetooth = false

    private class Seen(var at: Long, var lat: Double, var lon: Double, var loudest: Int)

    private var file: File? = null
    private var out: Writer? = null
    private val seen = HashMap<String, Seen>()
    private var rowsInFile = 0
    private var unflushed = 0
    private var flushedAt = 0L

    /** Networks and devices in the file being written now, for the status line. */
    @Volatile var inFile = 0; private set

    fun load(context: Context) {
        enabled = Prefs.wigleLog(context)
        bluetooth = Prefs.wigleLogBluetooth(context)
    }

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
    private fun uploadedDir(context: Context): File = File(dir(context), UPLOADED).apply { mkdirs() }

    /**
     * What one device heard by this phone's own radios or hardware gets: a row, when the log is on and
     * [loc] (a fresh, precise fix) is known. [whitelisted]: in the user's whitelist, never written.
     * Called from the scan pipeline's thread.
     */
    @Synchronized
    fun onAdvert(context: Context, a: Advert, loc: Location?, whitelisted: Boolean) {
        if (!enabled || loc == null || whitelisted) return
        val row = when {
            a.isWifi -> {
                val w = a.wifi ?: return
                if (w.client || w.frequencyMhz <= 0 || WigleCsv.optedOut(a.name)) return
                WigleCsv.Row(a.mac, a.name, w.capabilities.ifBlank { "[ESS]" }, a.timestamp, w.frequencyMhz, a.rssi,
                    loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else 0.0,
                    if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0, null, WigleCsv.Type.WIFI)
            }
            a.isBle && bluetooth -> WigleCsv.Row(a.mac, a.name, "Misc", a.timestamp, null, a.rssi,
                loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else 0.0,
                if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0, a.manufacturerData.keys.firstOrNull(), WigleCsv.Type.BLE)
            else -> return
        }
        val key = row.type.name + row.mac
        val s = seen[key]
        if (s != null) {
            val dt = a.timestamp - s.at
            val moved = FloatArray(1).also { Location.distanceBetween(s.lat, s.lon, loc.latitude, loc.longitude, it) }[0]
            val again = (dt >= AGAIN_MS && moved >= MOVED_M) || (dt >= LOUDER_AGAIN_MS && a.rssi >= s.loudest + LOUDER_DB)
            if (!again) return
        }
        try {
            val w = out ?: open(context)
            w.write(WigleCsv.row(row))
            if (s == null) { seen[key] = Seen(a.timestamp, loc.latitude, loc.longitude, a.rssi); inFile = seen.size }
            else { s.at = a.timestamp; s.lat = loc.latitude; s.lon = loc.longitude; s.loudest = maxOf(s.loudest, a.rssi) }
            rowsInFile++
            unflushed++
            val now = System.currentTimeMillis()
            if (unflushed >= FLUSH_ROWS || now - flushedAt >= FLUSH_MS) { w.flush(); unflushed = 0; flushedAt = now }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't write the WiGLE log: ${e.javaClass.simpleName}")
            closeQuietly()
        }
    }

    private fun open(context: Context): Writer {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir(context), "WigleWifi_RFSentinel_$stamp.csv")
        val w = f.outputStream().buffered().writer(Charsets.UTF_8)
        w.write(WigleCsv.header("RFSentinel-${BuildConfig.VERSION_NAME}", Build.MODEL, Build.VERSION.RELEASE,
            Build.DEVICE, Build.DISPLAY, Build.BOARD, Build.BRAND))
        file = f; out = w; rowsInFile = 0; unflushed = 0; flushedAt = System.currentTimeMillis()
        seen.clear(); inFile = 0
        return w
    }

    /** Closes the file being written (end of a scan, or before an upload or share); the next row starts a new one. */
    @Synchronized
    fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { out?.flush(); out?.close() }
        val f = file
        // A file with only its header has nothing for WiGLE.
        if (f != null && rowsInFile == 0) f.delete()
        out = null; file = null; seen.clear(); inFile = 0; rowsInFile = 0
    }

    /** Files ready to upload: written, closed (call [close] first), not uploaded yet; oldest first. */
    fun pending(context: Context): List<File> {
        val open = synchronized(this) { file }
        return dir(context).listFiles { f -> f.isFile && f.name.endsWith(".csv") && f != open }
            .orEmpty().sortedBy { it.name }
    }

    /** Moves an uploaded [f] out of the waiting list; uploaded files are kept until [deleteUploaded]. */
    fun markUploaded(context: Context, f: File) {
        val to = File(uploadedDir(context), f.name)
        if (!f.renameTo(to)) { f.copyTo(to, overwrite = true); f.delete() }
    }

    fun uploadedCount(context: Context): Int = uploadedDir(context).listFiles()?.size ?: 0

    fun deleteUploaded(context: Context) { uploadedDir(context).listFiles()?.forEach { it.delete() } }

    /** Rows in a WiGLE file (lines after the two header lines). */
    fun rows(f: File): Int = runCatching { f.useLines { l -> (l.count() - 2).coerceAtLeast(0) } }.getOrDefault(0)

    /** "2 files waiting (1,234 rows) · 3 uploaded · now writing: 57 networks". */
    fun status(context: Context): String {
        val waiting = pending(context)
        val rows = waiting.sumOf { rows(it) }
        val parts = mutableListOf(
            if (waiting.isEmpty()) "Nothing waiting to upload"
            else "${waiting.size} file${if (waiting.size == 1) "" else "s"} waiting (${"%,d".format(rows)} rows)")
        uploadedCount(context).takeIf { it > 0 }?.let { parts += "$it uploaded" }
        if (enabled && inFile > 0) parts += "this scan so far: $inFile"
        return parts.joinToString(" · ")
    }
}
