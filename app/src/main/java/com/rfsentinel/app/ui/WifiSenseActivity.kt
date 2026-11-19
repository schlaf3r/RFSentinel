package com.rfsentinel.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.sense.CsiNodes
import com.rfsentinel.app.sense.WifiSense

/**
 * WiFi Sense live view: the armed access point's RSSI motion plus every ESP32 CSI node, each as a
 * labelled motion bar and a scrolling trace. Receive-only; it transmits nothing. Add CSI nodes here or
 * in Settings › WiFi Sense.
 */
class WifiSenseActivity : AppCompatActivity() {

    companion object { const val EXTRA_MAC = "mac" }

    private lateinit var container: LinearLayout
    private val rows = LinkedHashMap<String, SourceRow>()
    private val onUpdate = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as? com.rfsentinel.app.RFSentinelApp)?.applyOrientation(this)
        title = "WiFi Sense"
        val dp = resources.displayMetrics.density
        val root = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        root.addView(container)
        setContentView(root)

        val headline = when {
            !WifiSense.isArmed -> "Add CSI nodes below to sense motion"
            WifiSense.armedChannel > 0 -> "Sensing motion on channel ${WifiSense.armedChannel}"
            else -> "Sensing motion on the selected network"
        }
        container.addView(TextView(this).apply { text = headline; textSize = 16f })
        container.addView(TextView(this).apply {
            text = "Receive-only: motion is read from signal already in the air, nothing is transmitted. " +
                "RSSI motion is coarse; CSI nodes are finer."
            alpha = 0.7f; textSize = 12f
            setPadding(0, (4 * dp).toInt(), 0, (12 * dp).toInt())
        })

        container.addView(Button(this).apply {
            text = "Add CSI node"
            setOnClickListener { addNodeDialog() }
        })
        rebuildRows()
    }

    override fun onResume() { super.onResume(); WifiSense.Listeners.add(onUpdate); refresh() }
    override fun onPause() { super.onPause(); WifiSense.Listeners.remove(onUpdate) }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the fast-RSSI draw on the armed AP when leaving (nodes keep running in the background).
        if (isFinishing) WifiSense.disarm()
    }

    private class SourceRow(val view: LinearLayout, val label: TextView, val meter: MeterView)

    private fun rebuildRows() {
        // Drop rows whose source is gone.
        val ids = WifiSense.sources().map { it.id }.toSet()
        rows.keys.filter { it !in ids }.forEach { rows.remove(it)?.let { r -> container.removeView(r.view) } }
        val dp = resources.displayMetrics.density
        for (s in WifiSense.sources()) {
            val row = rows.getOrPut(s.id) {
                val label = TextView(this).apply { textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
                val meter = MeterView(this)
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (12 * dp).toInt(), 0, 0)
                    addView(label)
                    addView(meter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (64 * dp).toInt()))
                }
                if (s.kind == WifiSense.Source.Kind.CSI) box.setOnLongClickListener { nodeMenu(s.id, s.label); true }
                container.addView(box)
                SourceRow(box, label, meter)
            }
            row.meter.source = s
        }
    }

    private fun refresh() {
        rebuildRows()
        for (s in WifiSense.sources()) {
            val row = rows[s.id] ?: continue
            val kind = if (s.kind == WifiSense.Source.Kind.CSI) "CSI" else "RSSI"
            val m = if (s.motion < 0f) "—" else s.motion.toInt().toString()
            row.label.text = "${s.label}  ·  $kind  ·  $m   ${s.detail}"
            row.meter.invalidate()
        }
    }

    private fun addNodeDialog() {
        val dp = resources.displayMetrics.density
        val name = EditText(this).apply { hint = "Name (e.g. Living room)" }
        val host = EditText(this).apply { hint = "IP or host (e.g. 192.168.1.50)"; inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val port = EditText(this).apply { hint = "Port (default 3333)"; inputType = InputType.TYPE_CLASS_NUMBER }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(name); addView(host); addView(port)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Add CSI node")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val h = host.text.toString().trim()
                if (h.isEmpty()) { toast("Enter the node's IP or host"); return@setPositiveButton }
                CsiNodes.addNode(this, name.text.toString().trim(), h, port.text.toString().trim().toIntOrNull() ?: 3333)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun nodeMenu(id: String, label: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setItems(arrayOf("Remove node")) { _, _ -> CsiNodes.removeNode(id); refresh() }
            .show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    /** A motion bar plus a scrolling trace of recent motion for one source. */
    class MeterView(ctx: android.content.Context) : View(ctx) {
        var source: WifiSense.Source? = null
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 128, 128, 128) }

        private fun colorFor(m: Float): Int = when {
            m < 0f -> Color.GRAY
            m < 20f -> Color.rgb(0x3d, 0xdc, 0x97)
            m < 55f -> Color.rgb(0xf5, 0xc5, 0x42)
            else -> Color.rgb(0xff, 0x5c, 0x5c)
        }

        override fun onDraw(canvas: Canvas) {
            val s = source ?: return
            val w = width.toFloat(); val h = height.toFloat()
            val barH = h * 0.42f
            canvas.drawRect(0f, 0f, w, barH, track)
            val m = s.motion.coerceAtLeast(0f)
            bar.color = colorFor(s.motion)
            canvas.drawRect(0f, 0f, w * (m / 100f), barH, bar)
            // Trace of the last points across the lower band.
            val pts = s.points()
            if (pts.size >= 2) {
                val t0 = pts.first().t.toFloat(); val span = (pts.last().t - pts.first().t).coerceAtLeast(1).toFloat()
                val top = barH + 6f; val bot = h
                line.color = colorFor(s.motion)
                var px = 0f; var py = bot
                pts.forEachIndexed { i, p ->
                    val x = (p.t - t0) / span * w
                    val y = bot - (p.motion.coerceIn(0f, 100f) / 100f) * (bot - top)
                    if (i > 0) canvas.drawLine(px, py, x, y, line)
                    px = x; py = y
                }
            }
        }
    }
}
