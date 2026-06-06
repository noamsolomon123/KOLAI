package ai.kolai.acquire

import ai.kolai.core.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the candidate-scoring logic ported 1:1 from
 * `backend/radioai/fetcher.py` (`_candidate_score` / `pick_best_candidate`).
 *
 * Scoring weights mirrored from the Python:
 *  - duration delta: max(0, 30 - |dur - song.durationS|)   (only if both truthy)
 *  - +5 if any good keyword in the lowercased title (once, no stacking)
 *  - -25 per distinct bad keyword present
 *  - +10 if the RAW (non-lowercased) title contains Hebrew
 *  - popularity: min(20, log10(views + 1) * 2)             (only if views > 0)
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
    fun goodKeyword_addsFiveOnce_noStacking() {
        // Two good keywords ("official" + "audio") present -> still only +5.
        val twoGood = Candidate(
            title = "official audio xyz", // ascii only, no Hebrew, no duration/views
            durationS = null,
            viewCount = null,
            id = "two",
        )
        val noGood = Candidate(
            title = "xyz",
            durationS = null,
            viewCount = null,
            id = "none",
        )
        assertEquals(5.0, candidateScore(song, twoGood), 1e-9)
        assertEquals(0.0, candidateScore(song, noGood), 1e-9)
    }

    @Test
    fun badKeyword_subtractsTwentyFivePerDistinctMatch() {
        val oneBad = Candidate(
            title = "live",
            durationS = null,
            viewCount = null,
            id = "one",
        )
        val twoBad = Candidate(
            title = "live remix",
            durationS = null,
            viewCount = null,
            id = "two",
        )
        assertEquals(-25.0, candidateScore(song, oneBad), 1e-9)
        assertEquals(-50.0, candidateScore(song, twoBad), 1e-9)
    }

    @Test
    fun hebrewBoost_appliesOnRawTitle_notLowercased() {
        // Hebrew chars are case-less, but the port must read the RAW title.
        // Use a Hebrew-only title with no keywords/duration/views so the only
        // contribution is the +10 Hebrew boost.
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
        // hebrew: +10 ; ascii: -25 (because "english" is a bad keyword)
        assertEquals(10.0, candidateScore(song, hebrew), 1e-9)
    }

    @Test
    fun durationFarOff_lowersScoreVsClose() {
        val close = Candidate(
            title = "xyz",
            durationS = 200.0, // exact match -> +30
            viewCount = null,
            id = "close",
        )
        val farOff = Candidate(
            title = "xyz",
            durationS = 400.0, // |400-200| = 200 -> max(0, 30-200) = 0
            viewCount = null,
            id = "far",
        )
        assertEquals(30.0, candidateScore(song, close), 1e-9)
        assertEquals(0.0, candidateScore(song, farOff), 1e-9)
        assertTrue(candidateScore(song, close) > candidateScore(song, farOff))
    }

    @Test
    fun popularity_isLog10Capped_andOnlyWhenViewsPositive() {
        val noViews = Candidate(
            title = "xyz", durationS = null, viewCount = 0L, id = "nov",
        )
        val nullViews = Candidate(
            title = "xyz", durationS = null, viewCount = null, id = "nullv",
        )
        // log10(1000+1)*2 ~= 6.0008..., under the 20 cap.
        val someViews = Candidate(
            title = "xyz", durationS = null, viewCount = 1000L, id = "some",
        )
        // Huge views -> capped at 20.
        val hugeViews = Candidate(
            title = "xyz", durationS = null, viewCount = 1_000_000_000_000L, id = "huge",
        )
        assertEquals(0.0, candidateScore(song, noViews), 1e-9)
        assertEquals(0.0, candidateScore(song, nullViews), 1e-9)
        assertEquals(Math.log10(1001.0) * 2.0, candidateScore(song, someViews), 1e-9)
        assertEquals(20.0, candidateScore(song, hugeViews), 1e-9)
    }
}
