package ai.kolai.analyze

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