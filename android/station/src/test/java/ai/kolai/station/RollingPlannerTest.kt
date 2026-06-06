package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for RollingPlanner, ported from backend/radioai/planner_rolling.py.
 *
 * TEST SEAM NOTE: the spec sketched a `FakeSetlistPlanner` that overrides
 * `plan`, but the already-ported `SetlistPlanner` is a final class with a final
 * `plan` (and the task forbids editing it to add `open`). So instead of
 * subclassing, these tests drive a REAL `SetlistPlanner` wrapping a recording
 * `FakeLlmClient`: the planner faithfully threads `RollingPlanner`'s
 * exclude/seed/mood into the prompt builders, so we assert pass-through by
 * inspecting the prompt the LLM was handed (exclude + seed) and the planner's
 * canned reply (the returned/history songs). `FakeTasteSource` still counts
 * getProfile calls (split by useCache) and lets the test flip the profile, and
 * the clock is injected via nowMs so refresh-by-TTL needs no real time.
 *
 * MVP caveat on mood: SetlistPlanner.moodBlock is intentionally a no-op for the
 * MVP (the moods table is not ported), so `mood` never reaches the prompt and
 * cannot be observed there. Mood pass-through is therefore verified at the
 * RollingPlanner boundary (setMood updates the value that nextSongs forwards as
 * `plan(mood = this.mood)`); the seed/exclude pass-through IS verified through
 * the prompt.
 */
class RollingPlannerTest {

    /** taste whose single top track is named [tag] so we can identify the profile. */
    private fun profile(tag: String) = TasteProfile(
        topTracks = listOf(TasteTrack(title = tag, artist = "A", durationS = 100.0)),
        topArtists = listOf(tag),
    )

    /** Counts getProfile calls (split by useCache); test can flip the result. */
    private class FakeTasteSource(var next: TasteProfile) : TasteSource {
        var cacheCalls = 0
        var forceCalls = 0
        override suspend fun getProfile(useCache: Boolean): TasteProfile {
            if (useCache) cacheCalls++ else forceCalls++
            return next
        }
    }

    /**
     * Records every prompt and replays one canned JSON setlist per plan() pass.
     * RollingPlanner calls plan(refine = default true) -> two LLM calls per
     * nextSongs (draft + refine); we serve the SAME canned array to both so the
     * refined result is what the test controls. Prompts are recorded so we can
     * assert exclude/seed pass-through.
     */
    private class FakeLlmClient(private val replies: List<String>) : LlmClient {
        val prompts = mutableListOf<String>()
        private var i = 0
        override suspend fun complete(prompt: String): String {
            prompts.add(prompt)
            return replies[(i++).coerceAtMost(replies.size - 1)]
        }
    }

    /** Build a canned JSON array reply from plain titles (artist always "A"). */
    private fun reply(vararg titles: String): String =
        titles.joinToString(prefix = "[", postfix = "]") {
            """{"title":"$it","artist":"A"}"""
        }

    // --- no-repeat history -------------------------------------------------

    @Test
    fun returned_songs_appear_in_next_calls_exclude_by_baseTitle() = runTest {
        // call 1 -> Creep (Live) + Yesterday ; call 2 -> Karma Police
        val client = FakeLlmClient(
            listOf(
                reply("Creep (Live)", "Yesterday"), // call1 draft
                reply("Creep (Live)", "Yesterday"), // call1 refine
                reply("Karma Police"),              // call2 draft
                reply("Karma Police"),              // call2 refine
            ),
        )
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(client),
            refreshEvery = 100, // keep refresh out of this test
        )

        rolling.nextSongs(n = 2)
        // After call 1 the history holds the baseTitle keys of its picks.
        assertEquals(listOf(baseTitle("Creep (Live)"), baseTitle("Yesterday")), rolling.history)

        rolling.nextSongs(n = 1)
        // After call 2 the new pick's key is appended too.
        assertEquals(
            listOf(baseTitle("Creep (Live)"), baseTitle("Yesterday"), baseTitle("Karma Police")),
            rolling.history,
        )

        // The 2nd nextSongs (prompts[2] = its draft) must HARD EXCLUDE the call-1 keys.
        val secondCallPrompt = client.prompts[2]
        assertTrue(secondCallPrompt.contains("HARD EXCLUDE"))
        assertTrue(secondCallPrompt.contains(baseTitle("Creep (Live)"))) // "creep"
        assertTrue(secondCallPrompt.contains(baseTitle("Yesterday")))    // "yesterday"

        // The very first nextSongs (prompts[0]) had nothing to exclude.
        assertFalse(client.prompts[0].contains("HARD EXCLUDE"))
    }

    @Test
    fun exclude_is_trimmed_to_noRepeatWindow() = runTest {
        // 6 calls, each returns 1 unique song; window = 3 keeps only last 3 keys.
        val replies = (0 until 6).flatMap { listOf(reply("T$it"), reply("T$it")) }
        val client = FakeLlmClient(replies)
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(client),
            refreshEvery = 100,
            noRepeatWindow = 3,
        )

        repeat(5) { rolling.nextSongs(n = 1) }

        // History grew to 5 keys; the exclude window is the LAST 3.
        assertEquals(listOf("t0", "t1", "t2", "t3", "t4"), rolling.history)

        // 5th nextSongs draft prompt (index 8: 2 prompts per call) excludes T1,T2,T3.
        val fifthDraft = client.prompts[8]
        assertTrue(fifthDraft.contains("t1; t2; t3")) // joined in order, "; " separator
        assertFalse(fifthDraft.contains("t0")) // trimmed out of the window
    }

    // --- refresh cadence ---------------------------------------------------

    @Test
    fun first_call_uses_cache_then_forces_refresh_after_refreshEvery_songs() = runTest {
        val replies = (0 until 5).flatMap { listOf(reply("T$it"), reply("T$it")) }
        val taste = FakeTasteSource(profile("p"))
        val rolling = RollingPlanner(
            tasteSource = taste,
            setlistPlanner = SetlistPlanner(FakeLlmClient(replies)),
            refreshEvery = 2,
            refreshTtlS = 1_000_000, // keep TTL out of this test
            nowMs = { 0L },
        )

        rolling.nextSongs(n = 1) // call1: first load (cache); songsSinceRefresh -> 1
        assertEquals(1, taste.cacheCalls)
        assertEquals(0, taste.forceCalls)

        rolling.nextSongs(n = 1) // call2: 1 < 2 -> no refresh; -> 2
        assertEquals(0, taste.forceCalls)

        rolling.nextSongs(n = 1) // call3: 2 >= 2 -> FORCE refresh; reset to 0 then +1
        assertEquals(1, taste.forceCalls)

        rolling.nextSongs(n = 1) // call4: 1 < 2 -> no refresh
        assertEquals(1, taste.forceCalls)

        rolling.nextSongs(n = 1) // call5: 2 >= 2 -> FORCE refresh again
        assertEquals(2, taste.forceCalls)
        assertEquals(1, taste.cacheCalls) // cache path only the very first load
    }

    @Test
    fun ttl_triggers_force_refresh_when_clock_advances() = runTest {
        var now = 0L
        val replies = (0 until 3).flatMap { listOf(reply("T$it"), reply("T$it")) }
        val taste = FakeTasteSource(profile("p"))
        val rolling = RollingPlanner(
            tasteSource = taste,
            setlistPlanner = SetlistPlanner(FakeLlmClient(replies)),
            refreshEvery = 1000, // keep count trigger out of this test
            refreshTtlS = 600,   // 600s == 600_000 ms
            nowMs = { now },
        )

        rolling.nextSongs(n = 1) // first load (cache), lastRefresh = 0
        assertEquals(1, taste.cacheCalls)
        assertEquals(0, taste.forceCalls)

        now = 600_000L // exactly TTL: strict > means NO refresh yet
        rolling.nextSongs(n = 1)
        assertEquals(0, taste.forceCalls)

        now = 600_001L // one ms past the window -> force refresh
        rolling.nextSongs(n = 1)
        assertEquals(1, taste.forceCalls)
    }

    @Test
    fun refresh_swaps_in_the_new_profile_observed_via_taste_block() = runTest {
        val replies = (0 until 3).flatMap { listOf(reply("T$it"), reply("T$it")) }
        val client = FakeLlmClient(replies)
        val taste = FakeTasteSource(profile("OLDTAG"))
        val rolling = RollingPlanner(
            tasteSource = taste,
            setlistPlanner = SetlistPlanner(client),
            refreshEvery = 2,
            refreshTtlS = 1_000_000,
            nowMs = { 0L },
        )

        rolling.nextSongs(n = 1) // loads "OLDTAG"
        taste.next = profile("NEWTAG") // flip the source's next return
        rolling.nextSongs(n = 1) // no refresh -> prompt still mentions OLDTAG
        rolling.nextSongs(n = 1) // refresh fires -> prompt mentions NEWTAG

        // prompts: call1 -> 0,1 ; call2 -> 2,3 ; call3 -> 4,5 (draft is even index)
        assertTrue(client.prompts[2].contains("OLDTAG")) // reused cached profile
        assertFalse(client.prompts[2].contains("NEWTAG"))
        assertTrue(client.prompts[4].contains("NEWTAG")) // refreshed profile in use
    }

    // --- seed pass-through (observed via the CONTINUITY prompt line) --------

    @Test
    fun seed_reaches_the_setlist_planner_prompt() = runTest {
        val client = FakeLlmClient(listOf(reply("T"), reply("T")))
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(client),
            refreshEvery = 100,
        )
        val seed = Song(title = "SeedSong", artist = "SeedArtist")

        rolling.nextSongs(n = 1, seed = seed)

        val draft = client.prompts[0]
        assertTrue(draft.contains("CONTINUITY"))
        assertTrue(draft.contains("SeedSong"))
        assertTrue(draft.contains("SeedArtist"))
    }

    @Test
    fun no_seed_omits_the_continuity_line() = runTest {
        val client = FakeLlmClient(listOf(reply("T"), reply("T")))
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(client),
            refreshEvery = 100,
        )
        rolling.nextSongs(n = 1)
        assertFalse(client.prompts[0].contains("CONTINUITY"))
    }

    // --- mood pass-through (boundary-level; moodBlock is a no-op for MVP) ---

    @Test
    fun setMood_updates_the_mood_forwarded_to_plan() = runTest {
        val client = FakeLlmClient(listOf(reply("T"), reply("T")))
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(client),
            refreshEvery = 100,
            mood = "chill",
        )
        assertEquals("chill", rolling.mood)
        rolling.setMood("party")
        assertEquals("party", rolling.mood) // this is the value nextSongs forwards

        rolling.nextSongs(n = 1) // exercises the plan(mood = this.mood) path
        assertEquals(1, rolling.history.size)
    }

    @Test
    fun mood_defaults_to_null() = runTest {
        val client = FakeLlmClient(listOf(reply("T"), reply("T")))
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(client),
        )
        assertEquals(null, rolling.mood)
    }
}