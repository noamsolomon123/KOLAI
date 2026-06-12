package ai.kolai.mix

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Pure-DSP FloatArray math ported 1:1 from backend/radioai/mixrenderer.py.
 *
 * All audio is in-memory PCM: mono, 44.1 kHz, samples in [-1, 1] as Float.
 * No Android / I/O dependencies live here — MediaCodec decode (load_mono),
 * the AAC encoder (write_mp3), and the deferred band-split / bass-swap /
 * time-stretch helpers are intentionally NOT ported (they belong to later
 * tasks or are post-MVP).
 *
 * Numpy parity notes:
 *  - np.linspace(0, 1, n) == i / (n - 1) for i in [0, n).
 *  - Equal-power ramps use float32 trig exactly as numpy does.
 *  - int(x) truncates toward zero; all sample-index conversions here use
 *    non-negative inputs, so Float.toInt() matches.
 *  - Python 3 round() is round-half-to-even; we mirror it with Math.rint.
 *  - dB -> linear gain is 10^(dB/20).
 */
object Dsp {

    /** Working sample rate (Hz). Mirrors mixrenderer.SR. */
    const val SR: Int = 44100

    /**
     * Clip every sample to [-1, 1]. Equivalent to np.clip(audio, -1, 1) used
     * throughout mixrenderer. (mixrenderer performs no peak-normalisation, so
     * this helper is just the clip.) Returns a new array; input is untouched.
     */
    fun peakNormalizeClip(audio: FloatArray): FloatArray {
        val out = FloatArray(audio.size)
        for (i in audio.indices) {
            val v = audio[i]
            out[i] = when {
                v > 1.0f -> 1.0f
                v < -1.0f -> -1.0f
                else -> v
            }
        }
        return out
    }

    /**
     * Strip leading/trailing near-silence so speech starts immediately.
     * Ports trim_silence: returns audio[first..last] inclusive where
     * |sample| > threshold; unchanged if everything is sub-threshold.
     */
    fun trimSilence(audio: FloatArray, threshold: Float = 0.01f): FloatArray {
        var first = -1
        var last = -1
        for (i in audio.indices) {
            if (kotlin.math.abs(audio[i]) > threshold) {
                if (first == -1) first = i
                last = i
            }
        }
        if (first == -1) return audio
        return audio.copyOfRange(first, last + 1)
    }

    /**
     * Cosine equal-power crossfade. Ports equal_power_crossfade.
     * Output length is len(a) + len(b) - n where n is the clamped overlap.
     * head = a[:-n]; mixed = a[-n:]*cos(t*pi/2) + b[:n]*cos((1-t)*pi/2);
     * tail = b[n:]; result clipped to [-1, 1].
     */
    fun equalPowerCrossfade(a: FloatArray, b: FloatArray, overlapS: Float): FloatArray {
        var n = (overlapS * SR).toInt()
        n = minOf(n, a.size, b.size)
        if (n <= 0) {
            return peakNormalizeClip(a + b)
        }

        val headLen = a.size - n
        val tailLen = b.size - n
        val out = FloatArray(headLen + n + tailLen)

        // head = a[:-n]
        for (i in 0 until headLen) {
            out[i] = a[i]
        }

        // mixed = a[-n:] * fade_out + b[:n] * fade_in
        val halfPi = (PI / 2.0).toFloat()
        for (i in 0 until n) {
            val t = if (n == 1) 0.0f else i.toFloat() / (n - 1).toFloat() // linspace(0,1,n)
            val fadeOut = cos(t * halfPi)
            val fadeIn = cos((1.0f - t) * halfPi)
            out[headLen + i] = a[headLen + i] * fadeOut + b[i] * fadeIn
        }

        // tail = b[n:]
        for (i in 0 until tailLen) {
            out[headLen + n + i] = b[n + i]
        }

        return peakNormalizeClip(out)
    }

    /**
     * Duck the music bed under a voice clip. Ports duck.
     * gain = 10^(attenuationDb/20). Over [start, start+ramp) the gain ramps
     * linearly from 1.0 down to `gain`; [start+ramp, end) is steady `gain`;
     * the voice is then overlaid onto [start, end); result clipped to [-1, 1].
     * Operates on a copy — `music` is not mutated.
     */
    fun duck(
        music: FloatArray,
        voice: FloatArray,
        startS: Float,
        attenuationDb: Float = -7.0f,
        rampS: Float = 0.3f,
    ): FloatArray {
        val out = music.copyOf()
        val start = (startS * SR).toInt()
        val end = minOf(out.size, start + voice.size)
        val gain = Math.pow(10.0, attenuationDb / 20.0).toFloat()
        val ramp = (rampS * SR).toInt()

        // ramp down: for i in [start, min(start+ramp, end))
        val rampEnd = minOf(start + ramp, end)
        val rampDen = maxOf(1, ramp)
        var i = start
        while (i < rampEnd) {
            val f = (i - start).toFloat() / rampDen.toFloat()
            out[i] *= (1.0f - f) + f * gain
            i++
        }

        // steady duck: out[min(start+ramp, end):end] *= gain
        var j = rampEnd
        while (j < end) {
            out[j] *= gain
            j++
        }

        // overlay voice: out[start:end] += voice[:vlen]
        val vlen = end - start
        var k = 0
        while (k < vlen) {
            out[start + k] += voice[k]
            k++
        }

        return peakNormalizeClip(out)
    }

    /**
     * Trim leading audio so the track starts on its first detected beat.
     * Ports start_on_beat. No beats, or a first beat <= 0 or > maxSkipS,
     * returns the audio unchanged. beatTimes are seconds (List<Double>,
     * matching core's TrackAnalysis.beatTimes).
     */
    fun startOnBeat(
        audio: FloatArray,
        beatTimes: List<Double>,
        sr: Int = SR,
        maxSkipS: Float = 4.0f,
    ): FloatArray {
        if (beatTimes.isEmpty()) return audio
        val first = beatTimes[0]
        if (first <= 0.0 || first > maxSkipS) return audio
        val start = (first * sr).toInt()
        return if (start < audio.size) audio.copyOfRange(start, audio.size) else audio
    }

    // ------------------------------------------------------------- loudness

    /**
     * Soft-knee safety limiter. Samples whose |value| <= [threshold] pass
     * BIT-EXACT (identity), so in-range audio is never recolored. Above the
     * threshold the overshoot is mapped through tanh, asymptotically
     * approaching threshold + (1 - threshold) == 1.0 (float rounding may land
     * exactly ON 1.0 for huge overshoots, never beyond) - no hard-clip
     * crackle. The mapping is continuous in value AND slope at
     * the knee (tanh(0) == 0, tanh'(0) == 1), monotonic, and symmetric.
     * Returns a new array; input untouched.
     */
    fun softClip(audio: FloatArray, threshold: Float = 0.95f): FloatArray {
        if (threshold >= 1.0f) return peakNormalizeClip(audio)
        val knee = 1.0f - threshold
        val out = FloatArray(audio.size)
        for (i in audio.indices) {
            val v = audio[i]
            val a = abs(v)
            out[i] = if (a <= threshold) {
                v
            } else {
                val shaped = threshold + knee * tanh(((a - threshold) / knee).toDouble()).toFloat()
                if (v > 0.0f) shaped else -shaped
            }
        }
        return out
    }

    /**
     * RMS loudness normalization. YouTube-sourced masters differ by many dB;
     * real radio is loudness-consistent, so every clip is gained toward
     * [targetRms].
     *
     * RMS is measured over the MIDDLE 60% of the clip (the first/last 20% are
     * skipped) so quiet intros/outros don't skew the level of the song body.
     * gain = clamp(targetRms / rms, 1/maxGain, maxGain); the gained signal
     * then runs through the [softClip] soft-knee safety so boosted peaks can
     * never hard-clip. All-silence input (rms ~ 0) returns an unmodified copy
     * (gain 1 - no NaN / Inf). Returns a new array; input untouched.
     */
    fun normalizeLoudness(
        audio: FloatArray,
        targetRms: Float = 0.08f,
        maxGain: Float = 4.0f,
    ): FloatArray {
        if (audio.isEmpty()) return FloatArray(0)
        val start = (audio.size * 0.2f).toInt()
        val end = maxOf(start + 1, (audio.size * 0.8f).toInt()).coerceAtMost(audio.size)
        var sumSq = 0.0
        for (i in start until end) {
            val v = audio[i].toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / (end - start))
        if (rms < 1e-8) return audio.copyOf()
        val gain = (targetRms / rms).toFloat().coerceIn(1.0f / maxGain, maxGain)
        val out = FloatArray(audio.size) { audio[it] * gain }
        return softClip(out)
    }

    /**
     * ~10 ms linear micro-fades at BOTH edges so decoded-AAC segment
     * boundaries never click: the very first and last samples land exactly at
     * zero. The fade window clamps to half the clip so in/out never overlap;
     * a fade of <= 0 samples is a plain copy. Returns a new array.
     */
    fun microFadeEdges(audio: FloatArray, fadeS: Float = 0.010f, sr: Int = SR): FloatArray {
        val out = audio.copyOf()
        val n = minOf((fadeS * sr).toInt(), out.size / 2)
        if (n <= 0) return out
        for (i in 0 until n) {
            val g = i.toFloat() / n
            out[i] *= g
            out[out.size - 1 - i] *= g
        }
        return out
    }
    /**
     * Round an overlap length to a whole number of beats (min 1) at `bpm`.
     * Ports snap_overlap_to_beats. Non-positive bpm returns overlap unchanged.
     */
    fun snapOverlapToBeats(overlapS: Float, bpm: Float): Float {
        if (bpm <= 0.0f) return overlapS
        val beat = 60.0 / bpm
        // Python 3 round() is round-half-to-even -> Math.rint.
        val n = maxOf(1L, Math.rint(overlapS / beat).toLong())
        return (n * beat).toFloat()
    }
}
