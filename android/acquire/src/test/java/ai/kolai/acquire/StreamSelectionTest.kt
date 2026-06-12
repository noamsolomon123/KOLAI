package ai.kolai.acquire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADAPTIVE-AUDIO selection unit tests (pure JVM, no NewPipe/network). Proves the
 * audio-fidelity ladder: adaptive audio-only wins whenever the PoToken unlocked a
 * non-empty audio list; otherwise the muxed itag-18 floor is used; both empty is
 * the full wall (null).
 */
class StreamSelectionTest {

    private fun audio(bitrate: Int, ext: String = "m4a", url: String = "a$bitrate") =
        StreamSelection.Option(url = url, ext = ext, bitrate = bitrate)

    private fun muxed(bitrate: Int, ext: String = "mp4", url: String = "m$bitrate") =
        StreamSelection.Option(url = url, ext = ext, bitrate = bitrate)

    @Test
    fun adaptiveAudioPresent_picksHighestBitrateAudioOnly() {
        // Simulates a valid PoToken: YouTube returned adaptive audio formats.
        val choice = StreamSelection.chooseStream(
            audioOnly = listOf(audio(128_000), audio(160_000), audio(96_000)),
            muxed = listOf(muxed(500_000)),
        )!!
        assertTrue("must prefer audio-only when present", choice.audioOnly)
        assertEquals("a160000", choice.url)
        assertEquals("m4a", choice.ext)
    }

    @Test
    fun noAdaptiveAudio_fallsBackToHighestMuxed() {
        // Simulates the PoToken wall: empty adaptive audio, only muxed itag-18.
        val choice = StreamSelection.chooseStream(
            audioOnly = emptyList(),
            muxed = listOf(muxed(300_000), muxed(700_000)),
        )!!
        assertFalse("muxed fallback is not audio-only", choice.audioOnly)
        assertEquals("m700000", choice.url)
        assertEquals("mp4", choice.ext)
    }

    @Test
    fun bothEmpty_returnsNull_theFullWall() {
        assertNull(StreamSelection.chooseStream(emptyList(), emptyList()))
    }

    @Test
    fun audioPreferredEvenWhenMuxedHasHigherBitrate() {
        // A 128 kbps audio-only stream is preferred over a 500 kbps muxed stream:
        // the muxed bitrate includes video, its AUDIO track is the lower-quality one.
        val choice = StreamSelection.chooseStream(
            audioOnly = listOf(audio(128_000)),
            muxed = listOf(muxed(500_000)),
        )!!
        assertTrue(choice.audioOnly)
        assertEquals("a128000", choice.url)
    }

    @Test
    fun blankExtensions_defaultPerKind() {
        val a = StreamSelection.chooseStream(listOf(audio(128_000, ext = "")), emptyList())!!
        assertEquals("m4a", a.ext)
        val m = StreamSelection.chooseStream(emptyList(), listOf(muxed(300_000, ext = "")))!!
        assertEquals("mp4", m.ext)
    }

    @Test
    fun muxedWithNonPositiveBitrates_stillReturnsAStream() {
        val choice = StreamSelection.chooseStream(
            audioOnly = emptyList(),
            muxed = listOf(muxed(0, url = "only")),
        )!!
        assertEquals("only", choice.url)
        assertFalse(choice.audioOnly)
    }
}