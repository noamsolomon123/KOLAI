package ai.kolai.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wrap raw 16-bit mono PCM (Gemini TTS output) into WAV container bytes.
 *
 * Ported 1:1 from backend/radioai/voice.py `pcm_to_wav` — a canonical 44-byte
 * little-endian WAV header (RIFF/WAVE/fmt /data, PCM=1, channels=1, 16-bit,
 * the given sample rate, with correct byte-rate/block-align/sizes) followed by
 * the PCM bytes verbatim.
 */
fun pcmToWav(pcm: ByteArray, sampleRate: Int = 24000): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val bytesPerSample = bitsPerSample / 8
    val byteRate = sampleRate * channels * bytesPerSample
    val blockAlign = channels * bytesPerSample
    val dataSize = pcm.size
    val chunkSize = 36 + dataSize // 4 + (8 + 16) + (8 + dataSize)

    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    // RIFF chunk descriptor
    header.put('R'.code.toByte()).put('I'.code.toByte())
        .put('F'.code.toByte()).put('F'.code.toByte())
    header.putInt(chunkSize)
    header.put('W'.code.toByte()).put('A'.code.toByte())
        .put('V'.code.toByte()).put('E'.code.toByte())
    // "fmt " sub-chunk
    header.put('f'.code.toByte()).put('m'.code.toByte())
        .put('t'.code.toByte()).put(' '.code.toByte())
    header.putInt(16)                          // Subchunk1Size for PCM
    header.putShort(1)                         // AudioFormat = PCM
    header.putShort(channels.toShort())        // NumChannels
    header.putInt(sampleRate)                  // SampleRate
    header.putInt(byteRate)                    // ByteRate
    header.putShort(blockAlign.toShort())      // BlockAlign
    header.putShort(bitsPerSample.toShort())   // BitsPerSample
    // "data" sub-chunk
    header.put('d'.code.toByte()).put('a'.code.toByte())
        .put('t'.code.toByte()).put('a'.code.toByte())
    header.putInt(dataSize)                    // Subchunk2Size

    val out = ByteArrayOutputStream(44 + dataSize)
    out.write(header.array())
    out.write(pcm)
    return out.toByteArray()
}
