package ai.kolai.core.taste

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Spotify TASTE data layer, ported 1:1 from the Python reference
 * backend/radioai/taste.py (parse_profile + _profile_to_dict/_profile_from_dict).
 *
 * Covers:
 *  - parseProfile() against a canned Spotify-shaped response, including the
 *    null-tolerance the Python relies on (missing artists -> "", missing
 *    duration_ms -> 0.0).
 *  - taste.json round-trip equality and the snake_case on-disk contract
 *    (top_tracks / duration_s / top_artists) so the file stays byte-compatible
 *    with an existing Python cache.
 */
class TasteTest {

    private fun obj(raw: String): JsonObject =
        Json.parseToJsonElement(raw).jsonObject

    @Test
    fun parseProfileMapsSpotifyShape() {
        val topTracks = obj(
            """
            {
              "items": [
                {
                  "name": "Track One",
                  "artists": [ { "name": "Artist A" }, { "name": "Artist B" } ],
                  "duration_ms": 210000
                },
                {
                  "name": "Track Two",
                  "artists": [ { "name": "Artist C" } ],
                  "duration_ms": 185500
                }
              ]
            }
            """.trimIndent()
        )
        val topArtists = obj(
            """
            { "items": [ { "name": "Artist A" }, { "name": "Artist C" } ] }
            """.trimIndent()
        )

        val profile = parseProfile(topTracks, topArtists)

        val expected = TasteProfile(
            topTracks = listOf(
                // artists[0].name wins; duration_ms / 1000.0 -> seconds.
                TasteTrack(title = "Track One", artist = "Artist A", durationS = 210.0),
                TasteTrack(title = "Track Two", artist = "Artist C", durationS = 185.5),
            ),
            topArtists = listOf("Artist A", "Artist C"),
        )
        assertEquals(expected, profile)
    }

    @Test
    fun parseProfileIsNullTolerant() {
        // Mirror Python's .get(...) defaults: missing name -> "", missing/empty
        // artists -> "", missing duration_ms -> 0.0.
        val topTracks = obj(
            """
            {
              "items": [
                { "name": "No Artists Track", "artists": [], "duration_ms": 1000 },
                { "artists": [ { "name": "Lonely" } ] },
                { }
              ]
            }
            """.trimIndent()
        )
        // Whole items list missing -> empty list (Python .get("items", [])).
        val topArtists = obj("""{ }""")

        val profile = parseProfile(topTracks, topArtists)

        val expected = TasteProfile(
            topTracks = listOf(
                TasteTrack(title = "No Artists Track", artist = "", durationS = 1.0),
                TasteTrack(title = "", artist = "Lonely", durationS = 0.0),
                TasteTrack(title = "", artist = "", durationS = 0.0),
            ),
            topArtists = emptyList(),
        )
        assertEquals(expected, profile)
    }

    @Test
    fun roundTripPreservesProfile() {
        val profile = TasteProfile(
            topTracks = listOf(
                TasteTrack("Shir Ehad", "Zamar", 201.0),
                TasteTrack("Shir Sheni", "Zameret", 178.25),
            ),
            topArtists = listOf("Zamar", "Zameret", "Lahaka"),
        )
        val restored = profileFromJson(profileToJson(profile))
        assertEquals(profile, restored)
    }

    @Test
    fun serializedJsonUsesSnakeCaseKeys() {
        val profile = TasteProfile(
            topTracks = listOf(TasteTrack("T", "A", 100.0)),
            topArtists = listOf("A"),
        )
        val json = profileToJson(profile)

        // Top-level snake_case keys, byte-compatible with the Python taste.json.
        assertTrue("expected top_tracks key", json.containsKey("top_tracks"))
        assertTrue("expected top_artists key", json.containsKey("top_artists"))

        val track0 = json["top_tracks"]!!.jsonArray[0].jsonObject
        assertTrue("expected title key", track0.containsKey("title"))
        assertTrue("expected artist key", track0.containsKey("artist"))
        assertTrue("expected duration_s key", track0.containsKey("duration_s"))

        assertEquals("T", track0["title"]!!.jsonPrimitive.content)
        assertEquals("A", track0["artist"]!!.jsonPrimitive.content)
    }
}
