package ai.kolai.analyze

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Pure-Kotlin, CPU-cheap, deterministic estimator of when LEAD VOCALS first
 * enter a track -- used so the DJ never talks over a singer.
 *
 * WHY THIS EXISTS
 * ---------------
 * The renderer used to trust Essentia's energy-based `introEndS` to decide
 * where to drop the opening talk-over. That value marks where the *arrangement*
 * fills out, NOT where the voice starts. Hebrew/pop songs that open singing
 * over a sparse intro therefore got talked over. This heuristic finds the
 * vocal entry directly from the decoded mono signal.
 *
 * SAFETY BIAS (read this before tuning anything)
 * ----------------------------------------------
 * The user's complaint is the DJ talking over a singer. So the COSTLY error is
 * a false "there's a long safe intro". Every inconclusive / weird / too-short
 * / NaN case therefore returns a SMALL, conservative onset (<= ~1 s) so the
 * renderer will NOT place talk over the opening. We would rather under-report
 * the safe window than ever over-report it.
 *
 * HEURISTIC
 * ---------
 *  - Frame the signal at ~46 ms with ~23 ms hop (~50% overlap).
 *  - Per frame: total RMS, plus RMS inside a cheap "vocal band" ~250 Hz-4 kHz
 *    obtained from inline one-pole IIRs (HP @250 then LP @4k) -- no FFT, no
 *    :mix Biquad dependency.
 *  - Vocal-presence proxy = (bandRms / totalRms) for frames above an absolute
 *    energy floor; near-silent frames score 0 (you can't sing in silence).
 *    A small spectral-flux-like novelty term (positive frame-to-frame rise in
 *    band energy) nudges the proxy up at true entries.
 *  - Threshold is ADAPTIVE: median proxy over the first ~20 s, scaled up, so it
 *    tracks the master instead of a fixed magic number.
 *  - Onset = first frame where the proxy stays above threshold for >= ~0.4 s
 *    (SUSTAINED rise, not a transient tick).
 *  - IMMEDIATE-VOCALS case: if the proxy is already high across the first
 *    ~0.5 s, the song opens singing -> return ~0 (no instrumental runway).
 */
object VocalOnset {

    // ---- framing -------------------------------------------------------------
    private const val FRAME_S = 0.046       // ~46 ms analysis frame
    private const val HOP_S = 0.023         // ~23 ms hop (~50% overlap)

    // ---- band filter corners -------------------------------------------------
    private const val HP_HZ = 250.0         // below this is bass/kick, not voice
    private const val LP_HZ = 4000.0        // above this is mostly sibilance/cymbals

    // ---- decision thresholds -------------------------------------------------
    private const val ENERGY_FLOOR = 1e-4   // total-RMS floor; below = silence
    private const val SUSTAIN_S = 0.40      // proxy must hold this long to count
    private const val ADAPT_WINDOW_S = 20.0 // adaptive baseline measured over this
    private const val THRESH_FLOOR = 0.45   // absolute band-ratio that reads as
                                            // mid-band (vocal) dominant
    private const val THRESH_MARGIN = 0.20  // proxy must beat the instrumental
                                            // floor by at least this much
    private const val IMMEDIATE_S = 0.50    // "opens singing" probe window
    private const val IMMEDIATE_RATIO = 0.6 // fraction of probe frames that must
                                            // be hot to call it immediate vocals

    // Conservative fallback when we can't analyse confidently. Small on purpose.
    private const val CONSERVATIVE_ONSET_S = 0.5
    /**
     * Estimated lead-vocal entry, seconds. ~0 means vocals from the start (no
     * instrumental runway). Returns a conservative SMALL value (<= ~1 s) when
     * the analysis is uncertain, the clip is too short/odd, or anything is
     * non-finite -- biased so the DJ never talks over a singer.
     *
     * @param audio mono PCM in [-1, 1].
     * @param sr    sample rate in Hz.
     */
    fun estimateOnsetS(audio: FloatArray, sr: Int): Double {
        if (sr <= 0) return CONSERVATIVE_ONSET_S
        val frame = (FRAME_S * sr).toInt()
        val hop = (HOP_S * sr).toInt()
        if (frame < 4 || hop < 1) return CONSERVATIVE_ONSET_S
        // Need a few frames to say anything; a <1 s clip can't establish an
        // instrumental runway -- assume vocals are right there.
        if (audio.size < frame + hop) return CONSERVATIVE_ONSET_S

        val nFrames = 1 + (audio.size - frame) / hop
        if (nFrames < 4) return CONSERVATIVE_ONSET_S

        // Pre-filter the WHOLE signal once with the cheap band-pass, then read
        // framed RMS from both the raw and the filtered copies. One-pole IIRs
        // are stateful across the stream so the band energy is continuous.
        val band = bandPass(audio, sr)

        val proxy = DoubleArray(nFrames)      // vocal-presence score per frame
        val bandEnergy = DoubleArray(nFrames) // for the novelty term
        var anyNaN = false

        for (f in 0 until nFrames) {
            val from = f * hop
            val to = from + frame
            var sumSqRaw = 0.0
            var sumSqBand = 0.0
            for (i in from until to) {
                val r = audio[i].toDouble()
                val b = band[i].toDouble()
                sumSqRaw += r * r
                sumSqBand += b * b
            }
            val rmsRaw = sqrt(sumSqRaw / frame)
            val rmsBand = sqrt(sumSqBand / frame)
            if (rmsRaw.isNaN() || rmsBand.isNaN()) { anyNaN = true; break }
            bandEnergy[f] = rmsBand
            proxy[f] = if (rmsRaw < ENERGY_FLOOR) 0.0 else (rmsBand / rmsRaw)
        }
        if (anyNaN) return CONSERVATIVE_ONSET_S // corrupt decode: stay safe

        // Spectral-flux-like novelty: positive band-energy rise frame-to-frame,
        // normalised by the max so it's a unitless [0,1] bump. Added gently to
        // the ratio proxy so a genuine vocal *entry* (energy jumping up) reads
        // hotter than a steady mid-heavy instrumental bed.
        var maxFlux = 0.0
        val flux = DoubleArray(nFrames)
        for (f in 1 until nFrames) {
            val d = bandEnergy[f] - bandEnergy[f - 1]
            val pos = if (d > 0.0) d else 0.0
            flux[f] = pos
            if (pos > maxFlux) maxFlux = pos
        }
        if (maxFlux > 0.0) {
            for (f in 0 until nFrames) {
                proxy[f] += 0.25 * (flux[f] / maxFlux)
            }
        }

        // Threshold. The band ratio is an ABSOLUTE indicator of vocal-band
        // dominance: a sub-bass instrumental (energy below the 250 Hz high-pass)
        // yields a LOW ratio, a mid-band voice a HIGH one. So the primary gate is
        // the absolute floor THRESH_FLOOR. The adaptive term only RAISES the bar
        // for masters whose instrumental bed already lives in the mid-band: take
        // a LOW percentile of the opening proxy as the "instrumental floor" and
        // require a clear margin above it. We never let the adaptive term pull
        // the threshold BELOW the absolute floor.
        val adaptFrames = minOf(nFrames, maxOf(4, (ADAPT_WINDOW_S / HOP_S).toInt()))
        val floorPct = percentile(proxy, adaptFrames, 0.25) // instrumental-bed level
        if (floorPct.isNaN()) return CONSERVATIVE_ONSET_S
        val threshold = maxOf(THRESH_FLOOR, floorPct + THRESH_MARGIN)

        // IMMEDIATE-VOCALS probe. CRITICAL: this is checked against the ABSOLUTE
        // floor, NOT the adaptive threshold. When a track opens singing there is
        // no instrumental section to set a low baseline -- the whole opening is
        // mid-band hot, so the adaptive floor would itself be high and the song
        // would never "clear" it. We therefore ask only: is the first ~0.5 s
        // already absolutely mid-band dominant? If so, it opens with singing ->
        // return 0 (no safe instrumental runway). This is the exact case the
        // user reported (DJ talking over a singer).
        val probeFrames = maxOf(1, minOf(nFrames, (IMMEDIATE_S / HOP_S).toInt()))
        var hot = 0
        for (f in 0 until probeFrames) if (proxy[f] >= THRESH_FLOOR) hot++
        if (hot >= (probeFrames * IMMEDIATE_RATIO).toInt().coerceAtLeast(1)) {
            return 0.0
        }

        // First SUSTAINED rise: proxy must hold above threshold for >= SUSTAIN_S.
        val sustainFrames = maxOf(1, (SUSTAIN_S / HOP_S).toInt())
        var run = 0
        for (f in 0 until nFrames) {
            if (proxy[f] >= threshold) {
                run++
                if (run >= sustainFrames) {
                    // Onset = start of the sustained run, in seconds.
                    val startFrame = f - sustainFrames + 1
                    val s = startFrame.toDouble() * hop / sr
                    return if (s < 0.0) 0.0 else s
                }
            } else {
                run = 0
            }
        }

        // No sustained vocal-like rise found anywhere. This is the genuinely
        // inconclusive case (e.g. a long instrumental, or a track we can't read
        // confidently). Bias to SAFETY: report a small onset so the renderer
        // does NOT assume a talk-over-safe intro.
        return CONSERVATIVE_ONSET_S
    }
    /**
     * The longest SAFE instrumental window at the start the DJ may talk over:
     * the estimated vocal onset minus a small guard, floored at 0.
     *
     * Integration note: call this on the decoded INCOMING song; if it is
     * < ~5 s, do NOT place the opening/intro talk-over over this song -- talk
     * over the previous song's outro instead, or skip the opener.
     */
    fun safeIntroWindowS(audio: FloatArray, sr: Int, guardS: Double = 0.4): Double {
        val onset = estimateOnsetS(audio, sr)
        val g = if (guardS.isFinite() && guardS > 0.0) guardS else 0.0
        val w = onset - g
        return if (w > 0.0) w else 0.0
    }

    // ---- cheap band-pass (inline one-pole IIRs, no Biquad) -------------------

    /**
     * Cheap vocal-band band-pass: TWO cascaded one-pole high-passes @ HP_HZ
     * (~12 dB/oct) followed by TWO cascaded one-pole low-passes @ LP_HZ. A
     * single one-pole (6 dB/oct) barely attenuates a tone three-quarters of an
     * octave below the corner -- a 150 Hz bass would still leak ~0.5 of its
     * amplitude through a 250 Hz HP and read as "mid-band". Cascading squares
     * that (~0.26) so sub-bass instrumental beds fall clearly below the
     * vocal-band threshold while a 1-2.5 kHz voice passes nearly unchanged.
     * Standard RC one-pole forms; coefficients from cutoff + sample rate.
     * NaN-safe: any non-finite input collapses to 0 for that sample so a
     * corrupt decode can't poison the running IIR state forever.
     */
    private fun bandPass(audio: FloatArray, sr: Int): FloatArray {
        val n = audio.size
        val out = FloatArray(n)

        val dt = 1.0 / sr
        // High-pass one-pole: alphaHp = RC / (RC + dt), RC = 1/(2*pi*fc).
        val rcHp = 1.0 / (2.0 * PI * HP_HZ)
        val alphaHp = rcHp / (rcHp + dt)
        // Low-pass one-pole: alphaLp = dt / (RC + dt).
        val rcLp = 1.0 / (2.0 * PI * LP_HZ)
        val alphaLp = dt / (rcLp + dt)

        // Stage 1 HP state.
        var prevIn1 = 0.0; var prevHp1 = 0.0
        // Stage 2 HP state.
        var prevIn2 = 0.0; var prevHp2 = 0.0
        // Two LP states.
        var lp1 = 0.0; var lp2 = 0.0
        for (i in 0 until n) {
            var x = audio[i].toDouble()
            if (!x.isFinite()) x = 0.0
            // HP stage 1: y = alpha * (y[-1] + x - x[-1])
            val hp1 = alphaHp * (prevHp1 + x - prevIn1)
            prevIn1 = x; prevHp1 = hp1
            // HP stage 2 (cascade)
            val hp2 = alphaHp * (prevHp2 + hp1 - prevIn2)
            prevIn2 = hp1; prevHp2 = hp2
            // LP stage 1
            lp1 += alphaLp * (hp2 - lp1)
            // LP stage 2 (cascade)
            lp2 += alphaLp * (lp1 - lp2)
            out[i] = lp2.toFloat()
        }
        return out
    }

    /**
     * The [q]-quantile (q in [0,1]) of the first [count] entries of [values];
     * NaN if count <= 0. Nearest-rank, no interpolation -- precision here is
     * irrelevant to the heuristic. q=0.5 is the median; q=0.25 the lower
     * quartile (used as the "instrumental bed" level).
     */
    private fun percentile(values: DoubleArray, count: Int, q: Double): Double {
        if (count <= 0) return Double.NaN
        val n = minOf(count, values.size)
        if (n <= 0) return Double.NaN
        val slice = values.copyOfRange(0, n)
        slice.sort()
        var idx = (q * (n - 1)).toInt()
        if (idx < 0) idx = 0
        if (idx > n - 1) idx = n - 1
        return slice[idx]
    }
}