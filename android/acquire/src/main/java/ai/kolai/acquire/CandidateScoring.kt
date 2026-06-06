package ai.kolai.acquire

import ai.kolai.core.Song
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Candidate-scoring logic ported 1:1 from `backend/radioai/fetcher.py`
 * (`_GOOD_KEYWORDS`, `_BAD_KEYWORDS`, `_has_hebrew`, `_candidate_score`,
 * `pick_best_candidate`).
 *
 * This is pure logic: no network, no yt-dlp. The download + cache layer
 * (NewPipeExtractor) is a later task and lives elsewhere in this module.
 */

/** Verbatim port of Python `_GOOD_KEYWORDS` (incl. Hebrew). */
internal val GOOD_KEYWORDS: List<String> = listOf(
    "official audio", "official video", "official", "audio",
    "הרשמי", "הקליפ הרשמי",
)

/**
 * Verbatim port of Python `_BAD_KEYWORDS`.
 * Covers / alternate cuts / foreign-language re-recordings we want to avoid.
 */
internal val BAD_KEYWORDS: List<String> = listOf(
    "live", "remix", "reaction", "cover", "karaoke", "sped up",
    "slowed", "nightcore", "8d", "instrumental", "acoustic",
    "lyrics video", "version", "english", "spanish", "portuguese",
    "french", "francais", "traducao", "mashup",
)

/**
 * Port of Python `_has_hebrew`: true if any char is a Hebrew letter
 * (`א`..`ת`) or in the Hebrew Unicode block (`֐`..`׿`).
 */
fun hasHebrew(text: String): Boolean =
    text.any { ch -> (ch in 'א'..'ת') || (ch in '֐'..'׿') }

/**
 * Port of Python `_candidate_score`. Weights, signs, and order match the
 * reference exactly:
 *  - duration delta: `max(0, 30 - |dur - song.durationS|)` (only when both
 *    `song.durationS` and `cand.durationS` are "truthy" — non-null & non-zero,
 *    matching Python's falsy-`0` semantics)
 *  - `+5` if any good keyword appears in the lowercased title (once, no stacking)
 *  - `-25` per distinct bad keyword present in the lowercased title
 *  - `+10` if the RAW (non-lowercased) title contains Hebrew
 *  - popularity: `min(20, log10(views + 1) * 2)` when views > 0
 */
fun candidateScore(song: Song, cand: Candidate): Double {
    val rawTitle = cand.title
    val title = rawTitle.lowercase()
    var score = 0.0

    // Python: `if song.duration_s and cand.get("duration")` — both must be
    // truthy, so null OR 0.0 skips this term.
    val songDur = song.durationS
    val candDur = cand.durationS
    if (songDur != null && songDur != 0.0 && candDur != null && candDur != 0.0) {
        val diff = abs(candDur - songDur)
        score += max(0.0, 30.0 - diff) // closer duration = higher
    }

    if (GOOD_KEYWORDS.any { it in title }) { // count once, no stacking
        score += 5.0
    }

    for (kw in BAD_KEYWORDS) {
        if (kw in title) {
            score -= 25.0
        }
    }

    // Hebrew-titled uploads are the canonical Israeli originals for our station.
    if (hasHebrew(rawTitle)) {
        score += 10.0
    }

    // Popularity: the original release is almost always the most-viewed.
    val views = cand.viewCount ?: 0L
    if (views > 0) {
        score += min(20.0, log10(views + 1.0) * 2.0)
    }

    return score
}

/**
 * Port of Python `pick_best_candidate`: returns the highest-scoring candidate,
 * or `null` if the list is empty.
 */
fun pickBestCandidate(song: Song, candidates: List<Candidate>): Candidate? {
    if (candidates.isEmpty()) return null
    return candidates.maxByOrNull { candidateScore(song, it) }
}
