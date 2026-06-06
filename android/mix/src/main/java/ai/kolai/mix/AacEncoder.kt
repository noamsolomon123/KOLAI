package ai.kolai.mix

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Standalone AAC-LC block encoder built on Android's MediaCodec + MediaMuxer.
 *
 * Mirrors the role of mixrenderer.write_mp3, except the on-device output is
 * AAC-LC in an MP4 (`.m4a`) container (ffmpeg-kit is retired). Takes the
 * in-memory mix block (mono, 44.1 kHz, samples in [-1, 1] as Float) and writes
 * a compact `.m4a` the player can play and the engine can cache.
 *
 * Deliberately NOT implementing the :station `BlockEncoder` interface: that
 * would create a :mix <-> :station module cycle. The app-wiring layer adapts
 * `AacEncoder.encode(path, audio, Dsp.SR)` to `BlockEncoder.encode(path, audio)`.
 *
 * Pure `android.media.*` — no new module dependencies for main code. Must run
 * on a device / emulator (the unit-test JVM has stub android.media classes).
 */
object AacEncoder {

    private const val TAG = "AacEncoder"

    /** Pull a settled output buffer at most this often (us). */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * Encode a mono Float PCM block to an AAC-LC `.m4a` file at [outPath].
     *
     * @param outPath    absolute path of the `.m4a` to write. Parent dirs are
     *                   created. The file is written atomically (to a `.tmp`
     *                   sibling, then renamed) so a partial/aborted encode never
     *                   leaves a half-written file at [outPath].
     * @param pcm        mono samples in [-1, 1]; values outside are clamped.
     * @param sampleRate output sample rate (Hz). Defaults to [Dsp.SR] (44100).
     * @param bitRate    target AAC bit rate (bits/s). Defaults to 128 kbps.
     *
     * @throws IllegalArgumentException if [pcm] is empty or [sampleRate] <= 0.
     * @throws java.io.IOException      on codec/muxer/filesystem failure (the
     *                                  partial temp file is cleaned up first).
     */
    fun encode(
        outPath: String,
        pcm: FloatArray,
        sampleRate: Int = Dsp.SR,
        bitRate: Int = 128_000,
    ) {
        require(pcm.isNotEmpty()) { "AacEncoder.encode: pcm is empty" }
        require(sampleRate > 0) { "AacEncoder.encode: sampleRate must be > 0, was $sampleRate" }

        val outFile = File(outPath)
        outFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
                throw java.io.IOException("AacEncoder: could not create parent dir ${parent.absolutePath}")
            }
        }
        // Atomic write: encode into a temp sibling, fsync, rename onto outPath.
        val tmpFile = File(outFile.parentFile, outFile.name + ".tmp")
        if (tmpFile.exists()) tmpFile.delete()

        try {
            encodeToFile(tmpFile.absolutePath, pcm, sampleRate, bitRate)

            if (outFile.exists() && !outFile.delete()) {
                throw java.io.IOException("AacEncoder: could not overwrite ${outFile.absolutePath}")
            }
            if (!tmpFile.renameTo(outFile)) {
                throw java.io.IOException(
                    "AacEncoder: atomic rename failed ${tmpFile.absolutePath} -> ${outFile.absolutePath}"
                )
            }
        } catch (t: Throwable) {
            if (tmpFile.exists()) tmpFile.delete()
            throw t
        }
    }

    /** Runs the full MediaCodec encode loop, writing AAC into the MP4 at [path]. */
    private fun encodeToFile(
        path: String,
        pcm: FloatArray,
        sampleRate: Int,
        bitRate: Int,
    ) {
        // 16-bit mono frame = 2 bytes/sample. Keep the codec input modest but
        // comfortably above one AAC frame (1024 samples * 2 B = 2048 B).
        val maxInputSize = 16 * 1024

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            /* channelCount = */ 1,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1

        try {
            codec.configure(format, /* surface = */ null, /* crypto = */ null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()

            // Total 16-bit PCM bytes we must feed (2 bytes per mono sample).
            val totalPcmBytes = pcm.size.toLong() * 2L
            var pcmSampleOffset = 0          // next Float sample to encode
            var bytesFed = 0L                // 16-bit bytes already submitted
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // ---- feed input ----------------------------------------------
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf: ByteBuffer = codec.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        inBuf.order(ByteOrder.LITTLE_ENDIAN)

                        val capacityBytes = inBuf.capacity()
                        // Whole 16-bit samples that fit this buffer.
                        val maxSamplesThisBuf = capacityBytes / 2
                        val remainingSamples = pcm.size - pcmSampleOffset
                        val samplesThisBuf = minOf(maxSamplesThisBuf, remainingSamples)

                        var s = 0
                        while (s < samplesThisBuf) {
                            val v = pcm[pcmSampleOffset + s]
                            val clamped = if (v > 1f) 1f else if (v < -1f) -1f else v
                            inBuf.putShort((clamped * 32767f).toInt().toShort())
                            s++
                        }
                        pcmSampleOffset += samplesThisBuf
                        val bytesThisBuf = samplesThisBuf * 2

                        // Presentation time of this chunk's FIRST sample.
                        // 1 sample = 1e6 / sampleRate microseconds.
                        val ptsUs = bytesToUs(bytesFed, sampleRate)
                        bytesFed += bytesThisBuf

                        val eos = pcmSampleOffset >= pcm.size
                        if (eos) inputDone = true

                        val flags = if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(inIndex, /* offset = */ 0, bytesThisBuf, ptsUs, flags)
                    }
                    // inIndex < 0 (try-again-later): just loop and also drain output.
                }

                // ---- drain output --------------------------------------------
                var drain = true
                while (drain) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            drain = false
                        }

                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AacEncoder: format changed twice" }
                            val outFormat = codec.outputFormat
                            trackIndex = muxer!!.addTrack(outFormat)
                            muxer.start()
                            muxerStarted = true
                        }

                        outIndex >= 0 -> {
                            val encoded: ByteBuffer = codec.getOutputBuffer(outIndex)!!

                            // The codec config blob (CSD) is delivered with this
                            // flag; MediaMuxer already took it from the output
                            // format, so we must NOT write it as a sample.
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }

                            if (bufferInfo.size > 0) {
                                check(muxerStarted) { "AacEncoder: got sample before INFO_OUTPUT_FORMAT_CHANGED" }
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer!!.writeSampleData(trackIndex, encoded, bufferInfo)
                            }

                            codec.releaseOutputBuffer(outIndex, /* render = */ false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                                drain = false
                            }
                        }
                    }
                }
            }

            android.util.Log.i(
                TAG,
                "encoded ${pcm.size} samples (${totalPcmBytes} PCM bytes) @ ${sampleRate}Hz, ${bitRate}bps -> $path",
            )
        } catch (e: Exception) {
            throw java.io.IOException("AacEncoder: encode failed for $path: ${e.message}", e)
        } finally {
            // Stop/release in reverse order; swallow secondary failures so the
            // primary exception (if any) propagates cleanly.
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            try {
                codec.release()
            } catch (_: Exception) {
            }
            muxer?.let {
                try {
                    if (muxerStarted) it.stop()
                } catch (_: Exception) {
                }
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Microsecond timestamp for the sample that begins at [byteOffset] of 16-bit mono PCM. */
    private fun bytesToUs(byteOffset: Long, sampleRate: Int): Long {
        val sampleIndex = byteOffset / 2L
        return sampleIndex * 1_000_000L / sampleRate.toLong()
    }
}