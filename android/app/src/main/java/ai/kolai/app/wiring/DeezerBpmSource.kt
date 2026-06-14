package ai.kolai.app.wiring

import ai.kolai.station.BpmSource
import ai.kolai.station.parseDeezerTrackBpm
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
 *
 * NON-BLOCKING (2026-06-13): so the planner''s plan() never suspends on the
 * network, this source also offers a SYNCHRONOUS cache read ([cachedBpm], instant
 * / null on a cold miss) and a fire-and-forget [warm] that resolves in the
 * BACKGROUND on an internal [Dispatchers.IO] scope. [warm] dedupes in-flight keys
 * via [inFlight] so a re-fired key launches at most one resolve, and -- like
 * [bpm] -- it never throws. The cache (positive + negative tombstone) is the
 * single source of truth shared by all three entry points.
 */
class DeezerBpmSource(
    private val http: HttpClient,
    /** Background scope for [warm]; defaults to a daemon SupervisorJob on IO so a
     *  single warm failure never cancels siblings and the app need not manage a
     *  lifecycle. Inject one in tests to control / await background work. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Optional disk cache so resolved + MEASURED (Essentia) BPMs survive app
     *  restarts and ACCUMULATE across runs, building a full tempo picture of the
     *  pool for vibe-match. Null = in-memory only. */
    private val persistFile: java.io.File? = null,
) : BpmSource {

    /** "artistLower|titleLower" -> resolved BPM. NULL VALUE = a cached "unknown"
     *  tombstone (we tried and Deezer had no usable bpm), so we never re-query
     *  it. ConcurrentHashMap forbids null values, so the tombstone is [UNKNOWN]. */
    private val cache = ConcurrentHashMap<String, Double>()

    /** Keys with a [warm] resolve currently launched, so a re-fire does not start
     *  a second round-trip for the same song. Cleared once the resolve stores. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun keyOf(artist: String, title: String): String =
        artist.trim().lowercase() + "|" + title.trim().lowercase()

    init { loadCache() }

    private fun loadCache() {
        val f = persistFile ?: return
        try {
            if (!f.exists()) return
            val obj = org.json.JSONObject(f.readText())
            val ks = obj.keys()
            while (ks.hasNext()) { val k = ks.next(); cache[k] = obj.getDouble(k) }
        } catch (_: Throwable) { }
    }

    @Synchronized private fun saveCache() {
        val f = persistFile ?: return
        try {
            val obj = org.json.JSONObject()
            for ((k, v) in cache) obj.put(k, v)
            f.parentFile?.mkdirs()
            f.writeText(obj.toString())
        } catch (_: Throwable) { }
    }

    override suspend fun bpm(artist: String, title: String): Double? {
        if (title.isBlank()) return null
        val key = keyOf(artist, title)
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

    /** SYNCHRONOUS cache-only read: the resolved BPM, or null on a cold miss /
     *  tombstone. Never touches the network, never throws. */
    override fun cachedBpm(artist: String, title: String): Double? {
        if (title.isBlank()) return null
        val v = cache[keyOf(artist, title)] ?: return null
        return if (v == UNKNOWN) null else v
    }

    /** Fire-and-forget background resolve: launches at most one network resolve
     *  per uncached, not-in-flight key; stores the result (or a tombstone) so a
     *  later [cachedBpm] hits. Never blocks, never throws. */
    override fun warm(artist: String, title: String) {
        if (title.isBlank()) return
        val key = keyOf(artist, title)
        if (cache.containsKey(key)) return            // already resolved (pos/neg)
        if (!inFlight.add(key)) return                // a resolve is already running
        scope.launch {
            val resolved = try {
                resolveBpm(artist, title)
            } catch (e: Exception) {
                null // best-effort; a warm failure is just a cached unknown
            }
            cache[key] = resolved ?: UNKNOWN
            inFlight.remove(key)
            saveCache()
            if (resolved != null) logI("warm bpm '$title' - $artist = $resolved")
        }
    }

    /** Inject a MEASURED bpm (on-device Essentia analysis) into the cache so the
     *  planner's [cachedBpm] returns real tempo even for songs Deezer has no bpm
     *  for (most Hebrew songs). A measured tempo overrides a Deezer value/tombstone
     *  (Deezer's is often octave-off or absent). Lets vibe-match work on real energy. */
    fun put(artist: String, title: String, bpm: Double) {
        if (title.isBlank() || bpm <= 0.0) return
        cache[keyOf(artist, title)] = bpm
        saveCache()
    }

    /** Cancel the background warm scope; call from a lifecycle hook if one exists
     *  (the default daemon SupervisorJob is otherwise fine to leave running). */
    fun close() {
        try { scope.cancel() } catch (_: Throwable) { }
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