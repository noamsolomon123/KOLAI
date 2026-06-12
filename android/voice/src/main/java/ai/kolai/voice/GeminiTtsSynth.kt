package ai.kolai.voice

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Synth over the raw Gemini TTS REST API (generateContent), called via Ktor.
 * Returns WAV bytes (wraps Gemini's raw 24 kHz 16-bit mono PCM). Rotates across
 * multiple API keys on failure (rate-limit / 5xx). A per-call voice override
 * replaces the default voice (used for two-host banter).
 *
 * Ported 1:1 from backend/radioai/voice.py `GeminiTTSSynth.synth`: request with
 * `generationConfig.responseModalities=["AUDIO"]` and
 * `generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`;
 * when the effective style (per-call `styleOverride`, else the constructor
 * `style`) is non-empty the content text is `"$style\n\n$text"`; on success
 * base64-decode `candidates[0].content.parts[0].inlineData.data` and wrap it via
 * [pcmToWav].
 *
 * [synthDialogue] uses the same endpoint/models with the multi-speaker variant:
 * `generationConfig.speechConfig.multiSpeakerVoiceConfig.speakerVoiceConfigs`
 * (speakers "A"/"B" mapped to prebuilt voices) and a script content text of
 * `"A: ...\nB: ..."` lines. Response shape is identical to single-speaker.
 *
 * The [httpClient] is injected so tests can pass a MockEngine client and
 * production an OkHttp one — the engine is never hardcoded here.
 */
class GeminiTtsSynth(
    apiKeys: List<String>,
    private val model: String,
    private val voice: String,
    private val style: String = "",
    private val sampleRate: Int = 24000,
    private val httpClient: HttpClient,
) {
    private val keys: List<String> = apiKeys.toList()
    private var idx = 0

    init {
        require(keys.isNotEmpty()) { "GeminiTtsSynth needs at least one API key" }
    }

    suspend fun synth(
        text: String,
        voiceOverride: String? = null,
        styleOverride: String? = null,
    ): ByteArray {
        val voiceName = voiceOverride ?: voice
        val body = requestBody(
            contentText = withStyle(text, styleOverride),
            speechConfig = singleSpeakerConfig(voiceName),
        )
        return postWithKeyRotation(body)
    }

    /**
     * Synthesize a two-host dialogue in one multi-speaker TTS call.
     *
     * [turns] are (speakerLabel, Hebrew text) pairs where the label is "A" or
     * "B"; they are joined into a `"A: ...\nB: ..."` script that Gemini voices
     * with speaker "A" = [voiceA] (defaulting to the constructor [voice]) and
     * speaker "B" = [voiceB]. Style semantics mirror [synth]: the effective
     * style ([styleOverride], else the constructor [style]) is prepended as its
     * own paragraph when non-empty. Returns WAV bytes like [synth].
     */
    suspend fun synthDialogue(
        turns: List<Pair<String, String>>,
        voiceA: String? = null,
        voiceB: String,
        styleOverride: String? = null,
    ): ByteArray {
        require(turns.isNotEmpty()) { "synthDialogue needs at least one turn" }
        val script = dialogueScript(turns)
        val body = requestBody(
            contentText = withStyle(script, styleOverride),
            speechConfig = multiSpeakerConfig(voiceA ?: voice, voiceB),
        )
        return postWithKeyRotation(body)
    }

    /** `"$style\n\n$text"` when the effective style is non-empty, else [text]. */
    private fun withStyle(text: String, styleOverride: String?): String {
        val effectiveStyle = styleOverride ?: style
        return if (effectiveStyle.isNotEmpty()) "$effectiveStyle\n\n$text" else text
    }

    /** generateContent request body shared by single- and multi-speaker calls. */
    private fun requestBody(contentText: String, speechConfig: JsonObject): String =
        buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", contentText) })
                        }
                    }
                )
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                put("speechConfig", speechConfig)
            }
        }.toString()

    private fun singleSpeakerConfig(voiceName: String): JsonObject = buildJsonObject {
        putJsonObject("voiceConfig") {
            putJsonObject("prebuiltVoiceConfig") {
                put("voiceName", voiceName)
            }
        }
    }

    private fun multiSpeakerConfig(voiceA: String, voiceB: String): JsonObject = buildJsonObject {
        putJsonObject("multiSpeakerVoiceConfig") {
            putJsonArray("speakerVoiceConfigs") {
                add(speakerVoiceConfig("A", voiceA))
                add(speakerVoiceConfig("B", voiceB))
            }
        }
    }

    private fun speakerVoiceConfig(speaker: String, voiceName: String): JsonObject =
        buildJsonObject {
            put("speaker", speaker)
            putJsonObject("voiceConfig") {
                putJsonObject("prebuiltVoiceConfig") {
                    put("voiceName", voiceName)
                }
            }
        }

    /** POST [body], rotating across keys on 429/5xx/exceptions; returns WAV bytes. */
    private suspend fun postWithKeyRotation(body: String): ByteArray {
        val errors = mutableListOf<String>()
        repeat(keys.size) {
            val key = keys[idx]
            try {
                val resp: HttpResponse = httpClient.post(endpoint(model, key)) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                if (!resp.status.isTransientFailure()) {
                    val pcm = parsePcm(resp.bodyAsText())
                    return pcmToWav(pcm, sampleRate)
                }
                errors.add("HTTP ${resp.status.value} from key #$idx")
            } catch (e: Exception) {
                errors.add(e.toString())
            }
            idx = (idx + 1) % keys.size
        }
        throw RuntimeException("All Gemini TTS keys failed: $errors")
    }

    private fun parsePcm(responseBody: String): ByteArray {
        val root = Json.parseToJsonElement(responseBody).jsonObject
        val b64 = root["candidates"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray[0]
            .jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content
        return Base64.getDecoder().decode(b64)
    }
}

/** Join dialogue turns into the `"A: ...\nB: ..."` script Gemini multi-speaker expects. */
internal fun dialogueScript(turns: List<Pair<String, String>>): String =
    turns.joinToString("\n") { (speaker, text) -> "$speaker: $text" }