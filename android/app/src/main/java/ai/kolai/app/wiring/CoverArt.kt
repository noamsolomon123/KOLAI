package ai.kolai.app.wiring

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * CoverArt - keyless album-cover lookup over Deezer's free /search endpoint
 * (api.deezer.com), for the player's cover slot. Mirrors the DeezerDiscovery
 * conventions: 10s-capped HTTP, ConcurrentHashMap cache, runtime-only JSON
 * tree parsing (no @Serializable), and a NEVER-throw contract - any failure
 * just yields null and the UI keeps its waveform fallback.
 *
 * Negative results are cached too (as [NONE]; ConcurrentHashMap cannot hold
 * null values) so a song with no cover doesn't refetch on every state poll.
 */
object CoverArt {

    /** Lowercased "artist|title" -> cover URL, or [NONE] for a cached miss. */
    private val cache = ConcurrentHashMap<String, String>()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Returns a cover image URL for (artist, title), or null. Cached; never throws. */
    suspend fun coverUrl(artist: String, title: String): String? {
        if (title.isBlank() && artist.isBlank()) return null
        val key = cacheKey(artist, title)
        cache[key]?.let { return it.ifEmpty { null } }
        val url = fetch(artist, title)
        if (cache.size > MAX_CACHE) cache.clear() // simple bound; entries are tiny
        cache[key] = url ?: NONE
        return url
    }

    /** Synchronous cache-only peek (for callers that can't suspend). */
    fun cachedCoverUrl(artist: String, title: String): String? =
        cache[cacheKey(artist, title)]?.ifEmpty { null }

    private fun cacheKey(artist: String, title: String): String =
        "${artist.trim().lowercase()}|${title.trim().lowercase()}"

    /** GET /search?q=artist+title&limit=3 -> best result's album cover. Soft-fails. */
    private suspend fun fetch(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode("$artist $title".trim(), "UTF-8")
            val request = Request.Builder()
                .url("$API_BASE/search?q=$q&limit=3")
                .build()
            val body = client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            } ?: return@withContext null
            val url = pickCover(body, artist)
            if (url != null) logI("cover for $artist - $title -> $url")
            url
        } catch (e: Exception) {
            logW("cover lookup failed for $artist - $title: ${e.message}")
            null
        }
    }

    /**
     * Pick the best of the (up to 3) search results: prefer one whose artist
     * name contains a token of the requested artist (case-insensitive), else
     * the first; then album.cover_xl ?: cover_big ?: cover_medium - falling
     * back to any result that has a cover at all.
     */
    private fun pickCover(json: String, artist: String): String? = try {
        val root = Json.parseToJsonElement(json) as? JsonObject
        val entries = (root?.get("data") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        if (entries.isEmpty()) {
            null
        } else {
            val tokens = artist.lowercase()
                .split(' ', ',', '&', '/')
                .map { it.trim() }
                .filter { it.length >= 2 }
            val best = entries.firstOrNull { entry ->
                val name = (entry["artist"] as? JsonObject)?.stringOrEmpty("name")?.lowercase() ?: ""
                tokens.any { name.contains(it) }
            } ?: entries.first()
            coverOf(best) ?: entries.firstNotNullOfOrNull { coverOf(it) }
        }
    } catch (e: Exception) {
        null
    }

    /** album.cover_xl ?: cover_big ?: cover_medium, skipping blank values. */
    private fun coverOf(entry: JsonObject): String? {
        val album = entry["album"] as? JsonObject ?: return null
        for (k in arrayOf("cover_xl", "cover_big", "cover_medium")) {
            val v = album.stringOrEmpty(k)
            if (v.isNotBlank()) return v
        }
        return null
    }

    /** (obj.get(key) or "") -- non-string / missing collapses to "". */
    private fun JsonObject.stringOrEmpty(key: String): String {
        val prim = this[key] as? JsonPrimitive ?: return ""
        if (!prim.isString) return ""
        return prim.content
    }

    // android.util.Log THROWS on the plain JVM; guard like DeezerDiscovery.
    private fun logI(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) { }
    }

    private fun logW(msg: String) {
        try { Log.w(TAG, msg) } catch (_: Throwable) { }
    }

    private const val TAG = "KolaiCover"
    private const val API_BASE = "https://api.deezer.com"
    private const val NONE = "" // negative-cache sentinel (CHM forbids null values)
    private const val MAX_CACHE = 300
}