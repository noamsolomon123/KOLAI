package ai.kolai.station

import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [mergeProfiles] -- the PURE base-first / deduped / capped merge that
 * backs `ai.kolai.app.ExpandedTasteSource`. No IO here; the Deezer HTTP + disk
 * cache around this function lives in :app (no JVM unit tests there).
 */
class TasteExpansionTest {

    private fun t(title: String, artist: String = "A", dur: Double = 0.0) =
        TasteTrack(title = title, artist = artist, durationS = dur)

    @Test
    fun base_tracks_keep_their_order_at_the_front() {
        val base = TasteProfile(
            topTracks = listOf(t("One"), t("Two"), t("Three")),
            topArtists = listOf("A"),
        )
        val merged = mergeProfiles(base, listOf(t("Disc1", "B"), t("Disc2", "C")))
        assertEquals(listOf("One", "Two", "Three", "Disc1", "Disc2"), merged.topTracks.map { it.title })
    }

    @Test
    fun discovered_dupes_of_base_are_dropped_by_base_title() {
        val base = TasteProfile(topTracks = listOf(t("Song")), topArtists = emptyList())
        // "Song (Live)" collapses to the same baseTitle as "Song" -> dropped.
        val merged = mergeProfiles(base, listOf(t("Song (Live)", "X"), t("Fresh", "Y")))
        assertEquals(listOf("Song", "Fresh"), merged.topTracks.map { it.title })
    }

    @Test
    fun discovered_internal_dupes_are_dropped() {
        val base = TasteProfile(topTracks = listOf(t("Base")), topArtists = emptyList())
        val merged = mergeProfiles(base, listOf(t("Dup", "X"), t("Dup", "Y"), t("Other", "Z")))
        assertEquals(listOf("Base", "Dup", "Other"), merged.topTracks.map { it.title })
    }

    @Test
    fun blank_titles_are_skipped() {
        val base = TasteProfile(topTracks = listOf(t("Base")), topArtists = emptyList())
        val merged = mergeProfiles(base, listOf(t("   ", "X"), t("Good", "Y")))
        assertEquals(listOf("Base", "Good"), merged.topTracks.map { it.title })
    }

    @Test
    fun cap_truncates_base_first() {
        val base = TasteProfile(topTracks = (1..5).map { t("B$it") }, topArtists = emptyList())
        val merged = mergeProfiles(base, (1..10).map { t("D$it", "Z") }, cap = 7)
        assertEquals(7, merged.topTracks.size)
        // The 5 base tracks survive, then 2 discovered fill to the cap.
        assertEquals(listOf("B1", "B2", "B3", "B4", "B5", "D1", "D2"), merged.topTracks.map { it.title })
    }

    @Test
    fun duration_is_carried_through_for_discovered_tracks() {
        val base = TasteProfile(topTracks = listOf(t("Base", dur = 100.0)), topArtists = emptyList())
        val merged = mergeProfiles(base, listOf(t("Disc", "X", dur = 211.0)))
        assertEquals(211.0, merged.topTracks[1].durationS, 0.0)
    }

    @Test
    fun artists_union_base_first_deduped_case_insensitively() {
        val base = TasteProfile(topTracks = emptyList(), topArtists = listOf("Daft Punk", "Muse"))
        val merged = mergeProfiles(
            base,
            discovered = emptyList(),
            extraArtists = listOf("daft punk", "Justice", "  "),
        )
        assertEquals(listOf("Daft Punk", "Muse", "Justice"), merged.topArtists)
    }

    @Test
    fun empty_discovery_returns_capped_base_unchanged() {
        val base = TasteProfile(topTracks = listOf(t("One"), t("Two")), topArtists = listOf("A"))
        val merged = mergeProfiles(base, emptyList())
        assertEquals(listOf("One", "Two"), merged.topTracks.map { it.title })
        assertEquals(listOf("A"), merged.topArtists)
    }

    @Test
    fun grows_a_tiny_base_into_a_satisfiable_pool() {
        // The real-world fix: 20 base + plenty discovered -> >= 60 unique tracks,
        // which is what makes the no-repeat window of 50 satisfiable.
        val base = TasteProfile(topTracks = (1..20).map { t("Base$it") }, topArtists = emptyList())
        val discovered = (1..100).map { t("Disc$it", "Art$it") }
        val merged = mergeProfiles(base, discovered, cap = 80)
        assertEquals(80, merged.topTracks.size)
        assertTrue(merged.topTracks.size > 50)
    }
}