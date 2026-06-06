package ai.kolai.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM tests for [Analyzer]. The native :dsp lib is NEVER loaded: every test
 * injects a fake `analyzeJson` seam returning a canned string, so nothing here
 * requires the arm64 .so.
 */
class AnalyzerTest {

    /** Build an Analyzer whose JSON producer always returns [json]. */
    private fun analyzerReturning(json: String): Analyzer =
        Analyzer(analyzeJson = { _, _ -> json })

    /** sr=10 so durationS math is exact and easy to read. */
    private val sr = 10
    private val pcm = FloatArray(1000) // 1000 / 10 = 100.0s duration

    private val wellFormed = """
        {
          "bpm": 120.0,
          "beatConfidence": 4.0,
          "energy": 0.42,
          "keyTonic": "C",
          "keyScale": "major",
          "keyStrength": 0.8,
          "sampleRate": 10,
          "beatTimes": [0.5, 1.5, 2.5, 3.5]
        }
    """.trimIndent()

    @Test
    fun wellFormedJson_mapsAllFields() {
        val a = analyzerReturning(wellFormed).analyze(pcm, sr, "song.mp3")

        assertEquals("song.mp3", a.path)
        // durationS derived from PCM length, NOT from JSON.
        assertEquals(100.0, a.durationS, 1e-9)
        assertEquals(120.0, a.bpm, 1e-9) // already in 70..180, unchanged
        assertEquals(0.42, a.energy, 1e-9)
        assertEquals(listOf(0.5, 1.5, 2.5, 3.5), a.beatTimes)
        // C major -> 8B per :core Keys.kt table.
        assertEquals("8B", a.keyCamelot)
    }

    @Test
    fun introOutro_matchAnalyzerPy() {
        val a = analyzerReturning(wellFormed).analyze(pcm, sr)
        // intro_end = min(8.0, duration*0.1) = min(8.0, 10.0) = 8.0
        assertEquals(8.0, a.introEndS, 1e-9)
        // outro_start = max(duration-8.0, duration*0.9) = max(92.0, 90.0) = 92.0
        assertEquals(92.0, a.outroStartS, 1e-9)
    }

    @Test
    fun introOutro_shortTrack_usesPercentBranch() {
        // duration = 50/10 = 5.0s -> intro = min(8.0, 0.5) = 0.5;
        // outro = max(-3.0, 4.5) = 4.5
        val a = analyzerReturning(wellFormed).analyze(FloatArray(50), sr)
        assertEquals(0.5, a.introEndS, 1e-9)
        assertEquals(4.5, a.outroStartS, 1e-9)
    }

    @Test
    fun vocalOnset_picksFirstBeatAboveOneSecond() {
        // beats = 0.5, 1.5, ... -> first > 1.0 is 1.5
        val a = analyzerReturning(wellFormed).analyze(pcm, sr)
        assertEquals(1.5, a.vocalOnsetS, 1e-9)
    }

    @Test
    fun vocalOnset_noBeatAboveOneSecond_isZero() {
        val json = """
            {"bpm":120.0,"beatConfidence":4.0,"energy":0.1,"keyTonic":"A","keyScale":"minor",
             "keyStrength":0.5,"sampleRate":10,"beatTimes":[0.2,0.5,0.9]}
        """.trimIndent()
        val a = analyzerReturning(json).analyze(pcm, sr)
        assertEquals(0.0, a.vocalOnsetS, 1e-9)
    }

    @Test
    fun errorJson_throws() {
        val json = """{"error":"essentia failed: bad input"}"""
        try {
            analyzerReturning(json).analyze(pcm, sr)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("essentia failed") || e.message!!.contains("error"))
        }
    }

    @Test
    fun nullBpm_throws() {
        val json = """
            {"bpm":null,"beatConfidence":4.0,"energy":0.1,"keyTonic":"C","keyScale":"major",
             "keyStrength":0.5,"sampleRate":10,"beatTimes":[]}
        """.trimIndent()
        try {
            analyzerReturning(json).analyze(pcm, sr)
            fail("expected IllegalStateException for null bpm")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun missingBpm_throws() {
        val json = """
            {"beatConfidence":4.0,"energy":0.1,"keyTonic":"C","keyScale":"major",
             "keyStrength":0.5,"sampleRate":10,"beatTimes":[]}
        """.trimIndent()
        try {
            analyzerReturning(json).analyze(pcm, sr)
            fail("expected IllegalStateException for missing bpm")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun nonFiniteEnergy_throws() {
        // JSON has no NaN literal; represent non-finite via missing energy.
        val json = """
            {"bpm":120.0,"beatConfidence":4.0,"keyTonic":"C","keyScale":"major",
             "keyStrength":0.5,"sampleRate":10,"beatTimes":[]}
        """.trimIndent()
        try {
            analyzerReturning(json).analyze(pcm, sr)
            fail("expected IllegalStateException for missing energy")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun bpm_octaveFolded() {
        val json = """
            {"bpm":35.0,"beatConfidence":4.0,"energy":0.1,"keyTonic":"C","keyScale":"major",
             "keyStrength":0.5,"sampleRate":10,"beatTimes":[1.5]}
        """.trimIndent()
        val a = analyzerReturning(json).analyze(pcm, sr)
        // 35 -> 70 after one doubling
        assertEquals(70.0, a.bpm, 1e-9)
    }

    @Test
    fun lowConfidence_clearsBeatTimes_keepsFoldedBpm() {
        val json = """
            {"bpm":35.0,"beatConfidence":1.0,"energy":0.1,"keyTonic":"C","keyScale":"major",
             "keyStrength":0.5,"sampleRate":10,"beatTimes":[0.5,1.5,2.5]}
        """.trimIndent()
        val a = analyzerReturning(json).analyze(pcm, sr)
        assertTrue("beatTimes should be cleared on low confidence", a.beatTimes.isEmpty())
        assertEquals("bpm preserved (and folded) on low confidence", 70.0, a.bpm, 1e-9)
        // With no beats, vocalOnset falls back to 0.0
        assertEquals(0.0, a.vocalOnsetS, 1e-9)
    }

    @Test
    fun highConfidence_preservesBeatTimes() {
        val json = """
            {"bpm":120.0,"beatConfidence":4.0,"energy":0.1,"keyTonic":"C","keyScale":"major",
             "keyStrength":0.5,"sampleRate":10,"beatTimes":[0.5,1.5,2.5]}
        """.trimIndent()
        val a = analyzerReturning(json).analyze(pcm, sr)
        assertEquals(listOf(0.5, 1.5, 2.5), a.beatTimes)
    }

    @Test
    fun enharmonicTonic_resolves() {
        // Essentia may emit a flat spelling; must still resolve to a Camelot code.
        val json = """
            {"bpm":120.0,"beatConfidence":4.0,"energy":0.1,"keyTonic":"Db","keyScale":"minor",
             "keyStrength":0.5,"sampleRate":10,"beatTimes":[1.5]}
        """.trimIndent()
        val a = analyzerReturning(json).analyze(pcm, sr)
        // Db minor / C# minor -> 12A
        assertEquals("12A", a.keyCamelot)
    }

    @Test
    fun seam_receivesPcmAndSr() {
        var seenSr = -1
        var seenLen = -1
        val a = Analyzer(analyzeJson = { p, s ->
            seenLen = p.size; seenSr = s; wellFormed
        })
        a.analyze(pcm, sr)
        assertEquals(sr, seenSr)
        assertEquals(pcm.size, seenLen)
    }
}