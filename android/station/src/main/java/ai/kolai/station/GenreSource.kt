package ai.kolai.station

/**
 * Seam over a REAL coarse-genre lookup for a song (production: Deezer's
 * free/keyless album/artist genre, resolved via /search/track -> /track/{id} ->
 * /album/{id} -> genres.data[0].name; the HTTP implementation is a :app task --
 * see DeezerParse.kt for the pure response parsing). Used by [TastePoolPlanner]
 * to make the song flow COHESIVE -- to chain consecutive picks into a coherent
 * RUN (a rap stretch, a jazz stretch, an English stretch) the way Spotify does,
 * instead of ping-ponging randomly between styles.
 *
 * The label is intentionally COARSE -- a small handful of buckets such as
 * "Rap/Hip Hop", "Rock", "Pop", "Jazz", "Dance", "R&B", "Alternative" -- because
 * cohesion only needs "is the next song the SAME kind of music as the last
 * one?", not a fine sub-genre taxonomy. The caller compares labels for EQUALITY
 * (case-insensitively), so the exact spelling does not matter as long as the
 * same song family resolves to the same string.
 *
 * COVERAGE / UNKNOWN-SAFE CONTRACT (mirrors [BpmSource]): genre coverage in the
 * real catalog is partial. [genre] therefore returns NULL whenever the genre is
 * unknown (network down, no match, album with no tagged genre). Unknown genre is
 * treated as NEUTRAL by every caller: it never biases a weight and -- crucially
 * -- never blocks or shrinks a pick. A coverage gap must degrade to today's
 * genre-blind behavior, never to an under-delivered setlist.
 *
 * RESILIENCE: like [BpmSource]/[DiscoverySource], implementations MUST be
 * best-effort and MUST NOT throw -- a timeout/network/parse failure simply
 * yields null (treated as "unknown genre").
 *
 * NON-BLOCKING SONG PICKING (2026-06-13): like [BpmSource], the planner no longer
 * suspends on the genre network. It reads [cachedGenre] SYNCHRONOUSLY (instant;
 * null on a cold cache) and fires [warm] to fill the cache in the BACKGROUND for
 * next time, so genre cohesion builds up over the session instead of blocking
 * each pick. The suspending [genre] is kept for tests / any awaiting caller.
 */
interface GenreSource {
    /**
     * The track's coarse genre label, or null when unknown (no match, untagged
     * album, network failure). MUST never throw. SUSPENDS on the network for a
     * cold-cache lookup -- prefer [cachedGenre] + [warm] on any latency-sensitive
     * path (e.g. song picking).
     *
     * @param artist the song's artist (used to disambiguate the catalog match).
     * @param title the song's title.
     */
    suspend fun genre(artist: String, title: String): String?

    /**
     * SYNCHRONOUS, cache-only genre: the already-resolved coarse label for the
     * track, or null when it is not yet in the cache (a cold miss -- fire [warm]
     * to fill it for next time) OR is a confirmed-unknown tombstone. NEVER
     * touches the network, NEVER suspends, NEVER throws. The default returns null
     * so simple (e.g. test) implementations need not override it.
     */
    fun cachedGenre(artist: String, title: String): String? = null

    /**
     * Fire-and-forget: if [artist]/[title] is not already cached (or in-flight),
     * resolve its genre in the BACKGROUND and store the result (or a tombstone)
     * so a LATER [cachedGenre] hits. Dedupes concurrent requests for the same
     * key, never blocks the caller, and never throws. A no-op by default.
     */
    fun warm(artist: String, title: String) {}
}