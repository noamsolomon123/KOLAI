package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile

/**
 * Seam over "pick the next songs for the station" so [RollingPlanner] does not
 * care HOW the setlist is produced. Two implementations exist:
 *
 *  - [SetlistPlanner]: the original LLM (Gemini) curator, kept as the reference
 *    implementation. The LLM invents setlists from prose, which means it can
 *    (and did) hallucinate songs that do not exist.
 *  - [TastePoolPlanner]: pure-code picking from the listener''s taste pool, with
 *    optional real-catalog discovery via a [DiscoverySource]. This is the
 *    production direction: code cannot hallucinate a song.
 *
 * @param taste the listener''s taste profile (topTracks ordered by rank).
 * @param n how many songs to return.
 * @param exclude recently played no-repeat keys; each entry is
 *   `baseTitle(title)` (the same key [RollingPlanner] keeps in its history).
 * @param seed the song the station just played, for continuity (the first pick
 *   should flow out of it).
 * @param mood optional station vibe.
 */
interface SetlistSource {
    suspend fun plan(
        taste: TasteProfile,
        n: Int,
        exclude: List<String>? = null,
        seed: Song? = null,
        mood: String? = null,
    ): List<Song>

    /**
     * Artist-fatigue-aware overload: like [plan], plus [recentArtists] -- the
     * artists of recently played songs (most recent last, any case). A planner
     * MAY use it to DEMOTE (not exclude) those artists so one artist does not
     * dominate the station across calls.
     *
     * Default implementation ignores [recentArtists] and delegates to the
     * 5-arg [plan], so existing implementations keep compiling and behaving
     * exactly as before without any edit.
     */
    suspend fun plan(
        taste: TasteProfile,
        n: Int,
        exclude: List<String>?,
        seed: Song?,
        mood: String?,
        recentArtists: List<String>?,
    ): List<Song> = plan(taste, n, exclude, seed, mood)

    /**
     * COHESION-aware overload: like the 6-arg [plan], plus [recentGenres] and
     * [recentLanguages] -- the coarse genre and language ("he"/"int") of recently
     * played songs (most recent last). A planner MAY read the TRAILING RUN from
     * them to bias consecutive picks toward the SAME genre/language as the seed
     * while a run is short, and EASE off once it is long (the Spotify-like "rap
     * songs, then jazz songs, then English songs" flow).
     *
     * Default implementation ignores the cohesion history and delegates to the
     * 6-arg [plan], so a planner that does not implement cohesion (e.g.
     * [SetlistPlanner], or a [TastePoolPlanner] wired without a [GenreSource])
     * keeps compiling and behaving exactly as before.
     */
    suspend fun plan(
        taste: TasteProfile,
        n: Int,
        exclude: List<String>?,
        seed: Song?,
        mood: String?,
        recentArtists: List<String>?,
        recentGenres: List<String>?,
        recentLanguages: List<String>?,
    ): List<Song> = plan(taste, n, exclude, seed, mood, recentArtists)
}