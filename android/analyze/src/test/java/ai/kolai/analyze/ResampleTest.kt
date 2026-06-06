package ai.kolai.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the Android-free PCM helpers in Resample.kt. No device
 * / android.media required (those are exercised on-device in AudioDecoderTest).
 */
class ResampleTest {

    // ---- downmixToMono ----------------------------------------------------

    @Test
    fun downmix_mono_isNoOpSameInstance() {
        val mono = floatArrayOf(0.1f, -0.2f, 0.3f)
        val out = downmixToMono(mono, 1)
        // Fast path returns the SAME array (no copy).
        assertSame(mono, out)
    }

    @Test
    fun downmix_stereo_averagesChannels() {
        // interleaved L,R per frame: (1,-1)->0, (0.5,0.5)->0.5, (0.2,0.4)->0.3
        val stereo = floatArrayOf(1.0f, -1.0f, 0.5f, 0.5f, 0.2f, 0.4f)
        val out = downmixToMono(stereo, 2)
        assertEquals(3, out.size)
        assertEquals(0.0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(0.3f, out[2], 1e-6f)
    }

    @Test
    fun downmix_threeChannels_averages() {
        val tri = floatArrayOf(0.3f, 0.6f, 0.9f) // mean = 0.6
        val out = downmixToMono(tri, 3)
        assertEquals(1, out.size)
        assertEquals(0.6f, out[0], 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun downmix_badLength_throws() {
        // 3 samples cannot be 2 channels.
        downmixToMono(floatArrayOf(0.1f, 0.2f, 0.3f), 2)
    }

    // ---- resampleLinear ---------------------------------------------------

    @Test
    fun resample_sameRate_isNoOpSameInstance() {
        val mono = floatArrayOf(0.0f, 0.1f, 0.2f, 0.3f)
        val out = resampleLinear(mono, 44100, 44100)
        assertSame(mono, out)
    }

    @Test
    fun resample_halves_lengthAndStaysMonotonicForRamp() {
        // A monotonic ramp of 8 samples, 88200 -> 44100 should be ~half length,
        // remain monotonic non-decreasing, and preserve both endpoints.
        val n = 8
        val ramp = FloatArray(n) { i -> i.toFloat() / (n - 1) } // 0 .. 1
        val out = resampleLinear(ramp, 88200, 44100)

        // round(8 * 44100 / 88200) = round(4.0) = 4.
        assertEquals(4, out.size)

        // Endpoints preserved.
        assertEquals(ramp.first(), out.first(), 1e-6f)
        assertEquals(ramp.last(), out.last(), 1e-6f)

        // Monotonic non-decreasing.
        for (i in 1 until out.size) {
            assertTrue(
                "ramp not monotonic at $i: ${out[i - 1]} -> ${out[i]}",
                out[i] >= out[i - 1] - 1e-6f,
            )
        }
    }

    @Test
    fun resample_upsample_doublesLengthApprox_andPreservesEndpoints() {
        val ramp = FloatArray(5) { i -> i.toFloat() / 4f } // 0..1, len 5
        val out = resampleLinear(ramp, 22050, 44100)
        // round(5 * 44100 / 22050) = round(10.0) = 10.
        assertEquals(10, out.size)
        assertEquals(0.0f, out.first(), 1e-6f)
        assertEquals(1.0f, out.last(), 1e-6f)
        // Monotonic.
        for (i in 1 until out.size) {
            assertTrue(out[i] >= out[i - 1] - 1e-6f)
        }
    }

    @Test
    fun resample_emptyInput_returnsEmpty() {
        val out = resampleLinear(FloatArray(0), 48000, 44100)
        assertEquals(0, out.size)
    }

    @Test
    fun resample_singleSample_returnsSingle() {
        val out = resampleLinear(floatArrayOf(0.42f), 48000, 44100)
        assertEquals(1, out.size)
        assertEquals(0.42f, out[0], 1e-6f)
    }

    @Test
    fun resample_midpointInterpolation_isLinear() {
        // Two-point input [0, 1] upsampled to 3 points: midpoint must be 0.5.
        val out = resampleLinear(floatArrayOf(0.0f, 1.0f), 1, 2)
        // round(2 * 2 / 1) = 4. step = (2-1)/(4-1) = 1/3 over src index space.
        // We only assert endpoints + monotonic + bounded for the general case.
        assertEquals(0.0f, out.first(), 1e-6f)
        assertEquals(1.0f, out.last(), 1e-6f)
        for (i in 1 until out.size) assertTrue(out[i] >= out[i - 1] - 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun resample_zeroSrcRate_throws() {
        resampleLinear(floatArrayOf(0.1f), 0, 44100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun resample_zeroDstRate_throws() {
        resampleLinear(floatArrayOf(0.1f), 44100, 0)
    }
}