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
     * Decode the audio in [path] to mono [targetSr] Float PCM in [-1, 1].
     *
     * Pipeline: MediaExtractor selects the first audio track -> MediaCodec
     * decodes to 16-bit interleaved PCM -> [downmixToMono] -> [resampleLinear].
     *
     * @param path     absolute filesystem path to the audio file.
     * @param targetSr desired output sample rate (Hz); default 44100.
     * @return mono FloatArray sampled at [targetSr], values in [-1, 1].
     * @throws IllegalStateException if [path] has no decodable audio track or
     *   decoding fails. (Callers / BlockRenderer skip such files.)
     */
    fun decodeToPcm(path: String, targetSr: Int = 44_100): FloatArray {
        require(targetSr > 0) { "targetSr must be > 0, was $targetSr" }
        val (interleaved, channels, decodedSr) = decodeWithSampleRate(path)
        val mono = downmixToMono(interleaved, channels)
        val out = resampleLinear(mono, decodedSr, targetSr)
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

    internal fun decodeWithSampleRate(path: String): DecodedPcm {
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
            // Grow a Float buffer as we go; pre-size loosely from duration if known.
            val initialCapacity = estimateSampleCapacity(inputFormat, channels)
            var collected = FloatArray(initialCapacity)
            var collectedLen = 0

            var inputDone = false
            var outputDone = false

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
                        Log.i(TAG, "output format changed: channels=$channels sampleRate=$sampleRate")
                    }

                    outIndex >= 0 -> {
                        val outBuf: ByteBuffer? = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)

                            // 16-bit PCM: 2 bytes per sample.
                            val shortCount = bufferInfo.size / 2
                            if (collectedLen + shortCount > collected.size) {
                                collected = growTo(collected, collectedLen + shortCount)
                            }
                            val shorts = outBuf.asShortBuffer()
                            var k = 0
                            while (k < shortCount) {
                                collected[collectedLen + k] = shorts.get(k) / 32768.0f
                                k++
                            }
                            collectedLen += shortCount
                        }
                        codec.releaseOutputBuffer(outIndex, /* render = */ false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
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

    /** Grow [arr] to at least [minCapacity], doubling to amortise reallocations. */
    private fun growTo(arr: FloatArray, minCapacity: Int): FloatArray {
        var newCap = if (arr.isEmpty()) 1 else arr.size
        while (newCap < minCapacity) newCap = newCap shl 1
        return arr.copyOf(newCap)
    }
}