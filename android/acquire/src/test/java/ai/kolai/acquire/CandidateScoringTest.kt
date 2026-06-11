package ai.kolai.acquire

import ai.kolai.core.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the candidate-scoring logic (originally ported from
 * `backend/radioai/fetcher.py`, extended on Android with a title-match term).
 *
 * Scoring formula under test:
 *  - duration delta: max(0, 30 - |dur - song.durationS|)   (only if both truthy)
 *  - duration sanity: -35 if cand.durationS > 720 (12 min)
 *  - +5 if any good keyword in the lowercased title (once, no stacking)
 *  - -25 per distinct bad keyword present
 *  - +10 if the RAW (non-lowercased) title contains Hebrew
 *  - title coverage: +coverage * 45 (fraction of requested-title tokens present
 *    in the candidate title), and an extra -45 if coverage < 0.5
 *  - +8 if ALL artist tokens appear in the candidate title
 *  - popularity: min(20, log10(views + 1) * 2)             (only if views > 0)
 *
 * Note: most exact-score tests use candidate titles that FULLY contain the
 * requested title tokens, so the coverage term is a known +45 constant and the
 * term under test stays isolated.
 */
class CandidateScoringTest {

    private val song = Song(
        title = "Test Song",
        artist = "Test Artist",
        durationS = 200.0,
    )

    @Test
    fun emptyCandidates_returnsNull() {
        assertNull(pickBestCandidate(song, emptyList()))
    }

    @Test
    fun cleanOfficialAudio_beatsLiveRemixWithBadKeywords() {
        // Clean: official audio, close duration, lots of views.
        val clean = Candidate(
            title = "Test Artist - Test Song (Official Audio)",
            durationS = 200.0,
            viewCount = 5_000_000L,
            id = "clean",
        )
        // Bad: live + remix (two bad keywords), duration off, fewer views.
        val bad = Candidate(
            title = "Test Artist - Test Song (Live Remix)",
            durationS = 320.0,
            viewCount = 1000L,
            id = "bad",
        )
        val best = pickBestCandidate(song, listOf(bad, clean))
        assertNotNull(best)
        assertEquals("clean", best!!.id)
        assertTrue(candidateScore(song, clean) > candidateScore(song, bad))
    }

    @Test
    fun uploadTypeLadder_audioBeatsOfficialBeatsClip_firstMatchOnly() {
        // All titles fully cover "test song" -> coverage term is +45 for all.
        fun cand(t: String, id: String) =
            Candidate(title = t, durationS = null, viewCount = null, id = id)
        val audio = cand("test song official audio", "audio")    // audio tier
        val officialOnly = cand("test song official", "off")     // official tier
        val clip = cand("test song official music video", "clip") // video tier
        val none = cand("test song xyz", "none")                  // no tier
        // First matching tier only, no stacking: "official audio" -> +12 (not +17).
        assertEquals(45.0 + 12.0, candidateScore(song, audio), 1e-9)
        assertEquals(45.0 + 5.0, candidateScore(song, officialOnly), 1e-9)
        assertEquals(45.0 + 3.0, candidateScore(song, clip), 1e-9)
        assertEquals(45.0, candidateScore(song, none), 1e-9)
    }

    @Test
    fun audioUpload_beatsMorePopularOfficialClip() {
        // The user-reported preference: never fetch the music video (clip)
        // when a matching audio upload exists -- even when the clip is far
        // more popular (clips usually are).
        val requested = Song(title = "Test Song", artist = "Some Artist")
        val audio = Candidate(
            title = "Some Artist - Test Song (Official Audio)",
            durationS = null,
            viewCount = 8_000_000,
            id = "audio",
        )
        val clip = Candidate(
            title = "Some Artist - Test Song (Official Music Video)",
            durationS = null,
            viewCount = 450_000_000,
            id = "clip",
        )
        val best = pickBestCandidate(requested, listOf(clip, audio))
        assertEquals("audio", best!!.id)
    }

    @Test
    fun badKeyword_subtractsTwentyFivePerDistinctMatch() {
        // Full title coverage (+45) keeps the bad-keyword delta isolated.
        val oneBad = Candidate(
            title = "test song live",
            durationS = null,
            viewCount = null,
            id = "one",
        )
        val twoBad = Candidate(
            title = "test song live remix",
            durationS = null,
            viewCount = null,
            id = "two",
        )
        // oneBad: 45 - 25 = 20 ; twoBad: 45 - 50 = -5 (still -25 per match).
        assertEquals(20.0, candidateScore(song, oneBad), 1e-9)
        assertEquals(-5.0, candidateScore(song, twoBad), 1e-9)
    }

    @Test
    fun hebrewBoost_appliesOnRawTitle_notLowercased() {
        // Hebrew chars are case-less, but the port must read the RAW title.
        // Request the Hebrew title itself so coverage is a known +45 and the
        // only other contribution is the +10 Hebrew boost.
        val hebSong = Song(title = "שיר עברי", artist = "זמר כלשהו")
        val hebrew = Candidate(
            title = "שיר עברי",
            durationS = null,
            viewCount = null,
            id = "heb",
        )
        val ascii = Candidate(
            title = "english title",
            durationS = null,
            viewCount = null,
            id = "asc",
        )
        assertTrue(hasHebrew("שיר עברי"))
        assertEquals(false, hasHebrew("english title"))
        // hebrew: +45 coverage +10 Hebrew = 55
        assertEquals(55.0, candidateScore(hebSong, hebrew), 1e-9)
        // ascii: -25 ("english" is bad) + 0 coverage - 45 low-coverage = -70
        assertTrue(candidateScore(hebSong, hebrew) > candidateScore(hebSong, ascii))
    }

    @Test
    fun durationFarOff_lowersScoreVsClose() {
        val close = Candidate(
            title = "test song",
            durationS = 200.0, // exact match -> +30
            viewCount = null,
            id = "close",
        )
        val farOff = Candidate(
            title = "test song",
            durationS = 400.0, // |400-200| = 200 -> max(0, 30-200) = 0
            viewCount = null,
            id = "far",
        )
        // Both titles fully match -> +45 coverage baseline.
        assertEquals(75.0, candidateScore(song, close), 1e-9)
        assertEquals(45.0, candidateScore(song, farOff), 1e-9)
        assertTrue(candidateScore(song, close) > candidateScore(song, farOff))
    }

    @Test
    fun popularity_isLog10Capped_andOnlyWhenViewsPositive() {
        // Full title coverage -> +45 baseline for every candidate here.
        val noViews = Candidate(
            title = "test song", durationS = null, viewCount = 0L, id = "nov",
        )
        val nullViews = Candidate(
            title = "test song", durationS = null, viewCount = null, id = "nullv",
        )
        // log10(1000+1)*2 ~= 6.0008..., under the 20 cap.
        val someViews = Candidate(
            title = "test song", durationS = null, viewCount = 1000L, id = "some",
        )
        // Huge views -> capped at 20.
        val hugeViews = Candidate(
            title = "test song", durationS = null, viewCount = 1_000_000_000_000L, id = "huge",
        )
        assertEquals(45.0, candidateScore(song, noViews), 1e-9)
        assertEquals(45.0, candidateScore(song, nullViews), 1e-9)
        assertEquals(45.0 + Math.log10(1001.0) * 2.0, candidateScore(song, someViews), 1e-9)
        assertEquals(65.0, candidateScore(song, hugeViews), 1e-9)
    }

    @Test
    fun artistPresence_addsEight_whenAllArtistTokensInTitle() {
        val withArtist = Candidate(
            title = "test artist test song", durationS = null, viewCount = null, id = "wa",
        )
        val withoutArtist = Candidate(
            title = "test song", durationS = null, viewCount = null, id = "na",
        )
        // withArtist: +45 coverage +8 artist = 53 ; withoutArtist: +45.
        assertEquals(53.0, candidateScore(song, withArtist), 1e-9)
        assertEquals(45.0, candidateScore(song, withoutArtist), 1e-9)
    }

    // ---- regression tests for the wrong-song bug -------------------------

    @Test
    fun regression_correctSong_beatsArtistsMostPopularVideo() {
        // THE bug: app showed "Waiting For Love" while playing "The Nights".
        // LLM-picked songs have durationS == null, so popularity used to win.
        val requested = Song(title = "Waiting For Love", artist = "Avicii")
        val theNights = Candidate(
            title = "Avicii - The Nights",
            durationS = 177.0,
            viewCount = 900_000_000L,
            id = "nights",
        )
        val waitingForLove = Candidate(
            title = "Avicii - Waiting For Love",
            durationS = 230.0,
            viewCount = 300_000_000L,
            id = "wfl",
        )
        val best = pickBestCandidate(requested, listOf(theNights, waitingForLove))
        assertNotNull(best)
        assertEquals("wfl", best!!.id)
        assertTrue(
            candidateScore(requested, waitingForLove) > candidateScore(requested, theNights),
        )
    }

    @Test
    fun hebrewTitleMatching_correctSongBeatsUnrelatedPopularHebrewVideo() {
        val requested = Song(title = "כולם גנבים", artist = "אושר כהן")
        val correct = Candidate(
            title = "אושר כהן - כולם גנבים (קליפ רשמי)",
            durationS = 200.0,
            viewCount = 5_000_000L,
            id = "correct",
        )
        val unrelatedPopular = Candidate(
            title = "אושר כהן - שיר אחר לגמרי",
            durationS = 210.0,
            viewCount = 200_000_000L,
            id = "unrelated",
        )
        val best = pickBestCandidate(requested, listOf(unrelatedPopular, correct))
        assertNotNull(best)
        assertEquals("correct", best!!.id)
    }

    @Test
    fun oneHourMix_losesToCorrectSingle_despiteHugeViews() {
        val requested = Song(title = "Waiting For Love", artist = "Avicii")
        val hourMix = Candidate(
            title = "Avicii - Waiting For Love 1 Hour Loop",
            durationS = 3600.0, // > 720s -> -35 sanity, plus bad-keyword hits
            viewCount = 800_000_000L,
            id = "mix",
        )
        val single = Candidate(
            title = "Avicii - Waiting For Love",
            durationS = 230.0,
            viewCount = 50_000_000L,
            id = "single",
        )
        val best = pickBestCandidate(requested, listOf(hourMix, single))
        assertNotNull(best)
        assertEquals("single", best!!.id)
        assertTrue(candidateScore(requested, single) > candidateScore(requested, hourMix))
    }

    @Test
    fun titleCoverage_belowHalf_getsExtraPenalty() {
        val requested = Song(title = "waiting for love", artist = "someone")
        val oneOfThree = Candidate(
            title = "love xyz", durationS = null, viewCount = null, id = "one",
        )
        val twoOfThree = Candidate(
            title = "waiting love xyz", durationS = null, viewCount = null, id = "two",
        )
        // 1/3 coverage: +15 - 45 penalty = -30 ; 2/3 coverage: +30, no penalty.
        assertEquals(-30.0, candidateScore(requested, oneOfThree), 1e-9)
        assertEquals(30.0, candidateScore(requested, twoOfThree), 1e-9)
    }

    @Test
    fun featClauses_strippedOnBothSides_doNotHurtCoverage() {
        // Candidate side: "(feat. ...)" must not lower coverage.
        val candWithFeat = Candidate(
            title = "Test Artist - Test Song (feat. Somebody Else)",
            durationS = null,
            viewCount = null,
            id = "cf",
        )
        // +45 coverage (full) + 8 artist; cand.durationS null -> no duration terms.
        assertEquals(53.0, candidateScore(song, candWithFeat), 1e-9)

        // Requested side: feat-clause in the LLM title must not demand extra tokens.
        val requestedWithFeat = Song(title = "Test Song (feat. Other)", artist = "Test Artist")
        val plainCand = Candidate(
            title = "test artist test song", durationS = null, viewCount = null, id = "pc",
        )
        assertEquals(53.0, candidateScore(requestedWithFeat, plainCand), 1e-9)
    }
}