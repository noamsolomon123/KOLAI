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
 */
class TastePoolPlanner(
    private val discovery: DiscoverySource? = null,
    private val rng: kotlin.random.Random = kotlin.random.Random.Default,
) : SetlistSource {

    /** A taste track prepared for picking: no-repeat key, weight, language. */
    private class Candidate(
        val track: TasteTrack,
        val key: String,
        val artistLower: String,
        val hebrew: Boolean,
        val weight: Double,
    )

    override suspend fun plan(
        taste: TasteProfile,
        n: Int,
        exclude: List<String>?,
        seed: Song?,
        mood: String?,
    ): List<Song> {
        // TODO post-MVP: use mood -- the moods table (Python radioai/moods.py)
        // is deferred, exactly like SetlistPlanner.moodBlock's TODO. When it
        // lands, mood should bias the candidate weighting (e.g. prefer tracks
        // fitting MOODS[mood]['song']) without abandoning the taste.
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
                    key = key,
                    artistLower = t.artist.trim().lowercase(),
                    hebrew = containsHebrew(title),
                    weight = 1.0 / (index + 6),
                ),
            )
        }

        val picks = mutableListOf<Song>()
        val chosenKeys = HashSet<String>()
        val chosenArtists = HashSet<String>() // lowercased
        val seedArtistLower = seed?.artist?.trim()?.lowercase().orEmpty()
        // Language alternation starts from the song just played, when known.
        var prevHebrew: Boolean? = seed?.let { containsHebrew(it.title) }
        var discoveriesUsed = 0

        while (picks.size < n) {
            // --- discovery slot: 25% chance, at most 1 per call -------------
            if (discovery != null && discoveriesUsed < 1 && rng.nextDouble() < 0.25) {
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

            val c = sampleWeighted(tier)
            picks.add(
                Song(
                    title = c.track.title,
                    artist = c.track.artist,
                    durationS = c.track.durationS.takeIf { it > 0.0 },
                ),
            )
            chosenKeys.add(c.key)
            if (c.artistLower.isNotEmpty()) chosenArtists.add(c.artistLower)
            prevHebrew = c.hebrew
        }
        return picks
    }

    /** Weighted sample of one candidate (weights ∝ 1/(rank+6)). */
    private fun sampleWeighted(candidates: List<Candidate>): Candidate {
        val total = candidates.sumOf { it.weight }
        var r = rng.nextDouble() * total
        for (c in candidates) {
            r -= c.weight
            if (r <= 0.0) return c
        }
        return candidates.last() // floating-point tail guard
    }
}
