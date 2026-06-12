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
    // Station mood key (see [Moods]); null = default behavior, fully backward
    // compatible. Android addition - the Python carries the mood separately.
    val mood: String? = null,
    // Israeli-calendar note for the DJ (Android addition): a short Hebrew label
    // when the moment is calendar-special ("ערב שבת", "מוצאי שבת", a holiday
    // name), else null. Set by the app-side context provider.
    val calendarNote: String? = null,
    // True on somber national days (יום הזיכרון / יום השואה): the DJ must drop
    // all wordplay/banter and keep a quiet, respectful tone.
    val somber: Boolean = false,
    // Friday weekly-recap brief (Android addition): pre-computed Hebrew-ready
    // stats text the DJ weaves into a special opening; null on a normal day.
    val recapBrief: String? = null,
)