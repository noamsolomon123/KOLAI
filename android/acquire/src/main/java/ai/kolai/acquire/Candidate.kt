package ai.kolai.acquire

/**
 * A single YouTube search result, holding only the fields the scorer reads.
 *
 * Ported 1:1 from the Python reference `backend/radioai/fetcher.py`, where a
 * candidate is a raw yt-dlp `dict`. The scorer (`_candidate_score`) reads:
 *  - `cand["title"]`       -> [title]
 *  - `cand["duration"]`    -> [durationS]  (seconds; Python `float`, may be missing)
 *  - `cand["view_count"]`  -> [viewCount]  (may be missing)
 * and `pick_best_candidate` later reads `cand["id"]` to build the watch URL.
 *
 * Nullable numeric fields mirror Python's missing-key / `None` semantics: when
 * a yt-dlp entry has no duration or view count those keys are absent.
 */
data class Candidate(
    val title: String,
    /** Track length in seconds, if reported by the source. */
    val durationS: Double? = null,
    /** Reported view count, if available. */
    val viewCount: Long? = null,
    /** YouTube video id (or full URL) used later to build the watch URL. */
    val id: String,
)
