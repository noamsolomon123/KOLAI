package ai.kolai.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * MockEngine-driven tests for [GeminiTtsSynth.synth] — no network/keys.
 * Mirrors backend/radioai/voice.py `GeminiTTSSynth`: base64-decode
 * inlineData.data (raw 16-bit PCM) and wrap it via pcmToWav.
 */
class GeminiTtsSynthTest {

    private val jsonHeaders =
        headersOf(HttpHeaders.ContentType, "application/json")

    private val knownPcm = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)

    private fun audioBody(pcm: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(pcm)
        return """{"candidates":[{"content":{"parts":[{"inlineData":{"data":"$b64"}}]}}]}"""
    }

    private fun mockClient(
        responses: List<Pair<HttpStatusCode, String>>,
        recordedUrls: MutableList<String>,
        recordedBodies: MutableList<String>,
    ): HttpClient {
        var i = 0
        val engine = MockEngine { request ->
            recordedUrls.add(request.url.toString())
            recordedBodies.add(
                (request.body as io.ktor.http.content.TextContent).text
            )
            val (status, body) = responses[i.coerceAtMost(responses.size - 1)]
            i++
            respond(content = body, status = status, headers = jsonHeaders)
        }
        return HttpClient(engine)
    }

    @Test
    fun synth_returns_wav_wrapping_decoded_pcm() = runTest {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = mockClient(
            listOf(HttpStatusCode.OK to audioBody(knownPcm)),
            urls, bodies,
        )
        val synth = GeminiTtsSynth(
            apiKeys = listOf("KEY_A"),
            model = "gemini-tts",
            voice = "Kore",
            httpClient = client,
        )
        val wav = synth.synth("שלום עולם")

        // starts with the RIFF magic
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        // header (44) + decoded PCM
        assertEquals(44 + knownPcm.size, wav.size)
        assertArrayEquals(knownPcm, wav.copyOfRange(44, wav.size))

        // request shape: AUDIO modality + the configured prebuilt voice name
        assertTrue(bodies[0].contains("\"responseModalities\""))
        assertTrue(bodies[0].contains("AUDIO"))
        assertTrue(bodies[0].contains("Kore"))
    }

    @Test
    fun synth_uses_voice_override_when_provided() = runTest {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = mockClient(
            listOf(HttpStatusCode.OK to audioBody(knownPcm)),
            urls, bodies,
        )
        val synth = GeminiTtsSynth(
            apiKeys = listOf("KEY_A"),
            model = "gemini-tts",
            voice = "Kore",
            httpClient = client,
        )
        synth.synth("hi", voiceOverride = "Puck")
        assertTrue("override voice in request", bodies[0].contains("Puck"))
    }

    @Test
    fun synth_prepends_style_to_content() = runTest {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = mockClient(
            listOf(HttpStatusCode.OK to audioBody(knownPcm)),
            urls, bodies,
        )
        val synth = GeminiTtsSynth(
            apiKeys = listOf("KEY_A"),
            model = "gemini-tts",
            voice = "Kore",
            style = "Say cheerfully",
            httpClient = client,
        )
        synth.synth("text-here")
        // mirrors voice.py: f"{style}\n\n{text}"
        assertTrue(bodies[0].contains("Say cheerfully"))
        assertTrue(bodies[0].contains("text-here"))
    }

    @Test
    fun synth_rotates_keys_on_failure() = runTest {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = mockClient(
            listOf(
                HttpStatusCode.TooManyRequests to "rate",
                HttpStatusCode.OK to audioBody(knownPcm),
            ),
            urls, bodies,
        )
        val synth = GeminiTtsSynth(
            apiKeys = listOf("KEY_A", "KEY_B"),
            model = "gemini-tts",
            voice = "Kore",
            httpClient = client,
        )
        val wav = synth.synth("hi")
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertTrue(urls[0].contains("key=KEY_A"))
        assertTrue(urls[1].contains("key=KEY_B"))
    }
}
