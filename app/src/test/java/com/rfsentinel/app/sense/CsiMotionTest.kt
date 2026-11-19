package com.rfsentinel.app.sense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/** CsiMotion reads ~0 on a still channel and rises when the subcarrier amplitudes start moving. */
class CsiMotionTest {

    private fun ampVec(base: DoubleArray, jitter: Double, rnd: Random) =
        DoubleArray(base.size) { base[it] + (rnd.nextDouble() - 0.5) * 2 * jitter }

    @Test fun stillChannelReadsLow() {
        val m = CsiMotion()
        val base = DoubleArray(52) { 20.0 + 5 * sin(it.toDouble()) }
        val rnd = Random(1)
        var last = 0f
        repeat(400) { last = m.pushAmplitudes(ampVec(base, 0.2, rnd)) }
        assertTrue("still channel should read low, was $last", last in 0f..15f)
    }

    @Test fun motionRaisesScore() {
        val m = CsiMotion()
        val base = DoubleArray(52) { 20.0 + 5 * sin(it.toDouble()) }
        val rnd = Random(2)
        repeat(400) { m.pushAmplitudes(ampVec(base, 0.2, rnd)) }   // settle quiet baseline
        var peak = 0f
        repeat(200) { peak = maxOf(peak, m.pushAmplitudes(ampVec(base, 6.0, rnd))) }  // big wobble = motion
        assertTrue("motion should raise the score, peak was $peak", peak > 40f)
    }

    @Test fun firstVectorPrimesReference() {
        val m = CsiMotion()
        assertEquals(-1f, m.pushAmplitudes(DoubleArray(8) { 10.0 }), 0.001f)
    }

    @Test fun iqAmplitudePairing() {
        val m = CsiMotion()
        // 3-4-5 triangle: amplitude 5 per subcarrier.
        assertEquals(-1f, m.pushIq(intArrayOf(3, 4, 3, 4)), 0.001f)  // primes ref, two subcarriers
    }
}
