package ai.kolai.mix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tests for the wave-1 Dsp toolkit additions: trimTrailingSilence, the eased
 * duck release, and firstBeatAtOrAfter. Pure JVM math, no Android deps.
 */
class DspToolkitTest {

    private val sr = Dsp.SR

    private fun sine(n: Int, amp: Float, freqHz: Double = 440.0): FloatArray =
        FloatArray(n) { (amp * sin(2.0 * PI * freqHz * it / sr)).toFloat() }

    // ---- trimTrailingSilence ---------------------------------------------

    @Test
    fun trim_drops_silent_tail_keeping_keepS() {
        // 1 s tone + 1 s silence. Windows are 50 ms aligned to the array end,
        // so the tail boundary lands exactly at sample sr.
        val src = sine(sr, 0.5f) + FloatArray(sr)
        val out = Dsp.trimTrailingSilence(src, sr) // keepS = 0.5
        assertEquals(sr + sr / 2, out.size)
        // Content before the cut is bit-exact.
        for (i in 0 until 1000) {
            assertEquals(src[i], out[i], 0.0f)
        }
    }

    @Test
    fun trim_identity_same_instance_when_no_silent_tail() {
        val src = sine(sr, 0.5f)
        assertSame(src, Dsp.trimTrailingSilence(src, sr))
    }

    @Test
    fun trim_identity_when_tail_shorter_than_keepS() {
        // 0.3 s of silence < keepS 0.5 s -> nothing to drop.
        val src = sine(sr, 0.5f) + FloatArray((0.3f * sr).toInt())
        assertSame(src, Dsp.trimTrailingSilence(src, sr))
    }

    @Test
    fun trim_all_silence_keeps_first_keepS() {
        val src = FloatArray(sr) // 1 s of digital silence
        val out = Dsp.trimTrailingSilence(src, sr)
        assertEquals(sr / 2, out.size)
    }

    @Test
    fun trim_nan_tail_is_treated_as_loud_identity() {
        val src = sine(sr, 0.5f) + FloatArray(sr) { Float.NaN }
        assertSame(src, Dsp.trimTrailingSilence(src, sr))
    }

    @Test
    fun trim_empty_input_identity() {
        val src = FloatArray(0)
        assertSame(src, Dsp.trimTrailingSilence(src, sr))
    }

    @Test
    fun trim_respects_custom_threshold() {
        // Tail at amplitude 0.01: silent for threshold 0.05, loud for 0.003.
        val src = sine(sr, 0.5f) + FloatArray(sr) { 0.01f }
        assertSame(src, Dsp.trimTrailingSilence(src, sr, threshold = 0.003f))
        val out = Dsp.trimTrailingSilence(src, sr, threshold = 0.05f)
        assertEquals(sr + sr / 2, out.size)
    }

    // ---- duck easedReleaseS ----------------------------------------------

    @Test
    fun duck_default_bit_exact_with_explicit_zero_release() {
        val music = sine(2 * sr, 0.5f)
        val voice = FloatArray(sr / 2) { 0.1f }
        val a = Dsp.duck(music, voice, 0.3f)
        val b = Dsp.duck(music, voice, 0.3f, easedReleaseS = 0.0f)
        assertArrayEquals(a, b, 0.0f) // BIT-exact: default == disabled
    }

    @Test
    fun duck_default_recovers_instantly_after_voice() {
        val music = FloatArray(2 * sr) { 0.5f }
        val voice = FloatArray(sr / 2) // zeros so we read the bed gain
        val out = Dsp.duck(music, voice, 0.5f)
        val end = (0.5f * sr).toInt() + voice.size
        // The very first sample after the voice is already back at unity.
        assertEquals(0.5f, out[end], 1e-6f)
    }

    @Test
    fun duck_eased_release_swells_back_smoothly() {
        val music = FloatArray(3 * sr) { 0.5f }
        val voice = FloatArray(sr) // 1 s of zeros
        val startS = 0.5f
        val easedS = 0.7f
        val out = Dsp.duck(music, voice, startS, easedReleaseS = easedS)
        val gain = Math.pow(10.0, -7.0 / 20.0).toFloat()
        val end = (startS * sr).toInt() + voice.size
        val relN = (easedS * sr).toInt()

        // Continuity: release starts at the ducked level...
        assertEquals(0.5f * gain, out[end], 1e-4f)
        // ...passes the smoothstep midpoint...
        val mid = gain + (1.0f - gain) * 0.5f
        assertEquals(0.5f * mid, out[end + relN / 2], 1e-3f)
        // ...and lands back at unity after relN samples.
        assertEquals(0.5f, out[end + relN], 1e-6f)

        // Monotonic non-decreasing recovery on a constant bed.
        for (i in 1 until relN) {
            assertTrue(
                "recovery not monotonic at " + i,
                out[end + i] >= out[end + i - 1] - 1e-7f,
            )
        }
    }

    @Test
    fun duck_eased_release_clamps_at_array_end() {
        // Voice ends 0.1 s before the music does; release wants 0.7 s.
        val music = FloatArray(sr) { 0.5f }
        val voiceLen = (0.4f * sr).toInt()
        val voice = FloatArray(voiceLen)
        val out = Dsp.duck(music, voice, 0.5f, easedReleaseS = 0.7f)
        assertEquals(music.size, out.size)
        // Last sample is still mid-recovery (below unity bed level), no NaN.
        assertTrue(out[out.size - 1] < 0.5f)
        for (v in out) assertFalse(v.isNaN())
    }

    @Test
    fun duck_eased_release_unchanged_before_voice_end() {
        // The eased release must only touch samples AFTER the voice region.
        val music = sine(2 * sr, 0.4f)
        val voice = FloatArray(sr / 4) { 0.05f }
        val plain = Dsp.duck(music, voice, 0.2f)
        val eased = Dsp.duck(music, voice, 0.2f, easedReleaseS = 0.7f)
        val end = (0.2f * sr).toInt() + voice.size
        for (i in 0 until end) {
            assertEquals("sample " + i, plain[i], eased[i], 0.0f)
        }
    }

    // ---- firstBeatAtOrAfter ----------------------------------------------

    @Test
    fun firstBeat_empty_returns_null() {
        assertNull(Dsp.firstBeatAtOrAfter(emptyList(), 1.0))
    }

    @Test
    fun firstBeat_before_first_returns_first() {
        assertEquals(0.5, Dsp.firstBeatAtOrAfter(listOf(0.5, 1.0, 1.5), 0.1)!!, 1e-9)
    }

    @Test
    fun firstBeat_exact_match_is_inclusive() {
        assertEquals(1.0, Dsp.firstBeatAtOrAfter(listOf(0.5, 1.0, 1.5), 1.0)!!, 1e-9)
    }

    @Test
    fun firstBeat_between_beats_returns_next() {
        assertEquals(1.5, Dsp.firstBeatAtOrAfter(listOf(0.5, 1.0, 1.5), 1.2)!!, 1e-9)
    }

    @Test
    fun firstBeat_after_last_returns_null() {
        assertNull(Dsp.firstBeatAtOrAfter(listOf(0.5, 1.0, 1.5), 2.0))
    }

    @Test
    fun firstBeat_nan_t_returns_null() {
        assertNull(Dsp.firstBeatAtOrAfter(listOf(0.5, 1.0), Double.NaN))
    }
}