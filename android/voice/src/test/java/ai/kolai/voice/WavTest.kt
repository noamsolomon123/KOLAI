package ai.kolai.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Verifies the 44-byte canonical WAV header produced by [pcmToWav], ported 1:1
 * from backend/radioai/voice.py `pcm_to_wav` (16-bit mono PCM, little-endian).
 */
class WavTest {

    private fun le32(b: ByteArray, off: Int): Int =
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun le16(b: ByteArray, off: Int): Int =
        ByteBuffer.wrap(b, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        String(b, off, len, Charsets.US_ASCII)

    @Test
    fun header_and_total_length_for_small_pcm() {
        // 4 bytes of PCM = 2 frames (16-bit mono).
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val sampleRate = 24000
        val wav = pcmToWav(pcm, sampleRate)

        // total length = 44-byte header + pcm
        assertEquals(44 + pcm.size, wav.size)

        // RIFF chunk
        assertEquals("RIFF", ascii(wav, 0, 4))
        // ChunkSize = 36 + dataSize
        assertEquals(36 + pcm.size, le32(wav, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))

        // fmt subchunk
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals(16, le32(wav, 16))           // Subchunk1Size (PCM)
        assertEquals(1, le16(wav, 20))            // AudioFormat = PCM
        assertEquals(1, le16(wav, 22))            // NumChannels = mono
        assertEquals(sampleRate, le32(wav, 24))   // SampleRate
        // ByteRate = sampleRate * channels * bytesPerSample
        assertEquals(sampleRate * 1 * 2, le32(wav, 28))
        // BlockAlign = channels * bytesPerSample
        assertEquals(2, le16(wav, 32))
        assertEquals(16, le16(wav, 34))           // BitsPerSample

        // data subchunk
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(pcm.size, le32(wav, 40))     // Subchunk2Size

        // payload follows the header unchanged
        val payload = wav.copyOfRange(44, wav.size)
        assertArrayEquals(pcm, payload)
    }

    @Test
    fun honors_non_default_sample_rate() {
        val pcm = byteArrayOf(0x00, 0x00)
        val wav = pcmToWav(pcm, 48000)
        assertEquals(48000, le32(wav, 24))
        assertEquals(48000 * 2, le32(wav, 28)) // byte-rate tracks sample rate
        assertEquals(44 + pcm.size, wav.size)
    }

    @Test
    fun empty_pcm_is_bare_header() {
        val wav = pcmToWav(ByteArray(0))
        assertEquals(44, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals(36, le32(wav, 4))
        assertEquals(0, le32(wav, 40))
    }
}
