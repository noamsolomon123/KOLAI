package ai.kolai.acquire

import ai.kolai.analyze.AudioDecoder
import ai.kolai.core.Song
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * INSTRUMENTED (on-device) proof of the acquire pipeline on CPH2747, Task 0.4 /
 * 2.2 download spike.
 *
 * Two kinds of tests:
 *  - [diagnose_*]: ALWAYS-run probes that log exactly what YouTube returns
 *    (resolved video, per-format stream counts). These PASS even when the
 *    download is blocked, so the spike always produces clean evidence.
 *  - [fetch_*]: the real end-to-end search -> download -> decode. These FAIL
 *    with the exact NewPipe symptom when the 2026 PO-token / bot-detection wall
 *    blocks playable streams (empty audioStreams). A failed fetch here is itself
 *    the headline finding.
 *
 * Run manually (Gradle connectedDebugAndroidTest is broken on this host):
 *   adb install -r -t acquire-debug-androidTest.apk
 *   adb shell am instrument -w \
 *     ai.kolai.acquire.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s NewPipeSourceTest
 */
@RunWith(AndroidJUnit4::class)
class NewPipeSourceTest {

    private companion object {
        const val TAG = "NewPipeSourceTest"
        const val SR = 44100
        const val MIN_FILE_BYTES = 100L * 1024L // > ~100 KB
        const val MIN_DECODED_SAMPLES = SR // > 1 s of audio
        const val MIN_PEAK = 0.001f // clearly non-silent

        val YELLOW = Song("Yellow", "Coldplay", durationS = 266.0, query = null)
        val HEBREW = Song("סע לאט", "עומר אדם", durationS = null, query = null)
        // Third, ultra-popular track to confirm the wall is systemic not per-video.
        val DESPACITO = Song("Despacito", "Luis Fonsi", durationS = 281.0, query = null)
    }

    // ---- diagnostics (always pass; capture the symptom) -------------------

    @Test
    fun diagnose_searchAndStreamAvailability() {
        val source = NewPipeSource()
        var anyAudio = false
        for (song in listOf(YELLOW, HEBREW, DESPACITO)) {
            val label = "${song.artist} - ${song.title}"
            try {
                val url = source.resolveSearchUrl(song)
                Log.i(TAG, "DIAG search OK  | '$label' -> $url")
                val d = source.diagnose(url)
                Log.i(
                    TAG,
                    "DIAG streams    | '$label' audio=${d.audioCount} " +
                        "video=${d.videoCount} videoOnly=${d.videoOnlyCount}",
                )
                if (d.audioCount > 0) anyAudio = true
            } catch (e: Exception) {
                Log.e(TAG, "DIAG FAILED     | '$label': ${e.javaClass.name}: ${e.message}", e)
            }
        }
        Log.i(
            TAG,
            "DIAG SUMMARY    | anyPlayableAudioStream=$anyAudio " +
                "(false => PO-token/bot-detection wall on this device/network)",
        )
        // Intentionally always passes: this is an evidence-gathering probe.
        assertTrue(true)
    }

    // ---- real end-to-end (fails on the wall, with the exact symptom) ------

    @Test
    fun fetch_english_yellow_downloadsAndDecodes() {
        runFetchAndDecode(YELLOW, label = "EN/Yellow")
    }

    @Test
    fun fetch_hebrew_song_downloadsAndDecodes() {
        // Popular Hebrew original -> exercises the Hebrew-aware scoring path.
        runFetchAndDecode(HEBREW, label = "HE/OmerAdam")
    }

    private fun runFetchAndDecode(song: Song, label: String) {
        val cacheDir = InstrumentationRegistry.getInstrumentation()
            .targetContext.cacheDir

        val source = NewPipeSource()

        val path: String = try {
            source.fetch(song, cacheDir)
        } catch (e: Exception) {
            // Surface the EXACT NewPipe / download symptom (bot-detection wall
            // shows up here). A failed fetch is itself a valid spike finding.
            val msg = "[$label] fetch FAILED for '${song.artist} - ${song.title}': " +
                "${e.javaClass.name}: ${e.message}"
            Log.e(TAG, msg, e)
            throw AssertionError(msg, e)
        }

        Log.i(TAG, "[$label] resolved local path: $path")

        val file = File(path)
        assertTrue("[$label] file does not exist: $path", file.exists())
        val size = file.length()
        Log.i(TAG, "[$label] file size: $size bytes (${size / 1024} KB)")
        assertTrue(
            "[$label] file too small ($size bytes) -- likely a stub / error page, not audio",
            size > MIN_FILE_BYTES,
        )

        // ---- decode: PROVE it is real, decodable audio ----
        val pcm: FloatArray = try {
            AudioDecoder.decodeToPcm(path, SR)
        } catch (e: Exception) {
            val msg = "[$label] downloaded file failed to decode ($path): ${e.message}"
            Log.e(TAG, msg, e)
            throw AssertionError(msg, e)
        }

        var peak = 0.0f
        var energy = 0.0
        for (v in pcm) {
            val a = abs(v)
            if (a > peak) peak = a
            energy += v.toDouble() * v.toDouble()
        }
        val rms = if (pcm.isNotEmpty()) Math.sqrt(energy / pcm.size) else 0.0
        val seconds = pcm.size.toDouble() / SR

        Log.i(
            TAG,
            "[$label] DECODED: samples=${pcm.size} (~${"%.1f".format(seconds)} s) " +
                "peak=$peak rms=${"%.5f".format(rms)}",
        )

        assertTrue(
            "[$label] decoded length ${pcm.size} < 1 s ($MIN_DECODED_SAMPLES samples)",
            pcm.size > MIN_DECODED_SAMPLES,
        )
        assertTrue(
            "[$label] decoded audio is silent (peak=$peak) -- not real audio",
            peak > MIN_PEAK,
        )

        Log.i(TAG, "[$label] PASS: real search -> download -> decode on device.")
    }
}