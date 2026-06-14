package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack

/**
 * True when [s] contains at least one Hebrew letter (the basic block א..ת).
 *
 * Deliberately tiny and local: :station must not depend on :acquire (which has
 * its own Hebrew detection), and no regex is used anywhere near this (Android's
 * ICU regex engine rejects (?U)/UNICODE_CHARACTER_CLASS, so the module avoids
 * Unicode-class regex entirely).
 */
internal fun containsHebrew(s: String): Boolean = s.any { it in 'א'..'ת' }

/**
 * Pure-code song picking from the listener''s taste pool -- the production
 * replacement for LLM setlist invention.
 *
 * WHY: [SetlistPlanner] asks Gemini to invent setlists, and an LLM is not a
 * music catalog -- it hallucinated a non-existent song on air. Code picking from
 * the listener''s REAL taste pool cannot hallucinate: every taste pick is a
 * track the listener actually listens to, and every discovery pick comes from a
 * real-catalog [DiscoverySource] (Deezer). The LLM keeps only what it is good
 * at: DJ speech ([DjBrain] is untouched).
 *
 * The craft heuristics the LLM prompt used to ask for are implemented in code:
 *
 *  - WEIGHTED TASTE SAMPLING: taste.topTracks order is the listener''s rank;
 *    each track is weighted 1.0 / (index + 6), so top tracks are favoured but
 *    the tail still surfaces. Sampling is WITHOUT replacement.
 *  - NO-REPEAT: a track is excluded when `baseTitle(title)` is in [plan]''s
 *    exclude list; the pool itself is also deduped by baseTitle.
 *  - ARTIST SPACING (soft): never two picks by the same artist within one
 *    returned list, and the FIRST pick avoids the seed''s artist. Relaxed --
 *    rather than under-delivering -- when the pool is starved.
 *  - LANGUAGE / GENRE COHESION (soft; the Spotify-like RUN behaviour, see the
 *    [genre] doc below): consecutive picks are biased toward the SAME language
 *    AND the SAME coarse genre as the seed, so the station drifts through
 *    coherent stretches (a rap run, a jazz run, an English run) instead of
 *    ping-ponging. RUN-LENGTH AWARE so a run does not last forever. ONLY active
 *    when a [genre] source is wired; with no [genre] source the planner keeps
 *    the OLDER Hebrew/international ALTERNATION preference, byte-for-byte.
 *  - MOOD BIAS (optional, LLM-assisted, hallucination-safe): a [curator] may
 *    only answer with indices into the deduped pool; fitting songs get an 8x
 *    weight. Any failure leaves weights untouched.
 *  - DISCOVERY (~1 in 4): each slot has a 25% chance of a discovery pick from
 *    [discovery] (capped at 1 per call). A null/throwing discovery falls back to
 *    a taste pick: discovery can never shrink the list.
 *  - RELAX-WHEN-STARVED: prefer fresh picks, fall back to excluded (recently
 *    played) tracks rather than returning fewer than n songs.
 *
 * NON-BLOCKING BPM/GENRE (2026-06-13): a live device test showed plan() AWAITING
 * up to ~30 cold-cache Deezer round-trips per pick, slowing a 2-song block to
 * ~100s and risking a buffer underrun. plan() therefore NO LONGER suspends on any
 * BPM/genre network. It reads tempo/genre from the source''s SYNCHRONOUS
 * cache-only accessors ([BpmSource.cachedBpm] / [GenreSource.cachedGenre]) and,
 * for each cold MISS, fires a fire-and-forget [BpmSource.warm] / [GenreSource.warm]
 * (capped per call) that fills the cache in the BACKGROUND for next time. The net
 * effect: the FIRST plays are tempo/genre-NEUTRAL (the cache is cold), and
 * cohesion/tempo bias WARMS IN over the session as the cache fills -- instead of
 * blocking each pick. Everything else (weights, relaxation, epsilon, the >=2-song
 * ordering pass -- which now uses the synchronously-known cand.bpm) is unchanged.
 *
 * @param discovery optional real-catalog discovery source; null disables it.
 * @param rng injectable randomness; tests pass a seeded Random for determinism.
 * @param curator optional LLM mood curator; null disables mood bias entirely.
 * @param discoveryRate per-slot probability of attempting a discovery pick.
 * @param uniformEpsilon epsilon-greedy floor for taste sampling.
 * @param bpm optional [BpmSource] for TEMPO AWARENESS; null disables every tempo
 *   behaviour. When wired it adds SOFT, multiplicative, never-shrinking tilts
 *   (per-mood BPM window bias, first-pick seed smoothing, an n>=2 ordering pass),
 *   all no-op on unknown BPM. Reads are CACHE-ONLY (never blocking); cold misses
 *   are warmed in the background, so tempo bias warms in over the session.
 * @param bpmLookupCap hard cap on candidate-pool BPM reads/warms per plan() call.
 * @param genre optional [GenreSource] for SONG-FLOW COHESION -- the Spotify-like
 *   "songs are connected, one after another (rap songs, jazz songs, English
 *   songs)" behaviour. null (the default) DISABLES cohesion entirely: plan() then
 *   reverts to the older Hebrew/international ALTERNATION preference and is
 *   byte-identical to the pre-cohesion planner in every existing test. When wired
 *   it adds three SOFT, multiplicative, never-shrinking, run-length-aware tilts:
 *     - LANGUAGE COHESION (REVERSES the old alternation): the next pick is biased
 *       toward the SAME language as the seed (English->English, Hebrew->Hebrew),
 *       so the station settles into a language stretch instead of alternating.
 *       Language is computed IN-CODE ([containsHebrew], free), so it applies on
 *       the very first play -- it never waits on a cache.
 *     - GENRE COHESION: the next pick is biased toward the seed''s coarse genre
 *       (rap->rap, jazz->jazz) when both genres are ALREADY WARMED (in cache);
 *       unknown / not-yet-warmed = neutral. Genre cohesion thus WARMS IN over the
 *       session as background warms fill the cache.
 *     - RUN-LENGTH EASING: a run that is still SHORT (<= [RUN_SHORT_MAX] same
 *       picks in a row, read from the recentGenres / recentLanguages history) gets
 *       the FULL cohesion boost; as the run grows the boost decays linearly to
 *       neutral by [RUN_LONG_MIN], so a stretch ends organically and the station
 *       drifts to a new genre/language run -- Spotify''s gradual shifts, not an
 *       endless single style.
 *   ARCHITECTURE HONESTY: a block is only 1-2 songs, so cohesion works ACROSS
 *   blocks via the seed -- each block''s first pick coheres with the PREVIOUS
 *   song (the seed), and that chains block-to-block into a felt run. The
 *   run-length easing uses the recentGenres/recentLanguages history threaded in
 *   by [RollingPlanner] to know how long the current stretch already is. All
 *   tilts are no-op on unknown genre/language and can never block or shrink a
 *   pick (a coverage gap degrades to neutral, never to an under-delivered list).
 * @param genreLookupCap hard cap on candidate-pool GENRE reads/warms per plan()
 *   call (highest-ranked first), bounding the number of background warms fired.
 *   The source caches across plans; the seed''s genre is one additional read/warm
 *   outside this cap. Ignored when [genre] is null.
 */
class TastePoolPlanner(
    private val discovery: DiscoverySource? = null,
    private val rng: kotlin.random.Random = kotlin.random.Random.Default,
    private val curator: MoodCurator? = null,
    private val discoveryRate: Double = 0.25,
    private val uniformEpsilon: Double = 0.1,
    private val bpm: BpmSource? = null,
    private val bpmLookupCap: Int = 30,
    private val genre: GenreSource? = null,
    private val genreLookupCap: Int = 30,
    // ARTIST BANLIST (2026-06-14): normalized (lowercase/trimmed) artist names
    // that must NEVER be picked -- filtered from BOTH the taste pool and
    // discovery. Empty disables it. Wired from DevConfig.bannedArtists.
    private val bannedArtists: Set<String> = emptySet(),
    // ENERGY-BASED MOOD-FIT (2026-06-15): a synchronous, measured-only (Essentia
    // RMS) energy lookup (artist,title)->energy?. null disables the per-mood
    // ENERGY window entirely (every existing test passes null -> byte-identical).
    // The octave-UNAMBIGUOUS complement to [bpm]: it demotes high-energy bangers
    // from calm moods (and ballads from party) even when BPM mis-reads the tempo.
    // Cache-only (no network/warm); an un-measured song is NEUTRAL.
    private val energyOf: ((String, String) -> Double?)? = null,
) : SetlistSource {

    private companion object {
        const val MOOD_ASK_CAP = 60
        const val MOOD_BOOST = 8.0
        // Songs the curator judges as NOT fitting the mood are DEMOTED (not just
        // left unboosted) so a slow ballad that is a top taste track no longer
        // leaks into a high-energy mood (party) -- and vice versa for late_night.
        // Soft (never zero) so a mis-judged song can still occasionally surface.
        const val MOOD_MISFIT_DEMOTE = 0.15
        const val ARTIST_FATIGUE = 0.25

        // --- per-mood BPM bias ----------------------------------------------
        const val BPM_IN_WINDOW_BOOST = 3.0
        const val BPM_FAR_OUTSIDE_DEMOTE = 0.4 // gentle: BPM is a noisy assist (Essentia octave/double-time errors, e.g. a ballad read as 162bpm); the low-temp curator is the PRIMARY mood signal
        const val BPM_FAR_MARGIN = 20.0

        // --- per-mood ENERGY bias (octave-UNAMBIGUOUS; calibrated on-device RMS)
        // Energy is a RELIABLE axis (no octave error), so it demotes a bit harder
        // than BPM: at x0.15 a banger the curator wrongly approved (x8) nets ~1.2,
        // far below a song boosted on BOTH curator+energy (~x20) -- so it can no
        // longer surface ahead of true mood-fits. In-window x2.5.
        const val ENERGY_IN_WINDOW_BOOST = 2.5
        const val ENERGY_FAR_OUTSIDE_DEMOTE = 0.15
        const val ENERGY_FAR_MARGIN = 0.05

        // --- seed proximity tilt for the FIRST pick -------------------------
        const val BPM_SEED_NEAR = 8.0
        const val BPM_SEED_MID = 16.0
        const val BPM_SEED_OK = 25.0
        const val BPM_SEED_NEAR_BOOST = 3.0
        const val BPM_SEED_MID_BOOST = 2.0
        const val BPM_SEED_OK_BOOST = 1.3

        // --- LANGUAGE / GENRE COHESION (the Spotify-like RUN behaviour) ------
        /** Same-genre-as-seed candidates are favored this much at FULL strength
         *  (a short run). Soft: a multiplicative boost, never a hard filter. */
        const val GENRE_COHESION_BOOST = 4.0
        /** Same-language-as-seed candidates are favored this much at FULL
         *  strength (a short run). REVERSES the old alternation preference. */
        const val LANG_COHESION_BOOST = 3.0
        /** A run this short (<=) gets the FULL cohesion boost. */
        const val RUN_SHORT_MAX = 3
        /** A run this long (>=) gets NO cohesion boost (neutral x1) -- the
         *  stretch is allowed to end so the station drifts to a new run.
         *  Between [RUN_SHORT_MAX] and this the boost decays linearly. */
        const val RUN_LONG_MIN = 6
    }

    private class Candidate(
        val track: TasteTrack,
        val rank: Int,
        val key: String,
        val artistLower: String,
        val hebrew: Boolean,
        var weight: Double,
        var bpm: Double? = null,
        var genre: String? = null,
    )

    override suspend fun plan(
        taste: TasteProfile,
        n: Int,
        exclude: List<String>?,
        seed: Song?,
        mood: String?,
    ): List<Song> = plan(taste, n, exclude, seed, mood, recentArtists = null)

    override suspend fun plan(
        taste: TasteProfile,
        n: Int,
        exclude: List<String>?,
        seed: Song?,
        mood: String?,
        recentArtists: List<String>?,
    ): List<Song> = plan(taste, n, exclude, seed, mood, recentArtists, null, null)

    /**
     * Cohesion-aware overload. [recentGenres] / [recentLanguages] are the coarse
     * genre and language ("he"/"int") of recently played songs, most recent
     * last (the seed is the last entry). The planner reads the TRAILING run from
     * them to know how long the current genre/language stretch already is, so it
     * can EASE cohesion once a run is long. Both default to null (no history ->
     * the run length collapses to "just the seed", still soft).
     */
    override suspend fun plan(
        taste: TasteProfile,
        n: Int,
        exclude: List<String>?,
        seed: Song?,
        mood: String?,
        recentArtists: List<String>?,
        recentGenres: List<String>?,
        recentLanguages: List<String>?,
    ): List<Song> {
        if (n <= 0) return emptyList()

        val excludeKeys: Set<String> = exclude.orEmpty().toSet()

        val pool = ArrayList<Candidate>(taste.topTracks.size)
        val poolKeys = HashSet<String>()
        taste.topTracks.forEachIndexed { index, t ->
            val title = t.title.trim()
            if (title.isEmpty()) return@forEachIndexed
            val artistLower = t.artist.trim().lowercase()
            if (artistLower in bannedArtists) return@forEachIndexed
            val key = baseTitle(title)
            if (key.isEmpty() || !poolKeys.add(key)) return@forEachIndexed
            pool.add(
                Candidate(
                    track = t,
                    rank = index,
                    key = key,
                    artistLower = artistLower,
                    hebrew = containsHebrew(title),
                    weight = 1.0 / (index + 6),
                ),
            )
        }

        // --- artist fatigue -------------------------------------------------
        val fatiguedArtists: Set<String> = recentArtists.orEmpty()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (fatiguedArtists.isNotEmpty()) {
            for (c in pool) {
                if (c.artistLower in fatiguedArtists) c.weight *= ARTIST_FATIGUE
            }
        }

        // --- mood bias ------------------------------------------------------
        if (curator != null && mood != null && mood != "mix") {
            val spec = Moods.spec(mood)
            val hint = if (spec.key == mood) spec.curationHint else null
            if (!hint.isNullOrBlank()) {
                val asked = if (pool.size > MOOD_ASK_CAP) pool.subList(0, MOOD_ASK_CAP) else pool
                val fit = curator.fitIndices(
                    hint,
                    asked.map { Song(title = it.track.title, artist = it.track.artist) },
                )
                if (fit != null) {
                    // BOOST fitting songs AND demote the rest, so the mood actually
                    // SHAPES the selection (party stays upbeat, late_night mellow)
                    // instead of merely nudging it (vibe-match, Noam 2026-06-14).
                    val fitSet = fit.toHashSet()
                    for (idx in asked.indices) {
                        asked[idx].weight *= if (idx in fitSet) MOOD_BOOST else MOOD_MISFIT_DEMOTE
                    }
                }
            }
        }

        // --- TEMPO AWARENESS: CACHE-ONLY reads + background warm -------------
        // NON-BLOCKING (2026-06-13): plan() must never suspend on the BPM
        // network. For the seed and each capped candidate we read the
        // SYNCHRONOUS cache (cachedBpm, instant); on a cold MISS we fire a
        // fire-and-forget warm() that fills the cache in the background for next
        // time (capped to bpmLookupCap warms so we never launch a pile of
        // coroutines). The per-mood window bias / seed smoothing / ordering pass
        // then act ONLY on already-warmed BPMs -> the first plays are tempo
        // neutral and tempo cohesion WARMS IN over the session. Gating is
        // unchanged: skip entirely on the no-seed cold-start opener (no window,
        // no seed, n<2 -> nothing to bias) so the first pick never even peeks.
        var seedBpm: Double? = null
        val spec = Moods.spec(mood)
        val moodWindow = spec.hasBpmWindow && spec.key == (mood ?: Moods.DEFAULT)
        val tempoActive = bpm != null && (moodWindow || seed != null || n >= 2)
        if (tempoActive) {
            val src = bpm!!
            val lookupN = minOf(bpmLookupCap, pool.size)
            seedBpm = seed?.takeIf { it.title.isNotBlank() }?.let { s ->
                readOrWarmBpm(src, s.artist, s.title)
            }
            for (i in 0 until lookupN) {
                pool[i].bpm = readOrWarmBpm(src, pool[i].track.artist, pool[i].track.title)
            }
            if (moodWindow) {
                val lo = spec.bpmLo!!
                val hi = spec.bpmHi!!
                for (c in pool) {
                    val b = c.bpm ?: continue
                    c.weight *= when {
                        b in lo..hi -> BPM_IN_WINDOW_BOOST
                        b < lo - BPM_FAR_MARGIN || b > hi + BPM_FAR_MARGIN -> BPM_FAR_OUTSIDE_DEMOTE
                        else -> 1.0
                    }
                }
            }
        }

        // --- per-mood ENERGY window: CACHE-ONLY measured RMS, the octave-SAFE
        // complement to the BPM window. Energy reads HIGH for a banger and LOW
        // for a ballad regardless of tempo, so it catches what the noisy BPM
        // window cannot: a ballad mis-read as double-time stays low (kept in calm
        // moods), and a banger like "The Middle" stays high (demoted from them).
        // [energyOf] is a synchronous measured-only lookup (no network, no warm);
        // an un-measured song returns null -> NEUTRAL. Soft + never-shrinking.
        if (energyOf != null && spec.hasEnergyWindow && spec.key == (mood ?: Moods.DEFAULT)) {
            val lo = spec.energyLo!!
            val hi = spec.energyHi!!
            for (c in pool) {
                val e = try {
                    energyOf.invoke(c.track.artist, c.track.title)
                } catch (ex: Exception) {
                    null // an energy read must never break song selection
                } ?: continue
                c.weight *= when {
                    e in lo..hi -> ENERGY_IN_WINDOW_BOOST
                    e < lo - ENERGY_FAR_MARGIN || e > hi + ENERGY_FAR_MARGIN -> ENERGY_FAR_OUTSIDE_DEMOTE
                    else -> 1.0
                }
            }
        }

        // --- SONG-FLOW COHESION: CACHE-ONLY reads + background warm ----------
        // ONLY when a [genre] source is wired. Mirrors the BPM gating: skip the
        // reads/warms entirely on the cold-start opener (no seed AND n<2 ->
        // nothing to cohere WITH and no second pick to cohere INTO), so the most
        // latency-sensitive first pick never even peeks. When needed the genre is
        // read from the SYNCHRONOUS cache (cachedGenre, instant); a cold MISS
        // fires a fire-and-forget warm() (capped to genreLookupCap) that fills the
        // cache in the background -> genre cohesion WARMS IN over the session.
        // Language cohesion (in-code, free) is unaffected and applies immediately.
        var seedGenre: String? = null
        val cohesionActive = genre != null && (seed != null || n >= 2)
        // Seed language is known whenever a seed exists (computed in-code, free).
        val seedHebrew: Boolean? = seed?.takeIf { it.title.isNotBlank() }
            ?.let { containsHebrew(it.title) }
        if (cohesionActive) {
            val src = genre!!
            val lookupN = minOf(genreLookupCap, pool.size)
            seedGenre = seed?.takeIf { it.title.isNotBlank() }?.let { s ->
                readOrWarmGenre(src, s.artist, s.title)
            }
            for (i in 0 until lookupN) {
                pool[i].genre = readOrWarmGenre(src, pool[i].track.artist, pool[i].track.title)
            }

            // RUN-LENGTH EASING factors in [0,1]: 1 while the run is short,
            // decaying linearly to 0 once it reaches RUN_LONG_MIN. Genre and
            // language runs are tracked independently (a long English run can
            // still be in a short rap run, and vice versa), so each cohesion
            // tilt eases on its OWN stretch length.
            val gNorm = sg(seedGenre)
            val genreRun = runLength(recentGenres, gNorm, gNorm != null)
            val langRun = runLength(
                recentLanguages,
                seedHebrew?.let { if (it) "he" else "int" },
                seedHebrew != null,
            )
            val genreEase = easing(genreRun)
            val langEase = easing(langRun)

            // GENRE COHESION (soft, multiplicative): boost candidates whose
            // genre EQUALS the seed''s, scaled by the run-length easing. Both
            // genres must be KNOWN (already warmed); unknown / cold -> neutral.
            if (gNorm != null && genreEase > 0.0) {
                val boost = 1.0 + (GENRE_COHESION_BOOST - 1.0) * genreEase
                for (c in pool) {
                    if (sg(c.genre) == gNorm) c.weight *= boost
                }
            }

            // LANGUAGE COHESION (soft, multiplicative; REVERSES the old
            // alternation): boost candidates in the SAME language as the seed,
            // scaled by the run-length easing. Seed language is always known
            // when a seed exists (in-code), so this is the workhorse of "English
            // songs, then Hebrew songs" runs -- and it never waits on a cache.
            if (seedHebrew != null && langEase > 0.0) {
                val boost = 1.0 + (LANG_COHESION_BOOST - 1.0) * langEase
                for (c in pool) {
                    if (c.hebrew == seedHebrew) c.weight *= boost
                }
            }
        }

        val picks = mutableListOf<Song>()
        val pickBpms = mutableListOf<Double?>()
        val chosenKeys = HashSet<String>()
        val chosenArtists = HashSet<String>()
        val seedArtistLower = seed?.artist?.trim()?.lowercase().orEmpty()
        // For the OLD alternation path (null genre source) only: the language
        // to alternate AWAY from, seeded by the song just played.
        var prevHebrew: Boolean? = seed?.let { containsHebrew(it.title) }
        var discoveriesUsed = 0

        while (picks.size < n) {
            // --- discovery slot ---------------------------------------------
            if (discovery != null && discoveriesUsed < 1 && rng.nextDouble() < discoveryRate) {
                val found = try {
                    discovery.discover(
                        taste = taste,
                        excludeKeys = excludeKeys + chosenKeys,
                        excludeArtists = buildSet {
                            picks.forEach { p -> if (p.artist.isNotBlank()) add(p.artist) }
                            seed?.artist?.takeIf { it.isNotBlank() }?.let { add(it) }
                            bannedArtists.forEach { add(it) }
                        },
                    )
                } catch (e: Exception) {
                    null
                }
                if (found != null && found.title.isNotBlank() && found.artist.isNotBlank() &&
                    found.artist.trim().lowercase() !in bannedArtists) {
                    val key = baseTitle(found.title)
                    if (key !in excludeKeys && key !in chosenKeys) {
                        picks.add(found)
                        pickBpms.add(null)
                        chosenKeys.add(key)
                        chosenArtists.add(found.artist.trim().lowercase())
                        prevHebrew = containsHebrew(found.title)
                        discoveriesUsed++
                        continue
                    }
                }
            }

            // --- taste pick: tiered constraint relaxation -------------------
            val notChosen = pool.filter { it.key !in chosenKeys }
            if (notChosen.isEmpty()) break

            val avoidArtists: Set<String> =
                if (picks.isEmpty() && seedArtistLower.isNotEmpty()) {
                    chosenArtists + seedArtistLower
                } else {
                    chosenArtists
                }
            val prev = prevHebrew

            fun fresh(c: Candidate) = c.key !in excludeKeys
            fun artistOk(c: Candidate) = c.artistLower.isEmpty() || c.artistLower !in avoidArtists
            fun otherLang(c: Candidate) = prev == null || c.hebrew != prev

            // COHESION (genre source wired): language cohesion is handled by the
            // weight tilts above (same-language boosted), so the language TIER
            // constraint is DROPPED here -- forcing alternation in the tier would
            // fight the cohesion. The OLD alternation tier is kept ONLY on the
            // null-source path, so existing tests / the no-genre config stay
            // byte-identical.
            val tier = if (genre != null) {
                notChosen.filter { fresh(it) && artistOk(it) }
                    .ifEmpty { notChosen.filter { fresh(it) } }
                    .ifEmpty { notChosen.filter { artistOk(it) } }
                    .ifEmpty { notChosen }
            } else {
                notChosen.filter { fresh(it) && artistOk(it) && otherLang(it) }
                    .ifEmpty { notChosen.filter { fresh(it) && artistOk(it) } }
                    .ifEmpty { notChosen.filter { fresh(it) } }
                    .ifEmpty { notChosen.filter { artistOk(it) } }
                    .ifEmpty { notChosen }
            }

            val sb = seedBpm
            val tilt: ((Candidate) -> Double)? =
                if (picks.isEmpty() && sb != null) {
                    { c -> seedProximityFactor(c.bpm, sb) }
                } else {
                    null
                }

            val c = sampleWeighted(tier, tilt)
            picks.add(
                Song(
                    title = c.track.title,
                    artist = c.track.artist,
                    durationS = c.track.durationS.takeIf { it > 0.0 },
                    tasteRank = c.rank,
                ),
            )
            pickBpms.add(c.bpm)
            chosenKeys.add(c.key)
            if (c.artistLower.isNotEmpty()) chosenArtists.add(c.artistLower)
            prevHebrew = c.hebrew
        }

        if (bpm != null && picks.size >= 2) {
            orderByBpm(picks, pickBpms, seedBpm)
        }
        return picks
    }

    /** Lowercased, trimmed genre label for EQUALITY comparison; null stays null. */
    private fun sg(g: String?): String? = g?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * SYNCHRONOUS, NON-BLOCKING tempo read: return the cached BPM if it is warm,
     * else fire a background [BpmSource.warm] (for next time) and return null
     * (NEUTRAL now). Blank titles are skipped. NEVER suspends, NEVER throws.
     */
    private fun readOrWarmBpm(src: BpmSource, artist: String, title: String): Double? {
        if (title.isBlank()) return null
        return try {
            val cached = src.cachedBpm(artist, title)
            if (cached == null) src.warm(artist, title)
            cached
        } catch (e: Exception) {
            null // a BPM read/warm must never break song selection
        }
    }

    /**
     * SYNCHRONOUS, NON-BLOCKING genre read: return the cached coarse genre if it
     * is warm, else fire a background [GenreSource.warm] (for next time) and
     * return null (NEUTRAL now). Blank titles are skipped. NEVER suspends, NEVER
     * throws.
     */
    private fun readOrWarmGenre(src: GenreSource, artist: String, title: String): String? {
        if (title.isBlank()) return null
        return try {
            val cached = src.cachedGenre(artist, title)
            if (cached == null) src.warm(artist, title)
            cached
        } catch (e: Exception) {
            null // a genre read/warm must never break song selection
        }
    }

    /**
     * Length of the trailing run in [history] whose entries equal [value]
     * (case-insensitively, trimmed), i.e. how many of the MOST RECENT plays were
     * the same genre/language as the seed. When [value] is unknown ([enabled]
     * false) the run is 0. Always counts the seed itself as length >= 1 when its
     * value is known, even with no history, so a fresh launch still coheres.
     */
    private fun runLength(history: List<String>?, value: String?, enabled: Boolean): Int {
        if (!enabled || value == null) return 0
        var run = 1 // the seed (the song just played) is in the run
        val h = history.orEmpty()
        // The last history entry is the seed itself; count matching entries
        // BEFORE it, walking backwards, to learn the stretch length.
        var i = h.size - 2
        while (i >= 0) {
            val e = h[i].trim().lowercase()
            if (e.isNotEmpty() && e == value) run++ else break
            i--
        }
        return run
    }

    /** Run-length easing factor in [0,1]: 1 while run <= RUN_SHORT_MAX, decaying
     *  LINEARLY to 0 by RUN_LONG_MIN, then 0 (fully eased -> a transition can
     *  happen). 0 when run is 0 (unknown -> neutral). */
    private fun easing(run: Int): Double = when {
        run <= 0 -> 0.0
        run <= RUN_SHORT_MAX -> 1.0
        run >= RUN_LONG_MIN -> 0.0
        else -> (RUN_LONG_MIN - run).toDouble() / (RUN_LONG_MIN - RUN_SHORT_MAX).toDouble()
    }

    private fun sampleWeighted(
        candidates: List<Candidate>,
        tilt: ((Candidate) -> Double)? = null,
    ): Candidate {
        if (rng.nextDouble() < uniformEpsilon) {
            return candidates[rng.nextInt(candidates.size)]
        }
        val total = candidates.sumOf { it.weight * (tilt?.invoke(it) ?: 1.0) }
        var r = rng.nextDouble() * total
        for (c in candidates) {
            r -= c.weight * (tilt?.invoke(c) ?: 1.0)
            if (r <= 0.0) return c
        }
        return candidates.last()
    }

    private fun seedProximityFactor(candidateBpm: Double?, seedBpm: Double?): Double {
        if (candidateBpm == null || seedBpm == null) return 1.0
        val d = kotlin.math.abs(candidateBpm - seedBpm)
        return when {
            d <= BPM_SEED_NEAR -> BPM_SEED_NEAR_BOOST
            d <= BPM_SEED_MID -> BPM_SEED_MID_BOOST
            d <= BPM_SEED_OK -> BPM_SEED_OK_BOOST
            else -> 1.0
        }
    }

    private fun orderByBpm(picks: MutableList<Song>, bpms: MutableList<Double?>, seedBpm: Double?) {
        val knownIdx = bpms.indices.filter { bpms[it] != null }
        if (knownIdx.isEmpty()) return

        val remaining: MutableList<Pair<Song, Double>> =
            knownIdx.map { picks[it] to bpms[it]!! }.toMutableList()
        val unknownOrdered = bpms.indices.filter { bpms[it] == null }.map { picks[it] }

        val chain = ArrayList<Pair<Song, Double>>(remaining.size)
        var anchor = seedBpm
        while (remaining.isNotEmpty()) {
            val nextI = if (anchor == null) {
                0
            } else {
                var best = 0
                var bestD = kotlin.math.abs(remaining[0].second - anchor)
                for (j in 1 until remaining.size) {
                    val dj = kotlin.math.abs(remaining[j].second - anchor)
                    if (dj < bestD) { bestD = dj; best = j }
                }
                best
            }
            val picked = remaining.removeAt(nextI)
            chain.add(picked)
            anchor = picked.second
        }

        val newSongs = ArrayList<Song>(picks.size)
        val newBpms = ArrayList<Double?>(picks.size)
        for ((song, b) in chain) { newSongs.add(song); newBpms.add(b) }
        for (song in unknownOrdered) { newSongs.add(song); newBpms.add(null) }
        picks.clear(); picks.addAll(newSongs)
        bpms.clear(); bpms.addAll(newBpms)
    }
}