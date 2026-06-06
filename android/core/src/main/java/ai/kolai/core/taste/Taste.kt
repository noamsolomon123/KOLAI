package ai.kolai.core.taste

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Spotify TASTE data layer, ported 1:1 from the Python reference
 * `backend/radioai/taste.py` (`TasteTrack`, `TasteProfile`, `parse_profile`,
 * `_profile_to_dict`, `_profile_from_dict`).
 *
 * Only the pure data types + the response parser + the profile<->dict
 * (taste.json) serialization are ported here. The Python `TasteService`
 * (Spotify OAuth/PKCE, network calls, and disk caching) is intentionally NOT
 * ported -- that is a later, device-dependent task.
 *
 * JSON uses kotlinx-serialization-json at RUNTIME only (no `@Serializable`, no
 * serialization compiler plugin): we work directly with [JsonObject] /
 * [buildJsonObject].
 *
 * Numeric intent: Python `float` is 64-bit, so durations map to Kotlin [Double]
 * (matching the existing `ai.kolai.core` convention, e.g. `Song.durationS`).
 *
 * On-disk contract: the taste.json keys are deliberately **snake_case**
 * (`top_tracks`, `duration_s`, `top_artists`) so the file stays byte-compatible
 * with an existing Python cache, even though the Kotlin fields are camelCase.
 */

/** Python `TasteTrack` dataclass (title, artist, duration_s). */
data class TasteTrack(
    val title: String,
    val artist: String,
    /** Track length in seconds (Python `duration_s`). */
    val durationS: Double,
)

/** Python `TasteProfile` dataclass (top_tracks, top_artists). */
data class TasteProfile(
    val topTracks: List<TasteTrack>,
    val topArtists: List<String>,
)

/**
 * Mirror of Python `parse_profile(top_tracks_json, top_artists_json)`.
 *
 * For each item in `topTracksJson["items"]`: `name` -> [TasteTrack.title],
 * `artists[0].name` -> [TasteTrack.artist] (empty when there are no artists),
 * and `duration_ms / 1000.0` -> [TasteTrack.durationS] (0.0 when `duration_ms`
 * is missing or null). Artist names come from `topArtistsJson["items"][].name`.
 *
 * Null-tolerant exactly like the Python `.get(...)` defaults: any missing
 * object, array, or field collapses to "" / 0.0 / empty-list rather than
 * throwing.
 */
fun parseProfile(topTracksJson: JsonObject, topArtistsJson: JsonObject): TasteProfile {
    val tracks = topTracksJson.arrayOrEmpty("items").map { itemEl ->
        val item = itemEl.objectOrEmpty()
        val artists = item.arrayOrEmpty("artists")
        // Python: artists[0].get("name", "") if artists else ""
        val artist = if (artists.isNotEmpty()) artists[0].objectOrEmpty().stringOrEmpty("name") else ""
        TasteTrack(
            title = item.stringOrEmpty("name"),
            artist = artist,
            // Python: (it.get("duration_ms", 0) or 0) / 1000.0
            durationS = item.doubleOrZero("duration_ms") / 1000.0,
        )
    }
    // Python: [a.get("name", "") for a in top_artists_json.get("items", [])]
    val artistNames = topArtistsJson.arrayOrEmpty("items").map { it.objectOrEmpty().stringOrEmpty("name") }
    return TasteProfile(topTracks = tracks, topArtists = artistNames)
}

/**
 * Mirror of Python `_profile_to_dict`: emits the taste.json contract with
 * **snake_case** keys (`top_tracks`, `title`, `artist`, `duration_s`,
 * `top_artists`) for byte-compatibility with the Python cache.
 */
fun profileToJson(p: TasteProfile): JsonObject = buildJsonObject {
    putJsonArray("top_tracks") {
        p.topTracks.forEach { t ->
            add(
                buildJsonObject {
                    put("title", t.title)
                    put("artist", t.artist)
                    put("duration_s", t.durationS)
                }
            )
        }
    }
    putJsonArray("top_artists") {
        p.topArtists.forEach { add(JsonPrimitive(it)) }
    }
}

/**
 * Mirror of Python `_profile_from_dict`: reads the snake_case taste.json
 * contract back into a [TasteProfile]. Null-tolerant like the Python
 * `d.get(..., [])` defaults.
 */
fun profileFromJson(json: JsonObject): TasteProfile {
    val tracks = json.arrayOrEmpty("top_tracks").map { trackEl ->
        val t = trackEl.objectOrEmpty()
        TasteTrack(
            title = t.stringOrEmpty("title"),
            artist = t.stringOrEmpty("artist"),
            durationS = t.doubleOrZero("duration_s"),
        )
    }
    val artists = json.arrayOrEmpty("top_artists").map { (it as? JsonPrimitive)?.content ?: "" }
    return TasteProfile(topTracks = tracks, topArtists = artists)
}

// --- small null-tolerant accessors mirroring Python dict.get(...) defaults ---

private fun JsonObject.arrayOrEmpty(key: String): List<JsonElement> =
    (this[key] as? JsonArray)?.toList() ?: emptyList()

private fun JsonElement.objectOrEmpty(): JsonObject =
    (this as? JsonObject) ?: JsonObject(emptyMap())

private fun JsonObject.stringOrEmpty(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: ""

private fun JsonObject.doubleOrZero(key: String): Double =
    (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
