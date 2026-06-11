package ai.kolai.acquire

import ai.kolai.core.Song
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Candidate-scoring logic, originally ported 1:1 from `backend/radioai/fetcher.py`
 * (`_GOOD_KEYWORDS`, `_BAD_KEYWORDS`, `_has_hebrew`, `_candidate_score`,
 * `pick_best_candidate`) and EXTENDED on Android with a title-match term.
 *
 * Why the extension: songs picked by the LLM setlist usually have
 * `durationS == null`, so the duration-closeness term was skipped and the score
 * was dominated by popularity (log10 views). Searching "Avicii Waiting For Love"
 * could therefore select the artist's MOST POPULAR video ("The Nights") over the
 * correct one -- the app showed one song while playing another. The title-match
 * term (+/-45) now dominates the popularity cap (20) so the wrong-but-popular
 * video can never outscore a candidate that actually matches the requested title.
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
 * Port of Python `_BAD_KEYWORDS`, extended with compilation/mix/long-form
 * guards (full albums, "best of", hour loops, Hebrew "מיקס", ...).
 * Covers / alternate cuts / foreign-language re-recordings we want to avoid.
 */
internal val BAD_KEYWORDS: List<String> = listOf(
    "live", "remix", "reaction", "cover", "karaoke", "sped up",
    "slowed", "nightcore", "8d", "instrumental", "acoustic",
    "lyrics video", "version", "english", "spanish", "portuguese",
    "french", "francais", "traducao", "mashup",
    // Compilation / mix / long-form guards (Android addition):
    "full album", "compilation", "playlist", "mix 20", "best of",
    "top 10", "1 hour", "hour loop", "מיקס",
)

/**
 * Port of Python `_has_hebrew`: true if any char is a Hebrew letter
 * (`א`..`ת`) or in the Hebrew Unicode block (`֐`..`׿`).
 */
fun hasHebrew(text: String): Boolean =
    text.any { ch -> (ch in 'א'..'ת') || (ch in '֐'..'׿') }

// ---- title normalization (Android addition; no Python counterpart) --------

/**
 * Strips every char that is not a letter, digit, or whitespace.
 *
 * IMPORTANT: explicit Unicode classes `\p{L}` / `\p{N}` on purpose -- they match
 * Hebrew letters out of the box. NEVER use `(?U)` or
 * `Pattern.UNICODE_CHARACTER_CLASS` here: they crash Android's ICU regex
 * (this exact pitfall already bit this codebase).
 */
private val NON_WORD_CHARS = Regex("[^\\p{L}\\p{N}\\s]")

/**
 * A "feat." / "ft." / "featuring" clause: the marker word plus everything up to
 * a closing paren/bracket, a dash, or end-of-string. Handles all of:
 * "Title (feat. X)", "Title feat. X", "Artist ft. X - Title".
 */
private val FEAT_CLAUSE = Regex("\\b(featuring|feat|ft)\\b\\.?[^)\\]-]*")

private val WHITESPACE = Regex("\\s+")

/**
 * Pure normalizer for title/artist comparison: lowercase, strip feat-clauses,
 * strip punctuation/symbols, collapse whitespace, tokenize.
 */
private fun normalizedTokens(text: String): List<String> =
    text.lowercase()
        .replace(FEAT_CLAUSE, " ")
        .replace(NON_WORD_CHARS, " ")
        .trim()
        .split(WHITESPACE)
        .filter { it.isNotEmpty() }

/**
 * Score one YouTube search candidate for a requested [Song]. Full formula:
 *
 *  - duration closeness: `max(0, 30 - |candDur - song.durationS|)` (only when
 *    both `song.durationS` and `cand.durationS` are "truthy" -- non-null &
 *    non-zero, matching Python's falsy-`0` semantics)
 *  - duration sanity: `-35` if `cand.durationS > 720` (12 min) -- almost
 *    certainly a mix/compilation, never a radio single (applies even when the
 *    requested duration is unknown)
 *  - `+5` if any good keyword appears in the lowercased title (once, no stacking)
 *  - `-25` per distinct bad keyword present in the lowercased title
 *  - `+10` if the RAW (non-lowercased) title contains Hebrew
 *  - TITLE COVERAGE: `+coverage * 45`, where coverage = fraction of the
 *    requested song-title tokens (normalized) present in the candidate title's
 *    token set; additionally `-45` if coverage < 0.5 (a candidate missing half
 *    the requested title words is almost certainly the wrong song)
 *  - artist presence: `+8` if ALL artist tokens appear in the candidate title
 *    (YouTube titles usually contain the artist; absence is weak evidence,
 *    presence is good)
 *  - popularity: `min(20, log10(views + 1) * 2)` when views > 0
 */
fun candidateScore(song: Song, cand: Candidate): Double {
    val rawTitle = cand.title
    val title = rawTitle.lowercase()
    var score = 0.0

    // Python: `if song.duration_s and cand.get("duration")` -- both must be
    // truthy, so null OR 0.0 skips this term.
    val songDur = song.durationS
    val candDur = cand.durationS
    if (songDur != null && songDur != 0.0 && candDur != null && candDur != 0.0) {
        val diff = abs(candDur - songDur)
        score += max(0.0, 30.0 - diff) // closer duration = higher
    }

    // Duration sanity even when the requested duration is unknown: a candidate
    // longer than 12 minutes is almost certainly a mix/compilation.
    if (candDur != null && candDur > 720.0) {
        score -= 35.0
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

    // TITLE MATCH (the fix for the wrong-song bug): the requested title's
    // tokens must actually appear in the candidate title. This term (+/-45)
    // dominates the popularity cap (20), so an artist's most-popular-but-wrong
    // video can never beat a candidate that matches the requested title.
    val candTokens = normalizedTokens(rawTitle).toSet()
    val requestedTokens = normalizedTokens(song.title)
    if (requestedTokens.isNotEmpty()) {
        val matched = requestedTokens.count { it in candTokens }
        val coverage = matched.toDouble() / requestedTokens.size
        score += coverage * 45.0
        if (coverage < 0.5) {
            score -= 45.0
        }
    }

    // Artist presence: presence of ALL artist tokens is good evidence; absence
    // is only weak evidence (some uploads omit the artist), hence a small +8.
    val artistTokens = normalizedTokens(song.artist)
    if (artistTokens.isNotEmpty() && candTokens.containsAll(artistTokens)) {
        score += 8.0
    }

    // Popularity: among MATCHING candidates the original release is almost
    // always the most-viewed. Capped at 20 so it can never beat title match.
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