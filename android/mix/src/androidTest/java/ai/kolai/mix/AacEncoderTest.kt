package ai.kolai.mix

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * On-device test for [AacEncoder]. Uses real android.media.* (MediaCodec +
 * MediaMuxer to encode, MediaExtractor to read the result back), so it must run
 * as an instrumented test on a device/emulator.
 *
 * Run manually (the Gradle connectedDebugAndroidTest path is broken on this
 * host):
 *   adb shell am instrument -w -r ai.kolai.mix.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class AacEncoderTest {

    private companion object {
        const val TAG = "AacEncoderTest"
        const val SR = 44100
        const val DURATION_S = 3.0
        const val FREQ_HZ = 440.0
        const val AMPLITUDE = 0.5f
        const val AAC_MIME = "audio/mp4a-latm"
    }

    @Test
    fun encodesMonoSineToPlayableM4a() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // ~3 s of a 440 Hz sine at 0.5 amplitude, mono, 44.1 kHz.
        val sampleCount = (DURATION_S * SR).toInt()
        val pcm = FloatArray(sampleCount) { i ->
            (AMPLITUDE * sin(2.0 * PI * FREQ_HZ * i / SR)).toFloat()
        }

        val outFile = File(ctx.cacheDir, "aac_encoder_test_${System.nanoTime()}.m4a")
        if (outFile.exists()) outFile.delete()

        try {
            AacEncoder.encode(outFile.absolutePath, pcm, sampleRate = SR)

            // --- file exists and is non-empty ---
            assertTrue("output file does not exist: ${outFile.absolutePath}", outFile.exists())
            val sizeBytes = outFile.length()
            assertTrue("output file is empty", sizeBytes > 0L)

            // --- read it back with MediaExtractor ---
            val extractor = MediaExtractor()
            var durationUs = -1L
            var channelCount = -1
            var sampleRate = -1
            var mime = "<none>"
            var audioTracks = 0
            try {
                extractor.setDataSource(outFile.absolutePath)
                assertEquals("expected exactly one track", 1, extractor.trackCount)

                for (t in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(t)
                    val m = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("audio/")) {
                        audioTracks++
                        mime = m
                        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            format.getLong(MediaFormat.KEY_DURATION)
                        } else {
                            -1L
                        }
                    }
                }
            } finally {
                extractor.release()
            }

            val durationS = durationUs / 1_000_000.0
            Log.i(
                TAG,
                "encoded m4a: path=${outFile.absolutePath} size=${sizeBytes}B " +
                    "mime=$mime channels=$channelCount sampleRate=$sampleRate " +
                    "durationUs=$durationUs durationS=$durationS",
            )

            assertEquals("expected exactly one audio track", 1, audioTracks)
            assertEquals("MIME", AAC_MIME, mime)
            assertEquals("channelCount", 1, channelCount)
            assertEquals("sampleRate", SR, sampleRate)

            // AAC priming / 1024-sample frame granularity -> allow +/- 0.3 s.
            assertTrue(
                "duration $durationS s not within +/-0.3 s of $DURATION_S s",
                kotlin.math.abs(durationS - DURATION_S) <= 0.3,
            )
        } finally {
            if (outFile.exists()) outFile.delete()
        }
    }
}