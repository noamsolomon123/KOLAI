package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
 * Pure-code song picking from the listener's taste pool -- the production
 * replacement for LLM setlist invention.
 *
 * WHY: [SetlistPlanner] asks Gemini to invent setlists, and an LLM is not a
 * music catalog -- it hallucinated a non-existent song on air ("מרגישים את זה"
 * by E-Z). Code picking from the listener's REAL taste pool cannot hallucinate:
 * every taste pick is a track the listener actually listens to, and every
 * discovery pick comes from a real-catalog [DiscoverySource] (Deezer). The LLM
 * keeps only what it is good at: DJ speech ([DjBrain] is untouched).
 *
 * The craft heuristics the LLM prompt used to ask for are implemented in code:
 *
 *  - WEIGHTED TASTE SAMPLING: taste.topTracks order is the listener's rank;
 *    each track is weighted 1.0 / (index + 6), so top tracks are favoured but
 *    the tail still surfaces. Sampling is WITHOUT replacement.
 *  - NO-REPEAT: a track is excluded when `baseTitle(title)` is in [plan]'s
 *    exclude list (the exact key [RollingPlanner] keeps in its history); the
 *    pool itself is also deduped by baseTitle so two versions of one song
 *    never both qualify.
 *  - ARTIST SPACING (soft): never two picks by the same artist within one
 *    returned list, and the FIRST pick avoids the seed's artist (the song just
 *    played). Relaxed -- rather than under-delivering -- when the pool is
 *    starved.
 *  - HEBREW/INTERNATIONAL ALTERNATION (soft): after each pick the next pick
 *    prefers (does not require) a candidate from the other language group,
 *    starting from the seed's language when a seed is given.
 *  - MOOD BIAS (optional, LLM-assisted, hallucination-safe): when a [mood]
 *    other than "mix" is requested and a [curator] is wired, the deduped pool
 *    (first 60 entries) is shown to the LLM as a NUMBERED LIST OF REAL SONGS
 *    and the LLM may only answer with indices into that list -- it cannot
 *    invent, rename or add a song. Pool entries whose index comes back get
 *    their sampling weight multiplied by 8.0, so mood-fitting songs dominate
 *    the picks while every pick stays a real taste-pool track. Degradation is
 *    graceful: a null curator, a null/"mix" mood, an unknown mood key, or any
 *    curator failure (it returns null, never throws) leaves the weights
 *    untouched -- behavior is then byte-identical to the unbiased planner.
 *  - DISCOVERY (~1 in 4): each slot has a 25% chance of being a discovery pick
 *    from [discovery] (capped at 1 per call -- blocks are 1-2 songs, so a
 *    per-call cap of 1 keeps the on-air ratio right). A null/unusable/throwing
 *    discovery falls back to a taste pick: discovery can never shrink the list.
 *  - RELAX-WHEN-STARVED (mirrors [RollingPlanner]): prefer fresh picks, but
 *    fall back to excluded (recently played) tracks rather than returning
 *    fewer than n songs. Only a pool with fewer than n unique songs total can
 *    yield a short list.
 *
 * durationS: taste picks carry TasteTrack.durationS when > 0 (this activates
 * the duration-closeness term in the YouTube candidate scorer -- an important
 * pick-accuracy win); discovery songs carry their own durationS from the
 * catalog.
 *
 * @param discovery optional real-catalog discovery source; null disables
 *   discovery (pure taste picking).
 * @param rng injectable randomness: production uses
 *   [kotlin.random.Random.Default]; tests pass a seeded Random for determinism.
 * @param curator optional LLM mood curator; null (the default) disables mood
 *   bias entirely -- plan() then behaves exactly as before this seam existed.
 * @param discoveryRate per-slot probability of attempting a discovery pick
 *   (still capped at 1 per call). Default 0.25; a tuning knob, e.g. 0.0
 *   disables discovery rolls entirely even when [discovery] is wired.
 * @param uniformEpsilon epsilon-greedy floor for taste sampling: with this
 *   probability a pick samples UNIFORMLY from the eligible tier instead of
 *   rank-weighted, so old favourites near the pool's tail (whose 1/(rank+6)
 *   weight is tiny) are guaranteed to keep surfacing. 0.0 restores pure
 *   weighted sampling.
 * @param bpm optional [BpmSource] for TEMPO AWARENESS; null (the default)
 *   disables every tempo behaviour -- plan() is then byte-identical to the
 *   tempo-blind planner. When wired it adds three SOFT, multiplicative,
 *   never-shrinking tilts (all no-op on unknown BPM, which the study found is
 *   ~57-70% of the catalog):
 *     - PER-MOOD BPM BIAS: candidates whose tempo fits the mood's
 *       [MoodSpec.bpmLo]/[MoodSpec.bpmHi] window get a weight boost;
 *       far-outside ones a mild demotion. The mood label becomes numerically
 *       tempo-distinct instead of cosmetic.
 *     - SEED SMOOTHING: the FIRST pick is tilted toward the seed song's tempo,
 *       so consecutive PLAYBACK starts with a smaller |dBPM| jump.
 *     - FINAL ORDERING PASS: a returned multi-song block is reordered to
 *       minimize the playback |dBPM| chain (seed -> first -> ...), parking
 *       unknown-BPM songs at the boundary.
 *   ARCHITECTURE HONESTY: a block is only 1-2 songs, so true GLOBAL tempo
 *   sorting (the study's 57%->1% jarring-rate fix) is not possible at pick
 *   time. This biases SELECTION toward a coherent tempo and smooths the
 *   seed->first transition; the realized jarring-rate gain is therefore a
 *   fraction of the offline simulation -- real but bounded by the n=1-2 block
 *   and by BPM coverage. None of it can ever block or shrink a pick.
 * @param bpmLookupCap hard cap on candidate-pool BPM lookups per plan() call
 *   (highest-ranked candidates first), bounding latency. The source caches, so
 *   repeated plans are near-free. The seed's BPM is one additional lookup
 *   outside this cap. Ignored entirely when [bpm] is null.
 */
class TastePoolPlanner(
    private val discovery: DiscoverySource? = null,
    private val rng: kotlin.random.Random = kotlin.random.Random.Default,
    private val curator: MoodCurator? = null,
    private val discoveryRate: Double = 0.25,
    private val uniformEpsilon: Double = 0.1,
    private val bpm: BpmSource? = null,
    private val bpmLookupCap: Int = 30,
) : SetlistSource {

    private companion object {
        /** Max pool entries shown to the mood curator (bounds the prompt). */
        const val MOOD_ASK_CAP = 60

        /** Weight multiplier for pool entries the curator says fit the mood. */
        const val MOOD_BOOST = 8.0

        /** Weight multiplier for artists heard recently (cross-call fatigue).
         *  A DEMOTION, not an exclusion: a fatigued artist still plays when
         *  the listener's pool offers little else (or the dice say so). */
        const val ARTIST_FATIGUE = 0.25

        // --- per-mood BPM bias (only when a window + a known BPM exist) -----
        /** In-window candidates are favored this much. */
        const val BPM_IN_WINDOW_BOOST = 3.0
        /** Candidates more than [BPM_FAR_MARGIN] outside the window are demoted
         *  (never excluded -- a beloved off-tempo song still surfaces). */
        const val BPM_FAR_OUTSIDE_DEMOTE = 0.4
        /** How far past a window edge counts as "far outside" (BPM). Inside this
         *  margin a candidate stays neutral (x1) rather than demoted. */
        const val BPM_FAR_MARGIN = 20.0

        // --- seed proximity tilt for the FIRST pick (smaller |dBPM| favored) -
        // Soft, tiered, and PENALTY-FREE at the tail (worst case x1, never 0):
        // never hard-excludes a far-tempo song, just nudges a closer one up.
        const val BPM_SEED_NEAR = 8.0   // |dBPM| <= this -> strongly favored
        const val BPM_SEED_MID = 16.0   // |dBPM| <= this -> favored
        const val BPM_SEED_OK = 25.0    // |dBPM| <= this -> mildly favored
        const val BPM_SEED_NEAR_BOOST = 3.0
        const val BPM_SEED_MID_BOOST = 2.0
        const val BPM_SEED_OK_BOOST = 1.3
    }

    /** A taste track prepared for picking: no-repeat key, weight, language.
     *  [rank] is the track's ORIGINAL index in taste.topTracks (the rank that
     *  produced its 1/(rank+6) weight); it is stamped onto the picked Song as
     *  [Song.tasteRank] so the DJ can acknowledge personal favorites.
     *  [bpm] is the candidate's tempo when known (null = unknown = NEUTRAL: it
     *  never biases a weight nor reorders -- coverage gaps stay tempo-blind). */
    private class Candidate(
        val track: TasteTrack,
        val rank: Int,
        val key: String,
        val artistLower: String,
        val hebrew: Boolean,
        var weight: Double,
        var bpm: Double? = null,
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
    ): List<Song> {
        if (n <= 0) return emptyList()

        val excludeKeys: Set<String> = exclude.orEmpty().toSet()

        // Candidate pool: topTracks (rank order) minus blanks, deduped by
        // baseTitle keeping the best-ranked version. Weight 1/(index+6).
        val pool = ArrayList<Candidate>(taste.topTracks.size)
        val poolKeys = HashSet<String>()
        taste.topTracks.forEachIndexed { index, t ->
            val title = t.title.trim()
            if (title.isEmpty()) return@forEachIndexed
            val key = baseTitle(title)
            if (key.isEmpty() || !poolKeys.add(key)) return@forEachIndexed
            pool.add(
                Candidate(
                    track = t,
                    rank = index,
                    key = key,
                    artistLower = t.artist.trim().lowercase(),
                    hebrew = containsHebrew(title),
                    weight = 1.0 / (index + 6),
                ),
            )
        }

        // --- artist fatigue: DEMOTE (never exclude) recently heard artists --
        // Cross-call counterpart of within-call artist spacing: an artist the
        // station played in the last few songs keeps a foot in the pool but
        // stops dominating it. Multiplicative, so it composes with the rank
        // weight above and the mood boost below.
        val fatiguedArtists: Set<String> = recentArtists.orEmpty()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (fatiguedArtists.isNotEmpty()) {
            for (c in pool) {
                if (c.artistLower in fatiguedArtists) c.weight *= ARTIST_FATIGUE
            }
        }

        // --- mood bias: the LLM SELECTS fitting songs FROM the real pool ----
        // Hallucination-safe by construction: the curator may only answer
        // with indices into the deduped pool we hand it. Any failure (null
        // hint, null result) leaves every weight untouched.
        if (curator != null && mood != null && mood != "mix") {
            // Moods.spec falls back to the DEFAULT ("mix") spec for unknown
            // keys; only bias when the spec really is the requested mood, so
            // an unknown key degrades to "no bias" instead of mix-hint bias.
            val spec = Moods.spec(mood)
            val hint = if (spec.key == mood) spec.curationHint else null
            if (!hint.isNullOrBlank()) {
                // Cap the asked list to bound the prompt; entries beyond the
                // cap simply get no boost.
                val asked = if (pool.size > MOOD_ASK_CAP) pool.subList(0, MOOD_ASK_CAP) else pool
                val fit = curator.fitIndices(
                    hint,
                    asked.map { Song(title = it.track.title, artist = it.track.artist) },
                )
                if (fit != null) {
                    for (i in fit) {
                        if (i in asked.indices) asked[i].weight *= MOOD_BOOST
                    }
                }
            }
        }

        // --- TEMPO AWARENESS: bounded BPM lookups + per-mood window bias -----
        // All no-ops when [bpm] is null (no lookups, no weight change) -> the
        // plan is then byte-identical to the tempo-blind planner. Lookups are
        // BOUNDED to the first [bpmLookupCap] pool candidates (highest-ranked
        // first) to cap latency; the source caches, so repeated plans are cheap.
        // A failed/slow/unknown lookup yields null = NEUTRAL: never biases,
        // never blocks, never shrinks the setlist.
        var seedBpm: Double? = null
        val spec = Moods.spec(mood)
        val moodWindow = spec.hasBpmWindow && spec.key == (mood ?: Moods.DEFAULT)
        // GATE (2026-06-13, cold-start fix): the candidate/seed BPMs feed ONLY
        // (a) the per-mood window bias, (b) first-pick seed smoothing, and (c)
        // the n>=2 ordering pass. With NO window, NO seed, and n<2 (the
        // cold-start opener: mix mood, no seed, 1 song) the lookups would be
        // DISCARDED - so skip them entirely rather than pay up to bpmLookupCap
        // network round-trips on the most latency-sensitive pick. When lookups
        // ARE needed they run CONCURRENTLY (not the old serial loop), bounding
        // latency to ~one round-trip; the source caches across plans.
        val tempoActive = bpm != null && (moodWindow || seed != null || n >= 2)
        if (tempoActive) {
            val lookupN = minOf(bpmLookupCap, pool.size)
            coroutineScope {
                val seedDef = seed?.takeIf { it.title.isNotBlank() }
                    ?.let { s -> async { bpmOf(s.artist, s.title) } }
                val candDefs = (0 until lookupN).map { i ->
                    async { bpmOf(pool[i].track.artist, pool[i].track.title) }
                }
                seedBpm = seedDef?.await()
                for (i in 0 until lookupN) pool[i].bpm = candDefs[i].await()
            }
            // PER-MOOD WINDOW BIAS (multiplicative; only when the mood has a
            // window). Only candidates with a KNOWN bpm are affected; unknown
            // stays neutral.
            if (moodWindow) {
                val lo = spec.bpmLo!!
                val hi = spec.bpmHi!!
                for (c in pool) {
                    val b = c.bpm ?: continue // unknown -> neutral
                    c.weight *= when {
                        b in lo..hi -> BPM_IN_WINDOW_BOOST
                        b < lo - BPM_FAR_MARGIN || b > hi + BPM_FAR_MARGIN -> BPM_FAR_OUTSIDE_DEMOTE
                        else -> 1.0 // just-outside the edge: stay neutral
                    }
                }
            }
        }

        val picks = mutableListOf<Song>()
        val pickBpms = mutableListOf<Double?>() // parallel to [picks]; null = unknown
        val chosenKeys = HashSet<String>()
        val chosenArtists = HashSet<String>() // lowercased
        val seedArtistLower = seed?.artist?.trim()?.lowercase().orEmpty()
        // Language alternation starts from the song just played, when known.
        var prevHebrew: Boolean? = seed?.let { containsHebrew(it.title) }
        var discoveriesUsed = 0

        while (picks.size < n) {
            // --- discovery slot: 25% chance, at most 1 per call -------------
            if (discovery != null && discoveriesUsed < 1 && rng.nextDouble() < discoveryRate) {
                val found = try {
                    discovery.discover(
                        taste = taste,
                        excludeKeys = excludeKeys + chosenKeys,
                        excludeArtists = buildSet {
                            picks.forEach { p -> if (p.artist.isNotBlank()) add(p.artist) }
                            seed?.artist?.takeIf { it.isNotBlank() }?.let { add(it) }
                        },
                    )
                } catch (e: Exception) {
                    null // discovery failures must never shrink the list
                }
                if (found != null && found.title.isNotBlank() && found.artist.isNotBlank()) {
                    val key = baseTitle(found.title)
                    if (key !in excludeKeys && key !in chosenKeys) {
                        picks.add(found)
                        // Discovery BPM is left UNKNOWN (null): it is outside the
                        // candidate-pool cap, so the final ordering parks it at a
                        // boundary rather than fabricating a tempo jump for it.
                        pickBpms.add(null)
                        chosenKeys.add(key)
                        chosenArtists.add(found.artist.trim().lowercase())
                        prevHebrew = containsHebrew(found.title)
                        discoveriesUsed++
                        continue
                    }
                }
                // fall through to a taste pick for this slot
            }

            // --- taste pick: tiered constraint relaxation -------------------
            val notChosen = pool.filter { it.key !in chosenKeys }
            if (notChosen.isEmpty()) break // pool has < n unique songs total

            // The seed's artist is avoided for the FIRST pick only.
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

            // Relaxation order: drop the language preference first, then the
            // artist spacing, then the freshness (recently-played) constraint
            // -- a fresh song by a repeated artist beats replaying a recent
            // song, and replaying beats under-delivering (RollingPlanner's
            // relax semantics).
            val tier = notChosen.filter { fresh(it) && artistOk(it) && otherLang(it) }
                .ifEmpty { notChosen.filter { fresh(it) && artistOk(it) } }
                .ifEmpty { notChosen.filter { fresh(it) } }
                .ifEmpty { notChosen.filter { artistOk(it) } }
                .ifEmpty { notChosen }

            // SEED SMOOTHING (soft, FIRST pick only): when both the seed and a
            // candidate have a known BPM, tilt that candidate's sampling weight
            // up the closer its tempo is to the seed's, so consecutive PLAYBACK
            // starts with a smaller |dBPM| jump. Penalty-free at the tail
            // (worst case x1) -> never hard-excludes a far-tempo song. No-ops
            // when bpm is null, the seed BPM is unknown, or it is not the first
            // pick -> sampling is then byte-identical to the tempo-blind path.
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

        // --- FINAL ORDERING PASS: minimize playback |dBPM| ------------------
        // A soft, BPM-only reorder of the returned songs so the jump from the
        // seed into the first pick (and between picks) is as small as the known
        // tempos allow. Songs with an UNKNOWN BPM keep their relative order and
        // are parked at the END (a missing value must not fabricate a false
        // jump -- the study's unknown-at-boundary rule). No-ops when bpm is
        // null or fewer than two picks carry a known BPM -> byte-identical
        // order to the tempo-blind planner in every existing test.
        if (bpm != null && picks.size >= 2) {
            orderByBpm(picks, pickBpms, seedBpm)
        }
        return picks
    }

    /**
     * Weighted sample of one candidate (weights ~ 1/(rank+6), times any mood /
     * fatigue / BPM-window multipliers already folded into [Candidate.weight]).
     *
     * [tilt] is an OPTIONAL per-candidate multiplier applied on top of the
     * stored weight for THIS draw only (used for the first-pick seed-BPM
     * smoothing, which must not mutate the shared pool weights). It does NOT
     * affect the epsilon-greedy uniform branch -- the floor stays a true
     * uniform sample over the tier, so the pool tail keeps surfacing.
     */
    private fun sampleWeighted(
        candidates: List<Candidate>,
        tilt: ((Candidate) -> Double)? = null,
    ): Candidate {
        // Epsilon-greedy floor: occasionally sample UNIFORMLY over the tier so
        // the pool's tail (old favourites with tiny 1/(rank+6) weights) is
        // guaranteed to keep surfacing on air.
        if (rng.nextDouble() < uniformEpsilon) {
            return candidates[rng.nextInt(candidates.size)]
        }
        val total = candidates.sumOf { it.weight * (tilt?.invoke(it) ?: 1.0) }
        var r = rng.nextDouble() * total
        for (c in candidates) {
            r -= c.weight * (tilt?.invoke(c) ?: 1.0)
            if (r <= 0.0) return c
        }
        return candidates.last() // floating-point tail guard
    }

    /** Look up a song's BPM via the wired [bpm] source; null when no source,
     *  blank inputs, or the source returns null/unknown. NEVER throws (the
     *  source contract is best-effort, and a defensive catch backstops it). */
    private suspend fun bpmOf(artist: String, title: String): Double? {
        val src = bpm ?: return null
        if (title.isBlank()) return null
        return try {
            src.bpm(artist, title)
        } catch (e: Exception) {
            null // a BPM lookup must never break song selection
        }
    }

    /** Soft, tiered weight multiplier favoring a candidate whose tempo is close
     *  to the seed's. Neutral (x1) when either BPM is unknown OR the gap is
     *  large -- a PENALTY-FREE tail, so a far-tempo song is nudged DOWN relative
     *  to a near one, never excluded. */
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

    /**
     * Reorder [picks] in place (with [bpms] kept parallel) to minimize the
     * playback |dBPM| chain, anchored on [seedBpm] when known. Greedy
     * nearest-neighbour over the KNOWN-BPM picks; UNKNOWN-BPM picks keep their
     * relative order and are appended LAST (parked at the boundary so a missing
     * value cannot fabricate a jump). With the production 1-2 song block this
     * reduces to "place the pick closer to the seed first"; it generalizes
     * cleanly to larger n without changing the small-n behaviour.
     */
    private fun orderByBpm(picks: MutableList<Song>, bpms: MutableList<Double?>, seedBpm: Double?) {
        val knownIdx = bpms.indices.filter { bpms[it] != null }
        // Nothing to gain when NO pick has a known tempo (all unknown -> keep
        // selection order). One known pick with unknown(s) still reorders: the
        // known song leads and the unknowns park at the boundary (so a missing
        // value never fabricates a jump); >=2 known are tempo-chained.
        if (knownIdx.isEmpty()) return

        // (song, bpm) for the known-BPM picks (stable original order).
        val remaining: MutableList<Pair<Song, Double>> =
            knownIdx.map { picks[it] to bpms[it]!! }.toMutableList()
        val unknownOrdered = bpms.indices.filter { bpms[it] == null }.map { picks[it] }

        // Greedy chain over the known-BPM songs, anchored on the seed tempo.
        val chain = ArrayList<Pair<Song, Double>>(remaining.size)
        var anchor = seedBpm
        while (remaining.isNotEmpty()) {
            val nextI = if (anchor == null) {
                0 // no anchor: keep the first remaining (stable) as the start
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

        // Rewrite picks/bpms: ordered known songs first, then parked unknowns.
        val newSongs = ArrayList<Song>(picks.size)
        val newBpms = ArrayList<Double?>(picks.size)
        for ((song, b) in chain) { newSongs.add(song); newBpms.add(b) }
        for (song in unknownOrdered) { newSongs.add(song); newBpms.add(null) }
        picks.clear(); picks.addAll(newSongs)
        bpms.clear(); bpms.addAll(newBpms)
    }
}
