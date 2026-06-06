package ai.kolai.station

import ai.kolai.core.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for DjBrain, ported from backend/radioai/djbrain.py (DJBrain). Uses a
 * recording FakeLlmClient so we can assert on the prompt the model received as
 * well as the shaped output.
 */
class DjBrainTest {

    /** Records every prompt; replays canned responses in order (last reused). */
    private class FakeLlmClient(
        private val responses: List<String>,
        val prompts: MutableList<String> = mutableListOf(),
    ) : LlmClient {
        private var i = 0
        override suspend fun complete(prompt: String): String {
            prompts.add(prompt)
            val call = i
            i++
            return responses[call.coerceAtMost(responses.size - 1)]
        }
    }

    private val prev = Song(title = "Yesterday", artist = "The Beatles")
    private val nxt = Song(title = "Creep", artist = "Radiohead")

    @Test
    fun writeBreak_song_allowSkip_returns_null_on_skip() = runTest {
        val client = FakeLlmClient(listOf("SKIP"))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0, allowSkip = true)
        assertNull(out)
    }

    @Test
    fun writeBreak_song_returns_cleaned_line() = runTest {
        val client = FakeLlmClient(listOf("**שלום** (english note) world!"))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0, allowSkip = true)
        assertNotNull(out)
        assertFalse(out!!.any { it in 'a'..'z' || it in 'A'..'Z' })
        assertTrue(out.contains("שלום"))
    }

    @Test
    fun writeBreak_song_prompt_contains_naming_instruction() = runTest {
        // Guards against silently dropping "name the songs on air".
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        val p = client.prompts.first()
        assertTrue("naming-line instruction missing", p.contains("back-announce"))
        assertTrue(p.contains("שזור את שם השיר ושם האמן"))
    }

    @Test
    fun writeBreak_weather_uses_weather_prompt_when_ctx_present() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(timeStr = "08:00", partOfDay = "בוקר", weather = "שמשי ונעים")
        brain.writeBreak(prev, nxt, beat = "weather", ctx = ctx, seconds = 8.0)
        val p = client.prompts.first()
        assertTrue("weather string not in prompt", p.contains("שמשי ונעים"))
        assertTrue(p.contains("מזג האוויר עכשיו"))
        // weather prompt has no naming-line (only the song handoff does)
        assertFalse(p.contains("back-announce"))
    }

    @Test
    fun writeBreak_weather_falls_back_to_song_prompt_when_ctx_null() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        // weather beat but ctx.weather is null -> song handoff prompt
        brain.writeBreak(prev, nxt, beat = "weather", ctx = DjContext(weather = null), seconds = 8.0)
        val p = client.prompts.first()
        assertFalse("should not be weather prompt", p.contains("מזג האוויר עכשיו"))
        assertTrue("should be song handoff", p.contains("back-announce"))
    }

    @Test
    fun writeIntro_returns_finished_line() = runTest {
        val client = FakeLlmClient(listOf("**ברוכים הבאים** to the show!"))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeIntro(prev = null, nxt = nxt, seconds = 8.0)
        assertFalse(out.any { it in 'a'..'z' || it in 'A'..'Z' })
        assertTrue(out.contains("ברוכים הבאים"))
        // opening: prev == null -> "זו פתיחת השידור."
        assertTrue(client.prompts.first().contains("זו פתיחת השידור"))
    }
}