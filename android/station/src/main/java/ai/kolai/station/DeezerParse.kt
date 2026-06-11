package ai.kolai.station

import ai.kolai.core.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * PURE parsing of the three Deezer API response shapes (api.deezer.com,
 * free/keyless) that the discovery flow needs. NO HTTP here -- the network
 * side of the [DiscoverySource] implementation is a separate task; these
 * functions only turn response bodies into data.
 *
 * JSON uses kotlinx-serialization-json at RUNTIME only (manual tree
 * navigation, no @Serializable / no serialization compiler plugin), matching
 * the module's NewsRss/StationPersistence conventions.
 *
 * All three parsers are null-tolerant and NEVER throw on malformed input:
 * any parse problem yields null / an empty list, and entries with blank
 * titles/names (or missing ids) are skipped rather than failing the batch.
 */

/** Parse a Deezer body's top-level {"data":[...]} into its object entries. */
private fun deezerData(json: String): List<JsonObject>? = try {
    val root = Json.parseToJsonElement(json) as? JsonObject
    (root?.get("data") as? JsonArray)?.mapNotNull { it as? JsonObject }
} catch (e: Exception) {
    null
}

/** Python-ish (obj.get(key) or "") -- non-string / missing collapses to "". */
private fun JsonObject.stringOrEmpty(key: String): String {
    val prim = this[key] as? JsonPrimitive ?: return ""
    if (!prim.isString) return ""
    return prim.content
}

/**
 * GET /search/artist?q=NAME -> {"data":[{"id":27,"name":"Daft Punk",...},...]}.
 * Returns the first entry's id (skipping entries without a usable id), or
 * null on empty/malformed input.
 */
fun parseDeezerArtistId(json: String): Long? =
    deezerData(json)?.firstNotNullOfOrNull { (it["id"] as? JsonPrimitive)?.longOrNull }

/**
 * GET /artist/{id}/related -> {"data":[{"id":..,"name":..},...]}.
 * Returns (id, name) pairs; entries with a missing id or blank name are
 * skipped. Empty/malformed input yields an empty list.
 */
fun parseDeezerRelatedArtists(json: String): List<Pair<Long, String>> =
    deezerData(json).orEmpty().mapNotNull { entry ->
        val id = (entry["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val name = entry.stringOrEmpty("name").trim()
        if (name.isEmpty()) return@mapNotNull null
        Pair(id, name)
    }

/**
 * GET /artist/{id}/top?limit=N ->
 * {"data":[{"title":"...","duration":211,"artist":{"name":"..."},...},...]}.
 * Returns Song(title, artist.name, durationS = duration when > 0 else null);
 * entries with a blank title or blank artist name are skipped. Empty/malformed
 * input yields an empty list.
 */
fun parseDeezerTopTracks(json: String): List<Song> =
    deezerData(json).orEmpty().mapNotNull { entry ->
        val title = entry.stringOrEmpty("title").trim()
        if (title.isEmpty()) return@mapNotNull null
        val artist = (entry["artist"] as? JsonObject)?.stringOrEmpty("name")?.trim().orEmpty()
        if (artist.isEmpty()) return@mapNotNull null
        val duration = (entry["duration"] as? JsonPrimitive)?.doubleOrNull
        Song(
            title = title,
            artist = artist,
            durationS = duration?.takeIf { it > 0.0 },
        )
    }
