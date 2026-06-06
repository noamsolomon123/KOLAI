package ai.kolai.station

/**
 * Show-meta segment building, ported 1:1 from backend/radioai/showmeta.py
 * (build_segments). The Python build_segments takes a list of song-marker dicts
 * ({"type","title","artist","start_s"}) and returns the same dicts with an
 * "end_s" filled in (next marker's start_s; the last one gets total_s). We model
 * the input/output as typed data classes mirroring those dict keys.
 *
 * write_show_json (JSON serialization to disk) is NOT ported here; it is a
 * device/serving concern handled elsewhere.
 *
 * Rounding note: Python round(x, 2) is round-half-to-even; mirror with Math.rint
 * (x * 100) / 100, matching the rest of the engine (Dsp).
 */

/** A song marker before end_s is filled. Mirrors the Python input dict
 *  {"type":"song","title","artist","start_s"} (type is implicit/constant). */
data class SongEvent(
    val title: String,
    val artist: String,
    val startS: Double,
)

/** A finished segment. Mirrors the Python output dict
 *  {"type","title","artist","start_s","end_s"} plus a stable ordinal [index]
 *  (the serve layer / UI uses the post-sort position). */
data class Segment(
    val index: Int,
    val title: String,
    val artist: String,
    val startS: Double,
    val endS: Double,
)

/**
 * Python: build_segments. Sort song markers by start_s, then fill end_s with the
 * next marker's start_s (the last marker gets total_s), each rounded to 2 dp.
 */
fun buildSegments(items: List<SongEvent>, totalS: Double): List<Segment> {
    val ordered = items.sortedBy { it.startS }
    val out = ArrayList<Segment>(ordered.size)
    for (idx in ordered.indices) {
        val it = ordered[idx]
        val end = if (idx + 1 < ordered.size) ordered[idx + 1].startS else totalS
        out.add(
            Segment(
                index = idx,
                title = it.title,
                artist = it.artist,
                startS = it.startS,
                endS = round2(end),
            )
        )
    }
    return out
}

/** Python round(x, 2): round-half-to-even to 2 decimal places. */
internal fun round2(x: Double): Double = Math.rint(x * 100.0) / 100.0