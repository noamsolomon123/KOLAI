package ai.kolai.station

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TDD for ShowMeta.buildSegments, ported 1:1 from backend/radioai/showmeta.py
 * (build_segments). Sort song markers by start_s and fill end_s (next start;
 * last = total). end_s is round(., 2) (round-half-to-even, like Python round).
 */
class ShowMetaTest {

    @Test
    fun buildSegments_sorts_and_fills_end_s() {
        val items = listOf(
            SongEvent(title = "B", artist = "AB", startS = 10.0),
            SongEvent(title = "A", artist = "AA", startS = 0.0),
            SongEvent(title = "C", artist = "AC", startS = 25.0),
        )
        val out = buildSegments(items, totalS = 40.0)

        assertEquals(listOf("A", "B", "C"), out.map { it.title })
        assertEquals(0.0, out[0].startS, 1e-9)
        assertEquals(10.0, out[0].endS, 1e-9)
        assertEquals(10.0, out[1].startS, 1e-9)
        assertEquals(25.0, out[1].endS, 1e-9)
        assertEquals(25.0, out[2].startS, 1e-9)
        assertEquals(40.0, out[2].endS, 1e-9) // last -> total
    }

    @Test
    fun buildSegments_single_item_end_is_total() {
        val out = buildSegments(listOf(SongEvent("only", "art", 0.0)), totalS = 12.345)
        assertEquals(1, out.size)
        // round(12.345, 2) -> 12.35 (note: 12.345 in double is slightly above)
        assertEquals(Math.rint(12.345 * 100.0) / 100.0, out[0].endS, 1e-9)
    }

    @Test
    fun buildSegments_empty_is_empty() {
        assertEquals(0, buildSegments(emptyList(), totalS = 5.0).size)
    }

    @Test
    fun buildSegments_preserves_artist_and_index() {
        val items = listOf(
            SongEvent(title = "X", artist = "ArtX", startS = 0.0),
            SongEvent(title = "Y", artist = "ArtY", startS = 5.0),
        )
        val out = buildSegments(items, totalS = 9.0)
        assertEquals("ArtX", out[0].artist)
        assertEquals("ArtY", out[1].artist)
        assertEquals(0, out[0].index)
        assertEquals(1, out[1].index)
    }
}