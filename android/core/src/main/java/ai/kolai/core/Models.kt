package ai.kolai.core

/**
 * Core shared data types, ported 1:1 from the Python reference
 * `backend/radioai/models.py`. Field names, types, and defaults match the
 * Python dataclasses. These are plain Kotlin data classes with no framework
 * dependencies — the other engine modules (analyze/mix/station) consume them.
 *
 * Numeric intent: Python `float` is 64-bit, so it maps to Kotlin [Double].
 */

/**
 * Allowed transition types. In Python this is
 * `TransitionType = Literal["talkover", "beatmatch", "crossfade", "cut"]`.
 * Kotlin has no string-literal union, so [Transition.type] is a plain [String];
 * these constants document/centralize the valid values.
 */
object TransitionType {
    const val TALKOVER = "talkover"
    const val BEATMATCH = "beatmatch"
    const val CROSSFADE = "crossfade"
    const val CUT = "cut"

    val ALL: Set<String> = setOf(TALKOVER, BEATMATCH, CROSSFADE, CUT)
}

data class Song(
    val title: String,
    val artist: String,
    /** Expected/reference duration if known. */
    val durationS: Double? = null,
    /** Optional explicit search query override. */
    val query: String? = null,
    /** Listener-taste rank when this pick came from the taste pool (0 = top
     *  track); null for discovery/unknown. Lets the DJ acknowledge a personal
     *  favorite ("taste wink") without re-deriving taste membership. */
    val tasteRank: Int? = null,
)

data class TrackAnalysis(
    val path: String,
    val durationS: Double,
    val bpm: Double,
    /** Beat onset times in seconds. */
    val beatTimes: List<Double>,
    /** e.g. "8A". */
    val keyCamelot: String,
    /** 0..1 normalized loudness. */
    val energy: Double,
    /** End of leading instrumental region. */
    val introEndS: Double,
    /** Start of trailing instrumental region. */
    val outroStartS: Double,
    /** Time the first vocal enters. */
    val vocalOnsetS: Double,
)

data class DJSlot(
    /** Hebrew script. */
    val text: String,
    /** Voiced audio file. */
    val audioPath: String,
    val durationS: Double,
)

data class Transition(
    /** One of [TransitionType] values: "talkover" | "beatmatch" | "crossfade" | "cut". */
    val type: String,
    val durationS: Double,
    val djSlot: DJSlot? = null,
)

data class PlanItem(
    val song: Song,
    val analysis: TrackAnalysis,
    /** How we move INTO this song from the previous one. */
    val transitionIn: Transition,
)
