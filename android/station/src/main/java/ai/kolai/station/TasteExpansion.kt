package ai.kolai.station

import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack

/**
 * PURE merge of a listener's BASE [TasteProfile] (the real ~20 Spotify top
 * tracks) with a list of Deezer-DISCOVERED [TasteTrack]s into one expanded
 * profile. This is the testable core of `ai.kolai.app.ExpandedTasteSource` (the
 * :app decorator that does the Deezer HTTP + disk cache around it); the network
 * side lives in :app, this stays a no-IO function with JVM unit tests.
 *
 * WHY THIS EXISTS: the bundled taste is only ~20 tracks, but [RollingPlanner]'s
 * no-repeat window is 50 -- mathematically unsatisfiable, so hot tracks repeat
 * 15-20x. Growing the pool to ~60-80 tracks lets the no-repeat window be
 * satisfied and (via the planner's epsilon floor + relaxation) spreads play
 * across a real catalog instead of hammering the same favourites.
 *
 * ORDER IS THE CONTRACT: [TastePoolPlanner] weights each track 1/(rank+6) by
 * its index in [TasteProfile.topTracks]. So the BASE tracks are kept FIRST in
 * their original order (they keep the highest weight -> the listener's real
 * favourites still dominate), and the discovered tracks are appended as a
 * lower-weight TAIL (ranks ~20..cap), which the epsilon floor + relaxation
 * surface enough to kill the window starvation without dethroning the top.
 *
 * DEDUPE: by [baseTitle] (the exact key the planner dedupes on), so (a) two
 * versions of one song never both appear and (b) the base 20 are never
 * duplicated by the expansion. The base set defines the seen-keys up front, so
 * a discovered track that collides with a base track (or an earlier discovered
 * track) is dropped. Blank titles are skipped.
 *
 * CAP: the merged [TasteProfile.topTracks] is truncated to [cap] entries
 * (base-first), bounding the pool the planner samples from.
 *
 * @param base the real listener profile (kept first, order preserved).
 * @param discovered Deezer top/related tracks to append as the tail.
 * @param cap maximum number of merged tracks (default 80). Values <= the base
 *   size effectively return the (capped) base unchanged.
 * @param extraArtists discovered artist NAMES to union onto
 *   [TasteProfile.topArtists] (base artists first, deduped case-insensitively,
 *   blanks dropped). Lets the planner's discovery seam fan out from the wider
 *   artist set too.
 */
fun mergeProfiles(
    base: TasteProfile,
    discovered: List<TasteTrack>,
    cap: Int = 80,
    extraArtists: List<String> = emptyList(),
): TasteProfile {
    val merged = ArrayList<TasteTrack>(minOf(cap, base.topTracks.size + discovered.size))
    val seen = HashSet<String>()

    // 1. BASE FIRST (preserve order -> preserve the top-favourite weights).
    for (t in base.topTracks) {
        if (merged.size >= cap) break
        val title = t.title.trim()
        if (title.isEmpty()) continue
        val key = baseTitle(title)
        if (key.isEmpty() || !seen.add(key)) continue
        merged.add(t)
    }

    // 2. DISCOVERED TAIL (deduped against the base + each other).
    for (t in discovered) {
        if (merged.size >= cap) break
        val title = t.title.trim()
        if (title.isEmpty()) continue
        val key = baseTitle(title)
        if (key.isEmpty() || !seen.add(key)) continue
        merged.add(t)
    }

    // Artists: base first, then the discovered names, deduped case-insensitively.
    val artists = ArrayList<String>(base.topArtists.size + extraArtists.size)
    val seenArtists = HashSet<String>()
    for (a in base.topArtists + extraArtists) {
        val name = a.trim()
        if (name.isEmpty()) continue
        if (!seenArtists.add(name.lowercase())) continue
        artists.add(name)
    }

    return TasteProfile(topTracks = merged, topArtists = artists)
}