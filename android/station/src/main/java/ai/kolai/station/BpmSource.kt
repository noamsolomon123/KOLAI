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
 */
fun interface BpmSource {
    /**
     * The track''s tempo in beats-per-minute, or null when unknown (no match,
     * catalog bpm of 0, network failure). MUST never throw.
     *
     * @param artist the song''s artist (used to disambiguate the catalog match).
     * @param title the song''s title.
     */
    suspend fun bpm(artist: String, title: String): Double?
}