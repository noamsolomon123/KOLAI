package ai.kolai.mix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tests for the broadcast voice chain: Biquad (RBJ cookbook, DF2T), the
 * soft-knee compressor and voiceBroadcastChain. Pure JVM math.
 */
class VoiceFxTest {

    private val sr = 44100

    private fun sine(n: Int, amp: Float, freqHz: Double): FloatArray =
        FloatArray(n) { (amp * sin(2.0 * PI * freqHz * it / sr)).toFloat() }

    /** RMS over the last 60% of the clip (skips the filter transient). */
    private fun rmsTail(x: FloatArray): Float {
        val from = (x.size * 0.4f).toInt()
        var s = 0.0
        for (i in from until x.size) s += x[i].toDouble() * x[i]
        return sqrt(s / (x.size - from)).toFloat()
    }

    private fun gainDb(out: FloatArray, inp: FloatArray): Float =
        (20.0 * log10(rmsTail(out).toDouble() / rmsTail(inp).toDouble())).toFloat()

    // ---- Biquad frequency response ---------------------------------------

    @Test
    fun lowPass_passes_passband_within_half_db() {
        val x = sine(sr / 2, 0.5f, 1000.0)
        val y = Biquad.lowPass(8000.0f, sr).process(x)
        assertTrue("1 kHz through LP 8 kHz must be ~unity", abs(gainDb(y, x)) < 0.5f)
    }

    @Test
    fun lowPass_attenuates_stopband() {
        val x = sine(sr / 2, 0.5f, 15000.0)
        val y = Biquad.lowPass(1000.0f, sr).process(x)
        assertTrue("15 kHz through LP 1 kHz must drop > 20 dB", gainDb(y, x) < -20.0f)
    }

    @Test
    fun highPass_passes_voice_band_within_half_db() {
        val x = sine(sr / 2, 0.5f, 1000.0)
        val y = Biquad.highPass(90.0f, sr).process(x)
        assertTrue("1 kHz through HP 90 Hz must be ~unity", abs(gainDb(y, x)) < 0.5f)
    }

    @Test
    fun highPass_kills_rumble() {
        val x = sine(sr / 2, 0.5f, 30.0)
        val y = Biquad.highPass(90.0f, sr).process(x)
        assertTrue("30 Hz through HP 90 Hz must drop > 15 dB", gainDb(y, x) < -15.0f)
    }

    @Test
    fun highShelf_boosts_treble_by_gainDb() {
        val x = sine(sr / 2, 0.2f, 10000.0)
        val y = Biquad.highShelf(3500.0f, sr, 2.5f).process(x)
        assertEquals("10 kHz through +2.5 dB shelf @ 3.5 kHz", 2.5f, gainDb(y, x), 0.6f)
    }

    @Test
    fun highShelf_leaves_lows_untouched() {
        val x = sine(sr / 2, 0.2f, 200.0)
        val y = Biquad.highShelf(3500.0f, sr, 2.5f).process(x)
        assertTrue("200 Hz through 3.5 kHz shelf ~unity", abs(gainDb(y, x)) < 0.5f)
    }

    // ---- Biquad stability / determinism -----------------------------------

    @Test
    fun biquad_impulse_response_decays_for_all_configs() {
        val impulse = FloatArray(4096).also { it[0] = 1.0f }
        val filters = listOf(
            Biquad.lowPass(1000.0f, sr),
            Biquad.highPass(90.0f, sr),
            Biquad.highShelf(3500.0f, sr, 2.5f),
        )
        for (f in filters) {
            val h = f.process(impulse)
            var tailMax = 0.0f
            for (i in 3096 until 4096) tailMax = maxOf(tailMax, abs(h[i]))
            assertTrue("impulse tail must decay to ~0, was " + tailMax, tailMax < 1e-6f)
            for (v in h) assertTrue("impulse response must stay finite", v.isFinite())
        }
    }

    @Test
    fun biquad_process_resets_state_bit_exact_repeatable() {
        val x = sine(sr / 4, 0.3f, 700.0)
        val f = Biquad.highPass(90.0f, sr)
        val a = f.process(x)
        val b = f.process(x)
        assertArrayEquals("process() must reset state", a, b, 0.0f)
    }

    @Test
    fun biquad_silence_in_silence_out() {
        val y = Biquad.lowPass(1000.0f, sr).process(FloatArray(2048))
        assertArrayEquals(FloatArray(2048), y, 0.0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun biquad_freq_at_or_above_nyquist_throws() {
        Biquad.lowPass(22050.0f, sr)
    }

    // ---- compress ----------------------------------------------------------

    @Test
    fun compress_subthreshold_is_bit_exact() {
        // amp 0.05 -> -26 dBFS, below threshold(-18) - knee/2(3) = -21 dB.
        val x = sine(sr / 2, 0.05f, 440.0)
        val y = compress(x, sr)
        assertArrayEquals("sub-threshold audio must pass bit-exact", x, y, 0.0f)
    }

    @Test
    fun compress_silence_is_bit_exact_no_nan() {
        val x = FloatArray(sr / 4)
        val y = compress(x, sr)
        assertArrayEquals(x, y, 0.0f)
    }

    @Test
    fun compress_reduces_loud_material() {
        val x = sine(sr / 2, 0.8f, 440.0)
        val y = compress(x, sr)
        // env ~0.8 -> ~16 dB over threshold -> ~-10 dB gain reduction.
        assertTrue("loud sine must be reduced", rmsTail(y) < 0.6f * rmsTail(x))
    }

    @Test
    fun compress_reduces_dynamic_range_monotonically() {
        val amps = floatArrayOf(0.05f, 0.1f, 0.2f, 0.4f, 0.8f)
        var prevOut = -1.0f
        var prevGain = 2.0f
        for (amp in amps) {
            val x = sine(sr / 2, amp, 440.0)
            val y = compress(x, sr)
            val outRms = rmsTail(y)
            val gain = outRms / rmsTail(x)
            assertTrue("output level must keep increasing (amp=" + amp + ")", outRms > prevOut)
            assertTrue("gain must be non-increasing with level (amp=" + amp + ")", gain <= prevGain + 1e-4f)
            assertTrue("compressor must never amplify (amp=" + amp + ")", gain <= 1.0f + 1e-6f)
            prevOut = outRms
            prevGain = gain
        }
        // End-to-end dynamic range shrank: 0.8/0.05 = 24 dB in, less out.
        val loud = rmsTail(compress(sine(sr / 2, 0.8f, 440.0), sr))
        val quiet = rmsTail(compress(sine(sr / 2, 0.05f, 440.0), sr))
        assertTrue("dynamic range must shrink", loud / quiet < 0.8f / 0.05f)
    }

    @Test
    fun compress_releases_back_to_unity_after_loud_burst() {
        val burst = sine(sr / 2, 0.8f, 440.0)
        val quiet = sine(sr / 2, 0.05f, 440.0)
        val x = burst + quiet
        val y = compress(x, sr)
        // Final 0.1 s: release (80 ms) has long recovered -> unity gain.
        var sIn = 0.0
        var sOut = 0.0
        for (i in x.size - sr / 10 until x.size) {
            sIn += x[i].toDouble() * x[i]
            sOut += y[i].toDouble() * y[i]
        }
        assertEquals("gain must release to ~1", 1.0, sqrt(sOut / sIn), 0.02)
    }

    @Test
    fun compress_nan_input_does_not_poison_the_rest() {
        val x = sine(sr / 4, 0.05f, 440.0)
        x[100] = Float.NaN
        val y = compress(x, sr)
        // Every sample except the NaN one stays finite (and sub-threshold exact).
        for (i in x.indices) {
            if (i != 100) assertTrue("sample " + i + " must be finite", y[i].isFinite())
        }
    }

    // ---- voiceBroadcastChain ----------------------------------------------

    @Test
    fun chain_kills_rumble() {
        val x = sine(sr / 2, 0.05f, 50.0)
        val y = voiceBroadcastChain(x, sr)
        assertTrue("50 Hz must drop > 7 dB", gainDb(y, x) < -7.0f)
    }

    @Test
    fun chain_boosts_presence_over_voice_band() {
        // Both below the compressor threshold so only the EQ acts.
        val lo = sine(sr / 2, 0.05f, 1000.0)
        val hi = sine(sr / 2, 0.05f, 8000.0)
        val gLo = rmsTail(voiceBroadcastChain(lo, sr)) / rmsTail(lo)
        val gHi = rmsTail(voiceBroadcastChain(hi, sr)) / rmsTail(hi)
        val ratio = gHi / gLo
        assertTrue("presence boost present, ratio=" + ratio, ratio > 1.15f)
        assertTrue("presence boost bounded, ratio=" + ratio, ratio < 1.45f)
    }

    @Test
    fun chain_compresses_loud_voice() {
        val x = sine(sr / 2, 0.5f, 1000.0)
        val eqOnly = Biquad.highShelf(3500.0f, sr, 2.5f)
            .process(Biquad.highPass(90.0f, sr).process(x))
        val y = voiceBroadcastChain(x, sr)
        assertTrue("compressor must engage on a -6 dBFS voice", rmsTail(y) < 0.8f * rmsTail(eqOnly))
    }

    @Test
    fun chain_silence_in_silence_out_no_nan() {
        val y = voiceBroadcastChain(FloatArray(sr / 4), sr)
        assertArrayEquals(FloatArray(sr / 4), y, 0.0f)
    }
}