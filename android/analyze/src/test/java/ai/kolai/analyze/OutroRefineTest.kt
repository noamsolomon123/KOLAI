package ai.kolai.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure-JVM tests for refineOutroStart: energy-based refinement of the
 * analyzer's crude duration-based outro hint against silent music-video tails.
 * Uses sr=8000 to keep the synthetic arrays small.
 */
class OutroRefineTest {

    private val sr = 8000

    private fun tone(seconds: Double, amp: Float = 0.5f, freqHz: Double = 440.0): FloatArray =
        FloatArray((seconds * sr).toInt()) { (amp * sin(2.0 * PI * freqHz * it / sr)).toFloat() }

    private fun silence(seconds: Double): FloatArray = FloatArray((seconds * sr).toInt())

    /** The analyzer's hint formula: max(d - 8, d * 0.9). */
    private fun hint(durationS: Double): Double = maxOf(durationS - 8.0, durationS * 0.9)

    // ---- refinement ---------------------------------------------------------

    @Test
    fun silent_tail_moves_outro_to_the_real_decay_point() {
        // 30 s of music + 10 s of dead music-video tail. Hint = max(32, 36) = 36
        // points into silence; the music actually ends at 30 s.
        val audio = tone(30.0) + silence(10.0)
        val h = hint(40.0)
        val refined = refineOutroStart(audio, sr, h)
        assertEquals(30.0, refined, 1e-6)
    }

    @Test
    fun refinement_is_clamped_to_hint_minus_12s() {
        // Music ends at 10 s but duration says 40 -> hint 36. The decay point
        // (10 s) is absurdly early; clamp to 36 - 12 = 24.
        val audio = tone(10.0) + silence(30.0)
        val refined = refineOutroStart(audio, sr, hint(40.0))
        assertEquals(24.0, refined, 1e-6)
    }

    @Test
    fun fadeout_decay_lands_inside_the_fade() {
        // 30 s tone, 5 s linear fade to zero, 5 s silence. The sustained-energy
        // decay (20% of median RMS) sits late in the fade, before the silence.
        val body = tone(30.0)
        val fadeLen = (5.0 * sr).toInt()
        val fade = FloatArray(fadeLen) { i ->
            val amp = 0.5f * (1.0f - i.toFloat() / fadeLen)
            (amp * sin(2.0 * PI * 440.0 * i / sr)).toFloat()
        }
        val audio = body + fade + silence(5.0)
        val refined = refineOutroStart(audio, sr, hint(40.0))
        assertTrue("refined=" + refined, refined >= 33.0 && refined <= 35.5)
    }

    // ---- inconclusive -> hint unchanged -------------------------------------

    @Test
    fun music_to_the_very_end_keeps_hint() {
        val audio = tone(40.0)
        val h = hint(40.0)
        assertEquals(h, refineOutroStart(audio, sr, h), 0.0)
    }

    @Test
    fun mid_song_quiet_gap_does_not_trigger() {
        // A 1 s breakdown in the middle is NOT a tail; energy returns after it.
        val audio = tone(20.0) + silence(1.0) + tone(19.0)
        val h = hint(40.0)
        assertEquals(h, refineOutroStart(audio, sr, h), 0.0)
    }

    @Test
    fun all_silence_keeps_hint() {
        val audio = silence(40.0)
        assertEquals(36.0, refineOutroStart(audio, sr, 36.0), 0.0)
    }

    @Test
    fun too_short_audio_keeps_hint() {
        // 1 s at 0.5 s windows -> 2 windows < 4 -> inconclusive.
        val audio = tone(1.0)
        assertEquals(0.9, refineOutroStart(audio, sr, 0.9), 0.0)
    }

    @Test
    fun empty_audio_keeps_hint() {
        assertEquals(36.0, refineOutroStart(FloatArray(0), sr, 36.0), 0.0)
    }

    @Test
    fun bad_params_keep_hint() {
        val audio = tone(40.0)
        assertEquals(36.0, refineOutroStart(audio, 0, 36.0), 0.0)
        assertEquals(36.0, refineOutroStart(audio, sr, 36.0, windowS = 0.0f), 0.0)
        assertTrue(refineOutroStart(audio, sr, Double.NaN).isNaN())
    }

    @Test
    fun nan_samples_keep_hint() {
        val audio = tone(30.0) + FloatArray(10 * sr) { Float.NaN }
        assertEquals(36.0, refineOutroStart(audio, sr, 36.0), 0.0)
    }
}