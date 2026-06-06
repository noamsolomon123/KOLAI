package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile

/**
 * Endless, taste-refreshing, non-repeating song selection.
 *
 * Ported 1:1 from `backend/radioai/planner_rolling.py` class `RollingPlanner`.
 * Wraps a [TasteSource] (Spotify taste) and a [SetlistPlanner] (LLM). Re-pulls
 * the taste every [refreshEvery] songs OR after [refreshTtlS] seconds so the
 * station keeps learning, and excludes the last [noRepeatWindow] titles so the
 * station does not repeat itself; it relaxes (accepts the planner's picks)
 * rather than stalling when the planner is starved.
 *
 * Deviations from the Python, deliberate per the Android task spec:
 *  - History/exclude key is [baseTitle] of the song title (Python keyed on the
 *    raw "title — artist"). baseTitle matches [SetlistPlanner]'s own
 *    [parseSetlist] dedup, so the no-repeat window collapses alternate versions
 *    (Live/Remix/feat.) of the same underlying song to one key.
 *  - The clock is injected via [nowMs] (epoch millis) instead of Python's
 *    `time.monotonic` (seconds), so refresh-by-TTL is testable without real
 *    time. TTL is therefore compared in milliseconds ([refreshTtlS] * 1000).
 *
 * Faithfully preserved from the Python:
 *  - First profile load uses cache (`getProfile(useCache=true)`); periodic
 *    refresh forces a re-learn (`getProfile(useCache=false)`).
 *  - The refresh counter counts SONGS, not calls: it advances by the number of
 *    songs actually chosen (`_songs_since_refresh += len(chosen)`), and the
 *    refresh fires when `songsSinceRefresh >= refreshEvery`. (The spec phrases
 *    this as "every refreshEvery calls"; with the common one-song-per-call
 *    cadence the two coincide, but the Python counts songs and we match it.)
 *  - Trigger conditions: `songsSinceRefresh >= refreshEvery` (>=) OR
 *    `(now - lastRefresh) > ttl` (strict >).
 *  - The exclude list is the TAIL of history (last [noRepeatWindow] keys);
 *    relax-when-starved keeps all picks if fewer than [n] are fresh.
 *
 * @param mood MVP: passed through to [SetlistPlanner.plan] unchanged; the moods
 *   table is not ported, so the planner currently ignores it (see moodBlock).
 * @param nowMs injectable clock returning epoch millis; defaults to the real one.
 */
class RollingPlanner(
    private val tasteSource: TasteSource,
    private val setlistPlanner: SetlistPlanner,
    private val refreshEvery: Int = 5,
    private val refreshTtlS: Long = 600,
    private val noRepeatWindow: Int = 50,
    mood: String? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Current station vibe; affects FUTURE song selection. Public like Python. */
    var mood: String? = mood
        private set

    private var profile: TasteProfile? = null
    private var songsSinceRefresh = 0
    private var lastRefreshMs = 0L

    /** Rolling no-repeat history of recently played keys ([baseTitle] of title). */
    val history: MutableList<String> = mutableListOf()

    /** Switch the station's vibe; affects FUTURE song selection. */
    fun setMood(mood: String?) {
        this.mood = mood
    }

    /** No-repeat key for a song: base title (matches SetlistPlanner dedup). */
    private fun key(song: Song): String = baseTitle(song.title)

    /**
     * Load the profile, forcing a fresh re-learn when a refresh is due. Mirrors
     * Python `_ensure_profile`: first load may use cache; thereafter refresh when
     * enough songs have played OR the TTL has elapsed.
     */
    private suspend fun ensureProfile(): TasteProfile {
        val now = nowMs()
        val current = profile
        if (current == null) {
            val loaded = tasteSource.getProfile(useCache = true) // first load: cache ok
            profile = loaded
            lastRefreshMs = now
            songsSinceRefresh = 0
            return loaded
        }
        if (songsSinceRefresh >= refreshEvery || (now - lastRefreshMs) > refreshTtlS * 1000) {
            val refreshed = tasteSource.getProfile(useCache = false) // FORCE re-learn
            profile = refreshed
            lastRefreshMs = now
            songsSinceRefresh = 0
            return refreshed
        }
        return current
    }

    /**
     * Pick the next [n] songs, optionally flowing out of [seed]. Mirrors Python
     * `next_songs`: excludes the last [noRepeatWindow] played keys, relaxes
     * (accepts the planner's picks) if too few are fresh, then records the chosen
     * keys in history.
     */
    suspend fun nextSongs(n: Int, seed: Song? = null): List<Song> {
        val profile = ensureProfile()
        val recent = history.takeLast(noRepeatWindow)
        val picks = setlistPlanner.plan(
            taste = profile,
            n = n,
            exclude = recent,
            seed = seed,
            mood = mood,
        )
        val recentSet = recent.toSet()
        val fresh = picks.filter { key(it) !in recentSet }
        // relax: accept the raw picks if the fresh set is starved (< n)
        val chosen = (if (fresh.size >= n) fresh else picks).take(n)
        for (s in chosen) {
            history.add(key(s))
        }
        songsSinceRefresh += chosen.size
        return chosen
    }
}