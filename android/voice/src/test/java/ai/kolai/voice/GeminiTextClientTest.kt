package ai.kolai.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * MockEngine-driven tests for [GeminiTextClient.complete] — no network/keys.
 * Mirrors backend/radioai/djbrain.py `GeminiClient.complete` rotation behavior.
 */
class GeminiTextClientTest {

    private val jsonHeaders =
        headersOf(HttpHeaders.ContentType, "application/json")

    private fun textBody(text: String): String =
        """{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}"""

    /** A MockEngine that replays [statuses]/[bodies] per call and records request URLs. */
    private fun mockClient(
        responses: List<Pair<HttpStatusCode, String>>,
        recordedUrls: MutableList<String>,
    ): HttpClient {
        var i = 0
        val engine = MockEngine { request ->
            recordedUrls.add(request.url.toString())
            val (status, body) = responses[i.coerceAtMost(responses.size - 1)]
            i++
            respond(content = body, status = status, headers = jsonHeaders)
        }
        return HttpClient(engine)
    }

    @Test
    fun complete_returns_text_from_candidates() = runTest {
        val urls = mutableListOf<String>()
        val client = mockClient(
            listOf(HttpStatusCode.OK to textBody("שלום")),
            urls,
        )
        val gemini = GeminiTextClient(
            apiKeys = listOf("KEY_A"),
            model = "gemini-test",
            httpClient = client,
        )
        assertEquals("שלום", gemini.complete("hi"))
        assertTrue("uses the only key", urls[0].contains("key=KEY_A"))
        assertTrue("hits the model endpoint", urls[0].contains("gemini-test:generateContent"))
    }

    @Test
    fun complete_rotates_to_next_key_on_429() = runTest {
        val urls = mutableListOf<String>()
        val client = mockClient(
            listOf(
                HttpStatusCode.TooManyRequests to """{"error":"rate"}""",
                HttpStatusCode.OK to textBody("עברתי"),
            ),
            urls,
        )
        val gemini = GeminiTextClient(
            apiKeys = listOf("KEY_A", "KEY_B"),
            model = "gemini-test",
            httpClient = client,
        )
        assertEquals("עברתי", gemini.complete("hi"))
        // first attempt used KEY_A, second advanced to KEY_B
        assertTrue(urls[0].contains("key=KEY_A"))
        assertTrue(urls[1].contains("key=KEY_B"))
        assertEquals(2, urls.size)
    }

    @Test
    fun complete_throws_when_all_keys_fail() = runTest {
        val urls = mutableListOf<String>()
        val client = mockClient(
            listOf(
                HttpStatusCode.TooManyRequests to "rate",
                HttpStatusCode.InternalServerError to "boom",
            ),
            urls,
        )
        val gemini = GeminiTextClient(
            apiKeys = listOf("KEY_A", "KEY_B"),
            model = "gemini-test",
            httpClient = client,
        )
        try {
            gemini.complete("hi")
            fail("expected all keys to fail")
        } catch (e: Exception) {
            // tried each key exactly once
            assertEquals(2, urls.size)
        }
    }
}
