package ai.kolai.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        recordedBodies: MutableList<String> = mutableListOf(),
    ): HttpClient {
        var i = 0
        val engine = MockEngine { request ->
            recordedUrls.add(request.url.toString())
            recordedBodies.add((request.body as io.ktor.http.content.TextContent).text)
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

    @Test
    fun complete_omits_generationConfig_when_temperature_null() = runTest {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = mockClient(listOf(HttpStatusCode.OK to textBody("legacy")), urls, bodies)
        val gemini = GeminiTextClient(apiKeys = listOf("KEY_A"), model = "gemini-test", httpClient = client)
        assertEquals("legacy", gemini.complete("hi"))
        // legacy (null) call: byte-identical contents-only body, no sampling config
        assertFalse("must not emit generationConfig", bodies[0].contains("generationConfig"))
        assertFalse(bodies[0].contains("temperature"))
    }

    @Test
    fun complete_emits_temperature_in_request_body() = runTest {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = mockClient(listOf(HttpStatusCode.OK to textBody("hot")), urls, bodies)
        val gemini = GeminiTextClient(apiKeys = listOf("KEY_A"), model = "gemini-test", httpClient = client)
        assertEquals("hot", gemini.complete("hi", temperature = 1.15))
        assertTrue("generationConfig missing", bodies[0].contains("generationConfig"))
        assertTrue("temperature missing", bodies[0].contains("\"temperature\":1.15"))
        // contents still present (prompt was not dropped)
        assertTrue(bodies[0].contains("contents"))
    }

    @Test
    fun complete_emits_temperature_and_topP_when_both_set() = runTest {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = mockClient(listOf(HttpStatusCode.OK to textBody("ok")), urls, bodies)
        val gemini = GeminiTextClient(apiKeys = listOf("KEY_A"), model = "gemini-test", httpClient = client)
        gemini.complete("hi", temperature = 0.4, topP = 0.95)
        assertTrue(bodies[0].contains("\"temperature\":0.4"))
        assertTrue(bodies[0].contains("\"topP\":0.95"))
    }

    // ---- SECURITY + ROBUSTNESS: key-free errors, 403 never NPEs (2026-06-13) --

    /** A real Gemini key shape; used to prove it never surfaces in an error. */
    private val fakeKey = "AIzaSyA_0123456789abcdefghijKLMNOPQRSTU"

    @Test
    fun all_keys_403_throws_clear_key_free_error_no_npe() = runTest {
        // A 403 (disabled/leaked key) returns NO `candidates`: the OLD code
        // skipped rotation on 4xx and fed the body to parseText() -> NPE. Now
        // ANY non-2xx rotates, and once every key is rejected we throw a clear
        // RuntimeException that carries NEITHER the key NOR the request URL.
        val urls = mutableListOf<String>()
        val client = mockClient(
            listOf(HttpStatusCode.Forbidden to """{"error":{"code":403,"status":"PERMISSION_DENIED"}}"""),
            urls,
        )
        val gemini = GeminiTextClient(
            apiKeys = listOf(fakeKey, fakeKey + "2"),
            model = "gemini-test",
            httpClient = client,
        )
        try {
            gemini.complete("hi")
            fail("expected all keys to be rejected")
        } catch (e: NullPointerException) {
            fail("403 must not NPE through parseText(): ${e.message}")
        } catch (e: RuntimeException) {
            // tried each key exactly once, then gave up
            assertEquals(2, urls.size)
            val msg = e.toString() + "|" + (e.message ?: "")
            // PROOF: the thrown message leaks NO key and NO key= query param.
            assertFalse("exception leaked an AIza key: $msg", msg.contains("AIza"))
            assertFalse("exception leaked a key= query: $msg", msg.contains("key="))
            // it IS clear about what happened (403, all keys rejected)
            assertTrue("error should mention HTTP 403", msg.contains("403"))
            assertTrue("error should say all keys rejected", msg.contains("rejected"))
        }
    }

    @Test
    fun transport_exception_error_is_key_free() = runTest {
        // A Ktor/transport exception could embed the request URL (which carries
        // `?key=AIza...`). The client must rewrap it to a key-free descriptor.
        val engine = MockEngine { throw RuntimeException("connect failed to https://x/?key=" + fakeKey) }
        val gemini = GeminiTextClient(
            apiKeys = listOf(fakeKey),
            model = "gemini-test",
            httpClient = HttpClient(engine),
        )
        try {
            gemini.complete("hi")
            fail("expected the transport failure to propagate")
        } catch (e: RuntimeException) {
            val msg = e.toString() + "|" + (e.message ?: "")
            assertFalse("exception leaked an AIza key: $msg", msg.contains("AIza"))
            assertFalse("exception leaked a key= query: $msg", msg.contains("key="))
        }
    }
}
