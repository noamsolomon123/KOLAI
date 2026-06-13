package ai.kolai.app.wiring

import ai.kolai.station.BpmSource
import ai.kolai.station.parseDeezerTrackBpm
import ai.kolai.station.parseDeezerTrackId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * DeezerBpmSource -- the production [BpmSource] over Deezer''s free/keyless API
 * (api.deezer.com). Pure response parsing lives in :station DeezerParse.kt
 * ([parseDeezerTrackId] / [parseDeezerTrackBpm]); this class is only the HTTP +
 * caching glue, mirroring [DeezerDiscovery].
 *
 * Per [bpm] call:
 *  1. GET /search/track?q="ARTIST TITLE" -> first track id (the /search results
 *     do NOT carry bpm, which is why the v3 corpus captured 0% coverage -- see
 *     docs/studies/findings-bpm-v3.md).
 *  2. GET /track/{id} -> read the `bpm` field (the ONLY endpoint that carries
 *     it). A bpm of 0 (Deezer''s "unknown") or any missing value -> null.
 *
 * RESILIENCE (matches [DeezerDiscovery] / the [BpmSource] contract): every HTTP
 * call is capped at 10s and wrapped so [bpm] NEVER throws -- any
 * timeout/network/HTTP/parse problem just yields null ("unknown tempo"), which
 * the planner treats as neutral. Results are cached by lowercased "artist|title"
 * (positive AND negative: a confirmed-unknown is cached as a tombstone so we do
 * not re-query a song with no Deezer BPM). Logging goes through the JVM-safe
 * helper (android.util.Log is a stub that THROWS on the plain JVM).
 */
class DeezerBpmSource(
    private val http: HttpClient,
) : BpmSource {

    /** "artistLower|titleLower" -> resolved BPM. NULL VALUE = a cached "unknown"
     *  tombstone (we tried and Deezer had no usable bpm), so we never re-query
     *  it. ConcurrentHashMap forbids null values, so the tombstone is [UNKNOWN]. */
    private val cache = ConcurrentHashMap<String, Double>()

    override suspend fun bpm(artist: String, title: String): Double? {
        if (title.isBlank()) return null
        val key = (artist.trim().lowercase() + "|" + title.trim().lowercase())
        cache[key]?.let { return if (it == UNKNOWN) null else it }

        val resolved = try {
            resolveBpm(artist, title)
        } catch (e: Exception) {
            null // a BPM lookup must never throw / never break selection
        }
        // Cache positive AND negative results (tombstone for unknown).
        cache[key] = resolved ?: UNKNOWN
        if (resolved != null) logI("bpm '$title' - $artist = $resolved")
        return resolved
    }

    /** /search/track -> id -> /track/{id} -> bpm (>0), or null. */
    private suspend fun resolveBpm(artist: String, title: String): Double? {
        val q = listOf(artist.trim(), title.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (q.isEmpty()) return null
        val searchBody = getText("$API_BASE/search/track", mapOf("q" to q)) ?: return null
        val id = parseDeezerTrackId(searchBody) ?: return null
        val trackBody = getText("$API_BASE/track/$id") ?: return null
        return parseDeezerTrackBpm(trackBody)
    }

    /**
     * GET [base] with query [params] (Ktor handles URL-encoding), 10s cap.
     * Soft-fails: any timeout/network/HTTP problem yields null, never a throw.
     */
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

    // android.util.Log is a no-op stub that THROWS on the plain JVM; guard so a
    // JVM unit test cannot crash on a log line (DeezerDiscovery precedent).
    private fun logI(msg: String) {
        try { android.util.Log.i(TAG, msg) } catch (_: Throwable) { }
    }

    private companion object {
        const val TAG = "KolaiBpm"
        const val API_BASE = "https://api.deezer.com"
        const val FETCH_TIMEOUT_MS = 10_000L
        /** Sentinel cached value meaning "tried, no usable BPM". */
        const val UNKNOWN = -1.0
    }
}