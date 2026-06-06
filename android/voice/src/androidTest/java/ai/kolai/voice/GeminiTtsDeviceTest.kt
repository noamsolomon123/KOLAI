package ai.kolai.voice

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * On-device integration test: the FIRST real Gemini TTS call from the OnePlus 15.
 *
 * Reads the API key / TTS model / TTS voice from instrumentation args (never
 * compiled into the APK) and synthesizes a Hebrew DJ line through the production
 * Ktor+OkHttp engine, then asserts a valid, non-silent WAV lands on disk.
 *
 * Run via:
 *   adb shell am instrument -w \
 *     -e geminiKey "<KEY>" -e ttsModel "<MODEL>" -e ttsVoice "<VOICE>" \
 *     ai.kolai.voice.test/androidx.test.runner.AndroidJUnitRunner
 */
class GeminiTtsDeviceTest {

    private val tag = "GeminiTtsDeviceTest"

    @Test
    fun gemini_tts_renders_nonsilent_hebrew_wav_on_device() {
        val args = InstrumentationRegistry.getArguments()
        val key = args.getString("geminiKey")
        val model = args.getString("ttsModel")
        val voice = args.getString("ttsVoice")
        assertTrue("missing -e geminiKey", !key.isNullOrBlank())
        assertTrue("missing -e ttsModel", !model.isNullOrBlank())
        assertTrue("missing -e ttsVoice", !voice.isNullOrBlank())
        Log.i(tag, "Using model=$model voice=$voice")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val synth = GeminiTtsSynth(
            apiKeys = listOf(key!!),
            model = model!!,
            voice = voice!!,
            httpClient = HttpClient(OkHttp),
        )
        val renderer = VoiceRenderer(synth, ctx.cacheDir)
        val text = "שלום, אתם מקשיבים לקול AI עם הקול אלגייבה"

        val slot = try {
            runBlocking { renderer.render(text) }
        } catch (e: Exception) {
            // Surface the EXACT Gemini error (model not found / quota / auth)
            Log.e(tag, "Gemini TTS call FAILED: ${e.message}", e)
            throw AssertionError(
                "Gemini TTS render failed (model=$model voice=$voice): ${e.message}", e
            )
        }

        val file = File(slot.audioPath)
        assertTrue("WAV not created at ${slot.audioPath}", file.exists())
        val bytes = file.readBytes()

        // valid RIFF/WAVE header
        assertTrue("file too small (${bytes.size} bytes)", bytes.size > 44)
        assertTrue("bad RIFF magic", String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        assertTrue("bad WAVE magic", String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")

        // peak |sample| over the 16-bit mono PCM payload (after 44-byte header)
        var peak = 0
        var i = 44
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val sample = (hi shl 8) or lo // signed 16-bit LE
            val mag = if (sample < 0) -sample else sample
            if (mag > peak) peak = mag
            i += 2
        }

        Log.i(
            tag,
            "RESULT fileSize=${bytes.size} bytes, durationS=${slot.durationS}, peakAbsSample=$peak"
        )

        assertTrue("durationS not plausible: ${slot.durationS}", slot.durationS > 1.0)
        assertTrue("PCM appears silent (peak=$peak)", peak > 500)
    }
}