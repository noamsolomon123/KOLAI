package ai.kolai.station

import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the setlist parsing helpers ported from
 * backend/radioai/setlist.py (_extract_json_array, _base_title, parse_setlist).
 */
class SetlistParsingTest {

    private val taste = TasteProfile(
        topTracks = listOf(
            TasteTrack(title = "Yesterday", artist = "The Beatles", durationS = 125.0),
            TasteTrack(title = "תפילה", artist = "אביב גפן", durationS = 240.0),
        ),
        topArtists = listOf("The Beatles", "אביב גפן"),
    )

    // --- extractJsonArray ---

    @Test
    fun extractJsonArray_tolerates_code_fences_and_prose() {
        val text = """
            Sure! Here is your setlist:
            ```json
            [{"title": "A", "artist": "X"}, {"title": "B", "artist": "Y"}]
            ```
            Enjoy the music.
        """.trimIndent()
        val arr = extractJsonArray(text)
        assertEquals(2, arr.size)
    }

    @Test
    fun extractJsonArray_falls_back_to_balanced_scan_when_greedy_parse_fails() {
        // Greedy \[.*\] would grab from the first [ to the LAST ] (the prose
        // bracket), producing invalid JSON; the balanced scan recovers the
        // first valid array.
        val text = "noise [ {\"title\":\"A\",\"artist\":\"X\"} ] then [not json]"
        val arr = extractJsonArray(text)
        assertEquals(1, arr.size)
        assertEquals("A", arr[0]["title"].toString().trim('"'))
    }

    @Test(expected = IllegalArgumentException::class)
    fun extractJsonArray_throws_on_null() {
        extractJsonArray(null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun extractJsonArray_throws_when_no_array_present() {
        extractJsonArray("absolutely no json here")
    }

    // --- baseTitle ---

    @Test
    fun baseTitle_collapses_live_remix_feat_variants_to_same_key() {
        val a = baseTitle("Song")
        assertEquals(a, baseTitle("Song (Live)"))
        assertEquals(a, baseTitle("Song - Remix"))
        assertEquals(a, baseTitle("Song feat. Drake"))
        assertEquals(a, baseTitle("Song [Acoustic]"))
        assertEquals(a, baseTitle("Song ft. X"))
    }

    @Test
    fun baseTitle_preserves_hebrew_word_chars() {
        // With Unicode-aware \w, Hebrew letters survive punctuation stripping.
        assertEquals("תפילה", baseTitle("תפילה (Live)"))
    }

    // --- parseSetlist ---

    @Test
    fun parseSetlist_dedups_exact_duplicates() {
        val text = """
            [{"title":"A","artist":"X"},{"title":"A","artist":"X"},{"title":"B","artist":"Y"}]
        """.trimIndent()
        val songs = parseSetlist(text, taste, n = 6)
        assertEquals(2, songs.size)
    }

    @Test
    fun parseSetlist_dedups_base_title_duplicates() {
        // "Song" and "Song (Live)" by the same artist collapse via base-title.
        val text = """
            [{"title":"Song","artist":"X"},{"title":"Song (Live)","artist":"X"}]
        """.trimIndent()
        val songs = parseSetlist(text, taste, n = 6)
        assertEquals(1, songs.size)
        assertEquals("Song", songs[0].title)
    }

    @Test
    fun parseSetlist_fills_durationS_from_taste_case_insensitively() {
        val text = """
            [{"title":"yesterday","artist":"the beatles"},{"title":"Unknown","artist":"Nobody"}]
        """.trimIndent()
        val songs = parseSetlist(text, taste, n = 6)
        assertEquals(2, songs.size)
        assertEquals(125.0, songs[0].durationS!!, 0.0001)
        assertNull(songs[1].durationS)
    }

    @Test
    fun parseSetlist_caps_at_n() {
        val text = """
            [{"title":"A","artist":"X"},{"title":"B","artist":"X"},
             {"title":"C","artist":"X"},{"title":"D","artist":"X"}]
        """.trimIndent()
        val songs = parseSetlist(text, taste, n = 2)
        assertEquals(2, songs.size)
    }

    @Test
    fun parseSetlist_skips_items_missing_title_or_artist() {
        val text = """
            [{"title":"","artist":"X"},{"title":"B","artist":""},{"title":"C","artist":"Z"}]
        """.trimIndent()
        val songs = parseSetlist(text, taste, n = 6)
        assertEquals(1, songs.size)
        assertEquals("C", songs[0].title)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseSetlist_throws_on_empty_array() {
        parseSetlist("[]", taste, n = 6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseSetlist_throws_on_garbage() {
        parseSetlist("totally not json", taste, n = 6)
    }
}