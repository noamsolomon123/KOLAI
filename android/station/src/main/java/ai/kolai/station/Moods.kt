package ai.kolai.station

/**
 * Mood presets for the station, ported from backend/radioai/moods.py (MOODS /
 * DEFAULT_MOOD). Each mood reshapes:
 *  - the talk cadence ([talkChance] / [banterChance] / [maxSilence], consumed
 *    per-block by [BlockRenderer.planFor]),
 *  - the TTS voice delivery ([ttsStyle], the English "Read this like..." prefix
 *    passed through the [VoiceRenderer] seam),
 *  - the DJ writing ([djLine], a Hebrew guidance line [DjBrain] appends to its
 *    prompts; Android addition - the Python reshapes prompts elsewhere),
 *  - song selection ([curationHint], the Python "song" vibe description, for
 *    the curation layer).
 *
 * The numbers and the English [ttsStyle] / [curationHint] strings are VERBATIM
 * from the Python (do not paraphrase - output quality depends on them). The
 * Hebrew [djLine] strings are Android-side additions; "mix" (the default) has
 * an empty djLine so default-mood prompts stay byte-identical to the
 * pre-mood-support prompts.
 */
data class MoodSpec(
    val key: String,
    val talkChance: Double,
    val banterChance: Double,
    val maxSilence: Int,
    val ttsStyle: String,
    val djLine: String,
    val curationHint: String,
)

object Moods {
    const val DEFAULT = "mix"

    val ALL: Map<String, MoodSpec> = listOf(
        MoodSpec(
            key = "mix",
            talkChance = 0.5, banterChance = 0.2, maxSilence = 4,
            ttsStyle = "Read this like a charismatic, warm, professional Israeli " +
                "FM radio host. Energetic but smooth, natural broadcast " +
                "pacing - a real radio personality. Speak only the Hebrew:",
            djLine = "",
            curationHint = "a flowing mix across energies with a natural arc - the " +
                "listener's favorites and closely related songs",
        ),
        MoodSpec(
            key = "party",
            talkChance = 0.7, banterChance = 0.4, maxSilence = 3,
            ttsStyle = "Read this like a high-energy, hyped, exciting party radio " +
                "host. Fast, punchy, fun, full of energy. Speak only the " +
                "Hebrew:",
            djLine = "השידור עכשיו במצב מסיבה - אנרגיה גבוהה, קצבי, משפטים קצרים ומלאי חיים.",
            curationHint = "high-energy, upbeat, danceable party bangers - energetic pop, " +
                "dance, EDM, hip-hop bangers (think 'The Middle' energy); keep " +
                "the energy high and the tempo up",
        ),
        MoodSpec(
            key = "late_night",
            talkChance = 0.3, banterChance = 0.1, maxSilence = 5,
            ttsStyle = "Read this like a soft, warm, intimate late-night radio " +
                "host. Slow, smooth, relaxed, calming, low and gentle - " +
                "unhurried. Speak only the Hebrew:",
            djLine = "השידור עכשיו במצב לילה - דבר רך, איטי ואינטימי, טון נמוך ורגוע.",
            curationHint = "low-energy, slow, smooth, intimate late-night songs - mellow " +
                "R&B, downtempo, soft ballads, chill electronic, dreamy vibes; " +
                "avoid loud high-tempo bangers",
        ),
        MoodSpec(
            key = "focus",
            talkChance = 0.2, banterChance = 0.05, maxSilence = 6,
            ttsStyle = "Read this calmly, briefly and low-key, unobtrusive and " +
                "even. Speak only the Hebrew:",
            djLine = "השידור עכשיו במצב ריכוז - דבר מעט, קצר ושקט, בלי להפריע.",
            curationHint = "steady, mellow, non-distracting songs for focus - chill, " +
                "instrumental-leaning, lo-fi, smooth grooves, minimal vocals; " +
                "consistent calm energy, nothing jarring",
        ),
        MoodSpec(
            key = "morning",
            talkChance = 0.55, banterChance = 0.25, maxSilence = 4,
            ttsStyle = "Read this like a warm, friendly, bright morning radio " +
                "host. Cheerful and welcoming, medium pace. Speak only " +
                "the Hebrew:",
            djLine = "השידור עכשיו במצב בוקר - חם, אופטימי ומאיר פנים.",
            curationHint = "bright, warm, uplifting mid-energy morning songs - feel-good " +
                "pop, sunny vibes, easy upbeat tracks; a positive start to the " +
                "day",
        ),
    ).associateBy { it.key }

    /** Resolve a mood key to its spec; null/unknown falls back to [DEFAULT]. */
    fun spec(mood: String?): MoodSpec = ALL[mood ?: DEFAULT] ?: ALL.getValue(DEFAULT)
}