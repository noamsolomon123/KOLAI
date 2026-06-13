package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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
        override suspend fun complete(prompt: String, temperature: Double?): String {
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

    // --- cross-launch history persistence (persistFile) ---------------------

    private fun newHistoryFile(): File =
        File(Files.createTempDirectory("rolling-test").toFile(), "history.txt")

    @Test
    fun history_persists_across_planner_instances_sharing_a_file() = runTest {
        val file = newHistoryFile()

        val client1 = FakeLlmClient(listOf(reply("Creep", "Yesterday"), reply("Creep", "Yesterday")))
        val p1 = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(client1),
            refreshEvery = 100,
            persistFile = file,
        )
        p1.nextSongs(n = 2)
        assertTrue(file.exists())

        // a SECOND planner instance over the same file restores the history...
        val client2 = FakeLlmClient(listOf(reply("Karma Police"), reply("Karma Police")))
        val p2 = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(client2),
            refreshEvery = 100,
            persistFile = file,
        )
        assertEquals(listOf(baseTitle("Creep"), baseTitle("Yesterday")), p2.history)

        // ...and its very FIRST prompt already hard-excludes the prior
        // launch's songs -- this is exactly what stops every app launch from
        // re-opening with the planner's default favourite track.
        p2.nextSongs(n = 1)
        val draft = client2.prompts[0]
        assertTrue(draft.contains("HARD EXCLUDE"))
        assertTrue(draft.contains(baseTitle("Creep")))
        assertTrue(draft.contains(baseTitle("Yesterday")))
    }

    @Test
    fun history_file_is_bounded_to_twice_the_noRepeatWindow() = runTest {
        val file = newHistoryFile()
        val replies = (0 until 6).flatMap { listOf(reply("T$it"), reply("T$it")) }
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(FakeLlmClient(replies)),
            refreshEvery = 100,
            noRepeatWindow = 2,
            persistFile = file,
        )

        repeat(6) { rolling.nextSongs(n = 1) } // history t0..t5

        // in-memory history keeps everything; the FILE keeps only the last 2*2
        assertEquals(listOf("t0", "t1", "t2", "t3", "t4", "t5"), rolling.history)
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("t2", "t3", "t4", "t5"), lines)
    }

    @Test
    fun corrupt_history_file_is_tolerated() = runTest {
        val file = newHistoryFile()
        // binary junk + blank lines: construction must not throw
        file.writeBytes(byteArrayOf(0, 1, 2, -1, -2) + "\n\n   \n".toByteArray())

        val client = FakeLlmClient(listOf(reply("T"), reply("T")))
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(client),
            refreshEvery = 100,
            persistFile = file,
        )

        // ...and the planner keeps working + persisting from here on
        rolling.nextSongs(n = 1)
        assertTrue(rolling.history.contains(baseTitle("T")))
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(lines.contains(baseTitle("T")))
    }

    @Test
    fun missing_history_file_and_null_persistFile_start_empty() = runTest {
        // null persistFile: in-memory only (the default; existing behavior)
        val rollingNull = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(FakeLlmClient(listOf(reply("T"), reply("T")))),
            refreshEvery = 100,
        )
        assertTrue(rollingNull.history.isEmpty())

        // persistFile that does not exist yet: also starts empty, no crash
        val file = newHistoryFile()
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = SetlistPlanner(FakeLlmClient(listOf(reply("T"), reply("T")))),
            refreshEvery = 100,
            persistFile = file,
        )
        assertTrue(rolling.history.isEmpty())
    }

    // --- cross-call artist fatigue (artistHistory -> plan recentArtists) -----

    /** Records the recentArtists each plan() received; replays canned batches. */
    private class FakeSetlistSource(private val batches: List<List<Song>>) : SetlistSource {
        val recentArtistsSeen = mutableListOf<List<String>?>()
        private var i = 0
        override suspend fun plan(
            taste: TasteProfile,
            n: Int,
            exclude: List<String>?,
            seed: Song?,
            mood: String?,
        ): List<Song> = plan(taste, n, exclude, seed, mood, recentArtists = null)

        override suspend fun plan(
            taste: TasteProfile,
            n: Int,
            exclude: List<String>?,
            seed: Song?,
            mood: String?,
            recentArtists: List<String>?,
        ): List<Song> {
            recentArtistsSeen.add(recentArtists)
            return batches[(i++).coerceAtMost(batches.size - 1)]
        }
    }

    @Test
    fun chosen_artists_are_recorded_lowercased_in_artistHistory() = runTest {
        val fake = FakeSetlistSource(
            listOf(
                listOf(
                    Song(title = "Creep", artist = "Radiohead"),
                    Song(title = "Yesterday", artist = "The Beatles"),
                ),
            ),
        )
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = fake,
            refreshEvery = 100,
        )
        rolling.nextSongs(n = 2)
        assertEquals(listOf("radiohead", "the beatles"), rolling.artistHistory)
    }

    @Test
    fun blank_artists_are_not_recorded_in_artistHistory() = runTest {
        val fake = FakeSetlistSource(listOf(listOf(Song(title = "Mystery", artist = "  "))))
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = fake,
            refreshEvery = 100,
        )
        rolling.nextSongs(n = 1)
        assertTrue(rolling.artistHistory.isEmpty())
        assertEquals(1, rolling.history.size) // title history is unaffected
    }

    @Test
    fun planner_receives_only_the_last_artistWindow_artists() = runTest {
        val batches = (0 until 4).map { listOf(Song(title = "T$it", artist = "Artist$it")) }
        val fake = FakeSetlistSource(batches)
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = fake,
            refreshEvery = 100,
            artistWindow = 2,
        )
        repeat(4) { rolling.nextSongs(n = 1) }
        assertEquals(emptyList<String>(), fake.recentArtistsSeen[0])
        assertEquals(listOf("artist0"), fake.recentArtistsSeen[1])
        assertEquals(listOf("artist0", "artist1"), fake.recentArtistsSeen[2])
        assertEquals(listOf("artist1", "artist2"), fake.recentArtistsSeen[3]) // windowed
    }

    @Test
    fun artist_history_persists_in_a_sibling_file_and_restores() = runTest {
        val file = newHistoryFile()
        val fake1 = FakeSetlistSource(listOf(listOf(Song(title = "Creep", artist = "Radiohead"))))
        val p1 = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = fake1,
            refreshEvery = 100,
            persistFile = file,
        )
        p1.nextSongs(n = 1)
        val sibling = File(file.parentFile, file.name + ".artists")
        assertTrue(sibling.exists())
        assertEquals(
            listOf("radiohead"),
            sibling.readLines().map { it.trim() }.filter { it.isNotEmpty() },
        )

        // a SECOND planner instance over the same file restores the artist
        // history and forwards it on its very FIRST plan() call.
        val fake2 = FakeSetlistSource(listOf(listOf(Song(title = "Karma Police", artist = "Radiohead"))))
        val p2 = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = fake2,
            refreshEvery = 100,
            persistFile = file,
        )
        assertEquals(listOf("radiohead"), p2.artistHistory)
        p2.nextSongs(n = 1)
        assertEquals(listOf("radiohead"), fake2.recentArtistsSeen[0])
    }

    @Test
    fun artist_file_is_bounded_to_twice_artistWindow() = runTest {
        val file = newHistoryFile()
        val batches = (0 until 6).map { listOf(Song(title = "T$it", artist = "Artist$it")) }
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = FakeSetlistSource(batches),
            refreshEvery = 100,
            persistFile = file,
            artistWindow = 2,
        )
        repeat(6) { rolling.nextSongs(n = 1) }

        // in-memory keeps everything; the FILE keeps only the last 2*2
        assertEquals(
            listOf("artist0", "artist1", "artist2", "artist3", "artist4", "artist5"),
            rolling.artistHistory,
        )
        val sibling = File(file.parentFile, file.name + ".artists")
        val lines = sibling.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("artist2", "artist3", "artist4", "artist5"), lines)
    }

    @Test
    fun corrupt_artist_file_is_tolerated() = runTest {
        val file = newHistoryFile()
        val sibling = File(file.parentFile, file.name + ".artists")
        sibling.writeBytes(byteArrayOf(0, 1, 2, -1, -2))

        val fake = FakeSetlistSource(listOf(listOf(Song(title = "T", artist = "B"))))
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = fake,
            refreshEvery = 100,
            persistFile = file,
        )
        // construction did not throw, and the planner keeps working + persisting
        rolling.nextSongs(n = 1)
        assertTrue(rolling.artistHistory.contains("b"))
        val lines = sibling.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(lines.contains("b"))
    }

    @Test
    fun a_planner_implementing_only_the_5_arg_plan_keeps_working() = runTest {
        // SetlistPlanner-shaped implementations (only the 5-arg plan) must keep
        // compiling and working: the interface's 6-arg default delegates.
        val canned = listOf(Song(title = "OnlyFive", artist = "B"))
        val fiveArgOnly = object : SetlistSource {
            override suspend fun plan(
                taste: TasteProfile,
                n: Int,
                exclude: List<String>?,
                seed: Song?,
                mood: String?,
            ): List<Song> = canned
        }
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = fiveArgOnly,
            refreshEvery = 100,
        )
        assertEquals(canned, rolling.nextSongs(n = 1))
        assertEquals(listOf("b"), rolling.artistHistory) // fatigue still tracked
    }

    // --- cohesion history: genre + language threading -----------------------

    /** Records the recentGenres/recentLanguages each 8-arg plan() received. */
    private class FakeCohesionSource(private val batches: List<List<Song>>) : SetlistSource {
        val recentGenresSeen = mutableListOf<List<String>?>()
        val recentLanguagesSeen = mutableListOf<List<String>?>()
        private var i = 0
        override suspend fun plan(
            taste: TasteProfile, n: Int, exclude: List<String>?, seed: Song?, mood: String?,
        ): List<Song> = plan(taste, n, exclude, seed, mood, null, null, null)

        override suspend fun plan(
            taste: TasteProfile, n: Int, exclude: List<String>?, seed: Song?, mood: String?,
            recentArtists: List<String>?, recentGenres: List<String>?, recentLanguages: List<String>?,
        ): List<Song> {
            recentGenresSeen.add(recentGenres)
            recentLanguagesSeen.add(recentLanguages)
            return batches[(i++).coerceAtMost(batches.size - 1)]
        }
    }

    /** A GenreSource canned by title, modelling an ALREADY-WARMED cache: the
     *  RollingPlanner labels NON-BLOCKING via [cachedGenre] (the suspend [genre]
     *  must never be taken -- it throws here to prove it). [warm] is a no-op. */
    private class FakeGenreSource(private val byTitle: Map<String, String?>) : GenreSource {
        override suspend fun genre(artist: String, title: String): String? =
            throw AssertionError("RollingPlanner must NOT call the suspending genre() path")
        override fun cachedGenre(artist: String, title: String): String? = byTitle[title]
        override fun warm(artist: String, title: String) {}
    }

    @Test
    fun language_history_is_recorded_and_forwarded_even_without_a_genre_source() = runTest {
        // No GenreSource: genre history stays blank, but LANGUAGE is computed
        // in-code and threaded -- language cohesion needs no network.
        val englishTitle = "English Hit"
        val batches = listOf(
            listOf(Song(title = englishTitle, artist = "A")),
            listOf(Song(title = "Another One", artist = "B")),
        )
        val fake = FakeCohesionSource(batches)
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = fake,
            refreshEvery = 100,
        )
        rolling.nextSongs(n = 1)
        rolling.nextSongs(n = 1)
        assertEquals(listOf("int", "int"), rolling.languageHistory)
        // first call had no history; second saw the first language.
        assertEquals(emptyList<String>(), fake.recentLanguagesSeen[0])
        assertEquals(listOf("int"), fake.recentLanguagesSeen[1])
        // genre history is blank (no source) -> blank entries recorded.
        assertEquals(listOf("", ""), rolling.genreHistory)
    }

    @Test
    fun genre_history_is_labelled_via_the_genre_source_and_forwarded() = runTest {
        val batches = listOf(
            listOf(Song(title = "Banger", artist = "MC")),
            listOf(Song(title = "Smooth", artist = "Sax")),
        )
        val fake = FakeCohesionSource(batches)
        val genreSrc = FakeGenreSource(mapOf("Banger" to "Rap/Hip Hop", "Smooth" to "Jazz"))
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = fake,
            refreshEvery = 100,
            genreSource = genreSrc,
        )
        rolling.nextSongs(n = 1)
        rolling.nextSongs(n = 1)
        assertEquals(listOf("rap/hip hop", "jazz"), rolling.genreHistory)
        assertEquals(emptyList<String>(), fake.recentGenresSeen[0])
        assertEquals(listOf("rap/hip hop"), fake.recentGenresSeen[1])
    }

    /** Cold-cache genre source: cachedGenre is null (a miss) and the suspend
     *  genre() THROWS, proving RollingPlanner labels NON-BLOCKING. A miss fires a
     *  warm and records a blank genre (a run break that warms in next time). */
    private class ColdGenreSource : GenreSource {
        val warmed = mutableListOf<String>()
        override suspend fun genre(artist: String, title: String): String? =
            throw AssertionError("RollingPlanner must NOT block on genre()")
        override fun cachedGenre(artist: String, title: String): String? = null
        override fun warm(artist: String, title: String) { warmed.add(title) }
    }

    @Test
    fun cold_genre_cache_records_blank_and_warms_without_blocking() = runTest {
        // A cold genre cache must NOT stall nextSongs (the suspend path throws);
        // it records a blank genre (a run break) and fires a background warm so a
        // LATER play is labelled. Language is still labelled in-code (free).
        val batches = listOf(listOf(Song(title = "Banger", artist = "MC")))
        val cold = ColdGenreSource()
        val rolling = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = FakeCohesionSource(batches),
            refreshEvery = 100,
            genreSource = cold,
        )
        rolling.nextSongs(n = 1) // no throw == never blocked on the genre network
        assertEquals(listOf(""), rolling.genreHistory) // blank: cold = unknown now
        assertEquals(listOf("int"), rolling.languageHistory) // language is free
        assertTrue("a cold miss must fire a warm", cold.warmed.contains("Banger"))
    }

    @Test
    fun genre_and_language_histories_persist_in_sibling_files_and_restore() = runTest {
        val file = newHistoryFile()
        val batches = listOf(listOf(Song(title = "Banger", artist = "MC")))
        val genreSrc = FakeGenreSource(mapOf("Banger" to "Rap/Hip Hop"))
        val p1 = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = FakeCohesionSource(batches),
            refreshEvery = 100,
            persistFile = file,
            genreSource = genreSrc,
        )
        p1.nextSongs(n = 1)
        val genreSibling = File(file.parentFile, file.name + ".genres")
        val langSibling = File(file.parentFile, file.name + ".langs")
        assertTrue(genreSibling.exists())
        assertTrue(langSibling.exists())

        // A SECOND planner over the same file restores both histories.
        val p2 = RollingPlanner(
            tasteSource = FakeTasteSource(profile("p")),
            setlistPlanner = FakeCohesionSource(batches),
            refreshEvery = 100,
            persistFile = file,
            genreSource = genreSrc,
        )
        assertEquals(listOf("rap/hip hop"), p2.genreHistory)
        assertEquals(listOf("int"), p2.languageHistory)
    }
}