package ai.kolai.analyze

import ai.kolai.mix.AacEncoder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * INSTRUMENTED (on-device) end-to-end proof of the decode path:
 *   synth mono 44.1k sine -> AacEncoder.encode (.m4a) -> AudioDecoder.decodeToPcm
 *   -> Analyzer().analyze (REAL native Essentia lib).
 *
 * This validates the full encode -> decode -> analysis chain on real hardware
 * (OnePlus 15 / CPH2747, arm64-v8a). Depends on :mix (AacEncoder) via
 * androidTestImplementation(project(":mix")).
 *
 * Run manually (Gradle connectedDebugAndroidTest is broken on this host):
 *   adb shell am instrument -w -r \
 *     ai.kolai.analyze.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s AudioDecoderTest
 */
@RunWith(AndroidJUnit4::class)
class AudioDecoderTest {

    private companion object {
        const val TAG = "AudioDecoderTest"
        const val SR = 44100
        const val DURATION_S = 3.0
        const val FREQ_HZ = 220.0
        const val AMPLITUDE = 0.5f
        // AAC-LC round-trip is NOT sample-exact: the encoder prepends priming
        // (encoder delay) and pads the tail up to a whole number of 1024-sample
        // frames, and this device's MediaCodec decoder does not strip them
        // (no gapless playback metadata is applied on a raw decode). Empirically
        // 3 s -> +2868 samples on CPH2747 (132 frames * 1024 = 135168 vs 132300).
        // Allow ~4 frames (~93 ms) of slack; still a tight correctness bound.
        const val LEN_TOLERANCE = 4096
    }

    @Test
    fun encodeDecodeAnalyze_roundTripOnDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // ~3 s mono 220 Hz sine @ 0.5 amplitude, 44.1 kHz.
        val sampleCount = (DURATION_S * SR).toInt()
        val original = FloatArray(sampleCount) { i ->
            (AMPLITUDE * sin(2.0 * PI * FREQ_HZ * i / SR)).toFloat()
        }

        val tmpM4a = File(ctx.cacheDir, "audiodecoder_roundtrip_${System.nanoTime()}.m4a")
        if (tmpM4a.exists()) tmpM4a.delete()

        try {
            // ---- encode ----
            AacEncoder.encode(tmpM4a.absolutePath, original, SR)
            assertTrue("encoded file missing: ${tmpM4a.absolutePath}", tmpM4a.exists())
            assertTrue("encoded file empty", tmpM4a.length() > 0L)

            // ---- decode ----
            val decoded = AudioDecoder.decodeToPcm(tmpM4a.absolutePath, SR)

            // length within +/- LEN_TOLERANCE of original (AAC priming/granularity).
            val lenDelta = abs(decoded.size - original.size)
            Log.i(
                TAG,
                "ROUND-TRIP: originalLen=${original.size} decodedLen=${decoded.size} " +
                    "delta=$lenDelta tolerance=$LEN_TOLERANCE",
            )
            assertTrue(
                "decoded length ${decoded.size} not within +/-$LEN_TOLERANCE of ${original.size} (delta=$lenDelta)",
                lenDelta <= LEN_TOLERANCE,
            )

            // values finite and in ~[-1, 1].
            var maxAbs = 0.0f
            for (v in decoded) {
                assertTrue("non-finite decoded sample: $v", v.isFinite())
                val a = abs(v)
                if (a > maxAbs) maxAbs = a
            }
            assertTrue("decoded peak $maxAbs exceeds ~1.0", maxAbs <= 1.02f)
            Log.i(TAG, "ROUND-TRIP: decoded peak=$maxAbs")

            // ---- analyze (REAL native Essentia) ----
            val analysis = Analyzer().analyze(decoded, SR, tmpM4a.absolutePath)
            val summary = "ANALYZE RESULT: bpm=${analysis.bpm} keyCamelot=${analysis.keyCamelot} " +
                "energy=${analysis.energy} durationS=${analysis.durationS} " +
                "beatTimes.size=${analysis.beatTimes.size}"
            Log.i(TAG, summary)
            println(summary)

            assertTrue("bpm must be finite, was ${analysis.bpm}", analysis.bpm.isFinite())
            assertTrue("bpm must be > 0, was ${analysis.bpm}", analysis.bpm > 0.0)
            assertTrue("energy must be >= 0, was ${analysis.energy}", analysis.energy >= 0.0)
            assertFalse("keyCamelot must be non-empty", analysis.keyCamelot.isBlank())
        } finally {
            if (tmpM4a.exists()) tmpM4a.delete()
        }
    }
}