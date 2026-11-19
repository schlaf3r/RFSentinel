package com.rfsentinel.app.sense

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns a stream of CSI amplitude vectors (one per packet, one value per subcarrier) into a 0..100
 * motion score. A still environment gives a near-constant channel response; a moving body changes the
 * multipath, so the per-subcarrier amplitude wobbles. The score is that wobble, measured against the
 * vector's own recent average and offset by an adaptive quiet baseline so a settled room reads ~0.
 *
 * Pure Kotlin, no Android — unit tested directly.
 */
class CsiMotion(
    /** How fast the reference channel response follows changes (0..1); lower = steadier reference. */
    private val refAlpha: Double = 0.05,
    /** Relative wobble (fraction of mean amplitude) that reads as full-scale motion. */
    private val fullScale: Double = 0.18,
    private val baselineRelearn: Double = 0.0004
) {
    private var ref: DoubleArray? = null
    private var baseline = Double.NaN
    private var smooth = 0.0

    /** CSI as interleaved (imag, real) int pairs, as esp-csi prints them. */
    fun pushIq(iq: IntArray): Float {
        val n = iq.size / 2
        if (n == 0) return lastScore()
        val amp = DoubleArray(n) { sqrt((iq[2 * it].toDouble() * iq[2 * it]) + (iq[2 * it + 1].toDouble() * iq[2 * it + 1])) }
        return pushAmplitudes(amp)
    }

    /** One amplitude value per subcarrier. */
    fun pushAmplitudes(amp: DoubleArray): Float {
        val r = ref
        if (r == null || r.size != amp.size) { ref = amp.copyOf(); return -1f }
        var dev = 0.0; var mag = 0.0
        for (i in amp.indices) {
            dev += abs(amp[i] - r[i]); mag += r[i]
            r[i] += refAlpha * (amp[i] - r[i])
        }
        if (mag <= 0.0) return lastScore()
        val instability = dev / mag                                   // 0 still, grows with motion
        smooth += 0.3 * (instability - smooth)
        baseline = if (baseline.isNaN()) smooth else minOf(baseline + baselineRelearn, smooth).coerceAtLeast(0.0)
        val score = (((smooth - baseline) / fullScale) * 100).coerceIn(0.0, 100.0).toFloat()
        last = score
        return score
    }

    private var last = -1f
    private fun lastScore() = last
}
