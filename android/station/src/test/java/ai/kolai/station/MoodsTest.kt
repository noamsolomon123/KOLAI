package ai.kolai.station

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-integrity tests for [Moods], the Kotlin port of
 * backend/radioai/moods.py. The cadence numbers and the [curationHint] strings
 * still match the Python VERBATIM. The [ttsStyle] strings were deliberately
 * rewritten Android-side (calmer + distinct per mood), so these tests assert
 * the NEW intent (calm / non-hyped / steers away from over-excitement) rather
 * than verbatim Python text.
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
    fun voiceNames_are_the_expected_distinct_defaults() {
        assertEquals("Algieba", Moods.ALL.getValue("mix").voiceName)
        assertEquals("Puck", Moods.ALL.getValue("party").voiceName)
        assertEquals("Enceladus", Moods.ALL.getValue("late_night").voiceName)
        assertEquals("Charon", Moods.ALL.getValue("focus").voiceName)
        assertEquals("Aoede", Moods.ALL.getValue("morning").voiceName)
    }

    @Test
    fun every_mood_has_a_nonblank_and_distinct_voiceName() {
        val voices = Moods.ALL.values.map { it.voiceName }
        for (spec in Moods.ALL.values) {
            assertTrue("${spec.key} voiceName must be non-blank", spec.voiceName.isNotBlank())
        }
        // distinct across all five moods - the whole point is an audible
        // per-mood voice difference, so no two moods may share a voice.
        assertEquals("voiceNames must be distinct across moods", voices.size, voices.toSet().size)
    }

    @Test
    fun ttsStyles_are_calm_and_distinct_and_speak_only_hebrew() {
        val styles = Moods.ALL.mapValues { it.value.ttsStyle }

        // all five end with the directive close + are distinct strings
        for ((key, style) in styles) {
            assertTrue("$key ttsStyle must close with the Hebrew-only directive",
                style.contains("Speak only the Hebrew, naturally:"))
        }
        assertEquals("ttsStyles must be distinct per mood",
            styles.size, styles.values.toSet().size)

        // mix: must steer AWAY from hype/over-excitement (the user complaint).
        val mix = styles.getValue("mix").lowercase()
        assertTrue("mix must explicitly avoid hyping", mix.contains("do not hype"))
        assertTrue("mix must avoid over-excitement", mix.contains("over-excited"))
        assertTrue("mix must read as relaxed/medium-low", mix.contains("relaxed") || mix.contains("medium-low"))
        // and must NOT carry the old over-the-top default wording.
        assertTrue("mix must drop the old 'energetic' default", !mix.contains("energetic"))

        // party stays energetic but controlled (not screaming).
        val party = styles.getValue("party").lowercase()
        assertTrue("party stays upbeat", party.contains("upbeat") || party.contains("lively"))
        assertTrue("party must stay controlled, not screaming", party.contains("controlled") || party.contains("smooth"))

        // late_night is soft/slow/intimate.
        val lateNight = styles.getValue("late_night").lowercase()
        assertTrue("late_night must be soft/slow", lateNight.contains("soft") && lateNight.contains("slow"))

        // focus is minimal / unobtrusive.
        val focus = styles.getValue("focus").lowercase()
        assertTrue("focus must be minimal/unobtrusive", focus.contains("minimal") || focus.contains("unobtrusive"))

        // morning is warm/friendly but explicitly NOT manic.
        val morning = styles.getValue("morning").lowercase()
        assertTrue("morning must be warm/friendly", morning.contains("warm") && morning.contains("friendly"))
        assertTrue("morning must not be manic", morning.contains("not manic") || morning.contains("over-excited"))
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