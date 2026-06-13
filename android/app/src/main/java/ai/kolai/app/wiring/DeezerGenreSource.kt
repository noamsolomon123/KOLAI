package ai.kolai.app.wiring

import ai.kolai.station.GenreSource
import ai.kolai.station.parseDeezerAlbumGenre
import ai.kolai.station.parseDeezerAlbumIdFromTrack
import ai.kolai.station.parseDeezerTrackId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * DeezerGenreSource -- the production [GenreSource] over Deezer's free/keyless
 * API (api.deezer.com). Pure response parsing lives in :station DeezerParse.kt
 * ([parseDeezerTrackId] / [parseDeezerAlbumIdFromTrack] / [parseDeezerAlbumGenre]);
 * this class is only the HTTP + caching glue, mirroring [DeezerBpmSource].
 *
 * Per [genre] call (a 3-hop resolve, all keyless):
 *  1. GET /search/track?q="ARTIST TITLE" -> first track id.
 *  2. GET /track/{id} -> the nested album id (the /track body carries no usable
 *     genre, but it carries album.id).
 *  3. GET /album/{id} -> genres.data[0].name -- the album's primary coarse genre,
 *     which the planner treats as the track's genre for COHESION (chaining
 *     consecutive picks into rap / jazz / English runs). A blank genres list
 *     (common on Deezer) -> null = unknown = neutral.
 *
 * RESILIENCE (matches [DeezerBpmSource] / the [GenreSource] contract): every HTTP
 * call is capped at 10s and wrapped so [genre] NEVER throws -- any
 * timeout/network/HTTP/parse problem just yields null ("unknown genre"), which
 * the planner treats as neutral. Results are cached by lowercased "artist|title"
 * (positive AND negative: a confirmed-unknown is cached as a tombstone so we do
 * not re-resolve a song with no Deezer genre). Logging goes through the JVM-safe
 * helper (android.util.Log is a stub that THROWS on the plain JVM).
 *
 * NON-BLOCKING (2026-06-13): mirrors [DeezerBpmSource] -- a SYNCHRONOUS cache read
 * ([cachedGenre], instant / null on a cold miss) and a fire-and-forget [warm]
 * that resolves the 3-hop genre in the BACKGROUND on an internal
 * [Dispatchers.IO] scope, deduped per key via [inFlight]. The planner reads
 * [cachedGenre] synchronously and fires [warm] for misses, so plan() never
 * suspends on the genre network.
 */
class DeezerGenreSource(
    private val http: HttpClient,
    /** Background scope for [warm]; defaults to a daemon SupervisorJob on IO (see
     *  [DeezerBpmSource]). Inject one in tests to control / await warm work. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : GenreSource {

    /** "artistLower|titleLower" -> resolved genre. The [UNKNOWN] sentinel is a
     *  cached "tried, no usable genre" tombstone (ConcurrentHashMap forbids null
     *  values), so a confirmed-unknown is never re-resolved. */
    private val cache = ConcurrentHashMap<String, String>()

    /** Keys whose [warm] resolve is currently launched (dedupe in-flight). */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun keyOf(artist: String, title: String): String =
        artist.trim().lowercase() + "|" + title.trim().lowercase()

    override suspend fun genre(artist: String, title: String): String? {
        if (title.isBlank()) return null
        val key = keyOf(artist, title)
        cache[key]?.let { return if (it == UNKNOWN) null else it }

        val resolved = try {
            resolveGenre(artist, title)
        } catch (e: Exception) {
            null // a genre lookup must never throw / never break selection
        }
        cache[key] = resolved ?: UNKNOWN
        if (resolved != null) logI("genre '" + title + "' - " + artist + " = " + resolved)
        return resolved
    }

    /** SYNCHRONOUS cache-only read: the resolved coarse genre, or null on a cold
     *  miss / tombstone. Never touches the network, never throws. */
    override fun cachedGenre(artist: String, title: String): String? {
        if (title.isBlank()) return null
        val v = cache[keyOf(artist, title)] ?: return null
        return if (v == UNKNOWN) null else v
    }

    /** Fire-and-forget background resolve: at most one network resolve per
     *  uncached, not-in-flight key; stores the result (or a tombstone) so a later
     *  [cachedGenre] hits. Never blocks, never throws. */
    override fun warm(artist: String, title: String) {
        if (title.isBlank()) return
        val key = keyOf(artist, title)
        if (cache.containsKey(key)) return            // already resolved (pos/neg)
        if (!inFlight.add(key)) return                // a resolve is already running
        scope.launch {
            val resolved = try {
                resolveGenre(artist, title)
            } catch (e: Exception) {
                null // best-effort; a warm failure is just a cached unknown
            }
            cache[key] = resolved ?: UNKNOWN
            inFlight.remove(key)
            if (resolved != null) logI("warm genre '" + title + "' - " + artist + " = " + resolved)
        }
    }

    /** Cancel the background warm scope; call from a lifecycle hook if one exists. */
    fun close() {
        try { scope.cancel() } catch (_: Throwable) { }
    }

    /** /search/track -> id -> /track/{id} -> album id -> /album/{id} -> genre. */
    private suspend fun resolveGenre(artist: String, title: String): String? {
        val q = listOf(artist.trim(), title.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (q.isEmpty()) return null
        val searchBody = getText("$API_BASE/search/track", mapOf("q" to q)) ?: return null
        val trackId = parseDeezerTrackId(searchBody) ?: return null
        val trackBody = getText("$API_BASE/track/$trackId") ?: return null
        val albumId = parseDeezerAlbumIdFromTrack(trackBody) ?: return null
        val albumBody = getText("$API_BASE/album/$albumId") ?: return null
        return parseDeezerAlbumGenre(albumBody)
    }

    private suspend fun getText(base: String, params: Map<String, String> = emptyMap()): String? =
        try {
            withTimeout(FETCH_TIMEOUT_MS) {
                http.get(base) {
                    url { params.forEach { (k, v) -> parameters.append(k, v) } }
                }.bodyAsText()
            }
        } catch (e: Exception) {
            null
        }

    private fun logI(msg: String) {
        try { android.util.Log.i(TAG, msg) } catch (_: Throwable) { }
    }

    private companion object {
        const val TAG = "KolaiGenre"
        const val API_BASE = "https://api.deezer.com"
        const val FETCH_TIMEOUT_MS = 10_000L
        /** Sentinel cached value meaning "tried, no usable genre". */
        const val UNKNOWN = " UNKNOWN "
    }
}