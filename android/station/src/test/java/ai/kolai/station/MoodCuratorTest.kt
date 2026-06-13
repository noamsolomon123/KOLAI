package ai.kolai.station

import ai.kolai.core.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MoodCurator] -- the hallucination-safe LLM vibe judge: the LLM
 * only SELECTS indices from a numbered list of real songs we hand it.
 */
class MoodCuratorTest {

    /** Replays canned responses in order; throws when they run out. Records prompts. */
    private class FakeLlm(private val replies: MutableList<String>) : LlmClient {
        var calls = 0
        val prompts = mutableListOf<String>()
        override suspend fun complete(prompt: String, temperature: Double?): String {
            calls++
            prompts.add(prompt)
            check(replies.isNotEmpty()) { "LLM down (no canned reply left)" }
            return replies.removeAt(0)
        }
    }

    private fun llm(vararg replies: String) = FakeLlm(replies.toMutableList())

    private val songs = listOf(
        Song(title = "Yesterday", artist = "The Beatles"),
        Song(title = "Creep", artist = "Radiohead"),
        Song(title = "Wonderwall", artist = "Oasis"),
    )

    // --- parsing -------------------------------------------------------------

    @Test
    fun clean_json_array_is_parsed() = runTest {
        val client = llm("[0, 2]")
        assertEquals(setOf(0, 2), MoodCurator(client).fitIndices("party bangers", songs))
        assertEquals(1, client.calls)
    }

    @Test
    fun markdown_fenced_array_is_parsed() = runTest {
        val client = llm("```json\n[1]\n```")
        assertEquals(setOf(1), MoodCurator(client).fitIndices("late night", songs))
    }

    @Test
    fun prose_wrapped_array_is_parsed() = runTest {
        val client = llm("Sure! The songs that fit are: [0, 1] - enjoy the show.")
        assertEquals(setOf(0, 1), MoodCurator(client).fitIndices("party bangers", songs))
    }

    @Test
    fun out_of_range_indices_are_dropped() = runTest {
        val client = llm("[0, 7, -1, 2]")
        assertEquals(setOf(0, 2), MoodCurator(client).fitIndices("party bangers", songs))
    }

    @Test
    fun empty_array_means_nothing_fits() = runTest {
        val client = llm("[]")
        assertEquals(emptySet<Int>(), MoodCurator(client).fitIndices("focus", songs))
    }

    @Test
    fun garbage_reply_returns_null() = runTest {
        assertNull(MoodCurator(llm("I cannot help with that.")).fitIndices("party", songs))
    }

    @Test
    fun unbalanced_array_returns_null() = runTest {
        assertNull(MoodCurator(llm("[0, 1")).fitIndices("party", songs))
    }

    @Test
    fun non_array_json_returns_null() = runTest {
        assertNull(MoodCurator(llm("{\"indices\": \"yes\"}")).fitIndices("party", songs))
    }

    @Test
    fun throwing_client_returns_null_never_throws() = runTest {
        val client = object : LlmClient {
            override suspend fun complete(prompt: String, temperature: Double?): String =
                throw RuntimeException("network down")
        }
        assertNull(MoodCurator(client).fitIndices("party", songs))
    }

    // --- prompt shape ----------------------------------------------------------

    @Test
    fun prompt_numbers_real_songs_and_carries_the_vibe_hint() = runTest {
        val client = llm("[]")
        MoodCurator(client).fitIndices("high-energy party bangers", songs)
        val p = client.prompts.single()
        assertTrue(p.contains("0. The Beatles — Yesterday"))
        assertTrue(p.contains("1. Radiohead — Creep"))
        assertTrue(p.contains("2. Oasis — Wonderwall"))
        assertTrue(p.contains("high-energy party bangers"))
        assertTrue(p.contains("JSON array"))
    }

    // --- caching -----------------------------------------------------------------

    @Test
    fun second_call_with_same_songs_does_not_hit_the_llm() = runTest {
        val client = llm("[0, 2]")
        val curator = MoodCurator(client)
        assertEquals(setOf(0, 2), curator.fitIndices("party", songs))
        assertEquals(setOf(0, 2), curator.fitIndices("party", songs))
        assertEquals(1, client.calls) // verdicts served from the cache
    }

    @Test
    fun cache_is_per_vibe_hint() = runTest {
        val client = llm("[0]", "[1]")
        val curator = MoodCurator(client)
        assertEquals(setOf(0), curator.fitIndices("party", songs))
        assertEquals(setOf(1), curator.fitIndices("late night chill", songs))
        assertEquals(2, client.calls) // a new vibe re-asks
    }

    @Test
    fun only_unknown_songs_are_asked_and_reply_indices_remap_to_originals() = runTest {
        val client = llm("[0]", "[0]")
        val curator = MoodCurator(client)
        // First call judges Yesterday + Creep: only Yesterday (0) fits.
        assertEquals(setOf(0), curator.fitIndices("party", songs.take(2)))
        // Second call adds Wonderwall: ONLY it is asked (renumbered 0 in the
        // prompt); its "[0]" verdict must map back to original index 2.
        assertEquals(setOf(0, 2), curator.fitIndices("party", songs))
        assertEquals(2, client.calls)
        val second = client.prompts[1]
        assertTrue(second.contains("0. Oasis — Wonderwall"))
        assertFalse(second.contains("Yesterday"))
        assertFalse(second.contains("Creep"))
    }

    @Test
    fun llm_failure_returns_null_but_keeps_cached_verdicts_usable() = runTest {
        val client = llm("[0]") // exactly one good reply; the next call throws
        val curator = MoodCurator(client)
        assertEquals(setOf(0), curator.fitIndices("party", songs.take(2)))
        // Wonderwall is unknown and the LLM is down -> null (graceful).
        assertNull(curator.fitIndices("party", songs))
        // But the first two verdicts are still cached: zero-LLM success.
        assertEquals(setOf(0), curator.fitIndices("party", songs.take(2)))
        assertEquals(2, client.calls)
    }

    @Test
    fun empty_song_list_returns_empty_set_without_calling_the_llm() = runTest {
        val client = llm()
        assertEquals(emptySet<Int>(), MoodCurator(client).fitIndices("party", emptyList()))
        assertEquals(0, client.calls)
    }
}