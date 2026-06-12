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
 *
 * Also covers the Android research deviations (2026-06-11): the one-listener
 * fragment on every prompt and the writeOpening session opening.
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

    /** Stable marker of the one-listener fragment (oneListenerLine). */
    private val oneListenerMarker = "מאזין אחד"

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

    // ---- one-listener fragment (research finding 1) -----------------------

    @Test
    fun every_prompt_contains_one_listener_fragment() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(
            timeStr = "08:00", partOfDay = "בוקר", weather = "שמשי",
            generalHeadline = "כותרת כללית",
            topicHeadlines = mapOf("ספורט" to "כותרת ספורט"),
        )
        brain.writeBreak(prev, nxt, beat = "song", ctx = ctx, seconds = 8.0)
        brain.writeBreak(prev, nxt, beat = "weather", ctx = ctx, seconds = 8.0)
        brain.writeBreak(prev, nxt, beat = "news", ctx = ctx, seconds = 8.0)
        brain.writeBreak(prev, nxt, beat = "topic", ctx = ctx, seconds = 8.0, topic = "ספורט")
        brain.refine("שורת קישור כלשהי", budget = 20)
        brain.writeOpening(nxt = nxt, ctx = ctx, seconds = 10.0)
        assertEquals(6, client.prompts.size)
        client.prompts.forEachIndexed { idx, p ->
            assertTrue("prompt #$idx missing one-listener fragment", p.contains(oneListenerMarker))
        }
    }

    // ---- writeOpening (research finding 3) --------------------------------

    @Test
    fun writeOpening_prompt_contains_partOfDay_time_song_and_one_listener() = runTest {
        val client = FakeLlmClient(listOf("בוקר טוב"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(timeStr = "08:00", partOfDay = "בוקר")
        brain.writeOpening(nxt = nxt, ctx = ctx, seconds = 10.0)
        val p = client.prompts.first()
        assertTrue("part of day missing", p.contains("בוקר"))
        assertTrue("time missing", p.contains("08:00"))
        assertTrue("song title missing", p.contains("Creep"))
        assertTrue("artist missing", p.contains("Radiohead"))
        assertTrue("one-listener fragment missing", p.contains(oneListenerMarker))
        // it is a real opening prompt, not the generic handoff
        assertTrue(p.contains("המילים הראשונות של השידור"))
        // an opening always speaks: no SKIP escape hatch
        assertFalse("opening must not offer SKIP", p.contains(SKIP_TOKEN))
    }

    @Test
    fun writeOpening_greets_generically_when_ctx_empty() = runTest {
        val client = FakeLlmClient(listOf("שלום שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeOpening(nxt = nxt, ctx = DjContext(), seconds = 10.0)
        val p = client.prompts.first()
        assertTrue("generic greeting instruction missing", p.contains("ברכת שלום חמה וכללית"))
        assertFalse("no time line expected", p.contains("השעה null"))
        assertTrue(p.contains(oneListenerMarker))
    }

    @Test
    fun writeOpening_output_is_finished_to_budget() = runTest {
        // seconds = 10.0 -> budget = 25 words; feed 40 words with no sentence end
        // so finish() must cap at the budget.
        val long = (1..40).joinToString(" ") { "שלום" }
        val client = FakeLlmClient(listOf(long))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeOpening(nxt = nxt, ctx = DjContext(partOfDay = "ערב"), seconds = 10.0)
        val words = out.split(Regex("\\s+")).filter { it.isNotEmpty() }
        assertTrue("output exceeds budget: ${words.size}", words.size <= wordsForSeconds(10.0))
        assertTrue(out.isNotEmpty())
    }

    // ---- mood line (mood support) ------------------------------------------

    /** Stable marker shared by all non-empty mood djLines. */
    private val moodMarker = "השידור עכשיו במצב"

    @Test
    fun writeBreak_prompt_contains_mood_line_when_mood_set() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(mood = "late_night"), seconds = 8.0)
        val p = client.prompts.first()
        assertTrue("mood line missing", p.contains(Moods.ALL.getValue("late_night").djLine))
    }

    @Test
    fun every_prompt_kind_contains_mood_line_when_mood_set() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(
            timeStr = "23:00", partOfDay = "לילה", weather = "קריר",
            generalHeadline = "כותרת כללית",
            topicHeadlines = mapOf("ספורט" to "כותרת ספורט"),
            mood = "late_night",
        )
        brain.writeBreak(prev, nxt, beat = "song", ctx = ctx, seconds = 8.0)
        brain.writeBreak(prev, nxt, beat = "weather", ctx = ctx, seconds = 8.0)
        brain.writeBreak(prev, nxt, beat = "news", ctx = ctx, seconds = 8.0)
        brain.writeBreak(prev, nxt, beat = "topic", ctx = ctx, seconds = 8.0, topic = "ספורט")
        brain.writeOpening(nxt = nxt, ctx = ctx, seconds = 10.0)
        assertEquals(5, client.prompts.size)
        client.prompts.forEachIndexed { idx, p ->
            assertTrue("prompt #$idx missing mood line", p.contains(moodMarker))
        }
    }

    @Test
    fun prompt_has_no_mood_line_when_mood_null() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        assertFalse(client.prompts.first().contains(moodMarker))
    }

    @Test
    fun mix_mood_prompt_is_byte_identical_to_null_mood_prompt() = runTest {
        // mix has an empty djLine: prompts must stay byte-identical to today's.
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(mood = null), seconds = 8.0)
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(mood = "mix"), seconds = 8.0)
        assertEquals(client.prompts[0], client.prompts[1])
        assertFalse(client.prompts[1].contains(moodMarker))
    }
}