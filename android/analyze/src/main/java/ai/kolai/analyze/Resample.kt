package ai.kolai.analyze

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Pure, Android-free PCM channel/rate helpers used by [AudioDecoder] to turn an
 * arbitrary decoded clip into KOLAI's canonical analysis/mix format: mono,
 * 44.1 kHz, Float samples in [-1, 1].
 *
 * These functions are deliberately free of any `android.*` dependency so they
 * are JVM-unit-testable in `src/test` without a device (see ResampleTest).
 * [AudioDecoder] composes them after MediaCodec hands back interleaved 16-bit
 * PCM converted to Float.
 */

/**
 * Average interleaved multi-channel PCM down to a single mono channel.
 *
 * @param interleaved channel-interleaved samples: frame f, channel c lives at
 *   index `f * channels + c`. Length must be a multiple of [channels].
 * @param channels number of interleaved channels (>= 1).
 * @return a new mono FloatArray of length `interleaved.size / channels`. When
 *   [channels] == 1 the input is returned UNCHANGED (no copy) as a fast path.
 * @throws IllegalArgumentException if [channels] < 1 or the length is not a
 *   whole number of frames.
 */
fun downmixToMono(interleaved: FloatArray, channels: Int): FloatArray {
    require(channels >= 1) { "channels must be >= 1, was $channels" }
    if (channels == 1) return interleaved
    require(interleaved.size % channels == 0) {
        "interleaved length ${interleaved.size} is not a multiple of channels $channels"
    }

    val frames = interleaved.size / channels
    val mono = FloatArray(frames)
    val inv = 1.0f / channels
    var f = 0
    while (f < frames) {
        val base = f * channels
        var sum = 0.0f
        var c = 0
        while (c < channels) {
            sum += interleaved[base + c]
            c++
        }
        mono[f] = sum * inv
        f++
    }
    return mono
}

/**
 * Resample mono PCM from [srcSr] to [dstSr] using linear interpolation.
 *
 * Output length is `round(mono.size * dstSr / srcSr)`. Endpoints are preserved:
 * `out[0] == mono[0]` and the last output sample maps to the last input sample.
 * When `srcSr == dstSr` the input is returned UNCHANGED (no copy).
 *
 * Linear interpolation is adequate for the MVP and keeps the decode path cheap;
 * this same PCM is also what gets PLAYED, so quality matters a little.
 * TODO post-MVP: replace with a higher-quality polyphase / windowed-sinc
 * resampler to reduce aliasing on large rate ratios.
 *
 * @throws IllegalArgumentException if either rate is <= 0.
 */
fun resampleLinear(mono: FloatArray, srcSr: Int, dstSr: Int): FloatArray {
    require(srcSr > 0) { "srcSr must be > 0, was $srcSr" }
    require(dstSr > 0) { "dstSr must be > 0, was $dstSr" }
    if (srcSr == dstSr) return mono
    if (mono.isEmpty()) return FloatArray(0)
    if (mono.size == 1) return floatArrayOf(mono[0])

    val srcLen = mono.size
    // Round to nearest to avoid systematic length truncation on odd ratios.
    val dstLen = Math.round(srcLen.toLong() * dstSr.toLong() / srcSr.toDouble())
        .toInt()
        .coerceAtLeast(1)
    val out = FloatArray(dstLen)

    if (dstLen == 1) {
        out[0] = mono[0]
        return out
    }

    // Map output index i in [0, dstLen-1] linearly onto input position in
    // [0, srcLen-1] so both endpoints land exactly on input samples.
    val step = (srcLen - 1).toDouble() / (dstLen - 1).toDouble()
    var i = 0
    while (i < dstLen) {
        val srcPos = i * step
        val i0 = srcPos.toInt()
        if (i0 >= srcLen - 1) {
            out[i] = mono[srcLen - 1]
        } else {
            val frac = (srcPos - i0).toFloat()
            out[i] = mono[i0] + (mono[i0 + 1] - mono[i0]) * frac
        }
        i++
    }
    return out
}

/**
 * Resample mono PCM from [fromSr] to [toSr] with a Hann-windowed-sinc kernel —
 * the QUALITY path (vs [resampleLinear], the cheap MVP path which stays the
 * decode-time default; existing callers are untouched).
 *
 * Intended for the broadcast voice chain: 24 kHz TTS -> 44.1 kHz, where linear
 * interpolation leaves audible imaging. The kernel is a [taps]-point sinc
 * (cutoff at 0.945 x the LOWER Nyquist, so it also anti-aliases when
 * downsampling) under a Hann window, evaluated at the exact fractional source
 * position of every output sample and normalized by the kernel sum so DC and
 * passband amplitude are preserved (1 kHz tone holds level within +/-0.5 dB).
 * Edges are handled by clamping (edge-sample replication).
 *
 * Output length is `round(x.size * toSr / fromSr)` — same convention as
 * [resampleLinear]. `fromSr == toSr` returns the input UNCHANGED (no copy).
 *
 * @param taps kernel length in source samples at unity/upsampling ratios
 *   (widened automatically by 1/ratio when downsampling). 32 gives ~ -44 dB
 *   stopband (Hann) which is plenty for voice.
 * @throws IllegalArgumentException if a rate is <= 0 or taps < 4.
 */
fun resampleSinc(x: FloatArray, fromSr: Int, toSr: Int, taps: Int = 32): FloatArray {
    require(fromSr > 0) { "fromSr must be > 0, was $fromSr" }
    require(toSr > 0) { "toSr must be > 0, was $toSr" }
    require(taps >= 4) { "taps must be >= 4, was $taps" }
    if (fromSr == toSr) return x
    if (x.isEmpty()) return FloatArray(0)

    val ratio = toSr.toDouble() / fromSr.toDouble()
    val dstLen = Math.round(x.size.toLong() * toSr.toLong() / fromSr.toDouble())
        .toInt()
        .coerceAtLeast(1)

    // Cutoff relative to the SOURCE Nyquist: 1.0 when upsampling, ratio when
    // downsampling (anti-alias), with a 0.945 guard band below Nyquist.
    val cut = minOf(1.0, ratio) * 0.945
    // Kernel half-width in source samples; widen when downsampling so the
    // narrower cutoff keeps the same number of effective sidelobes.
    val span = ceil((taps / 2) / minOf(1.0, ratio)).toInt().coerceAtLeast(2)

    val out = FloatArray(dstLen)
    val srcStep = fromSr.toDouble() / toSr.toDouble()
    val last = x.size - 1
    var i = 0
    while (i < dstLen) {
        val srcPos = i * srcStep
        val center = floor(srcPos).toInt()
        var acc = 0.0
        var wSum = 0.0
        var j = center - span + 1
        val jEnd = center + span
        while (j <= jEnd) {
            val d = srcPos - j
            val wArg = d / span // Hann window argument in (-1, 1)
            if (wArg > -1.0 && wArg < 1.0) {
                val w = 0.5 * (1.0 + cos(PI * wArg))
                val pcd = PI * cut * d
                val s = if (abs(pcd) < 1e-12) 1.0 else sin(pcd) / pcd
                val k = s * w
                wSum += k
                val xi = if (j < 0) 0 else if (j > last) last else j
                acc += x[xi] * k
            }
            j++
        }
        out[i] = if (wSum > 1e-12) (acc / wSum).toFloat() else 0.0f
        i++
    }
    return out
}