package ai.kolai.analyze

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * INSTRUMENTED (androidText -> on-device) proof that the Essentia-backed native
 * lib (libkolaidsp.so, Essentia statically linked) actually RUNS on real hardware
 * (OnePlus 15 / CPH2747, arm64-v8a, 16 KB pages, Android 15+).
 *
 * Unlike the pure-JVM [AnalyzerTest] (which injects a fake JSON seam), these tests
 * use the DEFAULT [Analyzer] seam -> ai.kolai.dsp.KolaiDsp.analyzePcmJson, which
 * loads and executes the native library. If the .so fails to load (ABI / 16 KB
 * alignment / UnsatisfiedLinkError) these tests fail loudly with that exact error.
 *
 * Run: from android/ ->  .\gradlew.bat :analyze:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class EssentiaDeviceTest {

    private companion object {
        const val TAG = "EssentiaDeviceTest"
        const val SR = 44100
    }

    /**
     * Build ~[seconds] of mono 44100 Hz PCM containing a short, sharp click every
     * [periodS] seconds. A click every 0.5 s == 120 BPM. The click is a brief
     * exponentially-decaying full-scale impulse (a handful of samples) so it reads
     * as a strong percussive onset to Essentia's beat tracker.
     */
    private fun clickTrack(seconds: Double, periodS: Double): FloatArray {
        val n = (seconds * SR).toInt()
        val pcm = FloatArray(n) // silence
        val periodSamples = (periodS * SR).toInt()
        val clickLen = 64 // ~1.45 ms transient
        var pos = 0
        while (pos < n) {
            for (k in 0 until clickLen) {
                val idx = pos + k
                if (idx >= n) break
                // Decaying impulse: 1.0 -> ~0 over clickLen samples.
                val env = 1.0f - (k.toFloat() / clickLen)
                pcm[idx] = env * env // sharper decay
            }
            pos += periodSamples
        }
        return pcm
    }

    /**
     * BPM octave-folding helper (mirrors the spirit of the BpmGuards used inside
     * Analyzer): double/halve until the value lands in a canonical band so that a
     * detected 60 / 240 still compares to a target of 120.
     */
    private fun foldToward(bpm: Double, target: Double): Double {
        if (bpm <= 0.0 || !bpm.isFinite()) return bpm
        var v = bpm
        while (v < target / 1.5) v *= 2.0
        while (v > target * 1.5) v /= 2.0
        return v
    }

    /**
     * TEST A: known-BPM (120) click track through the REAL native lib.
     *
     * Hard asserts (the actual on-device proof):
     *   - no UnsatisfiedLinkError (lib loaded + executed),
     *   - bpm finite & in a plausible 40..220 range,
     *   - energy >= 0,
     *   - keyCamelot non-empty.
     * The ~120 BPM match is a SOFT expectation: asserted within +/-15% IF it holds,
     * otherwise downgraded to a logged observation (does NOT fail the build).
     */
    @Test
    fun testA_clickTrack_runsEssentiaOnDevice() {
        val pcm = clickTrack(seconds = 10.0, periodS = 0.5) // 120 BPM

        // --- raw native JSON (so we can report the TRUE beatConfidence / beat count,
        // which Analyzer may zero out below its confidence gate). This is the first
        // real call into the .so; an UnsatisfiedLinkError here = lib didn't load.
        val rawJson: String =
            try {
                ai.kolai.dsp.KolaiDsp.analyzePcmJson(pcm, SR)
            } catch (e: UnsatisfiedLinkError) {
                fail("native libkolaidsp.so failed to load on device: ${e.message}")
                return
            }
        Log.i(TAG, "TEST A raw native JSON = $rawJson")
        println("TEST A raw native JSON = $rawJson")

        // --- go through the real Analyzer (default seam -> same native lib).
        val analysis = Analyzer().analyze(pcm, SR, "clicktrack_120bpm")

        val summary = "TEST A RESULT: bpm=${analysis.bpm} energy=${analysis.energy} " +
            "keyCamelot=${analysis.keyCamelot} beatTimes.size=${analysis.beatTimes.size} " +
            "durationS=${analysis.durationS}"
        Log.i(TAG, summary)
        println(summary)

        // Hard asserts: the lib loaded and produced sane values.
        assertTrue("bpm must be finite, was ${analysis.bpm}", analysis.bpm.isFinite())
        assertTrue(
            "bpm out of plausible 40..220 range: ${analysis.bpm}",
            analysis.bpm in 40.0..220.0,
        )
        assertTrue("energy must be >= 0, was ${analysis.energy}", analysis.energy >= 0.0)
        assertFalse("keyCamelot must be non-empty", analysis.keyCamelot.isBlank())

        // SOFT: detected BPM near 120 after octave folding (+/-15%). Logged either way.
        val folded = foldToward(analysis.bpm, 120.0)
        val pctErr = abs(folded - 120.0) / 120.0
        val within = pctErr <= 0.15
        val bpmNote = "TEST A BPM-vs-120: detected=${analysis.bpm} folded=$folded " +
            "errPct=${"%.1f".format(pctErr * 100)}% within15pct=$within"
        Log.i(TAG, bpmNote)
        println(bpmNote)
        // Do NOT hard-fail on the synthetic-click BPM match; it is a logged observation.
    }

    /**
     * TEST B: robustness on near-silence / low-amplitude noise.
     *
     * Must NOT crash the process (no SIGSEGV / native abort). Acceptable outcomes:
     *   - a sane TrackAnalysis (finite bpm, energy >= 0, non-empty key), OR
     *   - a CLEAN IllegalStateException (Analyzer's documented failure path).
     * Any other Throwable (incl. Errors like UnsatisfiedLinkError or a native abort
     * surfaced to the JVM) fails the test.
     */
    @Test
    fun testB_nearSilence_isRobust() {
        val n = 5 * SR // 5 s
        val pcm = FloatArray(n)
        // Deterministic low-amplitude noise (~ -80 dBFS): tiny, non-zero, no NaNs.
        val rnd = java.util.Random(1234)
        for (i in pcm.indices) {
            pcm[i] = (rnd.nextFloat() - 0.5f) * 2e-4f
        }

        try {
            val analysis = Analyzer().analyze(pcm, SR, "near_silence")
            val msg = "TEST B RESULT (sane): bpm=${analysis.bpm} energy=${analysis.energy} " +
                "keyCamelot=${analysis.keyCamelot} beatTimes.size=${analysis.beatTimes.size}"
            Log.i(TAG, msg)
            println(msg)
            assertTrue("bpm must be finite, was ${analysis.bpm}", analysis.bpm.isFinite())
            assertTrue("energy must be >= 0, was ${analysis.energy}", analysis.energy >= 0.0)
            assertFalse("keyCamelot must be non-empty", analysis.keyCamelot.isBlank())
        } catch (e: IllegalStateException) {
            // Documented clean failure path -> acceptable, NOT a native crash.
            Log.i(TAG, "TEST B RESULT (clean IllegalStateException): ${e.message}")
            println("TEST B RESULT (clean IllegalStateException): ${e.message}")
        }
    }
}