package ai.kolai.analyze

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [foldBpm] octave-normalizes tempo into ~70..180 so half/double-time detections
 * do not break later compatibility scoring.
 */
class BpmGuardsTest {

    @Test
    fun inRange_unchanged() {
        assertEquals(70.0, foldBpm(70.0), 1e-9)
        assertEquals(120.0, foldBpm(120.0), 1e-9)
        assertEquals(170.0, foldBpm(170.0), 1e-9)
        assertEquals(180.0, foldBpm(180.0), 1e-9)
    }

    @Test
    fun tooSlow_doublesUp() {
        assertEquals(70.0, foldBpm(35.0), 1e-9)   // x2 once
        assertEquals(120.0, foldBpm(60.0), 1e-9)  // x2 once
        assertEquals(80.0, foldBpm(20.0), 1e-9)   // x2 -> 40 -> 80
    }

    @Test
    fun tooFast_halvesDown() {
        assertEquals(100.0, foldBpm(200.0), 1e-9) // /2 once
        assertEquals(95.0, foldBpm(380.0), 1e-9)  // /2 -> 190 (>180) -> /2 = 95
    }

    @Test
    fun boundary_360_halvesToInRange() {
        // 360 > 180 -> /2 = 180 (180 is NOT > 180, so stop)
        assertEquals(180.0, foldBpm(360.0), 1e-9)
    }

    @Test
    fun zeroOrNegative_returnedAsIs() {
        // Defensive: non-positive bpm cannot be folded (would loop); pass through.
        assertEquals(0.0, foldBpm(0.0), 1e-9)
        assertEquals(-5.0, foldBpm(-5.0), 1e-9)
    }
}