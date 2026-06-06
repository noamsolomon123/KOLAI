package ai.kolai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for the Camelot wheel logic, mirrored 1:1 from the Python reference
 * backend/radioai/keys.py.
 */
class KeysTest {

    @Test
    fun majorMappings() {
        assertEquals("8B", camelotFromKey("C", "major"))
        assertEquals("9B", camelotFromKey("G", "major"))
        assertEquals("10B", camelotFromKey("D", "major"))
        assertEquals("11B", camelotFromKey("A", "major"))
        assertEquals("12B", camelotFromKey("E", "major"))
        assertEquals("1B", camelotFromKey("B", "major"))
        assertEquals("7B", camelotFromKey("F", "major"))
    }

    @Test
    fun majorEnharmonicEquivalents() {
        assertEquals("2B", camelotFromKey("F#", "major"))
        assertEquals("2B", camelotFromKey("Gb", "major"))
        assertEquals("3B", camelotFromKey("Db", "major"))
        assertEquals("3B", camelotFromKey("C#", "major"))
        assertEquals("4B", camelotFromKey("Ab", "major"))
        assertEquals("4B", camelotFromKey("G#", "major"))
        assertEquals("5B", camelotFromKey("Eb", "major"))
        assertEquals("5B", camelotFromKey("D#", "major"))
        assertEquals("6B", camelotFromKey("Bb", "major"))
        assertEquals("6B", camelotFromKey("A#", "major"))
    }

    @Test
    fun minorMappings() {
        assertEquals("8A", camelotFromKey("A", "minor"))
        assertEquals("9A", camelotFromKey("E", "minor"))
        assertEquals("10A", camelotFromKey("B", "minor"))
        assertEquals("4A", camelotFromKey("F", "minor"))
        assertEquals("5A", camelotFromKey("C", "minor"))
        assertEquals("6A", camelotFromKey("G", "minor"))
        assertEquals("7A", camelotFromKey("D", "minor"))
    }

    @Test
    fun minorEnharmonicEquivalents() {
        assertEquals("11A", camelotFromKey("F#", "minor"))
        assertEquals("11A", camelotFromKey("Gb", "minor"))
        assertEquals("12A", camelotFromKey("C#", "minor"))
        assertEquals("12A", camelotFromKey("Db", "minor"))
        assertEquals("1A", camelotFromKey("G#", "minor"))
        assertEquals("1A", camelotFromKey("Ab", "minor"))
        assertEquals("2A", camelotFromKey("D#", "minor"))
        assertEquals("2A", camelotFromKey("Eb", "minor"))
        assertEquals("3A", camelotFromKey("A#", "minor"))
        assertEquals("3A", camelotFromKey("Bb", "minor"))
    }

    @Test
    fun unknownTonicThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            camelotFromKey("H", "major")
        }
        assertThrows(IllegalArgumentException::class.java) {
            camelotFromKey("Z", "minor")
        }
    }

    @Test
    fun nonMajorModeUsesMinorTable() {
        // Python: table = MAJOR if mode == "major" else MINOR.
        assertEquals("8A", camelotFromKey("A", "minor"))
        assertEquals("8A", camelotFromKey("A", "min"))
        assertEquals("8A", camelotFromKey("A", "anything-else"))
    }

    @Test
    fun relationSame() {
        assertEquals("same", camelotRelation("8A", "8A"))
        assertEquals("same", camelotRelation("12B", "12B"))
    }

    @Test
    fun relationRelativeMajorMinor() {
        assertEquals("relative", camelotRelation("8A", "8B"))
        assertEquals("relative", camelotRelation("8B", "8A"))
        assertEquals("relative", camelotRelation("1A", "1B"))
    }

    @Test
    fun relationAdjacent() {
        assertEquals("adjacent", camelotRelation("8A", "9A"))
        assertEquals("adjacent", camelotRelation("9A", "8A"))
        assertEquals("adjacent", camelotRelation("8B", "7B"))
    }

    @Test
    fun relationAdjacentWrapAround() {
        assertEquals("adjacent", camelotRelation("12A", "1A"))
        assertEquals("adjacent", camelotRelation("1A", "12A"))
        assertEquals("adjacent", camelotRelation("12B", "1B"))
    }

    @Test
    fun relationClash() {
        assertEquals("clash", camelotRelation("8A", "9B"))
        assertEquals("clash", camelotRelation("8A", "10A"))
        assertEquals("clash", camelotRelation("1B", "5B"))
        assertEquals("clash", camelotRelation("8A", "3B"))
    }

    @Test
    fun compatibility() {
        assertEquals(true, areKeysCompatible("8A", "8A"))
        assertEquals(true, areKeysCompatible("8A", "8B"))
        assertEquals(true, areKeysCompatible("8A", "9A"))
        assertEquals(true, areKeysCompatible("12A", "1A"))
        assertEquals(false, areKeysCompatible("8A", "9B"))
        assertEquals(false, areKeysCompatible("8A", "10A"))
    }
}
