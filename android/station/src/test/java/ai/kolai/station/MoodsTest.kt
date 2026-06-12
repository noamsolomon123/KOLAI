package ai.kolai.station

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-integrity tests for [Moods], the Kotlin port of
 * backend/radioai/moods.py. The cadence numbers and the English ttsStyle /
 * curationHint strings must match the Python VERBATIM.
 */
class MoodsTest {

    @Test
    fun table_has_exactly_the_five_python_moods() {
        assertEquals(
            setOf("mix", "party", "late_night", "focus", "morning"),
            Moods.ALL.keys,
        )
        // every spec's key field matches its map key
        Moods.ALL.forEach { (k, spec) -> assertEquals(k, spec.key) }
    }

    @Test
    fun default_is_mix() {
        assertEquals("mix", Moods.DEFAULT)
    }

    @Test
    fun cadence_numbers_match_python() {
        fun check(key: String, talk: Double, banter: Double, silence: Int) {
            val s = Moods.ALL.getValue(key)
            assertEquals("$key talkChance", talk, s.talkChance, 0.0)
            assertEquals("$key banterChance", banter, s.banterChance, 0.0)
            assertEquals("$key maxSilence", silence, s.maxSilence)
        }
        check("mix", 0.5, 0.2, 4)
        check("party", 0.7, 0.4, 3)
        check("late_night", 0.3, 0.1, 5)
        check("focus", 0.2, 0.05, 6)
        check("morning", 0.55, 0.25, 4)
    }

    @Test
    fun ttsStyles_match_python_verbatim() {
        assertEquals(
            "Read this like a charismatic, warm, professional Israeli FM radio host. " +
                "Energetic but smooth, natural broadcast pacing - a real radio personality. " +
                "Speak only the Hebrew:",
            Moods.ALL.getValue("mix").ttsStyle,
        )
        assertEquals(
            "Read this like a high-energy, hyped, exciting party radio host. " +
                "Fast, punchy, fun, full of energy. Speak only the Hebrew:",
            Moods.ALL.getValue("party").ttsStyle,
        )
        assertEquals(
            "Read this like a soft, warm, intimate late-night radio host. " +
                "Slow, smooth, relaxed, calming, low and gentle - unhurried. " +
                "Speak only the Hebrew:",
            Moods.ALL.getValue("late_night").ttsStyle,
        )
        assertEquals(
            "Read this calmly, briefly and low-key, unobtrusive and even. " +
                "Speak only the Hebrew:",
            Moods.ALL.getValue("focus").ttsStyle,
        )
        assertEquals(
            "Read this like a warm, friendly, bright morning radio host. " +
                "Cheerful and welcoming, medium pace. Speak only the Hebrew:",
            Moods.ALL.getValue("morning").ttsStyle,
        )
    }

    @Test
    fun curationHints_match_python_verbatim() {
        assertEquals(
            "a flowing mix across energies with a natural arc - the " +
                "listener's favorites and closely related songs",
            Moods.ALL.getValue("mix").curationHint,
        )
        assertEquals(
            "high-energy, upbeat, danceable party bangers - energetic pop, " +
                "dance, EDM, hip-hop bangers (think 'The Middle' energy); keep " +
                "the energy high and the tempo up",
            Moods.ALL.getValue("party").curationHint,
        )
        assertEquals(
            "low-energy, slow, smooth, intimate late-night songs - mellow " +
                "R&B, downtempo, soft ballads, chill electronic, dreamy vibes; " +
                "avoid loud high-tempo bangers",
            Moods.ALL.getValue("late_night").curationHint,
        )
        assertEquals(
            "steady, mellow, non-distracting songs for focus - chill, " +
                "instrumental-leaning, lo-fi, smooth grooves, minimal vocals; " +
                "consistent calm energy, nothing jarring",
            Moods.ALL.getValue("focus").curationHint,
        )
        assertEquals(
            "bright, warm, uplifting mid-energy morning songs - feel-good " +
                "pop, sunny vibes, easy upbeat tracks; a positive start to the " +
                "day",
            Moods.ALL.getValue("morning").curationHint,
        )
    }

    @Test
    fun djLine_empty_only_for_mix() {
        assertTrue(Moods.ALL.getValue("mix").djLine.isEmpty())
        for (key in listOf("party", "late_night", "focus", "morning")) {
            val line = Moods.ALL.getValue(key).djLine
            assertTrue("$key djLine must be non-empty Hebrew", line.isNotBlank())
            assertTrue("$key djLine must share the mood marker", line.startsWith("השידור עכשיו במצב"))
        }
    }

    @Test
    fun spec_resolves_known_moods_and_falls_back_to_mix() {
        assertEquals("party", Moods.spec("party").key)
        assertEquals("mix", Moods.spec(null).key)
        assertEquals("mix", Moods.spec("no_such_mood").key)
        assertEquals("mix", Moods.spec("").key)
    }
}