package ai.kolai.station

import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.PatternSyntaxException

/**
 * ON-DEVICE proof for the SetlistParsing regex fix.
 *
 * Before the fix, FEAT_SPLIT/NON_WORD used the inline (?U) flag. The JVM accepts
 * it, but Android's ICU regex engine (com.android.icu.util.regex) rejects it,
 * throwing PatternSyntaxException ("Syntax error in regexp pattern near index 3")
 * the moment the SetlistParsing top-level vals are initialized (class-load).
 *
 * Each test here touches baseTitle / parseSetlist, which forces those vals to
 * compile on the device's actual regex engine. If the (?U) token were still
 * present, these would THROW at class-load on-device. They must now pass with the
 * Unicode behavior preserved (Hebrew \w/\b via Pattern.UNICODE_CHARACTER_CLASS).
 *
 * Run via manual adb (Gradle connectedAndroidTest is broken in this project):
 *   gradlew :station:assembleDebugAndroidTest
 *   adb install -r -t .../station-debug-androidTest.apk
 *   adb shell am instrument -w ai.kolai.station.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SetlistParsingDeviceTest {

    private val taste = TasteProfile(
        topTracks = listOf(
            TasteTrack(title = "Yellow", artist = "Coldplay", durationS = 267.0),
            TasteTrack(title = "בלילה", artist = "Moti Taka", durationS = 200.0),
        ),
        topArtists = listOf("Coldplay", "Moti Taka"),
    )

    @Test
    fun baseTitle_strips_hebrew_bracket_on_icu_engine() {
        // Exercises BRACKETED + NON_WORD (Unicode \w) on the device regex engine.
        try {
            assertEquals("תפילה", baseTitle("תפילה (Live)"))
        } catch (e: PatternSyntaxException) {
            fail("baseTitle threw PatternSyntaxException on ICU engine: ${e.message}")
        }
    }

    @Test
    fun baseTitle_strips_feat_on_icu_engine() {
        // Exercises FEAT_SPLIT (\b...\b with Unicode flag) on the device engine.
        try {
            assertEquals("song", baseTitle("Song feat. Someone"))
        } catch (e: PatternSyntaxException) {
            fail("baseTitle(feat) threw PatternSyntaxException on ICU engine: ${e.message}")
        }
    }

    @Test
    fun parseSetlist_parses_mixed_hebrew_english_without_pattern_exception() {
        val text = """[{"title":"בלילה","artist":"Moti Taka"},{"title":"Yellow","artist":"Coldplay"}]"""
        try {
            val songs = parseSetlist(text, taste, n = 6)
            assertEquals(2, songs.size)
            assertEquals("בלילה", songs[0].title)
            assertEquals("Moti Taka", songs[0].artist)
            assertEquals("Yellow", songs[1].title)
            assertEquals("Coldplay", songs[1].artist)
        } catch (e: PatternSyntaxException) {
            fail("parseSetlist threw PatternSyntaxException on ICU engine: ${e.message}")
        }
    }
}