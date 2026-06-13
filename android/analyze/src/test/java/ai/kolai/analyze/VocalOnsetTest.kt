package ai.kolai.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure-JVM tests for VocalOnset: a heuristic vocal-entry detector that keeps
 * the DJ from talking over a singer.
 *
 * "Instrumental" is simulated by a low 150 Hz tone (sub-band, attenuated by the
 * 250 Hz high-pass). "Vocal" is a mid-band mix (1 kHz + 2.5 kHz, the heart of
 * the 250 Hz-4 kHz vocal band). The detector should treat the mid-band content
 * as vocals and the low tone as instrumental runway.
 *
 * sr = 16000 keeps the synthetic arrays small while leaving headroom above the
 * 4 kHz low-pass corner.
 */
class VocalOnsetTest {

    private val sr = 16000

    private fun samples(seconds: Double): Int = (seconds * sr).toInt()

    /** A pure tone of the given length/freq/amplitude. */
    private fun tone(seconds: Double, freqHz: Double, amp: Double = 0.5): FloatArray =
        FloatArray(samples(seconds)) { (amp * sin(2.0 * PI * freqHz * it / sr)).toFloat() }

    /** Low-frequency "instrumental" bed (below the 250 Hz high-pass corner). */
    private fun instrumental(seconds: Double, amp: Double = 0.5): FloatArray =
        tone(seconds, 150.0, amp)

    /** Mid-band "vocal": 1 kHz fundamental plus a 2.5 kHz overtone. */
    private fun vocal(seconds: Double, amp: Double = 0.5): FloatArray {
        val n = samples(seconds)
        return FloatArray(n) { i ->
            val a = amp * sin(2.0 * PI * 1000.0 * i / sr)
            val b = 0.5 * amp * sin(2.0 * PI * 2500.0 * i / sr)
            (a + b).toFloat()
        }
    }

    /** Sum two equal-length arrays sample-wise (mix a vocal over a bed). */
    private fun mix(a: FloatArray, b: FloatArray): FloatArray {
        val n = minOf(a.size, b.size)
        return FloatArray(n) { a[it] + b[it] }
    }

    // ---- (1) instrumental runway then a vocal burst -------------------------

    @Test
    fun instrumental_then_vocal_burst_is_detected_near_6s() {
        // 6 s of low-freq instrumental, then a strong mid-band vocal over the
        // same bed. Onset should land near 6 s.
        val intro = instrumental(6.0, amp = 0.4)
        val body = mix(instrumental(8.0, amp = 0.4), vocal(8.0, amp = 0.6))
        val audio = intro + body
        val onset = VocalOnset.estimateOnsetS(audio, sr)
        assertEquals("onset=" + onset, 6.0, onset, 0.7)
    }

    // ---- (2) vocals from sample 0 -------------------------------------------

    @Test
    fun vocals_from_the_start_report_zero_and_no_safe_window() {
        // Strong mid-band signal from sample 0: opens singing.
        val audio = vocal(8.0, amp = 0.6)
        val onset = VocalOnset.estimateOnsetS(audio, sr)
        assertTrue("onset=" + onset, onset < 0.5)
        val safe = VocalOnset.safeIntroWindowS(audio, sr)
        assertEquals("safe=" + safe, 0.0, safe, 1e-9)
    }

    // ---- (3) silence -> conservative, no NaN --------------------------------

    @Test
    fun silence_returns_conservative_small_onset_no_nan() {
        val audio = FloatArray(samples(8.0)) // all zeros
        val onset = VocalOnset.estimateOnsetS(audio, sr)
        assertFalse("NaN onset", onset.isNaN())
        assertTrue("onset=" + onset, onset in 0.0..1.0)
    }

    @Test
    fun near_silence_returns_conservative_small_onset_no_nan() {
        val audio = tone(8.0, 1000.0, amp = 1e-6) // essentially inaudible
        val onset = VocalOnset.estimateOnsetS(audio, sr)
        assertFalse("NaN onset", onset.isNaN())
        assertTrue("onset=" + onset, onset in 0.0..1.0)
    }

    // ---- (4) short clip -> conservative, no crash ---------------------------

    @Test
    fun short_clip_is_conservative_and_does_not_crash() {
        val audio = vocal(0.3, amp = 0.6) // < 1 s
        val onset = VocalOnset.estimateOnsetS(audio, sr)
        assertFalse("NaN onset", onset.isNaN())
        assertTrue("onset=" + onset, onset in 0.0..1.0)
    }

    @Test
    fun empty_clip_is_conservative_and_does_not_crash() {
        val onset = VocalOnset.estimateOnsetS(FloatArray(0), sr)
        assertFalse("NaN onset", onset.isNaN())
        assertTrue("onset=" + onset, onset in 0.0..1.0)
    }

    // ---- bad params / NaN samples stay safe ---------------------------------

    @Test
    fun bad_sample_rate_is_conservative() {
        val audio = vocal(8.0)
        val onset = VocalOnset.estimateOnsetS(audio, 0)
        assertTrue("onset=" + onset, onset in 0.0..1.0)
    }

    @Test
    fun nan_samples_stay_safe_no_crash() {
        val audio = instrumental(3.0) + FloatArray(samples(3.0)) { Float.NaN }
        val onset = VocalOnset.estimateOnsetS(audio, sr)
        assertFalse("NaN onset", onset.isNaN())
        assertTrue("onset=" + onset, onset in 0.0..1.0)
    }

    // ---- (5) safeIntroWindowS = max(0, onset - guard) -----------------------

    @Test
    fun safe_intro_window_subtracts_guard_and_floors_at_zero() {
        // Long instrumental runway then a vocal -> onset comfortably > guard.
        val audio = instrumental(6.0, amp = 0.4) +
            mix(instrumental(6.0, amp = 0.4), vocal(6.0, amp = 0.6))
        val onset = VocalOnset.estimateOnsetS(audio, sr)
        val guard = 0.4
        val expected = (onset - guard).coerceAtLeast(0.0)
        val safe = VocalOnset.safeIntroWindowS(audio, sr, guard)
        assertEquals("onset=" + onset + " safe=" + safe, expected, safe, 1e-9)
        assertTrue("expected positive window, onset=" + onset, safe > 0.0)
    }

    @Test
    fun safe_intro_window_never_negative() {
        // Vocals from t=0 -> onset ~0 -> window floors at 0 regardless of guard.
        val audio = vocal(8.0, amp = 0.6)
        val safe = VocalOnset.safeIntroWindowS(audio, sr, guardS = 1.0)
        assertEquals(0.0, safe, 1e-9)
    }

    // ---- detector distinguishes instrumental from vocal ---------------------

    @Test
    fun pure_instrumental_does_not_report_a_late_safe_vocal_window() {
        // A long low-freq instrumental with NO vocals must NOT be reported as a
        // big talk-over-safe window (safety bias: small/conservative onset).
        val audio = instrumental(12.0, amp = 0.5)
        val onset = VocalOnset.estimateOnsetS(audio, sr)
        assertFalse("NaN onset", onset.isNaN())
        assertTrue("instrumental onset should stay small, onset=" + onset, onset <= 1.0)
    }
}