package ai.kolai.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * StationIdent - KOLAI's signature sonic open (petiach).
 *
 * Israeli-radio research finding 9 (docs/research/2026-06-11-israeli-radio-culture.md):
 * classic Israeli shows were identified by a signature instrumental opening
 * theme the listener recognized before any words. This is KOLAI's: a short,
 * gentle, fully code-generated ident played once at SESSION START, right
 * before the DJ's opening words.
 *
 * Musical design (deterministic, no assets, no randomness):
 *  - Three-note ascending motif: A4 (440 Hz) -> C#5 (554.37 Hz) -> E5
 *    (659.25 Hz) - an A-major arpeggio, warm and unmistakably "station ident".
 *  - Notes ring into each other (onsets 0 / 0.28 / 0.56 s) like a let-ring
 *    arpeggio, so the tail of the third note IS the sustained A-major chord
 *    that decays toward silence by the end (~2.4 s total).
 *  - Each note = two slightly detuned sines (+/-0.4%) for a soft chorus
 *    shimmer + a quieter octave-down sine for body, all under an exponential
 *    decay envelope (bell/pluck character, nothing percussive or harsh).
 *  - Peak-normalized to ~0.45 so it sits politely before/under the music;
 *    a 10 ms fade-in and a final fade-out guarantee click-free edges.
 */
object StationIdent {

    /** Total ident length in seconds. */
    const val DURATION_S: Float = 2.4f

    /** Target peak after normalization - polite, leaves headroom for the mix. */
    const val PEAK: Float = 0.45f

    private const val FADE_IN_S = 0.010f
    private const val FADE_OUT_S = 0.30f

    /** +/-0.4% detune between the paired sines of each note. */
    private const val DETUNE = 0.004

    /** Exponential decay time constant (seconds) - the "let ring" length. */
    private const val DECAY_TAU_S = 0.9

    /** Note onset (s) -> fundamental frequency (Hz). A-major ascending motif. */
    private val NOTES: List<Pair<Double, Double>> = listOf(
        0.00 to 440.00, // A4
        0.28 to 554.37, // C#5
        0.56 to 659.25, // E5
    )

    /**
     * Render the ident as mono PCM in [-1, 1] at [sr] (defaults to the
     * station-wide [Dsp.SR]). Pure math: two calls return identical arrays.
     */
    fun render(sr: Int = Dsp.SR): FloatArray {
        val n = (DURATION_S * sr).toInt()
        val out = FloatArray(n)
        val twoPiOverSr = 2.0 * PI / sr

        for ((onsetS, freq) in NOTES) {
            val onset = (onsetS * sr).toInt()
            for (i in onset until n) {
                val k = (i - onset).toDouble()
                val env = exp(-(k / sr) / DECAY_TAU_S)
                val phase = twoPiOverSr * k
                // two detuned sines (shimmer) + soft octave-down sine (body)
                val tone =
                    0.50 * sin(phase * freq * (1.0 + DETUNE)) +
                        0.50 * sin(phase * freq * (1.0 - DETUNE)) +
                        0.35 * sin(phase * (freq / 2.0))
                out[i] += (env * tone).toFloat()
            }
        }

        // Peak-normalize to PEAK so the ident never competes with the music.
        var peak = 0.0f
        for (v in out) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak > 0.0f) {
            val g = PEAK / peak
            for (i in out.indices) out[i] *= g
        }

        // 10 ms linear fade-in: no click at the very first samples.
        val fadeIn = (FADE_IN_S * sr).toInt().coerceAtMost(n)
        for (i in 0 until fadeIn) {
            out[i] *= i.toFloat() / fadeIn
        }

        // Sine fade-out over the tail: the exponential envelope alone never
        // reaches exactly zero, so force a click-free landing. Walking back
        // from the end, the gain ramps from 0 (very last sample, exactly
        // silent) up to 1 at the start of the fade window.
        val fadeOut = (FADE_OUT_S * sr).toInt().coerceAtMost(n)
        val halfPi = PI / 2.0
        for (i in 0 until fadeOut) {
            val t = i.toDouble() / (fadeOut - 1) // 0 at the last sample .. 1
            out[n - 1 - i] *= sin(t * halfPi).toFloat()
        }

        return out
    }
}
