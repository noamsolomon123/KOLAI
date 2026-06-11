package ai.kolai.station

/**
 * DjClock - ported 1:1 from backend/radioai/clock.py `now_parts`.
 *
 * Pure: takes the hour/minute as plain ints (the caller reads them from
 * `java.util.Calendar` / wherever) so this stays a JVM-only, trivially
 * testable mapping with no clock or Android dependency.
 */
object DjClock {

    /**
     * Python: now_parts(dt) -> ("HH:MM", Hebrew part-of-day).
     *
     * The time string is zero-padded explicitly (no locale-sensitive
     * formatting). Part-of-day buckets mirror the Python exactly:
     * 5-11 בוקר, 12-16 צהריים, 17-21 ערב, otherwise לילה.
     */
    fun nowParts(hour: Int, minute: Int): Pair<String, String> {
        val timeStr =
            hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
        val part = when (hour) {
            in 5..11 -> "בוקר"
            in 12..16 -> "צהריים"
            in 17..21 -> "ערב"
            else -> "לילה"
        }
        return timeStr to part
    }
}