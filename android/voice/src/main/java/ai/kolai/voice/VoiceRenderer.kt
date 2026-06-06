package ai.kolai.voice

import ai.kolai.core.DJSlot
import java.io.File
import java.security.MessageDigest

/**
 * Renders a Hebrew DJ line into a voiced WAV file on disk and returns a
 * [DJSlot] describing it.
 *
 * Ported from backend/radioai/voice.py `VoiceRenderer.render`:
 *  - synthesize WAV bytes via the injected [synth],
 *  - name the file `dj_<sha1(text)[:16]>.wav` inside [outDir] (created if absent),
 *  - compute duration by parsing the WAV header (PURE — no Android media APIs),
 *  - return `DJSlot(text, audioPath, durationS)`.
 *
 * sha1 file naming doubles as a content cache: if the target file already exists
 * we reuse it and skip re-synthesis (mirrors the Python sha1 caching intent).
 */
class VoiceRenderer(
    private val synth: GeminiTtsSynth,
    private val outDir: File,
) {
    /**
     * Synthesize (or reuse the cached WAV for) [text] and return its [DJSlot].
     * [voiceOverride] forwards to the synth for per-call voice selection.
     */
    suspend fun render(text: String, voiceOverride: String? = null): DJSlot {
        outDir.mkdirs()
        val outFile = File(outDir, "dj_${sha1Hex(text).substring(0, 16)}.wav")

        if (!outFile.exists()) {
            val bytes = synth.synth(text, voiceOverride)
            outFile.writeBytes(bytes)
        }

        val durationS = wavDurationSeconds(outFile.readBytes())
        return DJSlot(text = text, audioPath = outFile.absolutePath, durationS = durationS)
    }

    // TODO post-MVP: renderBanter(turns, voiceA, voiceB) two-voice stitch

    private fun sha1Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Compute the audio duration (seconds) of a canonical little-endian PCM WAV by
 * scanning its chunks: read sampleRate/channels/bitsPerSample from "fmt " and the
 * "data" chunk size, then
 *   durationS = dataBytes / (sampleRate * channels * bitsPerSample/8).
 * Pure JVM (no Android MediaExtractor) so it runs in plain unit tests too.
 */
internal fun wavDurationSeconds(wav: ByteArray): Double {
    require(wav.size >= 12) { "WAV too small: ${wav.size} bytes" }
    require(ascii(wav, 0, 4) == "RIFF") { "not a RIFF file" }
    require(ascii(wav, 8, 4) == "WAVE") { "not a WAVE file" }

    var sampleRate = 0
    var channels = 0
    var bitsPerSample = 0
    var dataBytes = -1L

    var pos = 12 // skip RIFF(4) + size(4) + WAVE(4)
    while (pos + 8 <= wav.size) {
        val chunkId = ascii(wav, pos, 4)
        val chunkSize = leU32(wav, pos + 4)
        val bodyStart = pos + 8
        when (chunkId) {
            "fmt " -> {
                channels = leU16(wav, bodyStart + 2)
                sampleRate = leU32(wav, bodyStart + 4).toInt()
                bitsPerSample = leU16(wav, bodyStart + 14)
            }
            "data" -> {
                dataBytes = chunkSize
            }
        }
        // chunks are word-aligned: bodies of odd size are padded by one byte.
        pos = bodyStart + chunkSize.toInt() + (chunkSize.toInt() and 1)
    }

    require(sampleRate > 0 && channels > 0 && bitsPerSample > 0) {
        "missing/invalid fmt chunk (sampleRate=$sampleRate channels=$channels bits=$bitsPerSample)"
    }
    require(dataBytes >= 0) { "missing data chunk" }

    val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
    require(bytesPerSecond > 0) { "invalid byte rate" }
    return dataBytes.toDouble() / bytesPerSecond.toDouble()
}

private fun ascii(b: ByteArray, off: Int, len: Int): String =
    String(b, off, len, Charsets.US_ASCII)

private fun leU16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

private fun leU32(b: ByteArray, off: Int): Long =
    (b[off].toLong() and 0xFF) or
        ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or
        ((b[off + 3].toLong() and 0xFF) shl 24)