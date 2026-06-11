package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile

/**
 * Seam over a REAL music catalog (production: Deezer's free/keyless API; the
 * HTTP implementation is a separate task -- see DeezerParse.kt for the pure
 * response parsing) used by [TastePoolPlanner] to sprinkle "discovery" picks
 * into a setlist: real songs the listener probably has not heard yet but is
 * likely to love, typically by artists adjacent to their taste (related
 * artists, same scene/era) -- though a fresh song by a taste artist is fine
 * too, as long as the SONG is fresh.
 *
 * This seam exists precisely because the LLM could NOT be trusted to do this:
 * asked for discovery picks it invented non-existent songs. Implementations
 * MUST return verifiably real songs (real catalog), never invented ones.
 */
fun interface DiscoverySource {
    /**
     * A real song the listener probably hasn't heard but will love, or null
     * when unavailable (network down, nothing suitable, etc.).
     *
     * @param taste the listener's taste profile to discover "adjacent" to.
     * @param excludeKeys `baseTitle(title)` keys that must NOT be returned
     *   (recently played history + songs already chosen for the current set).
     * @param excludeArtists artist names to avoid (artists already chosen for
     *   the current set, plus the seed song's artist), so a discovery does not
     *   clump the set onto one artist.
     */
    suspend fun discover(
        taste: TasteProfile,
        excludeKeys: Set<String>,
        excludeArtists: Set<String>,
    ): Song?
}
