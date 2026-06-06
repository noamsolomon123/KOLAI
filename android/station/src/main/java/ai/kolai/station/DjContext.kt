package ai.kolai.station

/**
 * Minimal context the DJ reads when writing a break, ported from the shape of
 * backend/radioai/djcontext.py (DJContext). DJBrain only reads these fields:
 * time_str, part_of_day, weather, general_headline, topic_headlines.
 *
 * This is intentionally a thin data holder. Full population (clock / weather /
 * news) is a later device-side task; the song-beat path of DjBrain uses none of
 * these fields, so the defaults keep that path testable in isolation.
 *
 * Python -> Kotlin field name mapping:
 *   time_str         -> timeStr
 *   part_of_day      -> partOfDay
 *   weather          -> weather
 *   general_headline -> generalHeadline
 *   topic_headlines  -> topicHeadlines
 */
data class DjContext(
    val timeStr: String? = null,
    val partOfDay: String? = null,
    val weather: String? = null,
    val generalHeadline: String? = null,
    val topicHeadlines: Map<String, String> = emptyMap(),
)