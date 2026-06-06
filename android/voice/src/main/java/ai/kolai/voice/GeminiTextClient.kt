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

    suspend fun complete(prompt: String): String {
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
        }
        val body = requestJson.toString()

        val errors = mutableListOf<String>()
        repeat(keys.size) {
            val key = keys[idx]
            try {
                val resp: HttpResponse = httpClient.post(endpoint(model, key)) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                if (!resp.status.isTransientFailure()) {
                    val text = resp.bodyAsText()
                    return parseText(text)
                }
                errors.add("HTTP ${resp.status.value} from key #$idx")
            } catch (e: Exception) {
                errors.add(e.toString())
            }
            idx = (idx + 1) % keys.size
        }
        throw RuntimeException("All Gemini keys failed: $errors")
    }

    private fun parseText(responseBody: String): String {
        val root = Json.parseToJsonElement(responseBody).jsonObject
        return root["candidates"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
    }
}
