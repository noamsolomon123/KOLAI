package ai.kolai.mix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for StationIdent - KOLAI's code-generated session-open ident (petiach,
 * research finding 9). Pure math, runs on the JVM.
 */
class StationIdentTest {

    @Test
    fun render_length_matches_expected_duration() {
        val out = StationIdent.render()
        assertEquals((StationIdent.DURATION_S * Dsp.SR).toInt(), out.size)
        // and at a custom sample rate
        val out22k = StationIdent.render(sr = 22050)
        assertEquals((StationIdent.DURATION_S * 22050).toInt(), out22k.size)
    }

    @Test
    fun render_peak_is_polite() {
        val out = StationIdent.render()
        var peak = 0.0f
        for (v in out) peak = maxOf(peak, abs(v))
        assertTrue("peak $peak must be <= 0.5", peak <= 0.5f)
        // normalization actually hits the target (not a silent buffer)
        assertEquals(StationIdent.PEAK, peak, 1e-3f)
    }

    @Test
    fun render_starts_near_zero_no_click() {
        val out = StationIdent.render()
        // 10 ms fade-in: the very first samples must be tiny
        assertTrue("first sample ${out[0]}", abs(out[0]) < 1e-3f)
        assertTrue("sample 10 ${out[10]}", abs(out[10]) < 0.05f)
    }

    @Test
    fun render_ends_at_silence_no_click() {
        val out = StationIdent.render()
        assertEquals("very last sample must be exactly silent", 0.0f, out.last(), 1e-6f)
        // the last 5 ms are all but inaudible
        val tail = (0.005f * Dsp.SR).toInt()
        for (i in out.size - tail until out.size) {
            assertTrue("tail sample $i = ${out[i]}", abs(out[i]) < 0.01f)
        }
    }

    @Test
    fun render_is_deterministic() {
        val a = StationIdent.render()
        val b = StationIdent.render()
        assertArrayEquals(a, b, 0.0f)
    }

    @Test
    fun render_has_audible_body() {
        // sanity: the motif region (0.6s-1.0s, all three notes ringing) carries
        // real energy - the ident is not accidentally near-silence.
        val out = StationIdent.render()
        val from = (0.6f * Dsp.SR).toInt()
        val to = (1.0f * Dsp.SR).toInt()
        var sumSq = 0.0
        for (i in from until to) sumSq += out[i] * out[i]
        val rms = Math.sqrt(sumSq / (to - from))
        assertTrue("rms $rms should be clearly audible", rms > 0.05)
    }
}
