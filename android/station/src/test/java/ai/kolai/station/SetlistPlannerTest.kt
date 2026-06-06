package ai.kolai.station

import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}