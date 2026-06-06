package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.TrackAnalysis

/**
 * Result + value types for [BlockRenderer], ported from
 * backend/radioai/block_renderer.py.
 *
 * The Python uses an opaque 4-tuple `(song, analysis, audio, path)` for
 * `last_track`/`prev_track`/each entry of `tracks`; we name it [LoadedTrack].
 * Event dicts in `_plan` become the typed [PlanEvent]. The meta dict
 * {"index","duration_s","segments","talk"} becomes [BlockMeta], and each talk
 * dict {"beat","text","start_s","end_s"} becomes [TalkEntry].
 */

/** Python tuple `(song, analysis, audio, path)`. The PCM [audio] is mono 44.1k. */
data class LoadedTrack(
    val song: Song,
    val analysis: TrackAnalysis,
    val audio: FloatArray,
    val path: String,
) {
    // FloatArray breaks data-class equals/hashCode; override so accidental
    // structural comparisons (e.g. in test assertions) behave sensibly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoadedTrack) return false
        return song == other.song && analysis == other.analysis &&
            path == other.path && audio.contentEquals(other.audio)
    }

    override fun hashCode(): Int {
        var result = song.hashCode()
        result = 31 * result + analysis.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + audio.contentHashCode()
        return result
    }
}

/**
 * A planned timeline event. Mirrors the Python event dicts:
 *   {"kind":"song",  "i":idx}
 *   {"kind":"open",  "text":str, "i":0}
 *   {"kind":"break", "text":str, "beat":str, "i":idx}
 * The "banter" kind is deferred (MVP substitutes a normal break); see
 * [BlockRenderer].
 */
data class PlanEvent(
    val kind: String,
    val i: Int,
    val text: String? = null,
    val beat: String? = null,
)

/** Python talk dict {"beat","text","start_s","end_s"}. */
data class TalkEntry(
    val beat: String,
    val text: String,
    val startS: Double,
    val endS: Double,
)

/** Python meta dict {"index","duration_s","segments","talk"}. */
data class BlockMeta(
    val index: Int,
    val durationS: Double,
    val segments: List<Segment>,
    val talk: List<TalkEntry>,
)

/**
 * Python BlockResult: the raw timeline audio, the meta the StationEngine serves,
 * the written block path, and the [LoadedTrack] that bridges into the next block.
 */
data class BlockResult(
    val audio: FloatArray,
    val meta: BlockMeta,
    val path: String,
    val lastTrack: LoadedTrack,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlockResult) return false
        return meta == other.meta && path == other.path &&
            lastTrack == other.lastTrack && audio.contentEquals(other.audio)
    }

    override fun hashCode(): Int {
        var result = meta.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + lastTrack.hashCode()
        result = 31 * result + audio.contentHashCode()
        return result
    }
}