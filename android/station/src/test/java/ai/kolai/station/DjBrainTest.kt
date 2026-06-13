package ai.kolai.station

import ai.kolai.core.Song
import kotlin.random.Random
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
        // Two fresh brains with the same seeded Random so the flavor nudge and
        // (empty) anti-repetition memory match; the only variable is the mood.
        val c1 = FakeLlmClient(listOf("שלום"))
        val c2 = FakeLlmClient(listOf("שלום"))
        val b1 = DjBrain(c1, persona = "דני", random = Random(42))
        val b2 = DjBrain(c2, persona = "דני", random = Random(42))
        b1.writeBreak(prev, nxt, beat = "song", ctx = DjContext(mood = null), seconds = 8.0)
        b2.writeBreak(prev, nxt, beat = "song", ctx = DjContext(mood = "mix"), seconds = 8.0)
        assertEquals(c1.prompts[0], c2.prompts[0])
        assertFalse(c2.prompts[0].contains(moodMarker))
    }

    // ---- anti-repetition memory (2026-06-12) -------------------------------

    /** Stable marker of the avoid-instruction (avoidLine). */
    private val avoidMarker = "אל תחזור על הפתיחים/הניסוחים האלה"

    @Test
    fun first_prompt_has_no_avoid_instruction() = runTest {
        val client = FakeLlmClient(listOf("שלום עולם"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        assertFalse(client.prompts.first().contains(avoidMarker))
    }

    @Test
    fun second_prompt_contains_avoid_instruction_with_recent_line() = runTest {
        val client = FakeLlmClient(listOf("ערב מושלם לשיר הזה", "שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        val p = client.prompts[1]
        assertTrue("avoid instruction missing", p.contains(avoidMarker))
        assertTrue("recent line missing from avoid list", p.contains("ערב מושלם לשיר הזה"))
    }

    @Test
    fun memory_keeps_last_8_and_evicts_oldest() = runTest {
        val responses = (1..10).map { "שורה מספר $it" }
        val client = FakeLlmClient(responses)
        val brain = DjBrain(client, persona = "דני")
        repeat(10) { brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0) }
        val mem = brain.recentLinesSnapshot()
        assertEquals(DjBrain.MEMORY_SIZE, mem.size)
        assertEquals("שורה מספר 3", mem.first()) // 1 and 2 evicted
        assertEquals("שורה מספר 10", mem.last())
    }

    @Test
    fun avoid_list_truncates_remembered_lines_to_60_chars() = runTest {
        val long = "א".repeat(80)
        val client = FakeLlmClient(listOf(long, "שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        val p = client.prompts[1]
        assertTrue("truncated line missing", p.contains("א".repeat(60)))
        assertFalse("line not truncated to 60", p.contains("א".repeat(61)))
    }

    @Test
    fun skipped_output_is_not_remembered() = runTest {
        val client = FakeLlmClient(listOf("SKIP", "שלום"))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0, allowSkip = true)
        assertNull(out)
        assertTrue("SKIP must not be remembered", brain.recentLinesSnapshot().isEmpty())
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        assertFalse(client.prompts[1].contains(avoidMarker))
    }

    @Test
    fun opening_and_intro_lines_are_remembered_too() = runTest {
        val client = FakeLlmClient(listOf("בוקר טוב לך", "קישור נחמד"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeOpening(nxt = nxt, ctx = DjContext(partOfDay = "בוקר"), seconds = 10.0)
        brain.writeIntro(prev = prev, nxt = nxt, seconds = 8.0)
        assertEquals(listOf("בוקר טוב לך", "קישור נחמד"), brain.recentLinesSnapshot())
        // and the second prompt warns against repeating the opening line
        assertTrue(client.prompts[1].contains(avoidMarker))
        assertTrue(client.prompts[1].contains("בוקר טוב לך"))
    }

    // ---- variety flavor nudges (seeded Random) -----------------------------

    @Test
    fun seeded_random_gives_reproducible_flavor_nudge() = runTest {
        val c1 = FakeLlmClient(listOf("שלום"))
        val c2 = FakeLlmClient(listOf("שלום"))
        val b1 = DjBrain(c1, persona = "דני", random = Random(7))
        val b2 = DjBrain(c2, persona = "דני", random = Random(7))
        b1.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        b2.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        assertEquals(c1.prompts[0], c2.prompts[0])
        assertTrue(
            "prompt must carry one of the flavor nudges",
            DjBrain.FLAVOR_NUDGES.any { c1.prompts[0].contains(it) },
        )
    }

    @Test
    fun flavor_nudges_rotate_across_calls() = runTest {
        // SKIP responses keep the memory empty, so prompts differ only by flavor.
        val client = FakeLlmClient(listOf("SKIP"))
        val brain = DjBrain(client, persona = "דני", random = Random(1))
        repeat(12) {
            brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0, allowSkip = true)
        }
        val used = DjBrain.FLAVOR_NUDGES.filter { n -> client.prompts.any { it.contains(n) } }
        assertTrue("expected at least 2 distinct nudges, got ${used.size}", used.size >= 2)
    }

    // ---- back-announce of the outgoing song --------------------------------

    /** Stable marker of the back-announce nudge (backAnnounceLine). */
    private val backAnnounceMarker = "השיר שהרגע הסתיים"

    @Test
    fun weather_prompt_offers_back_announce_when_prev_known() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(timeStr = "08:00", partOfDay = "בוקר", weather = "שמשי")
        brain.writeBreak(prev, nxt, beat = "weather", ctx = ctx, seconds = 8.0)
        val p = client.prompts.first()
        assertTrue("back-announce nudge missing", p.contains(backAnnounceMarker))
        assertTrue("outgoing song title missing", p.contains("Yesterday"))
        assertTrue("outgoing artist missing", p.contains("The Beatles"))
    }

    @Test
    fun weather_prompt_has_no_back_announce_when_prev_null() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(timeStr = "08:00", partOfDay = "בוקר", weather = "שמשי")
        brain.writeBreak(null, nxt, beat = "weather", ctx = ctx, seconds = 8.0)
        assertFalse(client.prompts.first().contains(backAnnounceMarker))
    }

    // ---- sharper quality gate ----------------------------------------------

    @Test
    fun allowSkip_prompt_contains_raised_bar_sentence() = runTest {
        val client = FakeLlmClient(listOf("SKIP"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0, allowSkip = true)
        val p = client.prompts.first()
        assertTrue("raised-bar sentence missing", p.contains("מילוי גרוע משתיקה"))
        // the original skip line stays verbatim right before it
        assertTrue("original skip line missing", p.contains("החזר בדיוק את המילה SKIP"))
    }

    @Test
    fun no_skip_prompt_has_no_raised_bar_sentence() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        assertFalse(client.prompts.first().contains("מילוי גרוע משתיקה"))
    }

    @Test
    fun additions_leave_existing_fragments_untouched() = runTest {
        // mood + one-listener + naming + persona must all survive the new
        // appended fragments on the same prompt.
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(mood = "late_night"), seconds = 8.0, allowSkip = true)
        val p = client.prompts.first()
        assertTrue(p.contains(oneListenerMarker))
        assertTrue(p.contains("back-announce"))
        assertTrue(p.contains(moodMarker))
        assertTrue(p.contains("שדרן רדיו ישראלי"))
    }

    // ---- calendar line + somber override (feature 7) -----------------------

    /** Stable marker of the calendar blessing line (calendarLine). */
    private val calendarMarker = "בלי לחפור"

    /** Stable marker of the somber respectful-tone line. */
    private val somberMarker = "בלי משחקי מילים"

    /** Stable marker of the taste wink fragment (tasteWinkLine). */
    private val winkMarker = "מהגדולים שלו"

    @Test
    fun calendar_line_present_in_song_weather_and_opening_prompts() = runTest {
        val client = FakeLlmClient(listOf("SKIP"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(
            timeStr = "18:00", partOfDay = "ערב", weather = "נעים",
            calendarNote = "ערב שבת",
        )
        brain.writeBreak(prev, nxt, beat = "song", ctx = ctx, seconds = 8.0, allowSkip = true)
        brain.writeBreak(prev, nxt, beat = "weather", ctx = ctx, seconds = 8.0, allowSkip = true)
        brain.writeOpening(nxt = nxt, ctx = ctx, seconds = 10.0)
        assertEquals(3, client.prompts.size)
        client.prompts.forEachIndexed { idx, p ->
            assertTrue("prompt #$idx missing calendar note", p.contains("ערב שבת"))
            assertTrue("prompt #$idx missing calendar line", p.contains(calendarMarker))
        }
    }

    @Test
    fun calendar_line_absent_when_note_null() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        assertFalse(client.prompts.first().contains(calendarMarker))
    }

    @Test
    fun somber_replaces_blessing_with_respectful_line_and_kills_flavor_nudge() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(calendarNote = "יום הזיכרון", somber = true)
        brain.writeBreak(prev, nxt, beat = "song", ctx = ctx, seconds = 8.0)
        val p = client.prompts.first()
        assertTrue("respectful line missing", p.contains(somberMarker))
        assertTrue("memorial-day name missing", p.contains("יום הזיכרון"))
        assertFalse("blessing line must not appear when somber", p.contains(calendarMarker))
        assertTrue(
            "somber must kill the flavor nudge",
            DjBrain.FLAVOR_NUDGES.none { p.contains(it) },
        )
    }

    @Test
    fun somber_without_note_still_adds_respectful_line() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(somber = true), seconds = 8.0)
        assertTrue(client.prompts.first().contains(somberMarker))
    }

    // ---- day-part handover (feature 8) --------------------------------------

    @Test
    fun writeHandover_prompt_marks_the_daypart_transition() = runTest {
        val client = FakeLlmClient(listOf("עשר בלילה"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(timeStr = "22:00", partOfDay = "לילה")
        brain.writeHandover(ctx = ctx, nxt = nxt, seconds = 8.0)
        val p = client.prompts.first()
        assertTrue("time missing", p.contains("22:00"))
        assertTrue("new part of day missing", p.contains("לילה"))
        assertTrue("transition instruction missing", p.contains("חילופי הזמן"))
        assertTrue("example feel missing", p.contains("העיר נרגעת"))
        assertTrue("next song missing", p.contains("Creep"))
        assertTrue("artist missing", p.contains("Radiohead"))
        assertTrue(p.contains(oneListenerMarker))
        assertFalse("handover must not offer SKIP", p.contains(SKIP_TOKEN))
    }

    @Test
    fun writeHandover_output_is_remembered_in_the_ring() = runTest {
        val client = FakeLlmClient(listOf("עשר בלילה, העיר נרגעת", "שלום"))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeHandover(ctx = DjContext(partOfDay = "לילה"), nxt = nxt)
        assertEquals(listOf(out), brain.recentLinesSnapshot())
        brain.writeHandover(ctx = DjContext(partOfDay = "לילה"), nxt = nxt)
        assertTrue(client.prompts[1].contains(avoidMarker))
        assertTrue(client.prompts[1].contains("עשר בלילה, העיר נרגעת"))
    }

    @Test
    fun writeHandover_respects_word_budget() = runTest {
        val long = (1..40).joinToString(" ") { "לילה" }
        val client = FakeLlmClient(listOf(long))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeHandover(ctx = DjContext(partOfDay = "לילה"), nxt = nxt, seconds = 8.0)
        val words = out.split(Regex("\\s+")).filter { it.isNotEmpty() }
        assertTrue("output exceeds budget: ${words.size}", words.size <= wordsForSeconds(8.0))
    }

    // ---- taste wink (feature 9) ---------------------------------------------

    @Test
    fun taste_wink_present_when_allowed_and_rank_low() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        val fav = nxt.copy(tasteRank = 0)
        brain.writeIntro(prev, fav, seconds = 8.0, ctx = DjContext(), allowTasteWink = true)
        val p = client.prompts.first()
        assertTrue("wink fragment missing", p.contains(winkMarker))
        assertTrue("wink should be optional, one hint", p.contains("בקריצה"))
    }

    @Test
    fun taste_wink_boundary_rank_14_in_15_out() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeIntro(prev, nxt.copy(tasteRank = 14), seconds = 8.0, allowTasteWink = true)
        brain.writeIntro(prev, nxt.copy(tasteRank = 15), seconds = 8.0, allowTasteWink = true)
        assertTrue("rank 14 must wink", client.prompts[0].contains(winkMarker))
        assertFalse("rank 15 must not wink", client.prompts[1].contains(winkMarker))
    }

    @Test
    fun taste_wink_absent_at_rank_20_even_when_allowed() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeIntro(prev, nxt.copy(tasteRank = 20), seconds = 8.0, allowTasteWink = true)
        assertFalse(client.prompts.first().contains(winkMarker))
    }

    @Test
    fun taste_wink_absent_by_default_even_for_top_taste_track() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeIntro(prev, nxt.copy(tasteRank = 0), seconds = 8.0)
        assertFalse(client.prompts.first().contains(winkMarker))
    }

    @Test
    fun default_writeIntro_prompt_is_byte_identical_to_explicit_wink_false() = runTest {
        val c1 = FakeLlmClient(listOf("שלום"))
        val c2 = FakeLlmClient(listOf("שלום"))
        val b1 = DjBrain(c1, persona = "דני", random = Random(42))
        val b2 = DjBrain(c2, persona = "דני", random = Random(42))
        val fav = nxt.copy(tasteRank = 0)
        b1.writeIntro(prev, fav, seconds = 8.0)
        b2.writeIntro(prev, fav, seconds = 8.0, ctx = null, allowTasteWink = false)
        assertEquals(c1.prompts[0], c2.prompts[0])
    }

    // ---- «משהו טוב לדרך» (feature 10) ---------------------------------------

    @Test
    fun goodThing_prompt_has_verbatim_opener_artist_restriction_and_high_bar() = runTest {
        val client = FakeLlmClient(listOf("SKIP"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(generalHeadline = "חדשות טובות בכלכלה")
        brain.writeGoodThing(ctx = ctx, nxt = nxt)
        val p = client.prompts.first()
        assertTrue("verbatim opener instruction missing", p.contains(DjBrain.GOOD_THING_OPENER))
        assertTrue("artist restriction missing", p.contains("ורק עליו"))
        assertTrue("artist name missing", p.contains("Radiohead"))
        assertTrue("headline missing", p.contains("חדשות טובות בכלכלה"))
        assertTrue("skip line missing", p.contains("החזר בדיוק את המילה SKIP"))
        assertTrue("high bar missing", p.contains("אם אין באמת משהו טוב או מעניין - SKIP"))
    }

    @Test
    fun goodThing_skip_returns_empty_and_is_not_remembered() = runTest {
        val client = FakeLlmClient(listOf("SKIP"))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeGoodThing(ctx = DjContext(), nxt = nxt)
        assertEquals("", out)
        assertTrue(brain.recentLinesSnapshot().isEmpty())
    }

    @Test
    fun goodThing_output_opens_with_brand_even_if_model_omitted_it() = runTest {
        val client = FakeLlmClient(listOf("רדיוהד הוציאו השבוע אלבום חדש."))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeGoodThing(ctx = DjContext(), nxt = nxt)
        assertTrue("brand opener missing: $out", out.startsWith("ומשהו טוב לדרך"))
        assertTrue(out.contains("רדיוהד"))
        assertEquals(listOf(out), brain.recentLinesSnapshot())
    }

    @Test
    fun goodThing_does_not_double_the_brand_when_model_includes_it() = runTest {
        val client = FakeLlmClient(listOf("ומשהו טוב לדרך - יום שמש נדיר החודש."))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeGoodThing(ctx = DjContext(), nxt = nxt)
        assertEquals(0, out.indexOf("ומשהו טוב לדרך"))
        assertEquals(0, out.lastIndexOf("ומשהו טוב לדרך"))
    }

    @Test
    fun goodThing_is_hard_capped_at_30_words_even_with_generous_seconds() = runTest {
        val long = "ומשהו טוב לדרך - " + (1..40).joinToString(" ") { "שמש" }
        val client = FakeLlmClient(listOf(long))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeGoodThing(ctx = DjContext(), nxt = nxt, seconds = 60.0)
        val words = out.split(Regex("\\s+")).filter { it.isNotEmpty() }
        assertTrue("good thing exceeds 30 words: ${words.size}", words.size <= DjBrain.GOOD_THING_WORD_CAP)
    }

    // ---- two-voice banter (feature 11) ---------------------------------------

    @Test
    fun banter_parses_clean_json_into_speaker_tagged_turns() = runTest {
        val reply = """[{"s":"A","t":"שמעת את השיר הבא?"},{"s":"B","t":"שמעתי. בסדר גמור."},{"s":"A","t":"בסדר גמור?! תקשיב עוד פעם."}]"""
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeBanter(prev, nxt, ctx = DjContext())
        assertEquals(3, turns.size)
        assertEquals(listOf("A", "B", "A"), turns.map { it.first })
        assertEquals("שמעת את השיר הבא?", turns[0].second)
        assertEquals("שמעתי. בסדר גמור.", turns[1].second)
    }

    @Test
    fun banter_parses_fenced_json() = runTest {
        val reply = "```json\n" +
            """[{"s":"A","t":"שיר ענק עכשיו."},{"s":"B","t":"סוף סוף משהו טוב."},{"s":"A","t":"קדימה, מוזיקה."}]""" +
            "\n```"
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeBanter(prev, nxt, ctx = DjContext())
        assertEquals(3, turns.size)
        assertEquals("A", turns.last().first)
    }

    @Test
    fun banter_garbage_returns_empty_list_and_never_throws() = runTest {
        val client = FakeLlmClient(listOf("סתם טקסט שהוא בכלל לא מערך"))
        val brain = DjBrain(client, persona = "דני")
        assertTrue(brain.writeBanter(prev, nxt, ctx = DjContext()).isEmpty())
    }

    @Test
    fun banter_empty_array_returns_empty_list() = runTest {
        val client = FakeLlmClient(listOf("[]"))
        val brain = DjBrain(client, persona = "דני")
        assertTrue(brain.writeBanter(prev, nxt, ctx = DjContext()).isEmpty())
    }

    @Test
    fun banter_invalid_speaker_returns_empty_list() = runTest {
        val reply = """[{"s":"C","t":"מי אני בכלל?"},{"s":"A","t":"לא יודע."}]"""
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        assertTrue(brain.writeBanter(prev, nxt, ctx = DjContext()).isEmpty())
    }

    @Test
    fun banter_five_turns_are_truncated_to_three_ending_with_A() = runTest {
        val reply = """[{"s":"A","t":"אחת."},{"s":"B","t":"שתיים."},{"s":"A","t":"שלוש."},{"s":"B","t":"ארבע."},{"s":"A","t":"חמש."}]"""
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeBanter(prev, nxt, ctx = DjContext())
        assertEquals(3, turns.size)
        assertEquals("A", turns.first().first)
        assertEquals("A", turns.last().first)
    }

    @Test
    fun banter_overlong_turn_is_dropped_whole_with_everything_after_it() = runTest {
        val longTurn = (1..25).joinToString(" ") { "מילה" }
        val reply = """[{"s":"A","t":"פתיח קצר."},{"s":"B","t":"תגובה יבשה."},{"s":"A","t":"סוגרים למוזיקה."},{"s":"B","t":"$longTurn"}]"""
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeBanter(prev, nxt, ctx = DjContext())
        assertEquals(3, turns.size)
        // words are never cut: the surviving turns are intact
        assertEquals("סוגרים למוזיקה.", turns.last().second)
    }

    @Test
    fun banter_prompt_carries_sidekick_persona_and_json_format() = runTest {
        val client = FakeLlmClient(listOf("[]"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBanter(prev, nxt, ctx = DjContext())
        val p = client.prompts.first()
        assertTrue("sidekick persona missing", p.contains(DjBrain.SIDEKICK_PERSONA))
        assertTrue("JSON format instruction missing", p.contains("\"s\":\"A\""))
        assertTrue("A must close into the music", p.contains("סוגר במסירה אל המוזיקה"))
    }

    @Test
    fun banter_is_remembered_in_the_ring() = runTest {
        val reply = """[{"s":"A","t":"שמעת את השיר הבא?"},{"s":"B","t":"שמעתי."},{"s":"A","t":"יאללה מוזיקה."}]"""
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBanter(prev, nxt, ctx = DjContext())
        val mem = brain.recentLinesSnapshot()
        assertEquals(1, mem.size)
        assertTrue(mem.first().contains("שמעת את השיר הבא?"))
    }

    @Test
    fun banter_never_fires_when_somber_no_llm_call() = runTest {
        val client = FakeLlmClient(listOf("לא אמור להיקרא"))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeBanter(prev, nxt, ctx = DjContext(somber = true))
        assertTrue(turns.isEmpty())
        assertTrue("somber banter must not call the LLM", client.prompts.isEmpty())
    }

    // ---- Friday recap opening (feature 12) -----------------------------------

    private val recapBrief = "47 שירים השבוע; אמן השבוע: עומר אדם (6 השמעות); ז'אנר מוביל: פופ"

    @Test
    fun recapOpening_prompt_contains_brief_numbers_and_greeting_fragments() = runTest {
        val client = FakeLlmClient(listOf("בוקר טוב"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(timeStr = "08:00", partOfDay = "בוקר", recapBrief = recapBrief)
        brain.writeRecapOpening(nxt = nxt, ctx = ctx, seconds = 20.0)
        val p = client.prompts.first()
        assertTrue("brief numbers missing", p.contains("47 שירים השבוע"))
        assertTrue("artist of the week missing", p.contains("עומר אדם"))
        assertTrue("opening greeting fragment missing", p.contains("המילים הראשונות של השידור"))
        assertTrue("moment greeting missing", p.contains("השעה 08:00"))
        assertTrue("surprising-stat instruction missing", p.contains("מפתיע"))
        assertTrue("first song missing", p.contains("Creep"))
        assertTrue(p.contains(oneListenerMarker))
        assertFalse("opening must not offer SKIP", p.contains(SKIP_TOKEN))
    }

    @Test
    fun recapOpening_falls_back_to_plain_opening_when_brief_null() = runTest {
        val client = FakeLlmClient(listOf("בוקר טוב"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeRecapOpening(nxt = nxt, ctx = DjContext(partOfDay = "בוקר"), seconds = 20.0)
        val p = client.prompts.first()
        assertTrue("should be the plain opening", p.contains("המילים הראשונות של השידור"))
        assertFalse("recap framing must be absent", p.contains("הסיכום השבועי האישי"))
    }

    @Test
    fun recapOpening_output_is_remembered_in_the_ring() = runTest {
        val client = FakeLlmClient(listOf("בוקר טוב, איזה שבוע"))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeRecapOpening(nxt = nxt, ctx = DjContext(recapBrief = recapBrief))
        assertEquals(listOf(out), brain.recentLinesSnapshot())
    }

    // ---- byte-identity lock: new features off => old prompts unchanged -------

    @Test
    fun legacy_prompts_carry_none_of_the_new_fragments_when_unused() = runTest {
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
        brain.writeIntro(prev, nxt.copy(tasteRank = 0), seconds = 8.0, ctx = ctx)
        brain.writeOpening(nxt = nxt, ctx = ctx, seconds = 10.0)
        brain.refine("שורת קישור כלשהי", budget = 20, ctx = ctx)
        val newMarkers = listOf(
            calendarMarker, somberMarker, winkMarker,
            DjBrain.GOOD_THING_OPENER, DjBrain.SIDEKICK_PERSONA,
            "הסיכום השבועי האישי", "חילופי הזמן",
        )
        assertEquals(7, client.prompts.size)
        client.prompts.forEachIndexed { idx, p ->
            newMarkers.forEach { m ->
                assertFalse("prompt #$idx unexpectedly contains new fragment: $m", p.contains(m))
            }
        }
    }

    // ---- trivia game (2026-06-13) -------------------------------------------

    /** Stable marker of the quizmaster framing (QUIZMASTER_FRAMING). */
    private val quizMarker = "הקוויזמאסטר"

    @Test
    fun trivia_parses_clean_json_into_speaker_tagged_turns() = runTest {
        val reply = """[{"s":"A","t":"מאיפה רדיוהד במקור?"},{"s":"B","t":"לא יודע, נשמע בריטי."},{"s":"A","t":"בול. נגלה אחרי השיר."}]"""
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeTrivia(nxt, ctx = DjContext())
        assertEquals(3, turns.size)
        assertEquals(listOf("A", "B", "A"), turns.map { it.first })
        assertEquals("מאיפה רדיוהד במקור?", turns[0].second)
    }

    @Test
    fun trivia_garbage_returns_empty_list_and_never_throws() = runTest {
        val client = FakeLlmClient(listOf("סתם טקסט שאינו מערך בכלל"))
        val brain = DjBrain(client, persona = "דני")
        assertTrue(brain.writeTrivia(nxt, ctx = DjContext()).isEmpty())
    }

    @Test
    fun trivia_empty_array_returns_empty_list() = runTest {
        val client = FakeLlmClient(listOf("[]"))
        val brain = DjBrain(client, persona = "דני")
        assertTrue(brain.writeTrivia(nxt, ctx = DjContext()).isEmpty())
    }

    @Test
    fun trivia_never_fires_when_somber_no_llm_call() = runTest {
        val client = FakeLlmClient(listOf("לא אמור להיקרא"))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeTrivia(nxt, ctx = DjContext(somber = true))
        assertTrue(turns.isEmpty())
        assertTrue("somber trivia must not call the LLM", client.prompts.isEmpty())
    }

    @Test
    fun trivia_prompt_carries_quizmaster_framing_artist_and_json_format() = runTest {
        val client = FakeLlmClient(listOf("[]"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeTrivia(nxt, ctx = DjContext())
        val p = client.prompts.first()
        assertTrue("quizmaster framing missing", p.contains(quizMarker))
        assertTrue("artist restriction missing", p.contains("על האמן של השיר הבא בלבד"))
        assertTrue("artist name missing", p.contains("Radiohead"))
        assertTrue("tease line missing", p.contains("נגלה אחרי השיר"))
        assertTrue("JSON format instruction missing", p.contains("\"s\":\"A\""))
        assertTrue(p.contains(oneListenerMarker))
    }

    @Test
    fun trivia_is_remembered_in_the_ring() = runTest {
        val reply = """[{"s":"A","t":"שאלה כיפית?"},{"s":"B","t":"אולי."},{"s":"A","t":"נגלה אחרי השיר."}]"""
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        brain.writeTrivia(nxt, ctx = DjContext())
        val mem = brain.recentLinesSnapshot()
        assertEquals(1, mem.size)
        assertTrue(mem.first().contains("שאלה כיפית?"))
    }

    @Test
    fun trivia_five_turns_truncated_and_ends_with_A() = runTest {
        val reply = """[{"s":"A","t":"אחת."},{"s":"B","t":"שתיים."},{"s":"A","t":"שלוש."},{"s":"B","t":"ארבע."},{"s":"A","t":"חמש."}]"""
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeTrivia(nxt, ctx = DjContext())
        assertEquals(3, turns.size)
        assertTrue("must not exceed cap", turns.size <= DjBrain.TRIVIA_MAX_TURNS)
        assertEquals("A", turns.last().first)
    }

    // ---- banter persona rotation (2026-06-13) -------------------------------

    @Test
    fun sidekick_persona_count_is_exposed_and_index_zero_is_legacy() = runTest {
        assertEquals(3, DjBrain.sidekickPersonaCount)
        assertEquals(DjBrain.SIDEKICK_PERSONA, DjBrain.SIDEKICK_PERSONAS[0])
    }

    @Test
    fun legacy_writeBanter_uses_persona_index_zero() = runTest {
        val client = FakeLlmClient(listOf("[]"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBanter(prev, nxt, ctx = DjContext())
        assertTrue(client.prompts.first().contains(DjBrain.SIDEKICK_PERSONAS[0]))
    }

    @Test
    fun banter_persona_rotation_injects_distinct_persona_text_per_index() = runTest {
        // one client, three calls with indices 0/1/2; each prompt must carry
        // its own persona and not the others'.
        val client = FakeLlmClient(listOf("[]"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeBanter(prev, nxt, ctx = DjContext(), seconds = 14.0, sidekickIndex = 0)
        brain.writeBanter(prev, nxt, ctx = DjContext(), seconds = 14.0, sidekickIndex = 1)
        brain.writeBanter(prev, nxt, ctx = DjContext(), seconds = 14.0, sidekickIndex = 2)
        assertEquals(3, client.prompts.size)
        for (i in 0..2) {
            assertTrue("prompt #$i missing its own persona", client.prompts[i].contains(DjBrain.SIDEKICK_PERSONAS[i]))
        }
        // the three personas are genuinely different text
        assertEquals(3, DjBrain.SIDEKICK_PERSONAS.toSet().size)
    }

    @Test
    fun banter_persona_index_wraps_modulo_and_never_throws() = runTest {
        val client = FakeLlmClient(listOf("[]"))
        val brain = DjBrain(client, persona = "דני")
        // index 3 wraps to 0, index -1 wraps to last
        brain.writeBanter(prev, nxt, ctx = DjContext(), seconds = 14.0, sidekickIndex = 3)
        brain.writeBanter(prev, nxt, ctx = DjContext(), seconds = 14.0, sidekickIndex = -1)
        assertTrue(client.prompts[0].contains(DjBrain.SIDEKICK_PERSONAS[0]))
        assertTrue(client.prompts[1].contains(DjBrain.SIDEKICK_PERSONAS[2]))
    }

    @Test
    fun banter_rotation_still_parses_and_remembers() = runTest {
        val reply = """[{"s":"A","t":"שיר טוב."},{"s":"B","t":"וואו, מהמם!"},{"s":"A","t":"יאללה מוזיקה."}]"""
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeBanter(prev, nxt, ctx = DjContext(), seconds = 14.0, sidekickIndex = 1)
        assertEquals(3, turns.size)
        assertEquals("A", turns.last().first)
        assertEquals(1, brain.recentLinesSnapshot().size)
    }

    @Test
    fun banter_rotation_empty_when_somber_no_llm_call() = runTest {
        val client = FakeLlmClient(listOf("לא אמור להיקרא"))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeBanter(prev, nxt, ctx = DjContext(somber = true), seconds = 14.0, sidekickIndex = 2)
        assertTrue(turns.isEmpty())
        assertTrue(client.prompts.isEmpty())
    }

    // ---- micro-segment: listening cue (2026-06-13) --------------------------

    @Test
    fun listeningCue_returns_finished_line() = runTest {
        val client = FakeLlmClient(listOf("שים לב לדרופ בדקה השנייה - מצמרר."))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeListeningCue(nxt, ctx = DjContext())
        assertTrue(out.contains("שים לב"))
        assertEquals(listOf(out), brain.recentLinesSnapshot())
    }

    @Test
    fun listeningCue_skip_returns_empty_and_not_remembered() = runTest {
        val client = FakeLlmClient(listOf("SKIP"))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeListeningCue(nxt, ctx = DjContext())
        assertEquals("", out)
        assertTrue(brain.recentLinesSnapshot().isEmpty())
    }

    @Test
    fun listeningCue_empty_when_somber_no_llm_call() = runTest {
        val client = FakeLlmClient(listOf("לא אמור להיקרא"))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeListeningCue(nxt, ctx = DjContext(somber = true))
        assertEquals("", out)
        assertTrue("somber cue must not call the LLM", client.prompts.isEmpty())
    }

    @Test
    fun listeningCue_prompt_has_artist_and_skip_gate() = runTest {
        val client = FakeLlmClient(listOf("SKIP"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeListeningCue(nxt, ctx = DjContext())
        val p = client.prompts.first()
        assertTrue("song title missing", p.contains("Creep"))
        assertTrue("cue framing missing", p.contains("שים לב לרגע ב"))
        assertTrue("skip line missing", p.contains("החזר בדיוק את המילה SKIP"))
        assertTrue(p.contains(oneListenerMarker))
    }

    // ---- micro-segment: two truths and a lie (2026-06-13) -------------------

    @Test
    fun twoTruthsLie_parses_clean_json() = runTest {
        val reply = """[{"s":"A","t":"שלוש על רדיוהד, אחת שקר."},{"s":"B","t":"השנייה שקר?"},{"s":"A","t":"נראה לי כן. מוזיקה!"}]"""
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeTwoTruthsLie(nxt, ctx = DjContext())
        assertEquals(3, turns.size)
        assertEquals("A", turns.last().first)
    }

    @Test
    fun twoTruthsLie_garbage_returns_empty_list() = runTest {
        val client = FakeLlmClient(listOf("בכלל לא מערך"))
        val brain = DjBrain(client, persona = "דני")
        assertTrue(brain.writeTwoTruthsLie(nxt, ctx = DjContext()).isEmpty())
    }

    @Test
    fun twoTruthsLie_empty_when_somber_no_llm_call() = runTest {
        val client = FakeLlmClient(listOf("לא אמור להיקרא"))
        val brain = DjBrain(client, persona = "דני")
        val turns = brain.writeTwoTruthsLie(nxt, ctx = DjContext(somber = true))
        assertTrue(turns.isEmpty())
        assertTrue(client.prompts.isEmpty())
    }

    @Test
    fun twoTruthsLie_prompt_restricts_to_artist_and_phrases_speculatively() = runTest {
        val client = FakeLlmClient(listOf("[]"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeTwoTruthsLie(nxt, ctx = DjContext())
        val p = client.prompts.first()
        assertTrue("two-truths framing missing", p.contains("שתי אמיתות ושקר"))
        assertTrue("artist name missing", p.contains("Radiohead"))
        assertTrue("speculative phrasing missing", p.contains("משוערת"))
        assertTrue("JSON format missing", p.contains("\"s\":\"A\""))
        assertTrue(p.contains(oneListenerMarker))
    }

    @Test
    fun twoTruthsLie_is_remembered_in_the_ring() = runTest {
        val reply = """[{"s":"A","t":"שתיים נכונות, אחת שקר."},{"s":"B","t":"הראשונה שקר."},{"s":"A","t":"נראה אחרי השיר. מוזיקה!"}]"""
        val client = FakeLlmClient(listOf(reply))
        val brain = DjBrain(client, persona = "דני")
        brain.writeTwoTruthsLie(nxt, ctx = DjContext())
        assertEquals(1, brain.recentLinesSnapshot().size)
    }

    // ---- byte-identity lock: new fun segments off => old prompts unchanged --

    @Test
    fun new_fun_segment_fragments_absent_from_legacy_prompts() = runTest {
        val client = FakeLlmClient(listOf("שלום"))
        val brain = DjBrain(client, persona = "דני")
        val ctx = DjContext(timeStr = "08:00", partOfDay = "בוקר", weather = "שמשי")
        brain.writeBreak(prev, nxt, beat = "song", ctx = ctx, seconds = 8.0)
        brain.writeBreak(prev, nxt, beat = "weather", ctx = ctx, seconds = 8.0)
        brain.writeOpening(nxt = nxt, ctx = ctx, seconds = 10.0)
        val funMarkers = listOf(
            "הקוויזמאסטר", "שים לב לרגע ב", "שתי אמיתות ושקר",
            DjBrain.SIDEKICK_PERSONAS[1], DjBrain.SIDEKICK_PERSONAS[2],
        )
        client.prompts.forEachIndexed { idx, p ->
            funMarkers.forEach { m ->
                assertFalse("prompt #$idx unexpectedly contains fun fragment: $m", p.contains(m))
            }
        }
    }

    // ---- BUG 1: dangling song-name guard (2026-06-13) -----------------------

    @Test
    fun danglingName_true_for_trailing_connectors_and_amp() = runTest {
        val brain = DjBrain(FakeLlmClient(listOf("שלום")), persona = "דני")
        // ends on the broken "עם של" phrase
        assertTrue(brain.danglingName("תירגע רגע עם של"))
        // bare trailing connectors (with and without trailing punctuation)
        assertTrue(brain.danglingName("קח אוויר עם של."))
        assertTrue(brain.danglingName("בוא תוריד רגל מהגז עם."))
        assertTrue(brain.danglingName("תעלה קצת את הווליום של"))
        assertTrue(brain.danglingName("נראה אם יצליח לענות לך עם?"))
        assertTrue(brain.danglingName("בוא נלמד שפה חדשה עם של"))
        // bare trailing ampersand / standalone amp token
        assertTrue(brain.danglingName("בוא נחלומי חלום קטן עם ה- &"))
        assertTrue(brain.danglingName("השיר הבא הוא X & לכבוד ערב שבת"))
        // empty quote pair
        assertTrue(brain.danglingName("השיר הבא, \"\", מזכיר ששפה זה כלי"))
    }

    @Test
    fun danglingName_false_for_complete_lines() = runTest {
        val brain = DjBrain(FakeLlmClient(listOf("שלום")), persona = "דני")
        assertFalse(brain.danglingName("תירגע רגע עם ניקלבק."))
        assertFalse(brain.danglingName("הנה קריפ של רדיוהד, תהנה."))
        assertFalse(brain.danglingName("בוא תקשיב לאישהשלי של צוקוש"))
        assertFalse(brain.danglingName("ערב טוב, איזה כיף שבאת"))
        assertFalse(brain.danglingName(""))
        assertFalse(brain.danglingName(null))
        // a word that merely STARTS with a connector letter is fine
        assertFalse(brain.danglingName("בוא נשמע את עומר אדם"))
    }

    @Test
    fun naming_beat_regenerates_once_when_first_result_is_dangling() = runTest {
        // first reply dangles, second is complete -> method returns the complete one.
        val client = FakeLlmClient(listOf("תירגע רגע עם של", "תירגע רגע עם ניקלבק"))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeHandover(ctx = DjContext(partOfDay = "בוקר"), nxt = nxt, seconds = 8.0)
        assertFalse("must not air a dangling line: $out", brain.danglingName(out))
        assertTrue("expected the regenerated complete line", out.contains("ניקלבק"))
        // the retry prompt carried the sharper instruction
        assertEquals(2, client.prompts.size)
        assertTrue(client.prompts[1].contains("כתוב משפט שלם שכולל את שם השיר המלא"))
    }

    @Test
    fun naming_beat_falls_back_clean_when_dangling_twice() = runTest {
        // both replies dangle -> result ends cleanly, never "עם של".
        val client = FakeLlmClient(listOf("תירגע רגע עם של", "קח אוויר עם של"))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        assertNotNull(out)
        assertFalse("must not end dangling: $out", brain.danglingName(out))
        assertFalse("must never air 'עם של'", out!!.trimEnd('.', '!', '?', ' ').endsWith("עם של"))
        assertFalse("must not end on bare של", out.trimEnd('.', '!', '?', ' ').endsWith(" של"))
    }

    @Test
    fun naming_beat_passes_clean_line_through_without_regen() = runTest {
        val client = FakeLlmClient(listOf("הנה קריפ של רדיוהד, תהנה."))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeIntro(prev, nxt, seconds = 8.0)
        assertEquals("הנה קריפ של רדיוהד, תהנה.", out)
        assertEquals("clean line must not trigger a retry", 1, client.prompts.size)
    }

    @Test
    fun truncation_at_budget_never_leaves_dangling_connector() = runTest {
        // A long naming line whose name sits at the end and has NO sentence-end
        // punctuation: finish() hard-cuts at the budget and (pre-fix) would leave
        // a dangling "של". The guard must repair it. seconds 8.0 -> budget 20.
        // Build: 19 filler words then "עם של ניקלבק" so the budget cut lands on של.
        val filler = (1..19).joinToString(" ") { "מילה" }
        val dangling = "$filler עם של ניקלבק"
        // first call returns the long line (finish cuts the name off -> dangling);
        // the regen returns the same kind of thing -> forces the repair fallback.
        val client = FakeLlmClient(listOf(dangling, dangling))
        val brain = DjBrain(client, persona = "דני")
        val out = brain.writeBreak(prev, nxt, beat = "song", ctx = DjContext(), seconds = 8.0)
        assertNotNull(out)
        assertFalse("budget-truncated naming line must not dangle: $out", brain.danglingName(out))
    }

    // ---- BUG 2: strengthened plural-address ban -----------------------------

    @Test
    fun plural_address_ban_present_in_trivia_banter_handover_prompts() = runTest {
        val client = FakeLlmClient(listOf("[]"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeTrivia(nxt, ctx = DjContext())
        brain.writeBanter(prev, nxt, ctx = DjContext())
        brain.writeHandover(ctx = DjContext(partOfDay = "ערב"), nxt = nxt)
        assertEquals(3, client.prompts.size)
        client.prompts.forEachIndexed { idx, p ->
            assertTrue("prompt #$idx missing one-listener doctrine", p.contains(oneListenerMarker))
            assertTrue("prompt #$idx must forbid כולם as address", p.contains("\"כולם\""))
            assertTrue("prompt #$idx must forbid אנשים as address", p.contains("\"אנשים\""))
        }
    }

    // ---- BUG 3: forbid invented facts in trivia / good_thing ----------------

    @Test
    fun hallucination_forbid_present_in_trivia_and_goodThing_prompts() = runTest {
        val client = FakeLlmClient(listOf("[]", "SKIP"))
        val brain = DjBrain(client, persona = "דני")
        brain.writeTrivia(nxt, ctx = DjContext())
        brain.writeGoodThing(ctx = DjContext(), nxt = nxt)
        assertEquals(2, client.prompts.size)
        client.prompts.forEach { p ->
            assertTrue("must forbid inventing facts/years/numbers", p.contains("אל תמציא עובדות, שנים, מספרים"))
            assertTrue("must forbid superlatives", p.contains("הראשון/הכי"))
            assertTrue("must steer to feeling/style", p.contains("הסגנון"))
        }
    }

}
