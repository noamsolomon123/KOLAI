package ai.kolai.analyze

/**
 * Tempo octave-normalization guard.
 *
 * Beat trackers frequently report half-time or double-time tempi (e.g. a 140 BPM
 * track read as 70 or 280). Folding the detected tempo into a canonical band
 * keeps later BPM-compatibility scoring from treating an octave-equivalent track
 * as a mismatch.
 *
 * Canonical band: roughly 70..180 BPM. We multiply by 2 while below 70 and
 * divide by 2 while above 180. The bounds are inclusive on the "stop" side
 * (exactly 70 or exactly 180 are left unchanged).
 *
 * Non-positive input cannot be folded (multiplying by 2 would never escape the
 * `< 70` loop), so 0 / negative values are returned unchanged as a defensive
 * guard. In practice the Analyzer rejects non-finite/missing BPM before this.
 */
fun foldBpm(bpm: Double): Double {
    if (bpm <= 0.0 || !bpm.isFinite()) return bpm
    var b = bpm
    while (b < 70.0) b *= 2.0
    while (b > 180.0) b /= 2.0
    return b
}