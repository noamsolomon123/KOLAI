package ai.kolai.acquire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PoToken -> NewPipe `PoTokenResult` mapping tests (pure JVM). Proves the bridge
 * is total and defensive: a real token maps field-for-field, a null source result
 * stays null, and a THROWING source collapses to null (never propagates) so
 * [NewPipeSource] always falls back to the muxed floor.
 */
class NpePoTokenTest {

    @Test
    fun tokensPresent_mapFieldForField() {
        val result = NewPipePoTokenAdapter.toNpeResult {
            PoTokens(
                visitorData = "VD",
                playerRequestPoToken = "PLAYER",
                streamingDataPoToken = "STREAM",
            )
        }!!
        assertEquals("VD", result.visitorData)
        assertEquals("PLAYER", result.playerRequestPoToken)
        assertEquals("STREAM", result.streamingDataPoToken)
    }

    @Test
    fun nullSourceResult_mapsToNull() {
        assertNull(NewPipePoTokenAdapter.toNpeResult { null })
    }

    @Test
    fun throwingSource_collapsesToNull_floorPreserved() {
        var logged = false
        val result = NewPipePoTokenAdapter.toNpeResult(log = { logged = true }) {
            throw RuntimeException("WebView timeout")
        }
        assertNull(result)
        org.junit.Assert.assertTrue("failure should be logged", logged)
    }

    @Test
    fun nullStreamingToken_allowed() {
        // streamingDataPoToken is @Nullable on the extractor's PoTokenResult.
        val result = NewPipePoTokenAdapter.toNpeResult {
            PoTokens(visitorData = "VD", playerRequestPoToken = "P", streamingDataPoToken = "")
        }!!
        assertEquals("VD", result.visitorData)
        assertEquals("P", result.playerRequestPoToken)
    }

    @Test
    fun nullVisitorData_unusable_mapsToNull() {
        // The extractor requires a non-null visitorData; without one we must fall
        // back rather than hand it an unusable token.
        val result = NewPipePoTokenAdapter.toNpeResult {
            PoTokens(visitorData = null, playerRequestPoToken = "P", streamingDataPoToken = "S")
        }
        assertNull(result)
    }
}