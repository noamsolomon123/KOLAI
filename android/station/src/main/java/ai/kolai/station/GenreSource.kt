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
 */
fun interface GenreSource {
    /**
     * The track's coarse genre label, or null when unknown (no match, untagged
     * album, network failure). MUST never throw.
     *
     * @param artist the song's artist (used to disambiguate the catalog match).
     * @param title the song's title.
     */
    suspend fun genre(artist: String, title: String): String?
}