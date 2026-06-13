package ai.kolai.station

/**
 * Mood presets for the station, ported from backend/radioai/moods.py (MOODS /
 * DEFAULT_MOOD). Each mood reshapes:
 *  - the talk cadence ([talkChance] / [banterChance] / [maxSilence], consumed
 *    per-block by [BlockRenderer.planFor]),
 *  - the TTS voice identity ([voiceName], a distinct prebuilt Gemini voice per
 *    mood - the most audible mood signal),
 *  - the TTS voice delivery ([ttsStyle], the English "Read this like..." prefix
 *    passed through the [VoiceRenderer] seam),
 *  - the DJ writing ([djLine], a Hebrew guidance line [DjBrain] appends to its
 *    prompts; Android addition - the Python reshapes prompts elsewhere),
 *  - song selection ([curationHint], the Python "song" vibe description, for
 *    the curation layer).
 *
 * The cadence numbers and the [curationHint] strings are VERBATIM from the
 * Python (do not paraphrase - curation quality depends on them). The
 * [ttsStyle] strings are NO LONGER verbatim from Python: they were rewritten
 * Android-side to be calmer and genuinely distinct per mood, because Gemini
 * read the old "charismatic... Energetic..." default as over-the-top/hyped.
 * The Hebrew [djLine] strings are Android-side additions; "mix" (the default)
 * has an empty djLine so default-mood prompts stay byte-identical to the
 * pre-mood-support prompts.
 *
 * [voiceName] is a Gemini TTS prebuilt voice. Known voice set (for easy
 * swapping / on-device tuning via DevConfig overrides):
 *   Zephyr, Puck, Charon, Kore, Fenrir, Leda, Orus, Aoede, Callirrhoe,
 *   Autonoe, Enceladus, Iapetus, Umbriel, Algieba, Despina, Erinome, Algenib,
 *   Rasalgethi, Laomedeia, Achernar, Alnilam, Schedar, Gacrux, Pulcherrima,
 *   Achird, Zubenelgenubi, Vindemiatrix, Sadachbia, Sadaltager, Sulafat.
 */
data class MoodSpec(
    val key: String,
    val talkChance: Double,
    val banterChance: Double,
    val maxSilence: Int,
    val voiceName: String,
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
            voiceName = "Algieba",
            ttsStyle = "Read this like a natural, warm Israeli radio host talking " +
                "to one friend in the car. Relaxed, conversational and real - " +
                "medium-low energy. Do NOT hype it up, do NOT shout, do NOT " +
                "sound over-excited. Calm and easy. Speak only the Hebrew, " +
                "naturally:",
            djLine = "",
            curationHint = "a flowing mix across energies with a natural arc - the " +
                "listener's favorites and closely related songs",
        ),
        MoodSpec(
            key = "party",
            talkChance = 0.7, banterChance = 0.4, maxSilence = 3,
            voiceName = "Puck",
            ttsStyle = "Read this like a confident, upbeat party radio host - " +
                "genuinely lively and fun, but still smooth and controlled. " +
                "Bring energy without screaming or going over-the-top. Speak " +
                "only the Hebrew, naturally:",
            djLine = "השידור עכשיו במצב מסיבה - אנרגיה גבוהה, קצבי, משפטים קצרים ומלאי חיים.",
            curationHint = "high-energy, upbeat, danceable party bangers - energetic pop, " +
                "dance, EDM, hip-hop bangers (think 'The Middle' energy); keep " +
                "the energy high and the tempo up",
        ),
        MoodSpec(
            key = "late_night",
            talkChance = 0.3, banterChance = 0.1, maxSilence = 5,
            voiceName = "Enceladus",
            ttsStyle = "Read this softly and slowly, like an intimate late-night " +
                "radio host. Low, gentle, almost whispered, unhurried and " +
                "calming. Do NOT raise your energy or sound excited. Speak " +
                "only the Hebrew, naturally:",
            djLine = "השידור עכשיו במצב לילה - דבר רך, איטי ואינטימי, טון נמוך ורגוע.",
            curationHint = "low-energy, slow, smooth, intimate late-night songs - mellow " +
                "R&B, downtempo, soft ballads, chill electronic, dreamy vibes; " +
                "avoid loud high-tempo bangers",
        ),
        MoodSpec(
            key = "focus",
            talkChance = 0.2, banterChance = 0.05, maxSilence = 6,
            voiceName = "Charon",
            ttsStyle = "Read this calmly and quietly, minimal and even - " +
                "unobtrusive, barely-there, low-key and steady. Do NOT add " +
                "energy or excitement. Speak only the Hebrew, naturally:",
            djLine = "השידור עכשיו במצב ריכוז - דבר מעט, קצר ושקט, בלי להפריע.",
            curationHint = "steady, mellow, non-distracting songs for focus - chill, " +
                "instrumental-leaning, lo-fi, smooth grooves, minimal vocals; " +
                "consistent calm energy, nothing jarring",
        ),
        MoodSpec(
            key = "morning",
            talkChance = 0.55, banterChance = 0.25, maxSilence = 4,
            voiceName = "Aoede",
            ttsStyle = "Read this like a warm, friendly morning radio host - " +
                "gently bright and welcoming, a soft smile in the voice. Keep " +
                "it easy and calm, NOT manic and NOT over-excited. Speak only " +
                "the Hebrew, naturally:",
            djLine = "השידור עכשיו במצב בוקר - חם, אופטימי ומאיר פנים.",
            curationHint = "bright, warm, uplifting mid-energy morning songs - feel-good " +
                "pop, sunny vibes, easy upbeat tracks; a positive start to the " +
                "day",
        ),
    ).associateBy { it.key }

    /** Resolve a mood key to its spec; null/unknown falls back to [DEFAULT]. */
    fun spec(mood: String?): MoodSpec = ALL[mood ?: DEFAULT] ?: ALL.getValue(DEFAULT)
}