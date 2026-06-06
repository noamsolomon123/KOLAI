package ai.kolai.station

import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SetlistPlanner ported from backend/radioai/setlist.py.
 * Uses a FakeLlmClient returning canned strings per call (draft, then refine).
 */
class SetlistPlannerTest {

    private val taste = TasteProfile(
        topTracks = listOf(
            TasteTrack(title = "Yesterday", artist = "The Beatles", durationS = 125.0),
        ),
        topArtists = listOf("The Beatles", "Radiohead"),
    )

    /**
     * A richer taste with several tracks/artists so the per-call SHUFFLE has
     * room to reorder things (a 1-track list cannot reorder). Used by the
     * variety tests.
     */
    private val richTaste = TasteProfile(
        topTracks = listOf(
            TasteTrack(title = "Yesterday", artist = "The Beatles", durationS = 125.0),
            TasteTrack(title = "Creep", artist = "Radiohead", durationS = 238.0),
            TasteTrack(title = "Karma Police", artist = "Radiohead", durationS = 261.0),
            TasteTrack(title = "Let It Be", artist = "The Beatles", durationS = 243.0),
            TasteTrack(title = "Wonderwall", artist = "Oasis", durationS = 258.0),
            TasteTrack(title = "Bohemian Rhapsody", artist = "Queen", durationS = 354.0),
        ),
        topArtists = listOf("The Beatles", "Radiohead", "Oasis", "Queen", "Nirvana"),
    )

    /** Replays canned responses in order; the last one is reused if calls overrun. */
    private class FakeLlmClient(
        private val responses: List<String>,
        val prompts: MutableList<String> = mutableListOf(),
        private val throwOnCall: Int? = null,
    ) : LlmClient {
        private var i = 0
        override suspend fun complete(prompt: String): String {
            prompts.add(prompt)
            val call = i
            i++
            if (throwOnCall != null && call == throwOnCall) {
                throw RuntimeException("simulated LLM failure on call $call")
            }
            return responses[call.coerceAtMost(responses.size - 1)]
        }
    }

    private val draftJson =
        """[{"title":"Creep","artist":"Radiohead"},{"title":"Yesterday","artist":"The Beatles"}]"""
    private val refinedJson =
        """[{"title":"Karma Police","artist":"Radiohead"},{"title":"Let It Be","artist":"The Beatles"}]"""

    @Test
    fun plan_refineFalse_returns_parsed_draft_and_calls_llm_once() = runTest {
        val client = FakeLlmClient(listOf(draftJson))
        val planner = SetlistPlanner(client)
        val songs = planner.plan(taste, n = 6, refine = false)
        assertEquals(2, songs.size)
        assertEquals("Creep", songs[0].title)
        assertEquals(1, client.prompts.size)
    }

    @Test
    fun plan_refineTrue_returns_refined_when_second_call_valid() = runTest {
        val client = FakeLlmClient(listOf(draftJson, refinedJson))
        val planner = SetlistPlanner(client)
        val songs = planner.plan(taste, n = 6, refine = true)
        assertEquals(2, songs.size)
        assertEquals("Karma Police", songs[0].title)
        assertEquals(2, client.prompts.size)
    }

    @Test
    fun plan_falls_back_to_draft_when_refine_throws() = runTest {
        // throwOnCall = 1 -> the refine (2nd) call throws.
        val client = FakeLlmClient(listOf(draftJson, refinedJson), throwOnCall = 1)
        val planner = SetlistPlanner(client)
        val songs = planner.plan(taste, n = 6, refine = true)
        assertEquals("Creep", songs[0].title)
    }

    @Test
    fun plan_falls_back_to_draft_when_refine_returns_garbage() = runTest {
        val client = FakeLlmClient(listOf(draftJson, "no json at all"))
        val planner = SetlistPlanner(client)
        val songs = planner.plan(taste, n = 6, refine = true)
        assertEquals("Creep", songs[0].title)
    }

    @Test
    fun plan_falls_back_to_draft_when_refine_returns_empty_array() = runTest {
        val client = FakeLlmClient(listOf(draftJson, "[]"))
        val planner = SetlistPlanner(client)
        val songs = planner.plan(taste, n = 6, refine = true)
        assertEquals("Creep", songs[0].title)
    }

    @Test
    fun prompt_contains_discovery_rule_text() = runTest {
        val client = FakeLlmClient(listOf(draftJson))
        val planner = SetlistPlanner(client)
        planner.plan(taste, n = 6, refine = false)
        val prompt = client.prompts.first()
        // Stable snippet from craft rule #4 (familiar + discovery 1-in-4).
        assertTrue(prompt.contains("FAMILIAR + DISCOVERY (IMPORTANT)"))
        assertTrue(prompt.contains("about 1 in 4 songs should be a real song"))
    }

    // ---- Variety (per-call randomization) -------------------------------

    @Test
    fun plan_with_seeded_rng_builds_a_deterministic_reproducible_prompt() = runTest {
        // Two planners seeded identically must build byte-identical prompts.
        val a = FakeLlmClient(listOf(draftJson))
        val b = FakeLlmClient(listOf(draftJson))
        SetlistPlanner(a, rng = kotlin.random.Random(42)).plan(richTaste, n = 6, refine = false)
        SetlistPlanner(b, rng = kotlin.random.Random(42)).plan(richTaste, n = 6, refine = false)
        assertEquals(a.prompts.first(), b.prompts.first())
    }

    @Test
    fun plan_with_different_seeds_varies_the_nonce() = runTest {
        // Different seeds -> different "Variety seed: <n>." nonce line.
        val a = FakeLlmClient(listOf(draftJson))
        val b = FakeLlmClient(listOf(draftJson))
        SetlistPlanner(a, rng = kotlin.random.Random(1)).plan(richTaste, n = 6, refine = false)
        SetlistPlanner(b, rng = kotlin.random.Random(2)).plan(richTaste, n = 6, refine = false)
        val nonceA = varietySeedOf(a.prompts.first())
        val nonceB = varietySeedOf(b.prompts.first())
        assertNotEquals(nonceA, nonceB)
        // The two whole prompts also differ (nonce and/or track order).
        assertNotEquals(a.prompts.first(), b.prompts.first())
    }

    @Test
    fun plan_with_different_seeds_varies_the_taste_track_order() = runTest {
        // The taste-track framing order should differ across seeds (input
        // re-framing run-to-run), driving Gemini off its default favourites.
        val a = FakeLlmClient(listOf(draftJson))
        val b = FakeLlmClient(listOf(draftJson))
        SetlistPlanner(a, rng = kotlin.random.Random(7)).plan(richTaste, n = 6, refine = false)
        SetlistPlanner(b, rng = kotlin.random.Random(9999)).plan(richTaste, n = 6, refine = false)
        assertNotEquals(trackOrderOf(a.prompts.first()), trackOrderOf(b.prompts.first()))
    }

    @Test
    fun variety_prompt_still_contains_all_taste_tracks_and_discovery_rule() = runTest {
        // Variety must NOT drop the grounding: every taste track + artist still
        // present, and craft rule #4 (discovery) intact.
        val client = FakeLlmClient(listOf(draftJson))
        SetlistPlanner(client, rng = kotlin.random.Random(123))
            .plan(richTaste, n = 6, refine = false)
        val prompt = client.prompts.first()
        for (t in richTaste.topTracks) {
            assertTrue("missing taste track ${t.title}", prompt.contains(t.title))
        }
        for (artist in richTaste.topArtists) {
            assertTrue("missing taste artist $artist", prompt.contains(artist))
        }
        assertTrue(prompt.contains("FAMILIAR + DISCOVERY (IMPORTANT)"))
        assertTrue(prompt.contains("about 1 in 4 songs should be a real song"))
        // And the variety directive is present.
        assertTrue(prompt.contains("Variety seed:"))
        assertTrue(prompt.contains("vary the OPENING song"))
    }

    @Test
    fun variety_nonce_is_shared_by_draft_and_refine_prompts() = runTest {
        // Both LLM passes in one plan() call must carry the SAME variety nonce.
        val client = FakeLlmClient(listOf(draftJson, refinedJson))
        SetlistPlanner(client, rng = kotlin.random.Random(55))
            .plan(richTaste, n = 6, refine = true)
        assertEquals(2, client.prompts.size)
        assertEquals(varietySeedOf(client.prompts[0]), varietySeedOf(client.prompts[1]))
    }

    /** Extract the "Variety seed: <int>." integer from a prompt. */
    private fun varietySeedOf(prompt: String): Int =
        Regex("""Variety seed: (\d+)\.""").find(prompt)!!.groupValues[1].toInt()

    /** The ordered list of taste-track titles as framed in a prompt. */
    private fun trackOrderOf(prompt: String): List<String> =
        Regex(""""([^"]+)" —""").findAll(prompt).map { it.groupValues[1] }.toList()
}