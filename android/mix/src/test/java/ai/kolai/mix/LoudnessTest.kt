package ai.kolai.mix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio-quality DSP tests: RMS loudness normalization (normalizeLoudness),
 * the soft-knee safety limiter (softClip) and edge micro-fades
 * (microFadeEdges). Pure JVM math, no Android dependencies.
 */
class LoudnessTest {

    private fun sine(n: Int, amp: Float, freqHz: Double = 440.0): FloatArray =
        FloatArray(n) { (amp * sin(2.0 * Math.PI * freqHz * it / Dsp.SR)).toFloat() }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from)).toFloat()
    }

    // ---- normalizeLoudness ------------------------------------------------

    @Test
    fun normalize_quiet_sine_gains_up_to_target_rms() {
        // amp 0.05 -> rms ~0.0354 -> wanted gain ~2.26 (inside +/-4x).
        val out = Dsp.normalizeLoudness(sine(Dsp.SR, 0.05f), targetRms = 0.08f, maxGain = 4.0f)
        assertEquals(0.08f, rms(out), 1e-3f)
    }

    @Test
    fun normalize_loud_sine_attenuates_to_target_rms() {
        // amp 0.2 -> rms ~0.1414 -> wanted gain ~0.566 (inside +/-4x).
        val out = Dsp.normalizeLoudness(sine(Dsp.SR, 0.2f), targetRms = 0.08f, maxGain = 4.0f)
        assertEquals(0.08f, rms(out), 1e-3f)
    }

    @Test
    fun normalize_boost_is_clamped_at_maxGain() {
        // amp 0.005 -> rms ~0.00354 -> wanted gain ~22.6 -> clamped to 4.
        val src = sine(Dsp.SR, 0.005f)
        val out = Dsp.normalizeLoudness(src, targetRms = 0.08f, maxGain = 4.0f)
        assertEquals(4.0f * rms(src), rms(out), 1e-3f)
        assertTrue("clamped boost must stay below target", rms(out) < 0.08f)
    }

    @Test
    fun normalize_cut_is_clamped_at_inverse_maxGain() {
        // amp 0.9 -> rms ~0.636 -> wanted gain ~0.126 -> clamped to 1/4.
        val src = sine(Dsp.SR, 0.9f)
        val out = Dsp.normalizeLoudness(src, targetRms = 0.08f, maxGain = 4.0f)
        assertEquals(0.25f * rms(src), rms(out), 1e-3f)
        assertTrue("clamped cut must stay above target", rms(out) > 0.08f)
    }

    @Test
    fun normalize_window_is_middle_60_percent_ignoring_silent_edges() {
        // Silent first/last 20%, sine (amp 0.16, rms ~0.113) in the middle.
        // Windowed rms = 0.113 -> gain ~0.707 -> middle lands at 0.08.
        // A FULL-array rms (~0.0876) would give gain ~0.913 -> middle ~0.103,
        // outside the tolerance below - so this asserts the window too.
        val n = 100_000
        val src = FloatArray(n)
        val mid = sine(60_000, 0.16f)
        for (i in 0 until 60_000) src[20_000 + i] = mid[i]
        val out = Dsp.normalizeLoudness(src, targetRms = 0.08f, maxGain = 4.0f)
        assertEquals(0.08f, rms(out, 20_000, 80_000), 1e-3f)
        // edges stay silent
        assertEquals(0.0f, out[0], 0.0f)
        assertEquals(0.0f, out[n - 1], 0.0f)
    }

    @Test
    fun normalize_all_silence_is_identity_no_nan() {
        val out = Dsp.normalizeLoudness(FloatArray(1000), targetRms = 0.08f, maxGain = 4.0f)
        assertEquals(1000, out.size)
        for (v in out) {
            assertFalse("no NaN on silence", v.isNaN())
            assertEquals(0.0f, v, 0.0f)
        }
    }

    @Test
    fun normalize_boosted_peaks_are_soft_limited_not_hard_clipped() {
        // Quiet body (rms drives a 4x boost) + a single hot transient at 0.5:
        // boosted transient would be 2.0 -> the soft knee must keep it < 1.
        val src = sine(Dsp.SR, 0.02f)
        src[Dsp.SR / 2] = 0.5f
        val out = Dsp.normalizeLoudness(src, targetRms = 0.08f, maxGain = 4.0f)
        assertTrue("peak must be soft-limited to <= 1", abs(out[Dsp.SR / 2]) <= 1.0f)
        assertTrue("peak must still rise above the knee", abs(out[Dsp.SR / 2]) > 0.95f)
    }

    @Test
    fun normalize_empty_input_returns_empty() {
        assertEquals(0, Dsp.normalizeLoudness(FloatArray(0)).size)
    }

    // ---- softClip -----------------------------------------------------------

    @Test
    fun softClip_is_bit_exact_identity_below_threshold() {
        val src = floatArrayOf(-0.95f, -0.5f, -0.001f, 0.0f, 0.3f, 0.9f, 0.95f)
        assertArrayEquals(src, Dsp.softClip(src, threshold = 0.95f), 0.0f)
    }

    @Test
    fun softClip_is_monotonic_and_bounded_above_threshold() {
        val n = 500
        val src = FloatArray(n) { it * 3.0f / (n - 1) } // 0 .. 3.0
        val out = Dsp.softClip(src, threshold = 0.95f)
        for (i in 1 until n) {
            assertTrue("monotonic at $i", out[i] >= out[i - 1])
        }
        for (v in out) {
            assertTrue("bounded at 1.0", v <= 1.0f)
        }
        // huge overshoot saturates at full scale (never beyond)
        assertEquals(1.0f, Dsp.softClip(floatArrayOf(10f))[0], 1e-3f)
    }

    @Test
    fun softClip_is_odd_symmetric() {
        val pos = floatArrayOf(0.96f, 1.0f, 1.5f, 3.0f)
        val neg = FloatArray(pos.size) { -pos[it] }
        val outP = Dsp.softClip(pos)
        val outN = Dsp.softClip(neg)
        for (i in pos.indices) {
            assertEquals(outP[i], -outN[i], 0.0f)
        }
    }

    @Test
    fun softClip_is_continuous_at_the_knee() {
        // Just below the knee is identity; just above barely differs.
        val out = Dsp.softClip(floatArrayOf(0.95f, 0.950001f))
        assertEquals(0.95f, out[0], 0.0f)
        assertEquals(0.95f, out[1], 1e-4f)
    }

    // ---- microFadeEdges ------------------------------------------------------

    @Test
    fun microFade_endpoints_are_zero_interior_untouched() {
        val n = Dsp.SR // 1 s of full-scale
        val out = Dsp.microFadeEdges(FloatArray(n) { 1.0f }, fadeS = 0.010f)
        val fadeN = (0.010f * Dsp.SR).toInt()
        assertEquals(0.0f, out[0], 0.0f)
        assertEquals(0.0f, out[n - 1], 0.0f)
        // ramps are strictly inside [0, 1)
        assertTrue(out[1] > 0.0f && out[1] < 1.0f)
        // beyond the fade windows: untouched
        assertEquals(1.0f, out[fadeN], 0.0f)
        assertEquals(1.0f, out[n - 1 - fadeN], 0.0f)
        assertEquals(1.0f, out[n / 2], 0.0f)
    }

    @Test
    fun microFade_clamps_to_half_length_on_tiny_arrays() {
        val out = Dsp.microFadeEdges(floatArrayOf(1f, 1f, 1f, 1f, 1f), fadeS = 0.010f)
        // n = min(441, 5/2 = 2) -> 2-sample fades each side; middle untouched
        assertEquals(0.0f, out[0], 0.0f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(1.0f, out[2], 0.0f)
        assertEquals(0.5f, out[3], 1e-6f)
        assertEquals(0.0f, out[4], 0.0f)
    }

    @Test
    fun microFade_does_not_mutate_input() {
        val src = FloatArray(100) { 1.0f }
        Dsp.microFadeEdges(src)
        for (v in src) assertEquals(1.0f, v, 0.0f)
    }
}