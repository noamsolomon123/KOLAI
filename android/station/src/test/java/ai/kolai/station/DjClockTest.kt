package ai.kolai.station

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DjClock.nowParts - ported from backend/radioai/clock.py now_parts. Covers
 * every part-of-day boundary hour (5/11, 12/16, 17/21, 22/4) plus zero-padded
 * formatting.
 */
class DjClockTest {

    @Test
    fun morning_boundaries() {
        assertEquals("בוקר", DjClock.nowParts(5, 0).second)
        assertEquals("בוקר", DjClock.nowParts(11, 59).second)
    }

    @Test
    fun noon_boundaries() {
        assertEquals("צהריים", DjClock.nowParts(12, 0).second)
        assertEquals("צהריים", DjClock.nowParts(16, 30).second)
    }

    @Test
    fun evening_boundaries() {
        assertEquals("ערב", DjClock.nowParts(17, 0).second)
        assertEquals("ערב", DjClock.nowParts(21, 45).second)
    }

    @Test
    fun night_boundaries() {
        assertEquals("לילה", DjClock.nowParts(22, 0).second)
        assertEquals("לילה", DjClock.nowParts(4, 59).second)
        assertEquals("לילה", DjClock.nowParts(0, 0).second)
    }

    @Test
    fun time_string_is_zero_padded() {
        assertEquals("08:05", DjClock.nowParts(8, 5).first)
        assertEquals("00:00", DjClock.nowParts(0, 0).first)
        assertEquals("23:59", DjClock.nowParts(23, 59).first)
        assertEquals("12:07", DjClock.nowParts(12, 7).first)
    }
}