package ai.kolai.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM tests for the windowed-sinc quality resampler (resampleSinc), the
 * broadcast-voice 24 kHz -> 44.1 kHz path. resampleLinear keeps its own tests
 * in ResampleTest; both must coexist (resampleSinc is additive).
 */
class SincResampleTest {

    private fun sine(n: Int, amp: Float, freqHz: Double, sr: Int): FloatArray =
        FloatArray(n) { (amp * sin(2.0 * PI * freqHz * it / sr)).toFloat() }

    /** RMS over [from, to). */
    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    /** Goertzel magnitude (normalized by N) of [freq] over x[from, to). */
    private fun goertzel(x: FloatArray, freq: Double, sr: Int, from: Int, to: Int): Double {
        val w = 2.0 * PI * freq / sr
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until to) {
            val s0 = x[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2) / (to - from)
    }

    // ---- identity / shape --------------------------------------------------

    @Test
    fun sameRate_isNoOpSameInstance() {
        val x = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertSame(x, resampleSinc(x, 44100, 44100))
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals(0, resampleSinc(FloatArray(0), 24000, 44100).size)
    }

    @Test
    fun outputLength_matches_linear_convention() {
        // round(24000 * 44100 / 24000) = 44100 — same rule as resampleLinear.
        assertEquals(44100, resampleSinc(FloatArray(24000), 24000, 44100).size)
    }

    @Test
    fun silence_in_silence_out_no_nan() {
        val y = resampleSinc(FloatArray(12000), 24000, 44100)
        for (v in y) assertEquals(0.0f, v, 0.0f)
    }

    @Test
    fun dc_is_preserved() {
        val y = resampleSinc(FloatArray(12000) { 0.25f }, 24000, 44100)
        // Kernel-sum normalization keeps DC exact everywhere incl. edges.
        for (i in y.indices) assertEquals("sample " + i, 0.25f, y[i], 1e-4f)
    }

    // ---- quality: passband amplitude ---------------------------------------

    @Test
    fun upsample_preserves_1khz_tone_amplitude_within_half_db() {
        val fromSr = 24000
        val toSr = 44100
        val x = sine(fromSr / 2, 0.5f, 1000.0, fromSr) // 0.5 s
        val y = resampleSinc(x, fromSr, toSr)
        // Middle 0.2 s (integer cycle count at 1 kHz) vs the ideal sine RMS.
        val from = (0.15 * toSr).toInt()
        val to = from + (0.2 * toSr).toInt()
        val ideal = 0.5 / sqrt(2.0)
        val db = 20.0 * log10(rms(y, from, to) / ideal)
        assertTrue("1 kHz amplitude must hold within ±0.5 dB, was " + db, kotlin.math.abs(db) < 0.5)
    }

    // ---- quality: image / alias rejection -----------------------------------

    @Test
    fun upsample_attenuates_spectral_image_by_25db() {
        // A 6 kHz tone at 24 kHz has its first image at 24-6 = 18 kHz. After a
        // proper 24->44.1 kHz resample that image must be far down; linear
        // interpolation leaves it at only ~ -21 dB.
        val fromSr = 24000
        val toSr = 44100
        val x = sine((0.5 * fromSr).toInt(), 0.5f, 6000.0, fromSr)
        val y = resampleSinc(x, fromSr, toSr)
        // 0.2 s window => integer cycles for both 6 kHz (1200) and 18 kHz (3600).
        val from = (0.15 * toSr).toInt()
        val to = from + (0.2 * toSr).toInt()
        val tone = goertzel(y, 6000.0, toSr, from, to)
        val image = goertzel(y, 18000.0, toSr, from, to)
        val rejectionDb = 20.0 * log10(tone / image)
        assertTrue("image rejection must exceed 25 dB, was " + rejectionDb, rejectionDb > 25.0)
    }

    @Test
    fun downsample_antialiases_out_of_band_tone() {
        // 15 kHz at 44.1 kHz is above the 22.05->11.025 kHz target Nyquist; a
        // naive decimator would alias it to ~7.05 kHz. The widened sinc kernel
        // must suppress it almost entirely.
        val fromSr = 44100
        val toSr = 22050
        val x = sine((0.4 * fromSr).toInt(), 0.3f, 15000.0, fromSr)
        val y = resampleSinc(x, fromSr, toSr)
        val from = 1000
        val to = from + (0.2 * toSr).toInt()
        val outRms = rms(y, from, to)
        val inRms = 0.3 / sqrt(2.0)
        assertTrue("aliased energy must be < 5% of the tone, was " + (outRms / inRms), outRms < 0.05 * inRms)
    }

    // ---- guards -------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun zeroFromRate_throws() {
        resampleSinc(floatArrayOf(0.1f), 0, 44100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroToRate_throws() {
        resampleSinc(floatArrayOf(0.1f), 24000, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tinyTaps_throws() {
        resampleSinc(floatArrayOf(0.1f, 0.2f), 24000, 44100, taps = 2)
    }
}