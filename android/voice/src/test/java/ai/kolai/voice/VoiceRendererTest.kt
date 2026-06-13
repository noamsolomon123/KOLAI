package ai.kolai.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/**
 * Pure-JVM tests for [VoiceRenderer] — no network. A FAKE synth is built from a
 * MockEngine that always returns a known WAV (Wav.pcmToWav(knownPcm, 24000)) and
 * counts how many times it was hit, so we can assert the sha1 cache-hit path.
 */
class VoiceRendererTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    // 24000 samples of 16-bit mono PCM == 48000 bytes == exactly 1.0 s @ 24 kHz.
    private val knownPcm = ByteArray(48000) { (it and 0x7F).toByte() }

    private fun audioBody(pcm: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(pcm)
        return """{"candidates":[{"content":{"parts":[{"inlineData":{"data":"$b64"}}]}}]}"""
    }

    /** A synth whose underlying HTTP engine records every call (to detect caching). */
    private fun fakeSynth(
        callCount: IntArray,
        recordedBodies: MutableList<String> = mutableListOf(),
    ): GeminiTtsSynth {
        val engine = MockEngine { request ->
            callCount[0]++
            recordedBodies.add((request.body as io.ktor.http.content.TextContent).text)
            respond(content = audioBody(knownPcm), status = HttpStatusCode.OK, headers = jsonHeaders)
        }
        return GeminiTtsSynth(
            apiKeys = listOf("KEY_A"),
            model = "gemini-tts",
            voice = "Algieba",
            httpClient = HttpClient(engine),
        )
    }

    @Test
    fun render_writes_wav_and_returns_djslot_with_correct_path_and_duration() = runTest {
        val calls = intArrayOf(0)
        val renderer = VoiceRenderer(fakeSynth(calls), tmp.root)
        val text = "שלום עולם"

        val slot = renderer.render(text)

        // file written under outDir with the dj_<sha1[:16]>.wav name
        val written = java.io.File(slot.audioPath)
        assertTrue("file exists", written.exists())
        assertEquals(tmp.root.absolutePath, written.parentFile!!.absolutePath)
        assertTrue("dj_ prefix", written.name.startsWith("dj_"))
        assertTrue(".wav suffix", written.name.endsWith(".wav"))
        // sha1 hex is 40 chars -> name is "dj_" + 16 + ".wav" = 23 chars
        assertEquals("dj_".length + 16 + ".wav".length, written.name.length)

        // slot text preserved (Hebrew round-trip)
        assertEquals(text, slot.text)

        // duration matches the known PCM: 48000 bytes / (24000*1*2) = 1.0 s
        assertEquals(1.0, slot.durationS, 1e-9)

        // a valid RIFF/WAVE on disk: 44-byte header + 48000 PCM bytes
        val bytes = written.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(44 + knownPcm.size, bytes.size)
    }

    @Test
    fun render_is_cached_by_sha1_and_does_not_resynth() = runTest {
        val calls = intArrayOf(0)
        val renderer = VoiceRenderer(fakeSynth(calls), tmp.root)
        val text = "אותו טקסט בדיוק"

        val first = renderer.render(text)
        val second = renderer.render(text)

        assertEquals("same cached path", first.audioPath, second.audioPath)
        assertEquals("same duration", first.durationS, second.durationS, 1e-9)
        // synth (HTTP engine) hit exactly once across two renders -> cache hit
        assertEquals("synth called only once", 1, calls[0])
    }

    @Test
    fun render_same_text_different_styles_uses_different_cache_files() = runTest {
        val calls = intArrayOf(0)
        val renderer = VoiceRenderer(fakeSynth(calls), tmp.root)
        val text = "אותו טקסט בדיוק"

        val night = renderer.render(text, style = "Read this like a soft late-night host")
        val morning = renderer.render(text, style = "Read this like an upbeat morning host")
        val plain = renderer.render(text)

        // three distinct cache files: a WAV voiced for one mood is never reused for another
        assertTrue("night vs morning", night.audioPath != morning.audioPath)
        assertTrue("night vs plain", night.audioPath != plain.audioPath)
        assertTrue("morning vs plain", morning.audioPath != plain.audioPath)
        assertEquals("each style synthesized once", 3, calls[0])

        // and each styled render is itself cached on repeat
        val nightAgain = renderer.render(text, style = "Read this like a soft late-night host")
        assertEquals(night.audioPath, nightAgain.audioPath)
        assertEquals("repeat styled render is a cache hit", 3, calls[0])
    }

    @Test
    fun render_null_style_keeps_legacy_sha1_text_filename() = runTest {
        val calls = intArrayOf(0)
        val renderer = VoiceRenderer(fakeSynth(calls), tmp.root)
        val text = "שלום עולם"

        val slot = renderer.render(text, style = null)

        // legacy key is sha1(text) alone — pre-style cached WAVs stay valid
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val expectedName = "dj_${sha1.substring(0, 16)}.wav"
        assertEquals(expectedName, java.io.File(slot.audioPath).name)

        // blank style is treated like null (same legacy file)
        val blank = renderer.render(text, style = "  ")
        assertEquals(slot.audioPath, blank.audioPath)
        assertEquals("blank style hits the legacy cache", 1, calls[0])
    }

    @Test
    fun render_forwards_style_to_synth_content() = runTest {
        val calls = intArrayOf(0)
        val bodies = mutableListOf<String>()
        val renderer = VoiceRenderer(fakeSynth(calls, bodies), tmp.root)

        renderer.render("text-here", style = "Read this like a soft late-night host")

        // synth received the style: contentText is "$style\n\n$text" (JSON-escaped)
        assertTrue(
            "style prepended in outbound request",
            bodies[0].contains("""Read this like a soft late-night host\n\ntext-here"""),
        )
    }

    @Test
    fun renderDialogue_writes_wav_and_returns_script_slot() = runTest {
        val calls = intArrayOf(0)
        val bodies = mutableListOf<String>()
        val renderer = VoiceRenderer(fakeSynth(calls, bodies), tmp.root)
        val turns = listOf("A" to "shalom", "B" to "ma kore")

        val slot = renderer.renderDialogue(turns, voiceB = "Iapetus")

        val written = java.io.File(slot.audioPath)
        assertTrue("file exists", written.exists())
        assertEquals(tmp.root.absolutePath, written.parentFile!!.absolutePath)
        assertTrue("dj_ prefix", written.name.startsWith("dj_"))
        assertTrue(".wav suffix", written.name.endsWith(".wav"))
        assertEquals("dj_".length + 16 + ".wav".length, written.name.length)

        // slot text is the joined "A: ...\nB: ..." script; duration from the WAV
        assertEquals("A: shalom\nB: ma kore", slot.text)
        assertEquals(1.0, slot.durationS, 1e-9)

        // the outbound request was a multi-speaker call carrying both voices
        assertTrue(bodies[0].contains("multiSpeakerVoiceConfig"))
        assertTrue("speaker A = synth constructor voice", bodies[0].contains("Algieba"))
        assertTrue("speaker B = per-call voice", bodies[0].contains("Iapetus"))
    }

    @Test
    fun renderDialogue_is_cached_second_call_makes_no_http() = runTest {
        val calls = intArrayOf(0)
        val renderer = VoiceRenderer(fakeSynth(calls), tmp.root)
        val turns = listOf("A" to "hi", "B" to "yo")

        val first = renderer.renderDialogue(turns, voiceB = "Iapetus", style = "Playful banter")
        val second = renderer.renderDialogue(turns, voiceB = "Iapetus", style = "Playful banter")

        assertEquals("same cached path", first.audioPath, second.audioPath)
        assertEquals("same duration", first.durationS, second.durationS, 1e-9)
        assertEquals("synth called only once", 1, calls[0])
    }

    @Test
    fun renderDialogue_voiceB_style_and_turns_each_change_cache_file() = runTest {
        val calls = intArrayOf(0)
        val renderer = VoiceRenderer(fakeSynth(calls), tmp.root)
        val turns = listOf("A" to "hi", "B" to "yo")

        val base = renderer.renderDialogue(turns, voiceB = "Iapetus")
        val otherVoice = renderer.renderDialogue(turns, voiceB = "Puck")
        val styled = renderer.renderDialogue(turns, voiceB = "Iapetus", style = "Late night")
        val otherTurns = renderer.renderDialogue(listOf("A" to "hi", "B" to "bye"), voiceB = "Iapetus")

        val paths = listOf(base, otherVoice, styled, otherTurns).map { it.audioPath }
        assertEquals("all four renders use distinct files", 4, paths.toSet().size)
        assertEquals("each variant synthesized once", 4, calls[0])

        // dialogue cache keys are disjoint from single-voice renders of the same script
        val single = renderer.render("A: hi\nB: yo")
        assertTrue("dlg| prefix keeps caches apart", single.audioPath != base.audioPath)
    }

    @Test
    fun render_same_text_different_voices_uses_different_cache_files() = runTest {
        val calls = intArrayOf(0)
        val renderer = VoiceRenderer(fakeSynth(calls), tmp.root)
        val text = "אותו טקסט בדיוק"

        val v1 = renderer.render(text, voiceOverride = "Algieba")
        val v2 = renderer.render(text, voiceOverride = "Puck")
        val plain = renderer.render(text)

        // a WAV voiced in one voice is never reused for another voice (or the default)
        assertTrue("Algieba vs Puck", v1.audioPath != v2.audioPath)
        assertTrue("Algieba vs plain", v1.audioPath != plain.audioPath)
        assertTrue("Puck vs plain", v2.audioPath != plain.audioPath)
        assertEquals("each voice synthesized once", 3, calls[0])

        // repeating a voiced render is a cache hit (no extra synth)
        val v1Again = renderer.render(text, voiceOverride = "Algieba")
        assertEquals(v1.audioPath, v1Again.audioPath)
        assertEquals("repeat voiced render is a cache hit", 3, calls[0])
    }

    @Test
    fun render_voice_and_style_each_change_cache_file() = runTest {
        val calls = intArrayOf(0)
        val renderer = VoiceRenderer(fakeSynth(calls), tmp.root)
        val text = "אותו טקסט בדיוק"

        val voiceOnly = renderer.render(text, voiceOverride = "Puck")
        val styleOnly = renderer.render(text, style = "Late night")
        val both = renderer.render(text, voiceOverride = "Puck", style = "Late night")

        val paths = listOf(voiceOnly, styleOnly, both).map { it.audioPath }
        assertEquals("voice, style, and both produce distinct files", 3, paths.toSet().size)
        assertEquals("each combination synthesized once", 3, calls[0])
    }

    @Test
    fun render_null_voice_and_null_style_hits_exact_legacy_filename() = runTest {
        val calls = intArrayOf(0)
        val renderer = VoiceRenderer(fakeSynth(calls), tmp.root)
        val text = "שלום עולם"

        // both voiceOverride and style null/blank -> legacy key is sha1(text) alone
        val slot = renderer.render(text, voiceOverride = null, style = null)

        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val expectedName = "dj_${sha1.substring(0, 16)}.wav"
        assertEquals(expectedName, java.io.File(slot.audioPath).name)

        // blank voiceOverride is treated like null too (still legacy file)
        val blankVoice = renderer.render(text, voiceOverride = "  ", style = null)
        assertEquals(slot.audioPath, blankVoice.audioPath)
        assertEquals("blank voice hits the legacy cache", 1, calls[0])
    }

    @Test
    fun renderDialogue_different_voiceA_uses_different_cache_file() = runTest {
        val calls = intArrayOf(0)
        val bodies = mutableListOf<String>()
        val renderer = VoiceRenderer(fakeSynth(calls, bodies), tmp.root)
        val turns = listOf("A" to "hi", "B" to "yo")

        val defaultA = renderer.renderDialogue(turns, voiceB = "Iapetus")
        val customA = renderer.renderDialogue(turns, voiceB = "Iapetus", voiceA = "Puck")
        val otherA = renderer.renderDialogue(turns, voiceB = "Iapetus", voiceA = "Charon")

        val paths = listOf(defaultA, customA, otherA).map { it.audioPath }
        assertEquals("varying voiceA produces distinct files", 3, paths.toSet().size)
        assertEquals("each voiceA synthesized once", 3, calls[0])

        // the custom host voice reached the synth (speaker A == voiceA)
        assertTrue("custom voiceA forwarded to synth", bodies.any { it.contains("Puck") })
        assertTrue("custom voiceA forwarded to synth", bodies.any { it.contains("Charon") })

        // repeating with the same voiceA is a cache hit
        val customAgain = renderer.renderDialogue(turns, voiceB = "Iapetus", voiceA = "Puck")
        assertEquals(customA.audioPath, customAgain.audioPath)
        assertEquals("repeat voiceA render is a cache hit", 3, calls[0])
    }

    @Test
    fun wavDurationSeconds_parses_known_pcm() {
        val wav = pcmToWav(knownPcm, 24000)
        assertEquals(1.0, wavDurationSeconds(wav), 1e-9)
    }
}