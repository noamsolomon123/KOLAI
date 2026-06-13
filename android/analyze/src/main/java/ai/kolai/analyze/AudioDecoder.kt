package ai.kolai.analyze

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an on-disk audio file (any container/codec the platform supports:
 * m4a/aac, mp3, opus/webm, etc., at any sample rate / channel count) into
 * KOLAI's canonical PCM: mono, [targetSr] (44.1 kHz by default), Float samples
 * in [-1, 1].
 *
 * Built purely on Android's [MediaExtractor] + [MediaCodec] (no ffmpeg, no extra
 * module deps). This is the inverse of [ai.kolai.mix.AacEncoder] and the
 * `load_mono` counterpart referenced by the ported [ai.kolai.mix.Dsp]. The
 * NewPipe acquire layer's downloaded files feed straight into [decodeToPcm];
 * the BlockRenderer wires `loadFn(path) = decodeToPcm(path)` and
 * `analyzeFn(path) = Analyzer().analyze(decodeToPcm(path), 44100, path)`.
 *
 * Must run on a device/emulator: the unit-test JVM only has stub android.media
 * classes. The pure channel-mix / resample math lives in Resample.kt and IS
 * unit-tested off-device.
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"

    /** Poll the codec at most this often (microseconds). */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * DECODE-LENGTH CAP (OOM safety net, 2026-06-13). Radio songs are < 8 min;
     * anything longer that survives the picker is almost certainly a
     * mix/compilation/DJ-set whose full PCM would blow the heap (a 24-min mono
     * 44.1k FloatArray is ~252 MB - the proven OOM allocation). We decode AT
     * MOST this many seconds of the decoded stream and stop cleanly once the
     * cap is reached. 10 min is comfortably above any real single, so a normal
     * song NEVER hits this path - it is purely a guard.
     */
    const val MAX_DECODE_SECONDS: Int = 420

    /**
     * ANALYZE-decode cap (OOM fix, 2026-06-14). BPM/key are GLOBAL song
     * properties; Essentia's beat/key extractors need only a representative
     * window, not the whole track. analyzeFn RE-decodes the file while loadFn's
     * full-song PCM is still retained, so an over-long track's SECOND ~200 MB
     * decode is exactly what OOM'd the render (analyzeFn -> decodeToPcm ->
     * growTo). Capping analyze to ~2 min keeps that allocation ~42 MB and is
     * still far more beats than the tempo/key estimators need.
     */
    const val ANALYZE_DECODE_SECONDS: Int = 120

    /**
     * The ceiling on the number of INTERLEAVED Float samples we will ever
     * collect, given the decoded [sampleRate], [channels] and the [maxSeconds]
     * cap. Pure (no MediaCodec), so it is unit-tested off-device; the decode
     * loop and the pre-size BOTH derive their bounds from it, so the single
     * worst-case allocation can never exceed maxSeconds*sampleRate*channels
     * samples (= *4 bytes).
     *
     * Returns [Int.MAX_VALUE] (effectively "no cap") when rate/channels are
     * non-positive (unknown at pre-size time) so a missing input format never
     * wrongly truncates - the real cap is applied once the output format reports
     * a sane rate. Clamped to [Int.MAX_VALUE] so the long arithmetic can never
     * overflow the FloatArray index space.
     */
    internal fun cappedSampleCeiling(sampleRate: Int, channels: Int, maxSeconds: Int): Int {
        if (sampleRate <= 0 || channels <= 0 || maxSeconds <= 0) return Int.MAX_VALUE
        val ceil = sampleRate.toLong() * channels.toLong() * maxSeconds.toLong()
        return ceil.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * The number of INTERLEAVED Float samples implied by a [durationUs]
     * (KEY_DURATION) at [sampleRate]/[channels], CLAMPED to the
     * [cappedSampleCeiling]. Used to PRE-SIZE the collect buffer exactly (no
     * growth-by-doubling overshoot: a 130 MB need would otherwise double to
     * 260 MB). Pure; unit-tested. Non-positive duration -> 0 (caller falls back
     * to a modest default + on-demand growth).
     */
    internal fun preSizeSamples(durationUs: Long, sampleRate: Int, channels: Int, maxSeconds: Int): Int {
        if (durationUs <= 0L || sampleRate <= 0 || channels <= 0) return 0
        val frames = durationUs * sampleRate / 1_000_000L
        val samples = frames * channels
        val ceiling = cappedSampleCeiling(sampleRate, channels, maxSeconds).toLong()
        return samples.coerceIn(0L, ceiling).toInt()
    }

    /**
     * Decode the audio in [path] to mono [targetSr] Float PCM in [-1, 1].
     *
     * Pipeline: MediaExtractor selects the first audio track -> MediaCodec
     * decodes to 16-bit interleaved PCM -> [downmixToMono] -> [resampleSinc].
     *
     * RESAMPLE QUALITY (wave 4): the rate conversion uses the windowed-sinc
     * [resampleSinc] (was [resampleLinear]). This is the broadcast-quality
     * path for the 24 kHz Gemini TTS voice WAVs AND for songs decoded at
     * non-44.1k rates - sinc is strictly better than linear in both cases,
     * and same-rate input is returned unchanged (no cost for 44.1k songs).
     *
     * @param path     absolute filesystem path to the audio file.
     * @param targetSr desired output sample rate (Hz); default 44100.
     * @return mono FloatArray sampled at [targetSr], values in [-1, 1].
     * @throws IllegalStateException if [path] has no decodable audio track or
     *   decoding fails. (Callers / BlockRenderer skip such files.)
     */
    fun decodeToPcm(path: String, targetSr: Int = 44_100, maxSeconds: Int = MAX_DECODE_SECONDS): FloatArray {
        require(targetSr > 0) { "targetSr must be > 0, was $targetSr" }
        val (interleaved, channels, decodedSr) = decodeWithSampleRate(path, maxSeconds)
        val mono = downmixToMono(interleaved, channels)
        val out = resampleSinc(mono, decodedSr, targetSr)
        Log.i(
            TAG,
            "decoded $path: frames=${interleaved.size / channels} channels=$channels " +
                "srcSr=$decodedSr -> mono targetSr=$targetSr len=${out.size}",
        )
        return out
    }

    /**
     * Raw decode result: interleaved Float PCM in [-1, 1], its channel count,
     * and its (true, possibly output-format-reported) sample rate.
     *
     * Exposed (internal) so the format-handling / channel & sample-rate plumbing
     * is testable in isolation from the mono/resample composition.
     */
    data class DecodedPcm(
        val interleaved: FloatArray,
        val channels: Int,
        val sampleRate: Int,
    ) {
        // data class auto-generates component1/2/3, equals/hashCode/toString.
        // FloatArray uses identity equality; that is fine here (this type is a
        // transient decode result, never used as a map key or compared).
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedPcm) return false
            return interleaved.contentEquals(other.interleaved) &&
                channels == other.channels &&
                sampleRate == other.sampleRate
        }

        override fun hashCode(): Int {
            var result = interleaved.contentHashCode()
            result = 31 * result + channels
            result = 31 * result + sampleRate
            return result
        }
    }

    internal fun decodeWithSampleRate(path: String, maxSeconds: Int = MAX_DECODE_SECONDS): DecodedPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)

            // ---- find the first audio track ----
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (t in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(t)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = t
                    inputFormat = fmt
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) {
                throw IllegalStateException("no audio track found in $path")
            }
            extractor.selectTrack(trackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("audio track missing MIME in $path")

            // Channel count / sample rate from the INPUT format are our initial
            // guess; the codec may correct them via INFO_OUTPUT_FORMAT_CHANGED.
            var channels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                0
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, /* surface = */ null, /* crypto = */ null, /* flags = */ 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            // PRE-SIZE the collect buffer exactly from KEY_DURATION (clamped to
            // the decode cap) so we avoid the growth-by-doubling OVERSHOOT that
            // could turn a 130 MB need into a 260 MB allocation. When the
            // duration is unknown we start at a modest size and grow on demand
            // (still bounded by the ceiling). The HARD CAP (break) below is the
            // real OOM guard regardless of how the buffer was sized.
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val preSize = preSizeSamples(durationUs, sampleRate, channels, maxSeconds)
            val initialCapacity = if (preSize > 0) preSize else estimateSampleCapacity(inputFormat, channels)
            var collected = FloatArray(initialCapacity)
            var collectedLen = 0
            // INTERLEAVED-sample ceiling for the CURRENT rate/channels. Updated
            // if INFO_OUTPUT_FORMAT_CHANGED reports a different (true) rate, so
            // the cap always tracks the real stream. Int.MAX_VALUE == no cap yet
            // (rate unknown) - the input-format guess almost always sets it.
            var sampleCeiling = cappedSampleCeiling(sampleRate, channels, maxSeconds)

            var inputDone = false
            var outputDone = false
            var capped = false

            while (!outputDone) {
                // ---- feed input from the extractor ----
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf: ByteBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            // End of stream: queue an empty EOS buffer.
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                    // inIndex < 0: try-again-later; fall through to drain output.
                }

                // ---- drain output ----
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // nothing ready yet
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The REAL channel count / sample rate often live here.
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        // The decode cap follows the REAL rate/channels.
                        sampleCeiling = cappedSampleCeiling(sampleRate, channels, maxSeconds)
                        Log.i(TAG, "output format changed: channels=$channels sampleRate=$sampleRate")
                    }

                    outIndex >= 0 -> {
                        val outBuf: ByteBuffer? = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)

                            // 16-bit PCM: 2 bytes per sample.
                            var shortCount = bufferInfo.size / 2
                            // DECODE CAP: never collect past the interleaved
                            // ceiling. If this buffer would cross it, take only
                            // the remainder, mark capped, and stop after copying.
                            if (collectedLen + shortCount > sampleCeiling) {
                                shortCount = (sampleCeiling - collectedLen).coerceAtLeast(0)
                                capped = true
                            }
                            if (shortCount > 0) {
                                if (collectedLen + shortCount > collected.size) {
                                    // growTo is bounded by the ceiling (see growTo).
                                    collected = growTo(collected, collectedLen + shortCount, sampleCeiling)
                                }
                                val shorts = outBuf.asShortBuffer()
                                var k = 0
                                while (k < shortCount) {
                                    collected[collectedLen + k] = shorts.get(k) / 32768.0f
                                    k++
                                }
                                collectedLen += shortCount
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, /* render = */ false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        // Reached the cap: stop the decode loop cleanly here
                        // (the finally block releases the codec + extractor) and
                        // return the capped PCM. This is the worst-case-heap
                        // guard for an over-long mix/compilation.
                        if (capped) {
                            Log.w(
                                TAG,
                                "decode CAPPED at ${maxSeconds}s " +
                                    "(${collectedLen} samples, sr=$sampleRate ch=$channels) for $path - " +
                                    "likely a mix/compilation; returning truncated PCM",
                            )
                            outputDone = true
                        }
                    }
                }
            }

            if (sampleRate <= 0) {
                throw IllegalStateException("could not determine sample rate for $path")
            }
            if (channels < 1) {
                throw IllegalStateException("invalid channel count $channels for $path")
            }

            val exact = if (collectedLen == collected.size) {
                collected
            } else {
                collected.copyOf(collectedLen)
            }
            return DecodedPcm(exact, channels, sampleRate)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("failed to decode $path: ${e.message}", e)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Loose initial Float-buffer size from the track duration (if present) so we
     * usually avoid reallocation. Falls back to a modest default. Always grown
     * on demand, so an under-estimate is harmless.
     */
    private fun estimateSampleCapacity(format: MediaFormat, channels: Int): Int {
        val sr = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            44_100
        }
        val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }
        if (durUs <= 0L) return 44_100 * channels.coerceAtLeast(1) // ~1 s fallback
        val frames = (durUs * sr / 1_000_000L)
        val samples = frames * channels.coerceAtLeast(1)
        // Cap the pre-size at ~10 min of stereo 48k to avoid silly allocations.
        return samples.coerceIn(1L, 48_000L * 2L * 600L).toInt()
    }

    /**
     * Grow [arr] to at least [minCapacity], doubling to amortise reallocations
     * but NEVER past [ceiling] interleaved samples (the decode-cap guard, so a
     * doubling step can't overshoot a 130 MB need into a 260 MB allocation).
     * [minCapacity] is always <= [ceiling] at the call site (the caller clamps
     * the copy count to the ceiling first), so the final capacity is in
     * [minCapacity, ceiling].
     */
    private fun growTo(arr: FloatArray, minCapacity: Int, ceiling: Int): FloatArray {
        var newCap = if (arr.isEmpty()) 1 else arr.size
        while (newCap < minCapacity) {
            // double, but clamp the doubled value at the ceiling so the largest
            // single allocation is bounded by MAX_DECODE_SECONDS worth of PCM.
            val doubled = newCap.toLong() shl 1
            newCap = if (doubled >= ceiling.toLong()) ceiling else doubled.toInt()
        }
        return arr.copyOf(newCap)
    }
}