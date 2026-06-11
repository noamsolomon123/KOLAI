package ai.kolai.app.wiring

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile
import ai.kolai.station.DiscoverySource
import ai.kolai.station.baseTitle
import ai.kolai.station.parseDeezerArtistId
import ai.kolai.station.parseDeezerRelatedArtists
import ai.kolai.station.parseDeezerTopTracks
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * DeezerDiscovery - the production [DiscoverySource] over Deezer's free/keyless
 * API (api.deezer.com). Pure response parsing lives in :station DeezerParse.kt;
 * this class is only the HTTP + caching + selection glue.
 *
 * Algorithm per [discover] call (up to [MAX_ATTEMPTS] random seed artists,
 * because some artists - especially Hebrew spellings - do not resolve):
 *  1. Pick a random seed artist from the taste (topArtists + topTracks artists).
 *  2. Resolve its Deezer id via /search/artist (cached by name; ids are stable).
 *  3. Fetch /artist/{id}/related (cached by id), drop excluded artists, prefer
 *     NON-taste artists (that is the point of discovery) with taste artists as
 *     fallback, and pick one at random.
 *  4. Fetch the related artist's /top tracks (popularity-ordered) and return
 *     the first one whose `baseTitle(title)` is not in [excludeKeys] and whose
 *     artist is not excluded - the artist's biggest song the listener hasn't
 *     played is the right discovery pick.
 *
 * Resilience contract (matches [LiveDjContext]): every HTTP call is capped at
 * 10s and wrapped in try/catch, a failed step soft-fails to the next attempt,
 * and [discover] NEVER throws - total failure just returns null (the planner
 * falls back to a taste pick). Logging goes through JVM-safe helpers (the
 * android.util.Log stub THROWS on the plain JVM - same guard as NewPipeSource).
 */
class DeezerDiscovery(private val http: HttpClient) : DiscoverySource {

    /** Seed-artist name (lowercased) -> Deezer artist id. Ids are stable: no TTL. */
    private val idCache = ConcurrentHashMap<String, Long>()

    /** Deezer artist id -> related artists (id, name). */
    private val relatedCache = ConcurrentHashMap<Long, List<Pair<Long, String>>>()

    override suspend fun discover(
        taste: TasteProfile,
        excludeKeys: Set<String>,
        excludeArtists: Set<String>,
    ): Song? {
        // 1. Seed pool: taste artists + track artists, deduped, non-blank.
        val seeds = (taste.topArtists + taste.topTracks.map { it.artist })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        if (seeds.isEmpty()) return null

        val excludeArtistsLower = excludeArtists.map { it.trim().lowercase() }.toSet()
        val tasteArtistsLower = seeds.map { it.lowercase() }.toSet()

        val attempts = seeds.shuffled().take(MAX_ATTEMPTS)
        for (seed in attempts) {
            val song = try {
                discoverFromSeed(seed, excludeKeys, excludeArtistsLower, tasteArtistsLower)
            } catch (e: Exception) {
                null // a failed seed must never fail the whole discovery
            }
            if (song != null) return song
        }
        logW("no discovery after ${attempts.size} seed attempt(s)") // one line, no per-attempt spam
        return null
    }

    /** One seed attempt: seed name -> related artist -> first fresh top track. */
    private suspend fun discoverFromSeed(
        seedName: String,
        excludeKeys: Set<String>,
        excludeArtistsLower: Set<String>,
        tasteArtistsLower: Set<String>,
    ): Song? {
        // 2. Resolve the seed's Deezer id (Deezer handles Hebrew names as-is).
        val seedId = resolveArtistId(seedName) ?: return null

        // 3. Related artists: drop excluded, prefer NON-taste (real discovery),
        //    allow taste artists as fallback, pick one at random.
        val related = relatedArtists(seedId)
            .filter { (_, name) -> name.trim().lowercase() !in excludeArtistsLower }
        if (related.isEmpty()) return null
        val nonTaste = related.filter { (_, name) -> name.trim().lowercase() !in tasteArtistsLower }
        val (relatedId, relatedName) = nonTaste.ifEmpty { related }.random()

        // 4. The related artist's top tracks are popularity-ordered: the first
        //    survivor is their biggest song the listener hasn't played.
        val body = getText(
            "$API_BASE/artist/$relatedId/top",
            mapOf("limit" to "10"),
        ) ?: return null
        val song = parseDeezerTopTracks(body).firstOrNull { s ->
            baseTitle(s.title) !in excludeKeys &&
                s.artist.trim().lowercase() !in excludeArtistsLower
        } ?: return null

        logI("discovered via '$seedName' -> '$relatedName': '${song.title}' - ${song.artist}")
        return song
    }

    /** /search/artist?q=name -> first artist id, cached by lowercased name. */
    private suspend fun resolveArtistId(name: String): Long? {
        val key = name.lowercase()
        idCache[key]?.let { return it }
        val body = getText("$API_BASE/search/artist", mapOf("q" to name)) ?: return null
        val id = parseDeezerArtistId(body) ?: return null
        idCache[key] = id
        return id
    }

    /** /artist/{id}/related -> (id, name) pairs, cached by artist id. */
    private suspend fun relatedArtists(artistId: Long): List<Pair<Long, String>> {
        relatedCache[artistId]?.let { return it }
        val body = getText("$API_BASE/artist/$artistId/related") ?: return emptyList()
        val related = parseDeezerRelatedArtists(body)
        if (related.isNotEmpty()) relatedCache[artistId] = related
        return related
    }

    // ------------------------------------------------------------ plumbing
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
    // future JVM unit test cannot crash on a log line (NewPipeSource precedent).
    private fun logI(msg: String) {
        try { android.util.Log.i(TAG, msg) } catch (_: Throwable) { }
    }

    private fun logW(msg: String) {
        try { android.util.Log.w(TAG, msg) } catch (_: Throwable) { }
    }

    private companion object {
        const val TAG = "KolaiDeezer"
        const val API_BASE = "https://api.deezer.com"
        const val FETCH_TIMEOUT_MS = 10_000L
        const val MAX_ATTEMPTS = 3
    }
}
