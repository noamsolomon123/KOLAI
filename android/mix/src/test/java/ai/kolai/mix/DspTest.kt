package ai.kolai.mix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * Pure-math tests for the FloatArray DSP ported 1:1 from
 * backend/radioai/mixrenderer.py. No Android dependencies, runs on the JVM.
 */
class DspTest {

    private val eps = 1e-5f

    // ---- peakNormalizeClip ----------------------------------------------

    @Test
    fun peakNormalizeClip_clamps_out_of_range_samples() {
        val out = Dsp.peakNormalizeClip(floatArrayOf(-2f, -0.5f, 0.5f, 2f, 1f, -1f))
        assertArrayEquals(floatArrayOf(-1f, -0.5f, 0.5f, 1f, 1f, -1f), out, eps)
    }

    @Test
    fun peakNormalizeClip_leaves_in_range_untouched() {
        val src = floatArrayOf(-1f, 0f, 0.3f, 0.99f)
        assertArrayEquals(src, Dsp.peakNormalizeClip(src), eps)
    }

    // ---- trimSilence ----------------------------------------------------

    @Test
    fun trimSilence_strips_leading_and_trailing_subthreshold() {
        // threshold default 0.01 -> 0.005 samples are silence.
        val src = floatArrayOf(0.001f, 0.005f, 0.5f, -0.2f, 0.8f, 0.002f, 0.0f)
        val out = Dsp.trimSilence(src)
        // first index above threshold = 2 (0.5), last = 4 (0.8) -> [2..4] inclusive
        assertArrayEquals(floatArrayOf(0.5f, -0.2f, 0.8f), out, eps)
    }

    @Test
    fun trimSilence_all_silent_returns_unchanged() {
        val src = floatArrayOf(0.001f, -0.002f, 0.003f)
        val out = Dsp.trimSilence(src)
        assertArrayEquals(src, out, eps)
    }

    @Test
    fun trimSilence_no_silence_returns_full_range() {
        val src = floatArrayOf(0.5f, -0.6f, 0.7f)
        val out = Dsp.trimSilence(src)
        assertArrayEquals(src, out, eps)
    }

    // ---- equalPowerCrossfade --------------------------------------------

    @Test
    fun crossfade_output_length_is_lenA_plus_lenB_minus_n() {
        val sr = Dsp.SR
        val a = FloatArray(sr) { 0.5f }      // 1 s
        val b = FloatArray(sr) { 0.5f }      // 1 s
        val overlapS = 0.25f
        val n = (overlapS * sr).toInt()
        val out = Dsp.equalPowerCrossfade(a, b, overlapS)
        assertEquals(a.size + b.size - n, out.size)
    }

    @Test
    fun crossfade_zero_overlap_is_concatenation() {
        val a = floatArrayOf(0.1f, 0.2f, 0.3f)
        val b = floatArrayOf(0.4f, 0.5f)
        val out = Dsp.equalPowerCrossfade(a, b, 0f)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f), out, eps)
    }

    @Test
    fun crossfade_equal_power_preserves_constant_energy_across_blend() {
        // Equal-power: at every point in the overlap, fadeOut^2 + fadeIn^2 == 1.
        val n = 64
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1).toFloat() // linspace(0,1,n)
            val fadeOut = cos(t * Math.PI.toFloat() / 2f)
            val fadeIn = cos((1f - t) * Math.PI.toFloat() / 2f)
            val sumSq = fadeOut * fadeOut + fadeIn * fadeIn
            assertEquals("equal-power at i=" + i, 1.0f, sumSq, 1e-4f)
        }

        // And the actual mixed region of two constant signals follows the ramp.
        val sr = Dsp.SR
        val a = FloatArray(sr) { 1.0f }
        val b = FloatArray(sr) { 1.0f }
        val overlapS = 0.1f
        val nn = (overlapS * sr).toInt()
        val out = Dsp.equalPowerCrossfade(a, b, overlapS)
        // The mixed region starts at index a.size - nn.
        val mixStart = a.size - nn
        for (i in 0 until nn) {
            val t = i.toFloat() / (nn - 1).toFloat()
            val expected = cos(t * Math.PI.toFloat() / 2f) + cos((1f - t) * Math.PI.toFloat() / 2f)
            val clipped = if (expected > 1f) 1f else expected
            assertEquals("mix sample " + i, clipped, out[mixStart + i], 1e-4f)
        }
    }

    // ---- duck -----------------------------------------------------------

    @Test
    fun duck_attenuates_steady_region_to_linear_gain_and_overlays_voice() {
        val sr = Dsp.SR
        val music = FloatArray(sr) { 0.5f } // 1 s of constant tone
        // voice present only in a short window, valued 0 so we can read the duck gain.
        val voiceLen = (0.6f * sr).toInt()
        val voice = FloatArray(voiceLen) { 0.0f }
        val startS = 0.2f
        val attenDb = -7.0f
        val rampS = 0.3f
        val out = Dsp.duck(music, voice, startS, attenuationDb = attenDb, rampS = rampS)

        val gain = Math.pow(10.0, attenDb / 20.0).toFloat()
        val start = (startS * sr).toInt()
        val ramp = (rampS * sr).toInt()
        val end = minOf(music.size, start + voice.size)
        // steady region: after the ramp, before end -> music * gain + voice(0) == 0.5 * gain
        val steadyIdx = minOf(start + ramp, end) + 100
        if (steadyIdx < end) {
            assertEquals(0.5f * gain, out[steadyIdx], 1e-4f)
        }

        // before the duck starts, music is untouched.
        assertEquals(0.5f, out[start - 1], eps)
        // after the voice ends, music is untouched again.
        if (end < music.size) {
            assertEquals(0.5f, out[end + 1], eps)
        }
    }

    @Test
    fun duck_overlays_voice_samples() {
        val sr = Dsp.SR
        val music = FloatArray(sr) { 0.0f } // silent bed so we read the voice directly
        val voice = FloatArray((0.5f * sr).toInt()) { 0.3f }
        val startS = 0.1f
        val out = Dsp.duck(music, voice, startS, attenuationDb = -7.0f, rampS = 0.3f)
        val start = (startS * sr).toInt()
        // Well inside the steady region the overlaid voice (0.3) dominates the
        // ducked-to-zero music bed.
        val idx = start + (0.4f * sr).toInt()
        assertEquals(0.3f, out[idx], 1e-4f)
    }

    @Test
    fun duck_ramp_starts_at_unity_gain() {
        val sr = Dsp.SR
        val music = FloatArray(sr) { 0.5f }
        val voice = FloatArray((0.6f * sr).toInt()) { 0.0f }
        val startS = 0.2f
        val out = Dsp.duck(music, voice, startS, attenuationDb = -7.0f, rampS = 0.3f)
        val start = (startS * sr).toInt()
        // i == start -> f == 0 -> gain factor (1-0)+0*g == 1.0 -> 0.5 unchanged + voice(0)
        assertEquals(0.5f, out[start], 1e-4f)
    }

    // ---- startOnBeat ----------------------------------------------------

    @Test
    fun startOnBeat_trims_to_first_beat_when_within_skip_window() {
        val sr = Dsp.SR
        val audio = FloatArray(2 * sr) { it.toFloat() } // 2 s ramp so the cut lands inside
        val firstBeat = 1.0 // seconds, within 4 s window; start = sr < audio.size
        val out = Dsp.startOnBeat(audio, listOf(firstBeat, 2.0, 3.0))
        val start = (firstBeat * sr).toInt()
        assertEquals(audio.size - start, out.size)
        assertEquals(audio[start], out[0], eps)
    }

    @Test
    fun startOnBeat_noop_when_no_beats() {
        val audio = floatArrayOf(0.1f, 0.2f, 0.3f)
        val out = Dsp.startOnBeat(audio, emptyList())
        assertArrayEquals(audio, out, eps)
    }

    @Test
    fun startOnBeat_noop_when_first_beat_beyond_max_skip() {
        val sr = Dsp.SR
        val audio = FloatArray(sr) { 0.1f }
        val out = Dsp.startOnBeat(audio, listOf(5.0)) // > 4 s default
        assertEquals(audio.size, out.size)
    }

    @Test
    fun startOnBeat_noop_when_first_beat_is_zero() {
        val audio = floatArrayOf(0.1f, 0.2f, 0.3f)
        val out = Dsp.startOnBeat(audio, listOf(0.0, 1.0))
        assertArrayEquals(audio, out, eps)
    }

    @Test
    fun startOnBeat_respects_custom_max_skip() {
        val sr = Dsp.SR
        val audio = FloatArray(sr) { 0.1f }
        // first beat 3 s, maxSkip 2 s -> no-op
        val out = Dsp.startOnBeat(audio, listOf(3.0), sr = sr, maxSkipS = 2.0f)
        assertEquals(audio.size, out.size)
    }

    // ---- snapOverlapToBeats ---------------------------------------------

    @Test
    fun snapOverlapToBeats_rounds_to_whole_beats() {
        val bpm = 120.0f // beat = 0.5 s
        // overlap 1.1 s / 0.5 = 2.2 -> round 2 -> 1.0 s
        assertEquals(1.0, Dsp.snapOverlapToBeats(1.1f, bpm).toDouble(), 1e-6)
        // overlap 1.3 s / 0.5 = 2.6 -> round 3 -> 1.5 s
        assertEquals(1.5, Dsp.snapOverlapToBeats(1.3f, bpm).toDouble(), 1e-6)
    }

    @Test
    fun snapOverlapToBeats_minimum_one_beat() {
        val bpm = 120.0f // beat = 0.5 s
        // overlap 0.1 s / 0.5 = 0.2 -> round 0 -> max(1,0) = 1 beat -> 0.5 s
        assertEquals(0.5, Dsp.snapOverlapToBeats(0.1f, bpm).toDouble(), 1e-6)
    }

    @Test
    fun snapOverlapToBeats_noop_for_nonpositive_bpm() {
        assertEquals(1.234, Dsp.snapOverlapToBeats(1.234f, 0.0f).toDouble(), 1e-6)
        assertEquals(1.234, Dsp.snapOverlapToBeats(1.234f, -10.0f).toDouble(), 1e-6)
    }

    // ---- guard: equal-power sum-of-squares sanity in isolation ----------

    @Test
    fun equalPower_gains_sum_of_squares_near_one() {
        val n = 100
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1).toDouble()
            val fo = cos(t * Math.PI / 2)
            val fi = cos((1 - t) * Math.PI / 2)
            assertTrue("sumSq near 1 at " + i, abs(fo * fo + fi * fi - 1.0) < 1e-9)
        }
    }
}

