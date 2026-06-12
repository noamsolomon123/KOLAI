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
    fun synth_styleOverride_prepends_to_content_over_constructor_style() = runTest {
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
            style = "Constructor style",
            httpClient = client,
        )
        synth.synth("text-here", styleOverride = "Read this like a soft late-night host")
        // contentText is "$style\n\n$text" (JSON-escaped newlines in the body)
        assertTrue(
            "override style prepended with blank line",
            bodies[0].contains("""Read this like a soft late-night host\n\ntext-here"""),
        )
        // the per-call override replaces the constructor style entirely
        assertTrue(!bodies[0].contains("Constructor style"))
    }

    @Test
    fun synth_null_styleOverride_falls_back_to_constructor_style() = runTest {
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
        synth.synth("text-here", styleOverride = null)
        assertTrue(bodies[0].contains("""Say cheerfully\n\ntext-here"""))
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

    // ---- single-speaker regression: the refactor must not move a byte ----

    @Test
    fun synth_request_body_keeps_exact_prerefactor_shape() = runTest {
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
        synth.synth("hello world")
        assertEquals(
            """{"contents":[{"parts":[{"text":"hello world"}]}],""" +
                """"generationConfig":{"responseModalities":["AUDIO"],""" +
                """"speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Kore"}}}}}""",
            bodies[0],
        )

        // styled call too: contentText is "$style\n\n$text", same envelope
        synth.synth("hello world", styleOverride = "Say cheerfully")
        assertEquals(
            """{"contents":[{"parts":[{"text":"Say cheerfully\n\nhello world"}]}],""" +
                """"generationConfig":{"responseModalities":["AUDIO"],""" +
                """"speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Kore"}}}}}""",
            bodies[1],
        )
    }

    // ---- multi-speaker dialogue ----

    @Test
    fun synthDialogue_sends_multiSpeakerVoiceConfig_with_script() = runTest {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = mockClient(
            listOf(HttpStatusCode.OK to audioBody(knownPcm)),
            urls, bodies,
        )
        val synth = GeminiTtsSynth(
            apiKeys = listOf("KEY_A"),
            model = "gemini-tts",
            voice = "Algieba",
            httpClient = client,
        )
        val wav = synth.synthDialogue(
            turns = listOf("A" to "shalom", "B" to "ma kore"),
            voiceB = "Iapetus",
        )

        // same WAV path as synth(): 44-byte header + decoded PCM
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertArrayEquals(knownPcm, wav.copyOfRange(44, wav.size))

        // exact request: "A: ...\nB: ..." script + both speakers mapped to voices
        assertEquals(
            """{"contents":[{"parts":[{"text":"A: shalom\nB: ma kore"}]}],""" +
                """"generationConfig":{"responseModalities":["AUDIO"],""" +
                """"speechConfig":{"multiSpeakerVoiceConfig":{"speakerVoiceConfigs":[""" +
                """{"speaker":"A","voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Algieba"}}},""" +
                """{"speaker":"B","voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Iapetus"}}}]}}}}""",
            bodies[0],
        )
    }

    @Test
    fun synthDialogue_voiceA_overrides_constructor_voice() = runTest {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = mockClient(
            listOf(HttpStatusCode.OK to audioBody(knownPcm)),
            urls, bodies,
        )
        val synth = GeminiTtsSynth(
            apiKeys = listOf("KEY_A"),
            model = "gemini-tts",
            voice = "Algieba",
            httpClient = client,
        )
        synth.synthDialogue(
            turns = listOf("A" to "hi", "B" to "yo"),
            voiceA = "Puck",
            voiceB = "Iapetus",
        )
        assertTrue(
            "speaker A uses the override voice",
            bodies[0].contains(
                """{"speaker":"A","voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Puck"}}}"""
            ),
        )
        assertTrue("constructor voice replaced", !bodies[0].contains("Algieba"))
    }

    @Test
    fun synthDialogue_prepends_style_paragraph_before_script() = runTest {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = mockClient(
            listOf(HttpStatusCode.OK to audioBody(knownPcm)),
            urls, bodies,
        )
        val synth = GeminiTtsSynth(
            apiKeys = listOf("KEY_A"),
            model = "gemini-tts",
            voice = "Algieba",
            httpClient = client,
        )
        synth.synthDialogue(
            turns = listOf("A" to "hi", "B" to "yo"),
            voiceB = "Iapetus",
            styleOverride = "Two playful hosts",
        )
        // style is its own paragraph above the script (JSON-escaped newlines)
        assertTrue(bodies[0].contains("""Two playful hosts\n\nA: hi\nB: yo"""))
    }

    @Test
    fun synthDialogue_null_styleOverride_falls_back_to_constructor_style() = runTest {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = mockClient(
            listOf(HttpStatusCode.OK to audioBody(knownPcm)),
            urls, bodies,
        )
        val synth = GeminiTtsSynth(
            apiKeys = listOf("KEY_A"),
            model = "gemini-tts",
            voice = "Algieba",
            style = "Constructor style",
            httpClient = client,
        )
        synth.synthDialogue(turns = listOf("A" to "hi", "B" to "yo"), voiceB = "Iapetus")
        assertTrue(bodies[0].contains("""Constructor style\n\nA: hi\nB: yo"""))
    }

    @Test
    fun synthDialogue_rotates_keys_on_failure() = runTest {
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
            voice = "Algieba",
            httpClient = client,
        )
        val wav = synth.synthDialogue(
            turns = listOf("A" to "hi", "B" to "yo"),
            voiceB = "Iapetus",
        )
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertTrue(urls[0].contains("key=KEY_A"))
        assertTrue(urls[1].contains("key=KEY_B"))
    }
}
