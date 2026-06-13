package ai.kolai.station

/**
 * Seam over a REAL tempo (BPM) lookup for a song (production: Deezer's
 * free/keyless /track endpoint, which carries a `bpm` field; the HTTP
 * implementation is a separate :app task -- see DeezerParse.kt for the pure
 * response parsing). Used by [TastePoolPlanner] to make song selection and
 * ordering TEMPO-AWARE:
 *
 *  - PER-MOOD BPM BIAS: mood windows (see [MoodSpec.bpmLo]/[MoodSpec.bpmHi])
 *    tilt the sampling weight toward candidates whose BPM fits the vibe.
 *  - SEED/ORDER SMOOTHING: the FIRST pick is tilted toward the seed song''s
 *    BPM, and a returned 2-song block is ordered to minimize the playback
 *    |dBPM| jumps -- cutting the jarring-transition rate the BPM study found
 *    (48-57% of consecutive transitions had |dBPM| > 25).
 *
 * COVERAGE / UNKNOWN-SAFE CONTRACT: BPM coverage in the real catalog is partial
 * (Deezer /track returns bpm for only ~30-43% of tracks). [bpm] therefore
 * returns NULL whenever the tempo is unknown (network down, no match, or a
 * catalog `bpm` of 0). Unknown BPM is treated as NEUTRAL by every caller: it
 * never biases a weight, never reorders, and -- crucially -- never blocks or
 * shrinks a pick. A coverage gap must degrade to today''s tempo-blind behavior,
 * never to an under-delivered setlist.
 *
 * RESILIENCE: like [DiscoverySource], implementations MUST be best-effort and
 * MUST NOT throw -- a timeout/network/parse failure simply yields null (treated
 * as "unknown BPM").
 *
 * NON-BLOCKING SONG PICKING (2026-06-13): a live device test showed that AWAITING
 * a pile of cold-cache Deezer round-trips inside plan() slowed a 2-song block to
 * ~100s, widening the "skip has no next block" window and risking a buffer
 * underrun. So the planner no longer suspends on the network: it reads
 * [cachedBpm] SYNCHRONOUSLY (instant; null on a cold cache) and fires [warm] to
 * populate the cache in the BACKGROUND for next time. Tempo cohesion then builds
 * up over the session instead of blocking each pick. The suspending [bpm] is kept
 * for tests and any caller that genuinely wants to await a resolve.
 */
interface BpmSource {
    /**
     * The track''s tempo in beats-per-minute, or null when unknown (no match,
     * catalog bpm of 0, network failure). MUST never throw. SUSPENDS on the
     * network for a cold-cache lookup -- prefer [cachedBpm] + [warm] on any
     * latency-sensitive path (e.g. song picking).
     *
     * @param artist the song''s artist (used to disambiguate the catalog match).
     * @param title the song''s title.
     */
    suspend fun bpm(artist: String, title: String): Double?

    /**
     * SYNCHRONOUS, cache-only tempo: the already-resolved BPM for the track, or
     * null when it is not yet in the cache (a cold miss -- fire [warm] to fill it
     * for next time) OR is a confirmed-unknown tombstone. NEVER touches the
     * network, NEVER suspends, NEVER throws. The default returns null so simple
     * (e.g. test) implementations need not override it.
     */
    fun cachedBpm(artist: String, title: String): Double? = null

    /**
     * Fire-and-forget: if [artist]/[title] is not already cached (or in-flight),
     * resolve its BPM in the BACKGROUND and store the result (or a negative
     * tombstone) so a LATER [cachedBpm] hits. Dedupes concurrent requests for the
     * same key, never blocks the caller, and never throws. A no-op by default.
     */
    fun warm(artist: String, title: String) {}
}