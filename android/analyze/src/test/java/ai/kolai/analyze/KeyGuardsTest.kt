package ai.kolai.analyze

import ai.kolai.core.camelotFromKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [normalizeEnharmonic] must let EVERY Essentia keyTonic spelling resolve through
 * the :core Camelot tables, and enharmonic pairs (Db/C#, Eb/D#, Gb/F#, Ab/G#,
 * Bb/A#) must collapse to the SAME Camelot code. Naturals pass through unchanged.
 */
class KeyGuardsTest {

    private fun camelot(tonic: String, mode: String): String =
        camelotFromKey(normalizeEnharmonic(tonic), mode)

    @Test
    fun naturals_passThrough() {
        assertEquals("C", normalizeEnharmonic("C"))
        assertEquals("G", normalizeEnharmonic("G"))
        assertEquals("B", normalizeEnharmonic("B"))
        assertEquals("F", normalizeEnharmonic("F"))
    }

    @Test
    fun sharps_passThrough() {
        assertEquals("C#", normalizeEnharmonic("C#"))
        assertEquals("F#", normalizeEnharmonic("F#"))
        assertEquals("G#", normalizeEnharmonic("G#"))
    }

    @Test
    fun flats_mapToSharps() {
        assertEquals("C#", normalizeEnharmonic("Db"))
        assertEquals("D#", normalizeEnharmonic("Eb"))
        assertEquals("F#", normalizeEnharmonic("Gb"))
        assertEquals("G#", normalizeEnharmonic("Ab"))
        assertEquals("A#", normalizeEnharmonic("Bb"))
    }

    @Test
    fun dbAndCsharpMinor_sameCamelot() {
        assertEquals(camelot("C#", "minor"), camelot("Db", "minor"))
        assertEquals("12A", camelot("Db", "minor"))
    }

    @Test
    fun gbAndFsharpMajor_sameCamelot() {
        assertEquals(camelot("F#", "major"), camelot("Gb", "major"))
        assertEquals("2B", camelot("Gb", "major"))
    }

    @Test
    fun ebAndDsharpMinor_sameCamelot() {
        assertEquals(camelot("D#", "minor"), camelot("Eb", "minor"))
        assertEquals("2A", camelot("Eb", "minor"))
    }

    @Test
    fun abAndGsharpMinor_sameCamelot() {
        assertEquals(camelot("G#", "minor"), camelot("Ab", "minor"))
        assertEquals("1A", camelot("Ab", "minor"))
    }

    @Test
    fun bbAndAsharpMajor_sameCamelot() {
        assertEquals(camelot("A#", "major"), camelot("Bb", "major"))
        assertEquals("6B", camelot("Bb", "major"))
    }

    @Test
    fun allTwelveTonics_resolveBothModes() {
        // Every pitch-class Essentia could emit (sharp + flat spellings) must
        // resolve without throwing, for both major and minor.
        val tonics = listOf(
            "C", "C#", "Db", "D", "D#", "Eb", "E", "F",
            "F#", "Gb", "G", "G#", "Ab", "A", "A#", "Bb", "B"
        )
        for (t in tonics) {
            // Will throw IllegalArgumentException if unresolved.
            camelot(t, "major")
            camelot(t, "minor")
        }
    }
}