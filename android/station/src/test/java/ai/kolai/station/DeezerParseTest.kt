package ai.kolai.station

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for DeezerParse.kt -- pure parsing of the three api.deezer.com response
 * shapes used by the discovery flow. Fixtures mirror real Deezer payloads
 * (extra fields present on purpose: parsers must ignore them).
 */
class DeezerParseTest {

    // --- parseDeezerArtistId (GET /search/artist?q=NAME) ---------------------

    @Test
    fun artistId_happy_path_returns_first_entry_id() {
        val json = """
            {"data":[
              {"id":27,"name":"Daft Punk","link":"https://www.deezer.com/artist/27",
               "picture":"https://api.deezer.com/artist/27/image","nb_album":36,
               "nb_fan":4435243,"radio":true,"type":"artist"},
              {"id":4836,"name":"Daft Punk Experience","type":"artist"}
            ],"total":2,"next":"https://api.deezer.com/search/artist?q=daft%20punk&index=25"}
        """.trimIndent()
        assertEquals(27L, parseDeezerArtistId(json))
    }

    @Test
    fun artistId_skips_entries_without_a_usable_id() {
        val json = """{"data":[{"name":"No Id Here"},{"id":9,"name":"Real"}]}"""
        assertEquals(9L, parseDeezerArtistId(json))
    }

    @Test
    fun artistId_tolerates_empty_and_malformed_input() {
        assertNull(parseDeezerArtistId("""{"data":[],"total":0}"""))
        assertNull(parseDeezerArtistId("""{}"""))
        assertNull(parseDeezerArtistId("""{"data":{"id":5}}""")) // data not an array
        assertNull(parseDeezerArtistId("""[1,2,3]""")) // root not an object
        assertNull(parseDeezerArtistId("not json at all"))
        assertNull(parseDeezerArtistId(""))
        assertNull(parseDeezerArtistId("""{"data":[{"id":"abc","name":"x"}]}""")) // non-numeric id
    }

    // --- parseDeezerRelatedArtists (GET /artist/{id}/related) -----------------

    @Test
    fun related_happy_path_returns_id_name_pairs_in_order() {
        val json = """
            {"data":[
              {"id":13,"name":"Justice","nb_fan":1203221,"type":"artist"},
              {"id":542,"name":"Air","type":"artist"},
              {"id":415,"name":"Phoenix","type":"artist"}
            ],"total":3}
        """.trimIndent()
        assertEquals(
            listOf(Pair(13L, "Justice"), Pair(542L, "Air"), Pair(415L, "Phoenix")),
            parseDeezerRelatedArtists(json),
        )
    }

    @Test
    fun related_skips_blank_names_and_missing_ids() {
        val json = """
            {"data":[
              {"id":1,"name":""},
              {"id":2,"name":"   "},
              {"name":"No Id"},
              {"id":3,"name":"Kept"}
            ]}
        """.trimIndent()
        assertEquals(listOf(Pair(3L, "Kept")), parseDeezerRelatedArtists(json))
    }

    @Test
    fun related_tolerates_empty_and_malformed_input() {
        assertTrue(parseDeezerRelatedArtists("""{"data":[]}""").isEmpty())
        assertTrue(parseDeezerRelatedArtists("""{}""").isEmpty())
        assertTrue(parseDeezerRelatedArtists("garbage").isEmpty())
        assertTrue(parseDeezerRelatedArtists("").isEmpty())
        assertTrue(parseDeezerRelatedArtists("""{"data":"oops"}""").isEmpty())
    }

    // --- parseDeezerTopTracks (GET /artist/{id}/top?limit=N) -------------------

    @Test
    fun topTracks_happy_path_maps_title_artist_and_duration() {
        val json = """
            {"data":[
              {"id":3135556,"readable":true,"title":"Harder, Better, Faster, Stronger",
               "title_short":"Harder, Better, Faster, Stronger","duration":225,"rank":854191,
               "preview":"https://cdns-preview.dzcdn.net/stream/x.mp3",
               "artist":{"id":27,"name":"Daft Punk","type":"artist"},
               "album":{"id":302127,"title":"Discovery","type":"album"},"type":"track"},
              {"id":67238735,"title":"תל אביב","duration":197,
               "artist":{"id":1424821,"name":"עומר אדם"},"type":"track"}
            ],"total":2}
        """.trimIndent()
        val songs = parseDeezerTopTracks(json)
        assertEquals(2, songs.size)
        assertEquals("Harder, Better, Faster, Stronger", songs[0].title)
        assertEquals("Daft Punk", songs[0].artist)
        assertEquals(225.0, songs[0].durationS!!, 0.0)
        assertEquals("תל אביב", songs[1].title)
        assertEquals("עומר אדם", songs[1].artist)
        assertEquals(197.0, songs[1].durationS!!, 0.0)
    }

    @Test
    fun topTracks_zero_or_missing_duration_becomes_null() {
        val json = """
            {"data":[
              {"title":"Zero","duration":0,"artist":{"name":"A"}},
              {"title":"Missing","artist":{"name":"B"}}
            ]}
        """.trimIndent()
        val songs = parseDeezerTopTracks(json)
        assertEquals(2, songs.size)
        assertNull(songs[0].durationS)
        assertNull(songs[1].durationS)
    }

    @Test
    fun topTracks_skips_blank_titles_and_missing_or_blank_artist_names() {
        val json = """
            {"data":[
              {"title":"","duration":100,"artist":{"name":"A"}},
              {"title":"   ","duration":100,"artist":{"name":"A"}},
              {"duration":100,"artist":{"name":"A"}},
              {"title":"No Artist Object","duration":100},
              {"title":"Blank Artist","duration":100,"artist":{"name":""}},
              {"title":"Kept","duration":100,"artist":{"name":"A"}}
            ]}
        """.trimIndent()
        val songs = parseDeezerTopTracks(json)
        assertEquals(1, songs.size)
        assertEquals("Kept", songs[0].title)
        assertEquals("A", songs[0].artist)
    }

    @Test
    fun topTracks_tolerates_empty_and_malformed_input() {
        assertTrue(parseDeezerTopTracks("""{"data":[]}""").isEmpty())
        assertTrue(parseDeezerTopTracks("""{}""").isEmpty())
        assertTrue(parseDeezerTopTracks("garbage").isEmpty())
        assertTrue(parseDeezerTopTracks("").isEmpty())
        assertTrue(parseDeezerTopTracks("""{"data":[42,"str",null]}""").isEmpty())
    }
}
