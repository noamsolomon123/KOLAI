package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TastePoolPlanner] -- pure-code song picking from the taste pool.
 *
 * Randomness seams used here:
 *  - seeded [kotlin.random.Random] for determinism / statistical assertions;
 *  - [ZeroRandom] (nextBits = 0 -> nextDouble() = 0.0): FORCES the 25%
 *    discovery roll to fire on every slot and makes the weighted sampler pick
 *    the first candidate of the tier -- fully deterministic discovery tests;
 *  - [MaxRandom] (all bits set -> nextDouble() ~ 0.999...): discovery NEVER
 *    fires.
 */
class TastePoolPlannerTest {

    /** nextDouble() == 0.0 always: discovery roll fires, sampler picks first. */
    private class ZeroRandom : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    /** nextDouble() ~ 0.999...: the 25% discovery roll never fires. */
    private class MaxRandom : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = ((1L shl bitCount) - 1).toInt()
    }

    /** Records discover() arguments and replays one canned song (or null). */
    private class FakeDiscovery(private val song: Song?) : DiscoverySource {
        var calls = 0
        val excludeKeysSeen = mutableListOf<Set<String>>()
        val excludeArtistsSeen = mutableListOf<Set<String>>()
        override suspend fun discover(
            taste: TasteProfile,
            excludeKeys: Set<String>,
            excludeArtists: Set<String>,
        ): Song? {
            calls++
            excludeKeysSeen.add(excludeKeys)
            excludeArtistsSeen.add(excludeArtists)
            return song
        }
    }

    /** 4 Hebrew + 4 English titled tracks, all distinct artists, with durations. */
    private val mixedTaste = TasteProfile(
        topTracks = listOf(
            TasteTrack(title = "תל אביב", artist = "עומר אדם", durationS = 210.0),
            TasteTrack(title = "Yesterday", artist = "The Beatles", durationS = 125.0),
            TasteTrack(title = "הללויה", artist = "שלמה ארצי", durationS = 240.0),
            TasteTrack(title = "Creep", artist = "Radiohead", durationS = 238.0),
            TasteTrack(title = "ירושלים של זהב", artist = "נעמי שמר", durationS = 180.0),
            TasteTrack(title = "Wonderwall", artist = "Oasis", durationS = 258.0),
            TasteTrack(title = "גולדן בוי", artist = "נדב גדג", durationS = 200.0),
            TasteTrack(title = "Bohemian Rhapsody", artist = "Queen", durationS = 354.0),
        ),
        topArtists = listOf("עומר אדם", "The Beatles", "שלמה ארצי", "Radiohead"),
    )

    private val mixedTitles = mixedTaste.topTracks.map { it.title }.toSet()

    /** 3 artists x 2 tracks each (all-English titles -> no language tier noise). */
    private val repeatedArtistsTaste = TasteProfile(
        topTracks = listOf(
            TasteTrack(title = "Song A1", artist = "Artist A", durationS = 100.0),
            TasteTrack(title = "Song A2", artist = "Artist A", durationS = 110.0),
            TasteTrack(title = "Song B1", artist = "Artist B", durationS = 120.0),
            TasteTrack(title = "Song B2", artist = "Artist B", durationS = 130.0),
            TasteTrack(title = "Song C1", artist = "Artist C", durationS = 140.0),
            TasteTrack(title = "Song C2", artist = "Artist C", durationS = 150.0),
        ),
        topArtists = listOf("Artist A", "Artist B", "Artist C"),
    )

    // --- exclusions ---------------------------------------------------------

    @Test
    fun excluded_baseTitles_are_never_picked_when_pool_has_room() = runTest {
        val exclude = listOf(baseTitle("Yesterday"), baseTitle("תל אביב"))
        for (seed in 0 until 20) {
            val planner = TastePoolPlanner(rng = kotlin.random.Random(seed))
            val songs = planner.plan(mixedTaste, n = 4, exclude = exclude)
            assertEquals(4, songs.size)
            for (s in songs) {
                assertTrue(
                    "excluded key picked: ${s.title} (rng seed $seed)",
                    baseTitle(s.title) !in exclude,
                )
            }
        }
    }

    @Test
    fun no_duplicate_songs_within_one_call() = runTest {
        for (seed in 0 until 20) {
            val planner = TastePoolPlanner(rng = kotlin.random.Random(seed))
            val songs = planner.plan(mixedTaste, n = 8)
            assertEquals(8, songs.size)
            assertEquals(8, songs.map { baseTitle(it.title) }.toSet().size)
        }
    }

    // --- artist spacing ------------------------------------------------------

    @Test
    fun no_same_artist_twice_within_a_call_when_pool_allows() = runTest {
        // 3 distinct artists available, n = 3 -> chosen artists must be distinct.
        for (seed in 0 until 20) {
            val planner = TastePoolPlanner(rng = kotlin.random.Random(seed))
            val songs = planner.plan(repeatedArtistsTaste, n = 3)
            assertEquals(3, songs.size)
            assertEquals(
                "artist repeated (rng seed $seed): ${songs.map { it.artist }}",
                3,
                songs.map { it.artist }.toSet().size,
            )
        }
    }

    @Test
    fun first_pick_avoids_the_seed_artist() = runTest {
        val seedSong = Song(title = "Old Song", artist = "Artist A")
        for (seed in 0 until 20) {
            val planner = TastePoolPlanner(rng = kotlin.random.Random(seed))
            val songs = planner.plan(repeatedArtistsTaste, n = 3, seed = seedSong)
            assertTrue(
                "first pick reused the seed artist (rng seed $seed)",
                songs.first().artist != "Artist A",
            )
        }
    }

    @Test
    fun artist_spacing_relaxes_instead_of_under_delivering() = runTest {
        // Single-artist pool: spacing is impossible, but n songs still return.
        val oneArtist = TasteProfile(
            topTracks = listOf(
                TasteTrack(title = "Solo 1", artist = "Only Artist", durationS = 100.0),
                TasteTrack(title = "Solo 2", artist = "Only Artist", durationS = 110.0),
                TasteTrack(title = "Solo 3", artist = "Only Artist", durationS = 120.0),
            ),
            topArtists = listOf("Only Artist"),
        )
        val songs = TastePoolPlanner(rng = kotlin.random.Random(1)).plan(oneArtist, n = 3)
        assertEquals(3, songs.size)
        assertEquals(3, songs.map { it.title }.toSet().size)
    }

    // --- durations -----------------------------------------------------------

    @Test
    fun taste_durations_are_carried_onto_songs_and_zero_becomes_null() = runTest {
        val taste = TasteProfile(
            topTracks = listOf(
                TasteTrack(title = "Alpha", artist = "A", durationS = 100.0),
                TasteTrack(title = "Beta", artist = "B", durationS = 0.0),
            ),
            topArtists = listOf("A", "B"),
        )
        val songs = TastePoolPlanner(rng = kotlin.random.Random(3)).plan(taste, n = 2)
        assertEquals(2, songs.size)
        assertEquals(100.0, songs.first { it.title == "Alpha" }.durationS!!, 0.0)
        assertNull(songs.first { it.title == "Beta" }.durationS)
    }

    // --- Hebrew/international alternation -------------------------------------

    @Test
    fun adjacent_picks_alternate_hebrew_and_international_when_both_groups_available() = runTest {
        // 4 Hebrew + 4 English, distinct artists, n = 6: the other-language
        // group is always non-empty, so the soft preference yields strict
        // alternation for every adjacent pair, under any rng seed.
        for (seed in 0 until 10) {
            val planner = TastePoolPlanner(rng = kotlin.random.Random(seed))
            val songs = planner.plan(mixedTaste, n = 6)
            assertEquals(6, songs.size)
            for (i in 1 until songs.size) {
                assertTrue(
                    "no alternation at $i (rng seed $seed): ${songs.map { it.title }}",
                    containsHebrew(songs[i].title) != containsHebrew(songs[i - 1].title),
                )
            }
        }
    }

    @Test
    fun first_pick_alternates_against_the_seed_songs_language() = runTest {
        val hebrewSeed = Song(title = "שיר של יום חולין", artist = "אריק איינשטיין")
        for (seed in 0 until 10) {
            val planner = TastePoolPlanner(rng = kotlin.random.Random(seed))
            val songs = planner.plan(mixedTaste, n = 2, seed = hebrewSeed)
            assertTrue(
                "first pick after a Hebrew seed should be international (rng seed $seed)",
                !containsHebrew(songs.first().title),
            )
        }
    }

    // --- discovery -------------------------------------------------------------

    @Test
    fun discovery_song_is_injected_and_taste_fills_the_rest() = runTest {
        val discovered = Song(title = "Smooth Operator", artist = "Sade", durationS = 290.0)
        val fake = FakeDiscovery(discovered)
        val planner = TastePoolPlanner(discovery = fake, rng = ZeroRandom())
        val songs = planner.plan(mixedTaste, n = 4)

        assertEquals(4, songs.size)
        // ZeroRandom fires the discovery roll on the first slot -> it opens.
        assertEquals("Smooth Operator", songs.first().title)
        assertEquals(290.0, songs.first().durationS!!, 0.0) // source duration kept
        assertEquals(1, songs.count { it.title == "Smooth Operator" })
        // capped at 1 discovery per call: after acceptance no further calls
        assertEquals(1, fake.calls)
        // the rest are real taste tracks
        assertTrue(songs.drop(1).all { it.title in mixedTitles })
    }

    @Test
    fun discovery_returning_null_still_yields_a_full_taste_list() = runTest {
        val fake = FakeDiscovery(null)
        val planner = TastePoolPlanner(discovery = fake, rng = ZeroRandom())
        val songs = planner.plan(mixedTaste, n = 4)

        assertEquals(4, songs.size)
        assertTrue(songs.all { it.title in mixedTitles })
        assertTrue(fake.calls >= 1) // discovery WAS consulted, then fell back
    }

    @Test
    fun discovery_song_with_excluded_baseTitle_is_replaced_by_a_taste_pick() = runTest {
        // The discovered title is an alternate version of a recently played
        // song: its baseTitle is in the exclude list, so it must be rejected.
        val fake = FakeDiscovery(Song(title = "Smooth Operator (Live)", artist = "Sade"))
        val planner = TastePoolPlanner(discovery = fake, rng = ZeroRandom())
        val songs = planner.plan(mixedTaste, n = 4, exclude = listOf(baseTitle("Smooth Operator")))

        assertEquals(4, songs.size) // discovery failure never shrinks the list
        assertTrue(songs.none { it.artist == "Sade" })
        assertTrue(songs.all { it.title in mixedTitles })
        assertTrue(fake.calls >= 1)
    }

    @Test
    fun discovery_throwing_never_shrinks_the_list() = runTest {
        val throwing = DiscoverySource { _, _, _ -> throw RuntimeException("network down") }
        val planner = TastePoolPlanner(discovery = throwing, rng = ZeroRandom())
        val songs = planner.plan(mixedTaste, n = 4)
        assertEquals(4, songs.size)
        assertTrue(songs.all { it.title in mixedTitles })
    }

    @Test
    fun discovery_never_fires_when_the_roll_misses() = runTest {
        val fake = FakeDiscovery(Song(title = "Smooth Operator", artist = "Sade"))
        val planner = TastePoolPlanner(discovery = fake, rng = MaxRandom())
        val songs = planner.plan(mixedTaste, n = 4)
        assertEquals(4, songs.size)
        assertEquals(0, fake.calls)
        assertTrue(songs.all { it.title in mixedTitles })
    }

    @Test
    fun discovery_receives_history_keys_seed_artist_and_already_chosen_artists() = runTest {
        val fake = FakeDiscovery(null) // null -> roll fires again on slot 2
        val planner = TastePoolPlanner(discovery = fake, rng = ZeroRandom())
        val seedSong = Song(title = "שיר פתיחה", artist = "SeedArtist")
        val songs = planner.plan(mixedTaste, n = 2, exclude = listOf("oldkey"), seed = seedSong)

        assertEquals(2, songs.size)
        assertEquals(2, fake.calls)
        // first slot: the rolling history exclude + the seed's artist
        assertTrue(fake.excludeKeysSeen[0].contains("oldkey"))
        assertTrue(fake.excludeArtistsSeen[0].contains("SeedArtist"))
        // second slot: the first chosen song is now excluded (key + artist)
        assertTrue(fake.excludeKeysSeen[1].contains(baseTitle(songs[0].title)))
        assertTrue(fake.excludeArtistsSeen[1].contains(songs[0].artist))
    }

    // --- starved pool / relax ---------------------------------------------------

    @Test
    fun starved_pool_relaxes_to_excluded_tracks_instead_of_under_delivering() = runTest {
        // Exclude EVERY track in the pool: mirrors RollingPlanner's relax --
        // prefer fresh, but fall back to recently played rather than starve.
        val allKeys = mixedTaste.topTracks.map { baseTitle(it.title) }
        for (seed in 0 until 10) {
            val planner = TastePoolPlanner(rng = kotlin.random.Random(seed))
            val songs = planner.plan(mixedTaste, n = 4, exclude = allKeys)
            assertEquals(4, songs.size) // still delivers n
            assertEquals(4, songs.map { baseTitle(it.title) }.toSet().size) // no dupes
        }
    }

    @Test
    fun fresh_tracks_are_preferred_over_excluded_ones() = runTest {
        // Only two tracks are fresh; with n = 2 the picks must be exactly them.
        val exclude = mixedTaste.topTracks.drop(2).map { baseTitle(it.title) }
        for (seed in 0 until 10) {
            val planner = TastePoolPlanner(rng = kotlin.random.Random(seed))
            val songs = planner.plan(mixedTaste, n = 2, exclude = exclude)
            assertEquals(2, songs.size)
            assertEquals(
                setOf(baseTitle("תל אביב"), baseTitle("Yesterday")),
                songs.map { baseTitle(it.title) }.toSet(),
            )
        }
    }

    // --- determinism --------------------------------------------------------------

    @Test
    fun fixed_rng_seed_makes_plan_fully_deterministic() = runTest {
        val a = TastePoolPlanner(rng = kotlin.random.Random(42)).plan(mixedTaste, n = 6)
        val b = TastePoolPlanner(rng = kotlin.random.Random(42)).plan(mixedTaste, n = 6)
        assertEquals(a, b)
    }

    @Test
    fun different_rng_seeds_vary_the_opening_song() = runTest {
        val openers = (0 until 21).map { seed ->
            TastePoolPlanner(rng = kotlin.random.Random(seed)).plan(mixedTaste, n = 1).first().title
        }.toSet()
        assertTrue("all 21 seeds opened with the same song", openers.size > 1)
    }

    // --- mood bias (LLM curator selecting FROM the real pool) -----------------------

    /** LlmClient replaying one fixed reply forever; counts calls, records prompts. */
    private class FixedLlm(private val reply: String) : LlmClient {
        var calls = 0
        val prompts = mutableListOf<String>()
        override suspend fun complete(prompt: String): String {
            calls++
            prompts.add(prompt)
            return reply
        }
    }

    /** 8 English-titled tracks by 8 distinct artists: no language/artist tier noise. */
    private val eightTaste = TasteProfile(
        topTracks = (0 until 8).map { i ->
            TasteTrack(title = "Track ${'A' + i}", artist = "Artist ${'A' + i}", durationS = 100.0)
        },
        topArtists = (0 until 8).map { i -> "Artist ${'A' + i}" },
    )

    @Test
    fun mood_fitting_songs_dominate_picks_under_a_seeded_rng() = runTest {
        // The curator says ONLY pool index 7 fits -- the LOWEST-weighted track
        // (weight 1/13). With the 8x boost it must win the n=1 slot far more
        // often than the unbiased planner ever picks it.
        val llm = FixedLlm("[7]")
        val curator = MoodCurator(llm)
        var biased = 0
        var unbiased = 0
        for (seed in 0 until 200) {
            val withMood = TastePoolPlanner(rng = kotlin.random.Random(seed), curator = curator)
                .plan(eightTaste, n = 1, mood = "party")
            if (withMood.single().title == "Track H") biased++
            val plain = TastePoolPlanner(rng = kotlin.random.Random(seed))
                .plan(eightTaste, n = 1)
            if (plain.single().title == "Track H") unbiased++
        }
        assertTrue("biased=" + biased, biased >= 60)
        assertTrue("unbiased=" + unbiased, unbiased <= 40)
        assertTrue("biased=" + biased + " unbiased=" + unbiased, biased > 2 * unbiased)
        assertEquals(1, llm.calls) // verdicts are cached across the 200 plans
    }

    @Test
    fun null_mood_mix_mood_and_null_curator_are_byte_identical_to_unbiased() = runTest {
        val llm = FixedLlm("[0]")
        val curator = MoodCurator(llm)
        val base = TastePoolPlanner(rng = kotlin.random.Random(42)).plan(mixedTaste, n = 6)
        val curatorNullMood = TastePoolPlanner(rng = kotlin.random.Random(42), curator = curator)
            .plan(mixedTaste, n = 6, mood = null)
        val curatorMixMood = TastePoolPlanner(rng = kotlin.random.Random(42), curator = curator)
            .plan(mixedTaste, n = 6, mood = "mix")
        val moodNoCurator = TastePoolPlanner(rng = kotlin.random.Random(42))
            .plan(mixedTaste, n = 6, mood = "party")
        assertEquals(base, curatorNullMood)
        assertEquals(base, curatorMixMood)
        assertEquals(base, moodNoCurator)
        assertEquals(0, llm.calls) // the LLM is never consulted in these cases
    }

    @Test
    fun curator_returning_null_yields_unbiased_picks() = runTest {
        val curator = MoodCurator(object : LlmClient {
            override suspend fun complete(prompt: String): String =
                throw RuntimeException("llm down")
        })
        for (seed in 0 until 10) {
            val base = TastePoolPlanner(rng = kotlin.random.Random(seed)).plan(mixedTaste, n = 6)
            val degraded = TastePoolPlanner(rng = kotlin.random.Random(seed), curator = curator)
                .plan(mixedTaste, n = 6, mood = "party")
            assertEquals("rng seed " + seed, base, degraded)
        }
    }

    @Test
    fun curator_is_asked_about_at_most_the_first_60_pool_entries() = runTest {
        val big = TasteProfile(
            topTracks = (0 until 70).map { i ->
                TasteTrack(title = "Song$i Unique", artist = "Artist$i", durationS = 100.0)
            },
            topArtists = emptyList(),
        )
        val llm = FixedLlm("[]")
        val curator = MoodCurator(llm)
        TastePoolPlanner(rng = kotlin.random.Random(1), curator = curator)
            .plan(big, n = 2, mood = "party")
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("Song59 Unique"))
        assertTrue(!prompt.contains("Song60 Unique"))
        assertTrue(!prompt.contains("Song69 Unique"))
    }

    // --- misc ----------------------------------------------------------------------

    @Test
    fun mood_without_a_curator_is_accepted_and_ignored() = runTest {
        val planner = TastePoolPlanner(rng = kotlin.random.Random(5))
        val songs = planner.plan(mixedTaste, n = 2, mood = "chill")
        assertEquals(2, songs.size)
    }

    @Test
    fun pool_smaller_than_n_returns_every_unique_song_once() = runTest {
        val tiny = TasteProfile(
            topTracks = listOf(
                TasteTrack(title = "Only One", artist = "A", durationS = 100.0),
                TasteTrack(title = "Only One (Live)", artist = "A", durationS = 101.0), // same baseTitle
            ),
            topArtists = listOf("A"),
        )
        val songs = TastePoolPlanner(rng = kotlin.random.Random(1)).plan(tiny, n = 5)
        assertEquals(1, songs.size) // cannot invent songs; dedup by baseTitle
        assertEquals("Only One", songs.first().title)
    }

    // --- artist fatigue (recentArtists demotion via the 6-arg overload) ------

    @Test
    fun recent_artists_are_demoted_not_excluded() = runTest {
        // Two near-equal-rank artists; fatiguing one (case-insensitively) must
        // make it open far less often -- but still sometimes (demotion!).
        val twoArtists = TasteProfile(
            topTracks = listOf(
                TasteTrack(title = "Hit Song", artist = "Fatigued", durationS = 100.0),
                TasteTrack(title = "Other Song", artist = "Fresh", durationS = 110.0),
            ),
            topArtists = listOf("Fatigued", "Fresh"),
        )
        var demoted = 0
        var plain = 0
        for (seed in 0 until 200) {
            val withFatigue = TastePoolPlanner(rng = kotlin.random.Random(seed)).plan(
                twoArtists, n = 1, exclude = null, seed = null, mood = null,
                recentArtists = listOf("FATIGUED"), // any case
            )
            if (withFatigue.single().artist == "Fatigued") demoted++
            val noFatigue = TastePoolPlanner(rng = kotlin.random.Random(seed))
                .plan(twoArtists, n = 1)
            if (noFatigue.single().artist == "Fatigued") plain++
        }
        assertTrue("demoted=$demoted", demoted > 0) // a DEMOTION, never an exclusion
        assertTrue("demoted=$demoted plain=$plain", demoted + 30 < plain)
    }

    @Test
    fun fatigue_on_every_artist_still_delivers_n_songs() = runTest {
        val songs = TastePoolPlanner(rng = kotlin.random.Random(7)).plan(
            repeatedArtistsTaste, n = 3, exclude = null, seed = null, mood = null,
            recentArtists = listOf("Artist A", "Artist B", "Artist C"),
        )
        assertEquals(3, songs.size)
        assertEquals(3, songs.map { it.artist }.toSet().size)
    }

    @Test
    fun empty_recentArtists_is_byte_identical_to_the_5_arg_plan() = runTest {
        val a = TastePoolPlanner(rng = kotlin.random.Random(42)).plan(mixedTaste, n = 6)
        val b = TastePoolPlanner(rng = kotlin.random.Random(42)).plan(
            mixedTaste, n = 6, exclude = null, seed = null, mood = null,
            recentArtists = emptyList(),
        )
        assertEquals(a, b)
    }

    // --- epsilon-greedy floor (pool tail love) --------------------------------

    /** 40 tracks, distinct artists, all-English titles: a long weighted tail. */
    private val fortyTaste = TasteProfile(
        topTracks = (0 until 40).map { i ->
            TasteTrack(title = "Deep$i Cut", artist = "Tail$i", durationS = 100.0)
        },
        topArtists = emptyList(),
    )

    private fun deepIndex(title: String): Int =
        title.removePrefix("Deep").substringBefore(" ").toInt()

    @Test
    fun uniform_epsilon_floor_surfaces_the_pool_tail() = runTest {
        // Openers from the BOTTOM half of a 40-track pool: epsilon = 1.0
        // (always uniform) must reach it far more often than epsilon = 0.0
        // (pure 1/(rank+6) weighting), and uniform sampling should put roughly
        // half the openers there.
        var greedy = 0
        var uniform = 0
        for (seed in 0 until 300) {
            val g = TastePoolPlanner(rng = kotlin.random.Random(seed), uniformEpsilon = 0.0)
                .plan(fortyTaste, n = 1).single()
            if (deepIndex(g.title) >= 20) greedy++
            val u = TastePoolPlanner(rng = kotlin.random.Random(seed), uniformEpsilon = 1.0)
                .plan(fortyTaste, n = 1).single()
            if (deepIndex(u.title) >= 20) uniform++
        }
        assertTrue("uniform=$uniform", uniform >= 120) // ~150 expected at 50%
        assertTrue("greedy=$greedy uniform=$uniform", uniform > greedy + 30)
    }

    @Test
    fun epsilon_uniform_picks_still_respect_tier_constraints() = runTest {
        // Even at epsilon = 1.0 the uniform sample is taken WITHIN the eligible
        // tier: with only two fresh tracks and n = 2 the picks must be them.
        val freshKeys = mixedTaste.topTracks.take(2).map { baseTitle(it.title) }.toSet()
        val exclude = mixedTaste.topTracks.drop(2).map { baseTitle(it.title) }
        for (seed in 0 until 10) {
            val songs = TastePoolPlanner(rng = kotlin.random.Random(seed), uniformEpsilon = 1.0)
                .plan(mixedTaste, n = 2, exclude = exclude)
            assertEquals(2, songs.size)
            assertEquals(freshKeys, songs.map { baseTitle(it.title) }.toSet())
        }
    }

    // --- discoveryRate knob ---------------------------------------------------

    @Test
    fun discoveryRate_zero_disables_discovery_even_on_the_lowest_roll() = runTest {
        val fake = FakeDiscovery(Song(title = "Smooth Operator", artist = "Sade"))
        val planner = TastePoolPlanner(discovery = fake, rng = ZeroRandom(), discoveryRate = 0.0)
        val songs = planner.plan(mixedTaste, n = 4)
        assertEquals(4, songs.size)
        assertEquals(0, fake.calls)
        assertTrue(songs.all { it.title in mixedTitles })
    }

    @Test
    fun discoveryRate_one_fires_even_on_the_highest_roll() = runTest {
        val fake = FakeDiscovery(Song(title = "Smooth Operator", artist = "Sade"))
        val planner = TastePoolPlanner(discovery = fake, rng = MaxRandom(), discoveryRate = 1.0)
        val songs = planner.plan(mixedTaste, n = 2)
        assertEquals(2, songs.size)
        assertEquals(1, fake.calls) // capped at 1 per call even at rate 1.0
        assertTrue(songs.any { it.title == "Smooth Operator" })
    }

    // --- alternation on a lopsided pool ----------------------------------------

    @Test
    fun mostly_single_language_pool_relaxes_alternation_and_terminates() = runTest {
        // 7 English + 1 Hebrew: strict alternation is impossible, so the soft
        // preference must relax -- full delivery, no dupes, no pathological
        // loop, and the lone Hebrew track plays exactly once.
        val lopsided = TasteProfile(
            topTracks = (0 until 7).map { i ->
                TasteTrack(title = "English Song $i", artist = "Band $i", durationS = 100.0)
            } + listOf(
                TasteTrack(title = "שיר", artist = "Singer", durationS = 100.0),
            ),
            topArtists = emptyList(),
        )
        for (seed in 0 until 20) {
            val songs = TastePoolPlanner(rng = kotlin.random.Random(seed)).plan(lopsided, n = 8)
            assertEquals(8, songs.size)
            assertEquals(8, songs.map { baseTitle(it.title) }.toSet().size)
            assertEquals(1, songs.count { containsHebrew(it.title) })
        }
    }
}
