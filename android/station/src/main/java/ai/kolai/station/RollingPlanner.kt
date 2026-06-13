package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile
import java.io.File

/**
 * Endless, taste-refreshing, non-repeating song selection.
 *
 * Wraps a [TasteSource] (Spotify taste) and a [SetlistSource] (LLM
 * [SetlistPlanner] or pure-code [TastePoolPlanner]). Re-pulls the taste every
 * [refreshEvery] songs OR after [refreshTtlS] seconds so the station keeps
 * learning, and excludes the last [noRepeatWindow] titles so the station does
 * not repeat itself; it relaxes (accepts the planner''s picks) rather than
 * stalling when the planner is starved.
 *
 * CROSS-CALL SIGNALS handed to the planner so it can shape the on-air flow:
 *  - recentArtists (artist fatigue): the last [artistWindow] played artists.
 *  - recentGenres / recentLanguages (SONG-FLOW COHESION): the last
 *    [cohesionWindow] played coarse genres and languages ("he"/"int"), most
 *    recent last. [TastePoolPlanner] reads the trailing RUN from them to know how
 *    long the current genre/language stretch already is, so it can bias toward
 *    cohesion while a run is short and EASE off once it is long (Spotify-like
 *    runs of rap / jazz / English that drift, rather than ping-pong). Language is
 *    computed in-code via [containsHebrew] (free); genre via the optional
 *    [genreSource] (best-effort, never blocks, cached). With no [genreSource] the
 *    genre history stays empty and the planner -- if itself wired without a
 *    GenreSource -- keeps its legacy alternation behaviour, byte-for-byte.
 *
 * CROSS-LAUNCH HISTORY: when [persistFile] is set, the no-repeat title history,
 * the artist history, and (new) the genre + language histories are loaded on
 * construction and re-saved after every [nextSongs] in DERIVED sibling files
 * ("<name>.artists" / ".genres" / ".langs"). Corrupt/unreadable files never
 * crash; persistence failures are swallowed.
 *
 * @param mood passed through to [SetlistSource.plan] unchanged.
 * @param nowMs injectable clock returning epoch millis; defaults to the real one.
 * @param persistFile optional cross-launch history file (see class doc).
 * @param artistWindow how many recently played artists reach the planner.
 * @param genreSource optional [GenreSource] used ONLY to LABEL the songs this
 *   planner returns (so the recentGenres run history is accurate); null leaves
 *   the genre history empty (language cohesion still works -- it needs no
 *   lookup). NON-BLOCKING: the label is a CACHE-ONLY read (cachedGenre) and a
 *   cold miss fires a fire-and-forget warm -- nextSongs never suspends on the
 *   genre network. Best-effort and never throws; a cold/unknown lookup records no
 *   genre for that song (a run break that warms in). NOT the planner''s own
 *   cohesion source -- that is wired separately into [TastePoolPlanner].
 * @param cohesionWindow how many recent genres/languages reach the planner.
 */
class RollingPlanner(
    private val tasteSource: TasteSource,
    private val setlistPlanner: SetlistSource,
    private val refreshEvery: Int = 5,
    private val refreshTtlS: Long = 600,
    private val noRepeatWindow: Int = 50,
    mood: String? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val persistFile: File? = null,
    private val artistWindow: Int = 12,
    private val genreSource: GenreSource? = null,
    private val cohesionWindow: Int = 8,
) {
    /** Current station vibe; affects FUTURE song selection. Public like Python. */
    var mood: String? = mood
        private set

    private var profile: TasteProfile? = null
    private var songsSinceRefresh = 0
    private var lastRefreshMs = 0L

    /** Rolling no-repeat history of recently played keys ([baseTitle] of title). */
    val history: MutableList<String> = mutableListOf()

    /** Rolling history of recently played artists (lowercased, most recent last). */
    val artistHistory: MutableList<String> = mutableListOf()

    /** Rolling history of recently played coarse GENRES (lowercased, most recent
     *  last). Empty entries mark a song whose genre was unknown -- they still
     *  occupy a slot so the trailing-run computation sees a stretch break. */
    val genreHistory: MutableList<String> = mutableListOf()

    /** Rolling history of recently played LANGUAGES ("he"/"int", most recent
     *  last). Always known (computed in-code), so it never has blank entries. */
    val languageHistory: MutableList<String> = mutableListOf()

    private val artistPersistFile: File? =
        persistFile?.let { File(it.parentFile, it.name + ".artists") }
    private val genrePersistFile: File? =
        persistFile?.let { File(it.parentFile, it.name + ".genres") }
    private val languagePersistFile: File? =
        persistFile?.let { File(it.parentFile, it.name + ".langs") }

    init {
        loadHistory()
    }

    /** Switch the station''s vibe; affects FUTURE song selection. */
    fun setMood(mood: String?) {
        this.mood = mood
    }

    private fun key(song: Song): String = baseTitle(song.title)

    private fun loadHistory() {
        loadLinesInto(persistFile, history)
        loadLinesInto(artistPersistFile, artistHistory)
        loadLinesInto(genrePersistFile, genreHistory)
        loadLinesInto(languagePersistFile, languageHistory)
    }

    private fun loadLinesInto(f: File?, into: MutableList<String>) {
        if (f == null) return
        try {
            if (!f.exists()) return
            f.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { into.add(it) }
        } catch (e: Exception) {
            into.clear()
        }
    }

    private fun saveHistory() {
        saveBounded(persistFile, history, noRepeatWindow * 2)
        saveBounded(artistPersistFile, artistHistory, artistWindow * 2)
        saveBounded(genrePersistFile, genreHistory, cohesionWindow * 2)
        saveBounded(languagePersistFile, languageHistory, cohesionWindow * 2)
    }

    /** Persist the last [keep] entries of [list] to [f] (bounds file growth).
     *  Best-effort: failures are swallowed. Blank-only tails are filtered so a
     *  trailing unknown-genre line is not re-read as a real entry. */
    private fun saveBounded(f: File?, list: List<String>, keep: Int) {
        if (f == null) return
        try {
            val tail = list.takeLast(keep)
            StationPersistence.writeAtomic(f, tail.joinToString("\n"))
        } catch (e: Exception) {
            // best-effort persistence; never fail song selection over it
        }
    }

    private suspend fun ensureProfile(): TasteProfile {
        val now = nowMs()
        val current = profile
        if (current == null) {
            val loaded = tasteSource.getProfile(useCache = true)
            profile = loaded
            lastRefreshMs = now
            songsSinceRefresh = 0
            return loaded
        }
        if (songsSinceRefresh >= refreshEvery || (now - lastRefreshMs) > refreshTtlS * 1000) {
            val refreshed = tasteSource.getProfile(useCache = false)
            profile = refreshed
            lastRefreshMs = now
            songsSinceRefresh = 0
            return refreshed
        }
        return current
    }

    /**
     * Pick the next [n] songs, optionally flowing out of [seed]. Excludes the
     * last [noRepeatWindow] played keys, relaxes (accepts the planner''s picks)
     * if too few are fresh, then records the chosen keys / artists / genres /
     * languages in history (and persists them when [persistFile] is set).
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
            recentArtists = artistHistory.takeLast(artistWindow),
            recentGenres = genreHistory.takeLast(cohesionWindow),
            recentLanguages = languageHistory.takeLast(cohesionWindow),
        )
        val recentSet = recent.toSet()
        val fresh = picks.filter { key(it) !in recentSet }
        val chosen = (if (fresh.size >= n) fresh else picks).take(n)
        for (s in chosen) {
            history.add(key(s))
            val artist = s.artist.trim().lowercase()
            if (artist.isNotEmpty()) artistHistory.add(artist)
            // LANGUAGE is free (in-code); GENRE is a CACHE-ONLY label (never
            // blocks on the network -- a cold miss fires a background warm and
            // records a blank line, a run break, that warms in over the session),
            // keeping the histories aligned.
            languageHistory.add(if (containsHebrew(s.title)) "he" else "int")
            genreHistory.add(labelGenre(s).orEmpty())
        }
        saveHistory()
        songsSinceRefresh += chosen.size
        return chosen
    }

    /** Best-effort coarse genre of a played song via [genreSource], NON-BLOCKING:
     *  reads the SYNCHRONOUS cache ([GenreSource.cachedGenre], instant) and, on a
     *  cold MISS, fires a fire-and-forget [GenreSource.warm] so the label is ready
     *  for a LATER play -- it NEVER suspends on the network here (a live device
     *  test showed awaiting genre lookups stalled song picking). null when no
     *  source, a cold/unknown cache, or any problem (it must never break or stall
     *  selection). A cold miss therefore records a blank genre (a run break) that
     *  warms in over the session. */
    private fun labelGenre(song: Song): String? {
        val src = genreSource ?: return null
        if (song.title.isBlank()) return null
        return try {
            val cached = src.cachedGenre(song.artist, song.title)
            if (cached == null) src.warm(song.artist, song.title)
            cached?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}