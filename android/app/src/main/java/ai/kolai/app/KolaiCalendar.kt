package ai.kolai.app

import android.icu.util.Calendar
import android.icu.util.HebrewCalendar
import android.icu.util.ULocale
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * KolaiCalendar - Israeli-calendar awareness for the DJ. A PURE, cheap mapping
 * from "now" to a short Hebrew calendar note + a somber flag, computed fully
 * on-device via android.icu.util.HebrewCalendar (API 24+, no network, free).
 * [ai.kolai.app.wiring.LiveDjContext.current] calls it once per rendered block
 * and fills DjContext.calendarNote / DjContext.somber.
 *
 * Rules (first match wins):
 *  1. Jewish-holiday / national-day note from the CIVIL day's Hebrew date
 *     (see [holidayFor]). Somber days (Yom HaZikaron / Yom HaShoah) also set
 *     somber=true so the DJ drops all humor.
 *  2. Shabbat clock:
 *       Friday   14:00-18:59 -> "ערב שבת"   (19:00 is a fixed crude stand-in
 *                                            for sunset; good enough year-round
 *                                            for a radio vibe, documented here
 *                                            instead of a real sunset calc)
 *       Friday   19:00-23:59 -> "שבת"       (shabbat has come in)
 *       Saturday 00:00-19:59 -> "שבת"
 *       Saturday 20:00-22:59 -> "מוצאי שבת"
 *  3. Otherwise (null, false).
 *
 * Simplifications, on purpose (this feeds DJ small-talk, not halacha):
 *  - Hebrew days are mapped from the CIVIL date - no sunset shift, so a
 *    holiday is flagged on its daytime civil day, not from the prior evening.
 *  - Yom HaZikaron (Iyar 4) / Yom HaShoah (Nisan 27) / Yom HaAtzmaut (Iyar 5)
 *    use the fixed Hebrew dates, ignoring the real-world Fri/Sun observance
 *    shifts.
 *  - Rosh Chodesh is deliberately SKIPPED (too frequent to be special).
 *
 * Self-check assertions (verified by inspection; :app has no JVM test source):
 *   calendarNote(Fri 15:00 plain week) == ("ערב שבת", false)
 *   calendarNote(Sat 10:00 plain week) == ("שבת", false)
 *   calendarNote(Sat 21:00 plain week) == ("מוצאי שבת", false)
 *   calendarNote(Tue 12:00 plain week) == (null, false)
 *   Hebrew Nisan 27 (any time)         == ("יום השואה", true)
 *   Hebrew Iyar 4 (any time)           == ("יום הזיכרון", true)
 *   Hebrew Kislev 25 (any time)        == ("חנוכה", false)
 */
object KolaiCalendar {

    /** (short Hebrew note or null, somber). Pure + allocation-light. */
    fun calendarNote(now: ZonedDateTime): Pair<String?, Boolean> {
        holidayFor(now)?.let { return it }

        val hour = now.hour
        return when (now.dayOfWeek) {
            DayOfWeek.FRIDAY -> when {
                hour in 14..18 -> "ערב שבת" to false
                hour >= 19 -> "שבת" to false
                else -> null to false
            }
            DayOfWeek.SATURDAY -> when {
                hour < 20 -> "שבת" to false
                hour < 23 -> "מוצאי שבת" to false
                else -> null to false
            }
            else -> null to false
        }
    }

    /** Holiday (note, somber) for the civil day of [now], or null. */
    private fun holidayFor(now: ZonedDateTime): Pair<String, Boolean>? {
        val cal = HebrewCalendar(ULocale("he_IL@calendar=hebrew"))
        cal.timeInMillis = now.toInstant().toEpochMilli()
        val month = cal.get(Calendar.MONTH)
        val day = cal.get(Calendar.DAY_OF_MONTH)

        // ICU month constants: ADAR_1 (5) only exists in leap years, so ADAR
        // (6) is always "the" Adar of Purim and NISAN is always 7.
        return when (month) {
            HebrewCalendar.TISHRI -> when (day) {
                1, 2 -> "ראש השנה" to false
                10 -> "יום כיפור" to false
                in 15..21 -> "סוכות" to false
                else -> null
            }
            HebrewCalendar.KISLEV ->
                if (day >= 25) "חנוכה" to false else null
            HebrewCalendar.TEVET -> {
                // Hanukkah spills into Tevet: 8 nights minus the 5 or 6 that
                // fit in Kislev (which has 29 or 30 days, year-dependent).
                val kislev = cal.clone() as HebrewCalendar
                kislev.set(Calendar.MONTH, HebrewCalendar.KISLEV)
                kislev.set(Calendar.DAY_OF_MONTH, 1)
                val tailDays = 8 - (kislev.getActualMaximum(Calendar.DAY_OF_MONTH) - 24)
                if (day <= tailDays) "חנוכה" to false else null
            }
            HebrewCalendar.ADAR ->
                if (day == 14) "פורים" to false else null
            HebrewCalendar.NISAN -> when (day) {
                in 15..21 -> "פסח" to false
                27 -> "יום השואה" to true
                else -> null
            }
            HebrewCalendar.IYAR -> when (day) {
                4 -> "יום הזיכרון" to true
                5 -> "יום העצמאות" to false
                18 -> "ל\"ג בעומר" to false
                else -> null
            }
            HebrewCalendar.SIVAN ->
                if (day == 6) "שבועות" to false else null
            else -> null
        }
    }
}