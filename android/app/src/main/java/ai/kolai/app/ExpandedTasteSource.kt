package ai.kolai.app

import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack
import ai.kolai.core.taste.profileFromJson
import ai.kolai.core.taste.profileToJson
import ai.kolai.station.TasteSource
import ai.kolai.station.mergeProfiles
import ai.kolai.station.parseDeezerArtistId
import ai.kolai.station.parseDeezerRelatedArtists
import ai.kolai.station.parseDeezerTopTracks
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * GROW-THE-POOL decorator over [SeededTasteSource]. The bundled taste is only
 * ~20 tracks, but [ai.kolai.station.RollingPlanner]'s no-repeat window is 50 --
 * mathematically unsatisfiable, so hot tracks repeat 15-20x. This source expands
 * the listener's real ~20 favourites into a ~60-80-track pool via Deezer's
 * free/keyless API (api.deezer.com), so the window becomes satisfiable and play
 * spreads across a real catalog (the planner's epsilon floor + relaxation
 * surface the lower-weighted tail).
 *
 * CONTRACT it must never break:
 *  - SAME INTERFACE as the wrapped source ([TasteSource]); a drop-in swap.
 *  - NEVER throws and NEVER blocks the playback-critical path. The first
 *    getProfile() returns the BASE 20 immediately; the (dozens-of-calls) Deezer
 *    expansion runs ONCE in the background on [scope] and is swapped in when
 *    ready. [ai.kolai.station.RollingPlanner] re-pulls via getProfile(useCache=
 *    false) periodically, so a later swap is picked up with no wiring change.
 *  - CACHED to disk (JSON via :core profileToJson/profileFromJson) with a TTL,
 *    so subsequent launches load the expanded pool instantly without re-hitting
 *    Deezer.
 *  - On ANY failure / offline / partial result, falls back to whatever
 *    expansion succeeded, and if nothing, to the BASE profile unchanged.
 *
 * ORDERING (= weighting): [mergeProfiles] keeps the base 20 FIRST (highest
 * 1/(rank+6) weight -> the listener's favourites still dominate) and appends the
 * discovered tail at lower ranks.
 */
class ExpandedTasteSource(
    private val base: TasteSource,
    private val http: HttpClient,
    cacheDir: File,
    private val scope: CoroutineScope,
    private val targetSize: Int = TARGET_SIZE,
    private val cacheTtlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : TasteSource {

    private val cacheFile = File(cacheDir, CACHE_NAME)

    /** The expanded profile once built (in-memory hot path after first build). */
    private val expanded = AtomicReference<TasteProfile?>(null)

    /** Guards against launching the (expensive) expansion more than once. */
    @Volatile private var expansionStarted = false

    override suspend fun getProfile(useCache: Boolean): TasteProfile {
        // Always have the real base ready; it is the floor we never go below.
        val baseProfile = base.getProfile(useCache)

        // Fast path: expansion already built this session -> serve it.
        expanded.get()?.let { return it }

        // Disk cache (fresh) -> adopt it without any network, no re-expansion.
        if (!expansionStarted) {
            loadFreshCache()?.let { cached ->
                expanded.set(cached)
                logI("loaded expanded pool from cache: ${cached.topTracks.size} tracks")
                return cached
            }
        }

        // Not built and no fresh cache: kick off the ONE-TIME background
        // expansion (off the critical path) and serve the base 20 right now.
        // The next getProfile(useCache=false) refresh picks up the swap.
        maybeStartExpansion(baseProfile)
        return baseProfile
    }

    /** Launch the background expansion exactly once. Never blocks the caller. */
    @Synchronized
    private fun maybeStartExpansion(baseProfile: TasteProfile) {
        if (expansionStarted) return
        expansionStarted = true
        scope.launch(Dispatchers.IO) {
            try {
                val built = buildExpanded(baseProfile)
                // Only publish if the expansion actually grew the pool; otherwise
                // leave the base in place (getProfile returns base by default).
                if (built.topTracks.size > baseProfile.topTracks.size) {
                    expanded.set(built)
                    saveCache(built)
                    logI("expanded pool ready: ${built.topTracks.size} tracks (base ${baseProfile.topTracks.size})")
                } else {
                    logW("expansion produced no growth; staying on base ${baseProfile.topTracks.size} tracks")
                }
            } catch (e: Throwable) {
                // Resilience: a failed expansion must never break the station.
                logW("expansion failed; staying on base: ${e.message}")
            }
        }
    }

    /**
     * Build the expanded profile by mining Deezer for each base seed artist:
     * the artist's own /top tracks plus a couple of related artists' /top
     * tracks. Soft-fails per call (withTimeoutOrNull + try/catch) so a partial
     * network yields a partial expansion rather than nothing. The pure
     * [mergeProfiles] then keeps the base first, dedupes by baseTitle, and caps.
     */
    private suspend fun buildExpanded(baseProfile: TasteProfile): TasteProfile {
        // Seed artists: the base top_artists plus each base track's artist,
        // deduped case-insensitively, non-blank.
        val seeds = (baseProfile.topArtists + baseProfile.topTracks.map { it.artist })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

        val discovered = ArrayList<TasteTrack>()
        val discoveredArtists = LinkedHashSet<String>()
        val seenRelated = HashSet<Long>()

        for (seed in seeds) {
            if (discovered.size >= targetSize) break
            val artistId = resolveArtistId(seed) ?: continue

            // The seed artist's own top tracks (deepens the listener's own
            // favourites with their wider catalog).
            songsFromArtist(artistId).forEach { discovered.add(it) }

            // A couple of related artists' top tracks (widens to neighbours).
            val related = relatedArtists(artistId).take(RELATED_PER_SEED)
            for ((relId, relName) in related) {
                if (discovered.size >= targetSize) break
                if (!seenRelated.add(relId)) continue
                val relSongs = songsFromArtist(relId)
                if (relSongs.isNotEmpty()) {
                    discoveredArtists.add(relName)
                    relSongs.forEach { discovered.add(it) }
                }
            }
        }

        return mergeProfiles(
            base = baseProfile,
            discovered = discovered,
            cap = targetSize,
            extraArtists = discoveredArtists.toList(),
        )
    }

    /** /artist/{id}/top?limit=N -> the artist's top tracks as TasteTracks. */
    private suspend fun songsFromArtist(artistId: Long): List<TasteTrack> {
        val body = getText("$API_BASE/artist/$artistId/top", mapOf("limit" to TOP_LIMIT.toString()))
            ?: return emptyList()
        return parseDeezerTopTracks(body).map { s ->
            // Carry Deezer duration when present (>0): it sharpens YouTube match
            // scoring downstream (the planner stamps durationS onto the pick).
            TasteTrack(title = s.title, artist = s.artist, durationS = s.durationS ?: 0.0)
        }
    }

    /** /search/artist?q=NAME -> first artist id (cached by lowercased name). */
    private suspend fun resolveArtistId(name: String): Long? {
        idCache[name.lowercase()]?.let { return it }
        val body = getText("$API_BASE/search/artist", mapOf("q" to name)) ?: return null
        val id = parseDeezerArtistId(body) ?: return null
        idCache[name.lowercase()] = id
        return id
    }

    /** /artist/{id}/related -> (id, name) pairs (cached by artist id). */
    private suspend fun relatedArtists(artistId: Long): List<Pair<Long, String>> {
        relatedCache[artistId]?.let { return it }
        val body = getText("$API_BASE/artist/$artistId/related") ?: return emptyList()
        val related = parseDeezerRelatedArtists(body)
        if (related.isNotEmpty()) relatedCache[artistId] = related
        return related
    }

    private val idCache = HashMap<String, Long>()
    private val relatedCache = HashMap<Long, List<Pair<Long, String>>>()

    /** GET with per-call timeout; soft-fails to null, NEVER throws. */
    private suspend fun getText(base: String, params: Map<String, String> = emptyMap()): String? =
        try {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                http.get(base) {
                    url { params.forEach { (k, v) -> parameters.append(k, v) } }
                }.bodyAsText()
            }
        } catch (e: Throwable) {
            null
        }

    // ----------------------------------------------------------- disk cache
    /** Load the cached expanded profile if present and within TTL; else null. */
    private fun loadFreshCache(): TasteProfile? = try {
        if (!cacheFile.exists()) {
            null
        } else if (nowMs() - cacheFile.lastModified() > cacheTtlMs) {
            null // stale: a future expansion will rebuild + overwrite it
        } else {
            val text = cacheFile.readText()
            val json = Json.parseToJsonElement(text) as JsonObject
            val p = profileFromJson(json)
            // Guard: only honour a cache that actually grew the pool.
            if (p.topTracks.size > 1) p else null
        }
    } catch (e: Throwable) {
        null
    }

    /** Persist the expanded profile (snake_case taste.json contract). */
    private fun saveCache(profile: TasteProfile) {
        try {
            cacheFile.parentFile?.mkdirs()
            // A JsonObject's toString() is valid JSON that profileFromJson +
            // Json.parseToJsonElement read straight back (no serializer-overload
            // ambiguity, matching the module's manual-tree JSON convention).
            cacheFile.writeText(profileToJson(profile).toString())
        } catch (e: Throwable) {
            logW("could not persist expanded pool: ${e.message}")
        }
    }

    // android.util.Log is a no-op stub that THROWS on the plain JVM; guard it
    // (same precedent as DeezerDiscovery/NewPipeSource) so any future JVM test
    // cannot crash on a log line.
    private fun logI(msg: String) {
        try { android.util.Log.i(TAG, msg) } catch (_: Throwable) { }
    }

    private fun logW(msg: String) {
        try { android.util.Log.w(TAG, msg) } catch (_: Throwable) { }
    }

    private companion object {
        const val TAG = "KolaiPool"
        const val API_BASE = "https://api.deezer.com"
        const val CACHE_NAME = "expanded_taste.json"
        const val FETCH_TIMEOUT_MS = 10_000L
        const val TARGET_SIZE = 80
        const val TOP_LIMIT = 10
        const val RELATED_PER_SEED = 2
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }
}