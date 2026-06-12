package ai.kolai.analyze

import kotlin.math.sqrt

/**
 * Refine the analyzer's crude outro hint (`max(duration - 8, duration * 0.9)`,
 * see [Analyzer]) against the ACTUAL energy envelope of the decoded audio.
 *
 * YouTube-muxed files frequently carry a silent music-video tail (credits,
 * black frames); the duration-based hint then points into dead air and the
 * crossfade blends into nothing. This scans windowed RMS over the whole track,
 * takes the median NON-SILENT window RMS as the track's reference level, and finds where
 * sustained energy decays below ~20% of it — i.e. the start of the silent
 * tail. The refined value is clamped to `[hintS - 12, duration]` so a freak
 * tail can never yank the outro into the middle of the song.
 *
 * Returns [hintS] UNCHANGED whenever the scan is inconclusive:
 *  - empty / too-short audio, non-positive [sr] or [windowS], non-finite hint;
 *  - the whole track is (near-)silent, or any window RMS is NaN;
 *  - energy runs all the way to the end (no silent tail to refine).
 *
 * @param audio mono PCM in [-1, 1].
 * @param sr sample rate of [audio] in Hz.
 * @param hintS the analyzer's outroStartS hint, seconds.
 * @param windowS RMS window length, seconds (default 0.5 s).
 * @return refined outro start in seconds, or [hintS] when inconclusive.
 */
fun refineOutroStart(audio: FloatArray, sr: Int, hintS: Double, windowS: Float = 0.5f): Double {
    if (sr <= 0 || windowS <= 0.0f || !hintS.isFinite()) return hintS
    val win = (windowS * sr).toInt()
    if (win < 1) return hintS
    // Need at least a handful of windows for a meaningful median.
    val nWin = (audio.size + win - 1) / win // last window may be partial
    if (nWin < 4) return hintS

    // Windowed RMS over the whole track.
    val rms = DoubleArray(nWin)
    for (w in 0 until nWin) {
        val from = w * win
        val to = minOf(audio.size, from + win)
        var sumSq = 0.0
        for (i in from until to) {
            val v = audio[i].toDouble()
            sumSq += v * v
        }
        rms[w] = sqrt(sumSq / (to - from))
    }
    for (v in rms) {
        if (v.isNaN()) return hintS // corrupt decode: inconclusive
    }

    // Reference level = median RMS of the NON-silent windows. Using all
    // windows would let a very long dead tail (or a mostly-silent file) drag
    // the median to ~0 and break the 20% threshold.
    val loud = rms.filter { it >= 1e-5 }.sorted()
    if (loud.isEmpty()) return hintS // whole track ~silent: inconclusive
    val m = loud.size
    val median = if (m % 2 == 1) {
        loud[m / 2]
    } else {
        0.5 * (loud[m / 2 - 1] + loud[m / 2])
    }

    val threshold = 0.2 * median

    // Last window with sustained energy; everything after it is the tail.
    var lastLoud = -1
    for (w in nWin - 1 downTo 0) {
        if (rms[w] >= threshold) {
            lastLoud = w
            break
        }
    }
    if (lastLoud < 0) return hintS // nothing above threshold: inconclusive
    if (lastLoud >= nWin - 1) return hintS // energy runs to the end: keep hint

    // Decay point = end of the last loud window.
    val decayS = ((lastLoud + 1).toLong() * win).toDouble() / sr
    val durationS = audio.size.toDouble() / sr
    var refined = decayS
    val lo = hintS - 12.0
    if (refined < lo) refined = lo
    if (refined > durationS) refined = durationS
    return refined
}