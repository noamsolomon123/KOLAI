package ai.kolai.voice

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * LLMClient over the raw Gemini REST API (generateContent), called via Ktor.
 * Rotates across multiple API keys on failure (e.g. rate-limit / 5xx) to
 * extend the free-tier quota.
 *
 * Ported 1:1 from backend/radioai/djbrain.py `GeminiClient.complete`: build
 * `{"contents":[{"parts":[{"text":prompt}]}]}`, POST with the current key, read
 * `candidates[0].content.parts[0].text`; on a transient failure advance to the
 * next key and retry; if every key fails, throw with the collected errors.
 *
 * SAMPLING (2026-06-13, generation-diversity fix): complete() takes optional
 * temperature and topP. When EITHER is non-null a generationConfig object is
 * added to the request body carrying the provided field(s); when BOTH are null
 * the body is byte-identical to the legacy contents-only shape (no
 * generationConfig at all), so the model applies its own defaults exactly as
 * before. The study found the missing sampling config was the #1 driver of
 * near-deterministic DJ output; DjBrain now passes a per-beat temperature.
 *
 * The [httpClient] is injected so tests can pass a MockEngine client and
 * production an OkHttp one — the engine is never hardcoded here.
 */
class GeminiTextClient(
    apiKeys: List<String>,
    private val model: String,
    private val httpClient: HttpClient,
) {
    private val keys: List<String> = apiKeys.toList()
    private var idx = 0

    init {
        require(keys.isNotEmpty()) { "GeminiTextClient needs at least one API key" }
    }

    suspend fun complete(
        prompt: String,
        temperature: Double? = null,
        topP: Double? = null,
    ): String {
        val requestJson = buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", prompt) })
                        }
                    }
                )
            }
            // Only emit generationConfig when a sampling field is set, so the
            // legacy (null) call keeps the byte-identical contents-only body.
            if (temperature != null || topP != null) {
                putJsonObject("generationConfig") {
                    if (temperature != null) put("temperature", temperature)
                    if (topP != null) put("topP", topP)
                }
            }
        }
        val body = requestJson.toString()

        // KEY-FREE error trail (SECURITY, 2026-06-13): a real key leaked once via
        // a surfaced request URL (the key rides in `?key=AIza...`). EVERY error
        // recorded here is built from the key INDEX only - never the URL, never
        // the key value. Any Ktor exception (which may embed the URL) is caught
        // and rewrapped to a key-free descriptor before it can reach a log/disk.
        val errors = mutableListOf<String>()
        var lastStatus = -1
        repeat(keys.size) {
            val key = keys[idx]
            try {
                val resp: HttpResponse = httpClient.post(endpoint(model, key)) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                // ROBUSTNESS (2026-06-13): only a 2xx body is safe to parse. A
                // 403/4xx (e.g. a disabled/leaked key) has no `candidates`, so
                // calling parseText() would NPE; instead record a key-free error
                // and rotate to the next key. isTransientFailure() (429/5xx) is
                // subsumed by this: ANY non-2xx rotates.
                if (resp.status.value in 200..299) {
                    val text = resp.bodyAsText()
                    return parseText(text)
                }
                lastStatus = resp.status.value
                errors.add("HTTP ${resp.status.value} from key #$idx")
            } catch (e: Exception) {
                // Rewrap with a key-free message: e.toString() / e.message could
                // embed the request URL (and thus the key) on transport errors.
                errors.add("transport error from key #$idx (${e.javaClass.simpleName})")
            }
            idx = (idx + 1) % keys.size
        }
        val lastDesc = if (lastStatus >= 0) "HTTP $lastStatus" else "transport error"
        throw RuntimeException(
            "All Gemini text keys rejected (last: $lastDesc); tried ${keys.size} key(s): $errors"
        )
    }

    private fun parseText(responseBody: String): String {
        val root = Json.parseToJsonElement(responseBody).jsonObject
        return root["candidates"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
    }
}
