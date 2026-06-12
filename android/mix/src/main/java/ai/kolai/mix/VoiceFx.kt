package ai.kolai.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Broadcast voice-processing chain for the DJ/TTS clips: pure Float math, no
 * Android dependencies, JVM-unit-testable (see VoiceFxTest).
 *
 * Composition order in [voiceBroadcastChain]:
 *  1. [Biquad.highPass] ~90 Hz       — kills rumble / TTS DC wander.
 *  2. [Biquad.highShelf] +2.5 dB @ 3.5 kHz — presence / on-air "air".
 *  3. [compress] -18 dBFS, 3:1       — evens syllable-to-syllable level.
 *
 * RESAMPLING NOTE: the 24 kHz TTS -> 44.1 kHz path uses the windowed-sinc
 * `resampleSinc` that lives in :analyze (next to `resampleLinear` in
 * Resample.kt); run it BEFORE this chain so the EQ frequencies are computed at
 * the working rate.
 */

/**
 * One RBJ-cookbook biquad section, direct form II transposed.
 *
 * Stateful: [processSample] streams; [process] resets state first, filters a
 * whole clip, and returns a NEW array (input untouched), so each [process]
 * call is deterministic and bit-reproducible for the same input.
 *
 * Coefficients are computed in Double from the Audio-EQ-Cookbook formulas and
 * stored as Float (the per-sample math is Float, matching the rest of :mix).
 */
class Biquad private constructor(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float,
) {
    private var s1 = 0.0f
    private var s2 = 0.0f

    /** Zero the two delay-line states (start of a fresh clip). */
    fun reset() {
        s1 = 0.0f
        s2 = 0.0f
    }

    /** Filter one sample (direct form II transposed). */
    fun processSample(x: Float): Float {
        val y = b0 * x + s1
        s1 = b1 * x - a1 * y + s2
        s2 = b2 * x - a2 * y
        return y
    }

    /** Reset state, filter the whole clip, return a new array. */
    fun process(x: FloatArray): FloatArray {
        reset()
        val out = FloatArray(x.size)
        for (i in x.indices) {
            out[i] = processSample(x[i])
        }
        return out
    }

    companion object {
        /** Butterworth (maximally flat) quality factor, 1/sqrt(2). */
        const val BUTTERWORTH_Q: Float = 0.70710678f

        /** 2nd-order low-pass at [freqHz] for sample rate [sr], quality [q]. */
        fun lowPass(freqHz: Float, sr: Int, q: Float = BUTTERWORTH_Q): Biquad {
            requireParams(freqHz, sr, q)
            val w0 = 2.0 * PI * freqHz / sr
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                (((1.0 - cosW) / 2.0) / a0).toFloat(),
                ((1.0 - cosW) / a0).toFloat(),
                (((1.0 - cosW) / 2.0) / a0).toFloat(),
                ((-2.0 * cosW) / a0).toFloat(),
                ((1.0 - alpha) / a0).toFloat(),
            )
        }

        /** 2nd-order high-pass at [freqHz] for sample rate [sr], quality [q]. */
        fun highPass(freqHz: Float, sr: Int, q: Float = BUTTERWORTH_Q): Biquad {
            requireParams(freqHz, sr, q)
            val w0 = 2.0 * PI * freqHz / sr
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                (((1.0 + cosW) / 2.0) / a0).toFloat(),
                ((-(1.0 + cosW)) / a0).toFloat(),
                (((1.0 + cosW) / 2.0) / a0).toFloat(),
                ((-2.0 * cosW) / a0).toFloat(),
                ((1.0 - alpha) / a0).toFloat(),
            )
        }

        /**
         * High shelf: [gainDb] of boost (or cut, if negative) above [freqHz].
         * RBJ cookbook with shelf slope S = 1 (the gentlest non-resonant
         * shelf) — at [freqHz] the response sits at gainDb/2.
         */
        fun highShelf(freqHz: Float, sr: Int, gainDb: Float): Biquad {
            requireParams(freqHz, sr, 1.0f)
            require(gainDb.isFinite()) { "gainDb must be finite, was $gainDb" }
            val bigA = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freqHz / sr
            val cosW = cos(w0)
            // S = 1: alpha = sin(w0)/2 * sqrt((A + 1/A)(1/S - 1) + 2)
            val alpha = sin(w0) / 2.0 * sqrt((bigA + 1.0 / bigA) * (1.0 - 1.0) + 2.0)
            val twoSqrtAAlpha = 2.0 * sqrt(bigA) * alpha
            val a0 = (bigA + 1.0) - (bigA - 1.0) * cosW + twoSqrtAAlpha
            return Biquad(
                ((bigA * ((bigA + 1.0) + (bigA - 1.0) * cosW + twoSqrtAAlpha)) / a0).toFloat(),
                ((-2.0 * bigA * ((bigA - 1.0) + (bigA + 1.0) * cosW)) / a0).toFloat(),
                ((bigA * ((bigA + 1.0) + (bigA - 1.0) * cosW - twoSqrtAAlpha)) / a0).toFloat(),
                ((2.0 * ((bigA - 1.0) - (bigA + 1.0) * cosW)) / a0).toFloat(),
                (((bigA + 1.0) - (bigA - 1.0) * cosW - twoSqrtAAlpha) / a0).toFloat(),
            )
        }

        private fun requireParams(freqHz: Float, sr: Int, q: Float) {
            require(sr > 0) { "sr must be > 0, was $sr" }
            require(freqHz > 0.0f && freqHz < sr / 2.0f) {
                "freqHz must be in (0, sr/2), was $freqHz at sr=$sr"
            }
            require(q > 0.0f) { "q must be > 0, was $q" }
        }
    }
}

/**
 * Soft-knee feed-forward compressor with a peak envelope follower.
 *
 * Envelope: one-pole attack/release smoothing of |x| (attack when rising,
 * release when falling). Transfer: below thresholdDb - knee/2 the gain is
 * EXACTLY 1 so sub-threshold audio passes BIT-EXACT; inside the 6 dB knee the
 * gain reduction blends in quadratically (soft transfer, no corner); above the
 * knee the level is reduced by (1 - 1/ratio) dB per dB over threshold.
 *
 * All-silence input passes bit-exact (env -> 0 dB-floors at -120 dBFS which is
 * far below the knee -> unity gain, no NaN / -Inf). Returns a new array.
 */
fun compress(
    x: FloatArray,
    sr: Int,
    thresholdDb: Float = -18.0f,
    ratio: Float = 3.0f,
    attackMs: Float = 5.0f,
    releaseMs: Float = 80.0f,
    kneeDb: Float = 6.0f,
): FloatArray {
    require(sr > 0) { "sr must be > 0, was $sr" }
    require(ratio >= 1.0f) { "ratio must be >= 1, was $ratio" }
    require(attackMs > 0.0f && releaseMs > 0.0f) { "attack/release must be > 0" }
    require(kneeDb >= 0.0f) { "kneeDb must be >= 0, was $kneeDb" }

    val aAtt = exp(-1.0 / (attackMs * 1e-3 * sr))
    val aRel = exp(-1.0 / (releaseMs * 1e-3 * sr))
    val slope = 1.0 / ratio - 1.0 // dB of gain per dB over threshold, <= 0
    val knee = kneeDb.toDouble()

    val out = FloatArray(x.size)
    var env = 0.0
    for (i in x.indices) {
        val a = abs(x[i].toDouble())
        // NaN input: keep env unchanged (NaN > env is false -> release branch
        // with NaN target would poison env, so guard explicitly).
        if (!a.isNaN()) {
            env = if (a > env) {
                aAtt * env + (1.0 - aAtt) * a
            } else {
                aRel * env + (1.0 - aRel) * a
            }
        }
        val lvlDb = 20.0 * log10(maxOf(env, 1e-6)) // floor -120 dBFS
        val over = lvlDb - thresholdDb
        val gainDb = when {
            2.0 * over <= -knee -> 0.0
            2.0 * over < knee -> slope * (over + knee / 2.0).let { it * it } / (2.0 * knee)
            else -> slope * over
        }
        out[i] = if (gainDb == 0.0) {
            x[i] // bit-exact sub-threshold pass-through
        } else {
            x[i] * 10.0.pow(gainDb / 20.0).toFloat()
        }
    }
    return out
}

/**
 * The full broadcast voice chain (see file kdoc): high-pass 90 Hz ->
 * presence high-shelf +2.5 dB @ 3.5 kHz -> soft-knee compressor (defaults
 * -18 dBFS, 3:1, 5/80 ms). Pure function; returns a new array.
 */
fun voiceBroadcastChain(x: FloatArray, sr: Int): FloatArray {
    val rumbleFree = Biquad.highPass(90.0f, sr).process(x)
    val present = Biquad.highShelf(3500.0f, sr, 2.5f).process(rumbleFree)
    return compress(present, sr)
}