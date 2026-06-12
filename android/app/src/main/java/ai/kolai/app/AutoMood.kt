package ai.kolai.app

import ai.kolai.station.Moods
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * AutoMood - the broadcast clock. Maps wall-clock time to the mood a real
 * Israeli station would be running, so KOLAI reshapes itself through the day
 * without the listener touching a chip (the "auto" mode in [KolaiMood]).
 *
 * Rule table (first match wins, top to bottom):
 *
 *  | window                                    | mood       |
 *  |-------------------------------------------|------------|
 *  | Thu/Fri 21:00-23:59 + Fri/Sat 00:00-01:59 | party      | (weekend nights)
 *  | 23:00-03:59 (any day)                     | late_night |
 *  | 06:00-09:59 (any day)                     | morning    |
 *  | anything else                             | mix        |
 *
 * Party deliberately OUTRANKS late_night where they overlap (Thu/Fri
 * 23:00-02:00): Thursday night is THE going-out night in Israel and Friday
 * night follows, so those hours stay up-tempo; by 02:00 the party window
 * closes and the late_night rule takes over until 04:00.
 *
 * Pure function of (hour, dayOfWeek) - trivially verifiable by inspection:
 *   moodForClock(7,  MONDAY)   == "morning"
 *   moodForClock(15, MONDAY)   == "mix"
 *   moodForClock(23, MONDAY)   == "late_night"
 *   moodForClock(22, THURSDAY) == "party"
 *   moodForClock(1,  FRIDAY)   == "party"      (Thursday night's small hours)
 *   moodForClock(3,  FRIDAY)   == "late_night" (party window closed at 02:00)
 *   moodForClock(1,  SATURDAY) == "party"      (Friday night's small hours)
 *   moodForClock(22, SATURDAY) == "mix"
 */
object AutoMood {

    /** The clock-derived mood key for [hour] (0-23) on [dayOfWeek]. */
    fun moodForClock(hour: Int, dayOfWeek: DayOfWeek): String {
        val partyEvening = hour >= 21 &&
            (dayOfWeek == DayOfWeek.THURSDAY || dayOfWeek == DayOfWeek.FRIDAY)
        val partySmallHours = hour < 2 &&
            (dayOfWeek == DayOfWeek.FRIDAY || dayOfWeek == DayOfWeek.SATURDAY)
        return when {
            partyEvening || partySmallHours -> "party"
            hour >= 23 || hour < 4 -> "late_night"
            hour in 6..9 -> "morning"
            else -> Moods.DEFAULT // "mix"
        }
    }

    /** [moodForClock] for the device clock right now. */
    fun now(): String {
        val now = ZonedDateTime.now()
        return moodForClock(now.hour, now.dayOfWeek)
    }
}