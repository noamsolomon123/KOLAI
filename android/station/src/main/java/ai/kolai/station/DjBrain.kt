package ai.kolai.station

import ai.kolai.core.Song
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Single-host DJ line generation, ported 1:1 from backend/radioai/djbrain.py
 * (class DJBrain). All Hebrew prompt strings are verbatim from the Python --
 * output quality depends on them, so do NOT paraphrase.
 *
 * Scope: originally single-host MVP; 2026-06-12 added the DJ writing
 * capabilities (features 7-12): calendar/somber awareness, writeHandover,
 * the taste wink, writeGoodThing, writeBanter (a fresh Android two-voice
 * design - NOT a port of the Python write_banter) and writeRecapOpening.
 *
 * The GeminiClient in the Python file was ported into :voice already and is NOT
 * re-ported here; DjBrain talks to the LLM only through [LlmClient].
 *
 * write_intro / write_break call client.complete and are therefore `suspend`.
 * Output shaping (clean / finish / isSkip / wordsForSeconds) lives in DjText.kt.
 *
 * Intentional Android deviations from the Python original (Israeli radio craft
 * research, docs/research/2026-06-11-israeli-radio-culture.md). The ported
 * Hebrew fragments stay verbatim; these are ADDITIONS only:
 *  1) [oneListenerLine] - a NEW shared prompt fragment appended to EVERY prompt
 *     (song / weather / news / topic / refine / opening): address ONE listener
 *     in second person; plural address is banned (finding 1, HIGH confidence).
 *  2) [writeOpening] - a NEW public API (with its own prompt) for the very
 *     first words of a broadcast session: a real radio opening that greets by
 *     part of day, welcomes the listener and flows into the first song
 *     (finding 3). No SKIP is allowed there - an opening always speaks.
 */
class DjBrain(
    private val client: LlmClient,
    private val persona: String,
    private val random: Random = Random.Default,
) {

    // ---- anti-repetition memory + variety dials (additions, 2026-06-12) --

    /**
     * NEW (no Python counterpart): a small ring of the last [MEMORY_SIZE]
     * lines this brain actually put on air. Every generation prompt gets an
     * appended Hebrew instruction listing the most recent [AVOID_COUNT] of
     * them (each truncated to [AVOID_TRUNC] chars) so the model stops
     * recycling the same openers/phrasings block after block. Guarded by
     * [memoryLock] because the renderer may call from worker threads.
     */
    private val recentLines = ArrayDeque<String>()
    private val memoryLock = Any()

    /**
     * NEW (2026-06-13, diversity fix): a SEPARATE, longer-lived ring of recent
     * OPENERS (first [OPENER_WORDS] words of each aired line, normalized). The
     * 75%-shared-first-word finding needs a memory that spans far more lines
     * than the phrasing ring, so the engine refuses an opener it used dozens of
     * lines ago. Guarded by the same [memoryLock].
     */
    private val recentOpeners = ArrayDeque<String>()

    /** First [OPENER_WORDS] words of [line], whitespace-normalized; "" if blank. */
    private fun openerOf(line: String): String =
        line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .take(OPENER_WORDS).joinToString(" ")

    private fun remember(line: String?) {
        if (line.isNullOrBlank()) return
        synchronized(memoryLock) {
            recentLines.addLast(line)
            while (recentLines.size > MEMORY_SIZE) recentLines.removeFirst()
            val opener = openerOf(line)
            if (opener.isNotEmpty()) {
                recentOpeners.addLast(opener)
                while (recentOpeners.size > OPENER_MEMORY_SIZE) recentOpeners.removeFirst()
            }
        }
    }

    /** Snapshot for tests/debugging; newest line last. */
    internal fun recentLinesSnapshot(): List<String> =
        synchronized(memoryLock) { recentLines.toList() }

    /** Snapshot of the opener ring for tests/debugging; newest opener last. */
    internal fun recentOpenersSnapshot(): List<String> =
        synchronized(memoryLock) { recentOpeners.toList() }

    private fun avoidLine(): String {
        val recent = synchronized(memoryLock) { recentLines.toList() }
        if (recent.isEmpty()) return clicheLine()
        val items = recent.takeLast(AVOID_COUNT)
            .joinToString(" | ") { "\"" + it.take(AVOID_TRUNC) + "\"" }
        return "\n" + "אל תחזור על הפתיחים/הניסוחים האלה מהשורות האחרונות שלך: " +
            items + ". תפתח ותנסח אחרת לגמרי." + openerAvoidLine() + clicheLine()
    }

    /**
     * NEW (2026-06-13, diversity fix): list recent OPENERS and forbid reusing
     * them. Directly targets the 75%-shared-first-word finding - it is a much
     * longer-lived memory than the phrasing ring. Empty until something aired.
     */
    private fun openerAvoidLine(): String {
        val openers = synchronized(memoryLock) { recentOpeners.toList() }
        if (openers.isEmpty()) return ""
        val items = openers.takeLast(OPENER_AVOID_COUNT)
            .joinToString(" | ") { "\"" + it.take(AVOID_TRUNC) + "\"" }
        return "\n" + "ובמיוחד אל תפתח באותו אופן כמו השורות האלה - תמנע מהפתיחים: " +
            items + ". תמצא מילת פתיחה אחרת לגמרי."
    }

    /**
     * NEW (2026-06-13, diversity fix): an always-on ban on the over-used tics
     * ([CLICHE_PHRASES]) the study flagged as the narrow verbal fingerprint.
     * Appended via [avoidLine] to every generation prompt (even the first,
     * before anything has aired).
     */
    private fun clicheLine(): String =
        "\n" + "הימנע מהנדושים האלה ואל תישען עליהם: " +
            CLICHE_PHRASES.joinToString(", ") { "\"" + it + "\"" } + "."

    /**
     * NEW (2026-06-13, diversity fix): a short per-mood register/diction hint
     * woven into prompts so wording differs by mood. "mix" / null / unknown
     * append NOTHING, so default-mood prompts stay byte-identical.
     */
    private fun moodRegisterLine(ctx: DjContext?): String {
        val hint = ctx?.mood?.let { MOOD_REGISTER[it] }.orEmpty()
        return if (hint.isEmpty()) "" else "\n" + hint
    }

    /**
     * NEW (2026-06-13, diversity fix): a small exclusion ring of the last few
     * ANGLE indices used, so [flavorLine] never picks an angle it just used.
     * Guarded by [memoryLock] like the line/opener rings.
     */
    private val recentAngles = ArrayDeque<Int>()

    /**
     * Pick ONE [ANGLES] index via the injected [random], excluding the last
     * [ANGLE_AVOID] used (seeded-testable). Remembers the choice in
     * [recentAngles]. Pure index math - no Hebrew here.
     */
    private fun nextAngleIndex(): Int = synchronized(memoryLock) {
        val recent = recentAngles.toSet()
        val pool = ANGLES.indices.filter { it !in recent }.ifEmpty { ANGLES.indices.toList() }
        val chosen = pool[random.nextInt(pool.size)]
        recentAngles.addLast(chosen)
        while (recentAngles.size > ANGLE_AVOID) recentAngles.removeFirst()
        chosen
    }

    /**
     * NEW (rewritten 2026-06-13, diversity fix): ONE distinct prompt ANGLE per
     * spoken line, rotated from [ANGLES] via the injected [random] with an
     * exclusion ring. (The per-mood register hint rides on [moodLine], so it
     * reaches every prompt site - not only the ones that call this.) The angle
     * reframes HOW the DJ builds the line (and lets it START differently),
     * directly
     * attacking the cloned-opener finding; the old 5-item tone nudge was too
     * weak. Chosen via the injectable [random] (tests pass a seeded Random) so
     * rotation is reproducible. Somber days outrank variety: returns "" (no
     * angle, no register) so the quiet/respectful tone is never undercut.
     */
    private fun flavorLine(ctx: DjContext? = null): String =
        if (ctx?.somber == true) "" // somber days outrank variety: no angle/register nudge
        else "\n" + ANGLES[nextAngleIndex()]

    /**
     * NEW: when the previous song is known, nudge the model that it may also
     * close the song that just ended (back-announce), not only tee up the
     * next one - natural radio behavior. Optional by design ("לא חובה") so it
     * never becomes a formula. Appended to the weather/news/topic prompts,
     * whose verbatim bodies never mention the outgoing song.
     */
    private fun backAnnounceLine(prev: Song?): String =
        if (prev == null) "" else
            "\n" + "אם זה יושב טוב, אפשר גם מילה קטנה על השיר שהרגע הסתיים - " +
                "\"${prev.title}\" של ${prev.artist} - כמו שדרן שסוגר שיר באוויר. לא חובה."

    /** NEW: raises the SKIP bar; appended right after the verbatim [skipLine]. */
    private fun skipGateLine(): String =
        "והרף גבוה: דבר רק אם יש לך משהו באמת מעניין, מפתיע או מצחיק להגיד - " +
            "כל דבר פחות מזה הוא מילוי, ומילוי גרוע משתיקה, אז SKIP."

    internal companion object {
        /** Standalone Hebrew connector words that must never end a naming
         *  line (BUG 1, dangling song name). Single-letter prefixes (ה/ו/ב/ל/מ)
         *  normally attach to the next word, so a lone trailing one is broken. */
        val DANGLING_CONNECTORS = setOf("עם", "של", "את", "ה", "ו", "ב", "ל", "מ")

        // ---- anti-repetition ring sizes (enlarged 2026-06-13, diversity fix) --
        // The study found 75% of scripts share their FIRST WORD: the old ring
        // (last 3 of 8) only fought the immediately-preceding lines. Enlarged so
        // the engine refuses an opener/phrasing it used dozens of lines ago.
        const val MEMORY_SIZE = 24
        const val AVOID_COUNT = 8
        const val AVOID_TRUNC = 60

        // A SEPARATE, longer-lived ring of recent OPENERS (the first few words of
        // each aired line) - this directly targets the shared-first-word finding.
        const val OPENER_MEMORY_SIZE = 16
        const val OPENER_AVOID_COUNT = 16
        /** How many leading words define an "opener" for the opener ring. */
        const val OPENER_WORDS = 4
        /** How many recently-used ANGLE indices to exclude before re-picking. */
        const val ANGLE_AVOID = 5

        // ---- sampling temperatures per beat-type (2026-06-13, diversity fix) ---
        // The Gemini request previously carried NO generationConfig, so output
        // was near-deterministic. DjBrain now passes an explicit temperature:
        //  - CREATIVE_TEMP: free-text creative lines (intro / opening / handover /
        //    good-thing narrative / listening-cue / recap opening). Highest, to
        //    break the cloned openers/phrasings.
        //  - STRUCTURED_TEMP: the JSON-array beats (banter / trivia / two-truths).
        //    Lower so the array stays parseable, but still varied; parsing already
        //    tolerates garbage -> emptyList().
        //  - PRECISE_TEMP: refine() + the de-dangle regeneration, where format
        //    fidelity (full sentence, full song name, no dangling connector) is
        //    critical and creativity is not the goal.
        const val CREATIVE_TEMP = 1.15
        const val STRUCTURED_TEMP = 0.9
        const val PRECISE_TEMP = 0.4

        // ---- ANGLE ROTATION (2026-06-13, diversity fix) -----------------------
        // Replaces the old 5-item FLAVOR_NUDGES (a tone nudge) with ~12 DISTINCT
        // angles that reframe HOW the DJ approaches the line - and crucially let
        // it START differently (a question / a fact / a scene / dry one-liner),
        // attacking the shared-opener finding. Each call gets exactly ONE angle,
        // rotated via the injected Random with an exclusion ring. Kept SHORT to
        // honor the sparse-talk law and within the no-hallucination rules.
        val ANGLES = listOf(
            "זווית: תאר במשפט אחד את הווייב של השיר הבא - איך הוא מרגיש, לא מה הוא.",
            "זווית: פתח בשאלה קצרה ואמיתית אל המאזין, בלי לענות עליה.",
            "זווית: קשר את השיר לזיכרון או אסוציאציה קטנה - נוסטלגיה זריזה, בלי להמציא שנה.",
            "זווית: חבר את השיר שהסתיים לבא דרך ניגוד חד (איטי->מהיר, עצוב->שמח).",
            "זווית: וידוי קטן ואישי שלך כשדרן (האמת? חיכיתי לשיר הזה...).",
            "זווית: עגן את הרגע בשעה/אור/מזג האוויר ותן לזה לזרום אל השיר.",
            "זווית: כוון את האוזן לרגע ספציפי בשיר - דרופ, מעבר או סולו.",
            "זווית: צייר תמונה קטנה של איפה המאזין עכשיו (פקק, מטבח, מקלדת) והכנס את השיר לתוכה.",
            "זווית: ישר ויבש - בלי קישוט, רק משפט אחד חד שמכריז על השיר. לקוני בכוונה.",
            "זווית: משחק מילים או אנקדוטה קטנה סביב שם השיר/האמן עצמו.",
            "זווית: המשכיות - התייחס למה שקרה קודם בשידור ובנה גשר רגשי קטן אל הבא.",
            "זווית: תאר את מצב הרוח בחדר עכשיו ותן לשיר להמשיך אותו.",
        )

        // BACKWARD-COMPAT: the original 5-item nudge list, kept so older call
        // sites / tests that reference DjBrain.FLAVOR_NUDGES still resolve. The
        // live variety now comes from [ANGLES]; these remain as a tiny secondary
        // tone hint and are NOT what rotates per call anymore.
        val FLAVOR_NUDGES = listOf(
            "הפעם אפשר זווית אישית קטנה.",
            "הפעם משפט אחד בלבד, חד ויפה.",
            "הפעם אפשר פאן קטן על השיר הבא.",
            "הפעם בלי שאלה רטורית - רק אמירה.",
            "הפעם תיכנס ישר לעניין, בלי חימום.",
        )

        // ---- ANTI-CLICHE (2026-06-13, diversity fix) --------------------------
        // The exact tics the study flagged as the over-used verbal fingerprint
        // (counts in docs/studies/findings-diversity.md). Listed in an avoid
        // fragment so the model has to find fresh connectors.
        val CLICHE_PHRASES = listOf(
            "הגיע הזמן",
            "תגביר את הווליום",
            "תרים את הווליום",
            "אז בוא",
            "אבל עכשיו",
        )

        // ---- PER-MOOD REGISTER / DICTION (2026-06-13, diversity fix) ----------
        // The study found mood barely moved the wording (within-vs-across cosine
        // delta ~noise). A short register hint per mood is woven into prompts so
        // the DICTION differs by mood. "mix" is empty so default-mood prompts
        // stay byte-identical to the pre-diversity prompts.
        val MOOD_REGISTER = mapOf(
            "party" to "משלב: מילים קצרות ופאנציות, סלנג ישראלי חי, קצב גבוה.",
            "late_night" to "משלב: מילים רכות ומושהות, טון לחשני ואינטימי, נשימה ארוכה.",
            "focus" to "משלב: מינימלי וענייני, מעט מילים, בלי קישוטים.",
            "morning" to "משלב: חם ומאיר פנים, חיוך רך בקול, מזמין.",
        )

        // ---- 2026-06-12 DJ writing capabilities (features 7-12) ----------

        /** Taste wink fires only for the listener's very top tracks. */
        const val TASTE_WINK_MAX_RANK = 15

        /** Branded opener of the «משהו טוב לדרך» micro-segment - verbatim. */
        const val GOOD_THING_OPENER = "ומשהו טוב לדרך - "

        /** Hard word cap for the good-thing segment (it stays micro). */
        const val GOOD_THING_WORD_CAP = 30

        /** Banter: keep at most this many turns before the ends-with-A trim. */
        const val BANTER_MAX_TURNS = 4

        /** Banter: a single turn longer than this (words) is dropped, along
         *  with everything after it - we drop turns, never cut words. The
         *  prompt asks for ~15; this is 15 plus a little slack. */
        const val BANTER_TURN_MAX_WORDS = 18

        /** The second voice (B) in banter: ONE fixed trait, always the same.
         *  Kept for backward-compat (the no-index writeBanter delegates to
         *  SIDEKICK_PERSONAS[0], which is byte-identical to this string). */
        const val SIDEKICK_PERSONA =
            "שדרן משנה יבש ולקוני, ספקן חביב - עונה קצר, בלי התלהבות מופרזת, " +
                "אבל בעומק רואים שהוא נהנה."

        /**
         * NEW (2026-06-13, voice variety): a rotation of distinct sidekick
         * personas for B in the banter, so the second voice doesn't always feel
         * like the same person. Index 0 is byte-identical to [SIDEKICK_PERSONA]
         * so the legacy single-arg [writeBanter] is unchanged. The renderer
         * rotates with [sidekickPersonaCount]; an out-of-range index is wrapped
         * (modulo), never throws. APPEND-only - the existing persona text is
         * verbatim.
         */
        val SIDEKICK_PERSONAS = listOf(
            // 0: הציני היבש (== SIDEKICK_PERSONA, verbatim)
            SIDEKICK_PERSONA,
            // 1: הנלהב־מדי
            "שדרן משנה נלהב־מדי, מתפעל מכל דבר וקופץ קדימה - אנרגיה גבוהה, " +
                "מגזים קצת בהתלהבות, חם ומדבק, אבל לא מציף את A.",
            // 2: היודע־כל
            "שדרן משנה יודע־כל חביב, תמיד עם פינת טריוויה או עובדה קטנה - " +
                "בטוח בעצמו ומשועשע, בלי להיות יהיר, ובסוף מוסר בכיף ל-A.",
        )

        /** Count of sidekick personas, exposed so the renderer can rotate. */
        val sidekickPersonaCount: Int get() = SIDEKICK_PERSONAS.size

        /** Trivia: keep at most this many turns (short bit, never a lecture). */
        const val TRIVIA_MAX_TURNS = 4

        /** Trivia: the quizmaster framing for host A. */
        const val QUIZMASTER_FRAMING =
            "פינת טריוויה קצרה וכיפית: A הוא הקוויזמאסטר ושואל שאלת טריוויה " +
                "אחת קלילה ומשעשעת על האמן של השיר הבא בלבד - לא על תאריכים " +
                "מעורפלים ולא על פרטים אזוטריים, מותר שתהיה שעשועית או שאלת דעה. " +
                "B מגיב ומנחש, ו-A נותן את התשובה או חומק בחיוך עם " +
                "\"נגלה אחרי השיר\", ואז מוסר אל המוזיקה."

        /** Two-truths-and-a-lie: keep at most this many turns. */
        const val TWO_TRUTHS_MAX_TURNS = 4
    }

    /** Lenient runtime-only JSON reader for the banter array (no @Serializable). */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- shared prompt fragments ----------------------------------------

    private fun personaLine(): String =
        "אתה $persona, שדרן רדיו ישראלי כריזמטי, שנון ומצחיק שמדבר כמו " +
            "בנאדם אמיתי ברדיו FM - חם, אנרגטי, זורם ואנושי."

    /**
     * NEW (not in the Python; research finding 1, HIGH confidence): the
     * one-listener address doctrine, appended to every prompt. Radio is heard
     * alone - the DJ speaks to a single "you", never to a crowd.
     */
    private fun oneListenerLine(): String =
        "אתה מדבר אל מאזין אחד בלבד, בגוף שני (את/אתה) - כמו חבר שיושב לידו " +
            "ברכב או במטבח. לעולם אל תפנה לקהל: אסור להגיד \"מאזינים\", " +
            "\"המאזינים\", \"כל מי שמאזין\", \"אתם\", \"לכולם\", \"כולם\", \"אנשים\" או \"חברים\" כפנייה אל קהל. " +
            "\"אנחנו\" מותר רק כשמדובר ברגע משותף באמת."

    /**
     * Short budget -> punchy one-liner. Long budget -> genuinely richer content
     * (a cool fact / mini-story / interesting angle), never filler.
     * Python: _depth_line.
     */
    private fun depthLine(budget: Int): String {
        if (budget >= RICH_BUDGET) {
            return "יש לך זמן אוויר אמיתי כאן - אל תמתח משפט אחד, תן תוכן עשיר " +
                "ומעניין: עובדה מגניבה על השיר או האמן, סיפור קטן, או זווית " +
                "מקורית ומשעשעת. שתי-שלוש מחשבות שזורמות, לא מילוי סרק."
        }
        return "שורה זריזה אחת, קולעת ומצחיקה - בלי למתוח."
    }

    private fun witLine(): String =
        "תהיה חכם ומשעשע, ושאף לעשות משחק מילים שנון על שם השיר או שם האמן. " +
            "אל תחזור על אותה פתיחה פעמיים - תפתיע, תהיה מגוון ואנושי."

    /**
     * NEW (2026-06-13, BUG 3 - hallucination risk in trivia / good-thing):
     * forbid asserting invented specific facts. Years, numbers, "first band",
     * "most ... of the sixties" and the like read as confident truth but are
     * unverifiable - so ban them and steer toward the FEELING / style / a light
     * opinion instead. Trivia may still pose a playful opinion/taste question;
     * it just may not state an invented fact as certain.
     */
    private fun noInventedFactsLine(): String =
        "אל תמציא עובדות, שנים, מספרים, או 'הראשון/הכי/הגדול ביותר' - אל תציג " +
            "פרט שאינך בטוח בו כעובדה ודאית. אם אינך בטוח, דבר על התחושה או " +
            "הסגנון של השיר/האמן, או הצב שאלת דעה קלילה, במקום לטעון עובדה."

    /**
     * Real DJs announce / back-announce the tracks. Tell the model to weave the
     * outgoing and incoming song + artist names into the line naturally, with
     * wordplay where it fits - but to vary it, not name them formulaically every
     * single time. Python: _naming_line.
     */
    private fun namingLine(): String =
        "כמו שדרן אמיתי, שזור את שם השיר ושם האמן בתוך המשפט בטבעיות - " +
            "הכרז על השיר שעולה ו/או 'סגור' את השיר שהרגע התנגן (back-announce), " +
            "רצוי עם קריצה או משחק מילים על שם השיר/האמן. אבל אל תעשה את זה " +
            "בכל פעם באותה צורה ולא תמיד - תהיה מגוון, לפעמים רק מזכיר שם אחד, " +
            "לפעמים את שניהם, ולפעמים סתם זורם - שזה יישמע אנושי ולא רובוטי. " +
            "כשאתה כן מזכיר שם, כתוב את שם השיר המלא ו/או שם האמן המלא במפורש - " +
            "לעולם אל תשאיר מילת חיבור תלויה בסוף (כמו 'עם', 'של', 'את') בלי " +
            "השם אחריה, ואל תכתוב 'עם של' בלי שם."

    private fun formatLine(budget: Int): String =
        "דבר בעברית מדוברת בלבד (סלנג ישראלי בסדר גמור), עד $budget מילים. " +
            "אסור לחרוג מ-$budget מילים וחובה לסיים במשפט שלם. החזר אך ורק את " +
            "הטקסט המדובר עצמו - בלי אנגלית, בלי מרכאות, בלי כוכביות או עיצוב, " +
            "בלי הסברים, בלי רשימות ובלי אפשרויות."

    private fun skipLine(): String =
        "אם אין כרגע שום דבר באמת שנון, מעניין או רלוונטי להגיד כאן - עדיף " +
            "שתשתוק. במקרה כזה החזר בדיוק את המילה SKIP (ותו לא)."

    private fun maybeSkip(budget: Int, allowSkip: Boolean): String =
        if (allowSkip) "\n" + skipLine() + "\n" + skipGateLine() + "\n" else ""

    /**
     * NEW (Android mood support, no Python counterpart): when [DjContext.mood]
     * resolves to a [MoodSpec] with a non-empty [MoodSpec.djLine], that Hebrew
     * guidance line is appended to every prompt the brain builds. An empty
     * djLine (the "mix" default) or a null/unknown mood appends NOTHING, so
     * default-mood prompts stay byte-identical to the pre-mood prompts.
     */
    private fun moodLine(ctx: DjContext?): String {
        // SOMBER OVERRIDE (2026-06-13, review finding): on somber national days
        // ([DjContext.somber]) the sacred quiet/respectful tone outranks the
        // mood. An auto-mood (e.g. 'party') must NOT leak its upbeat djLine NOR
        // its per-mood register into a Yom HaZikaron/HaShoah prompt - that would
        // sit beside the calendarLine "no humor / quiet" instruction and
        // contradict it. So suppress BOTH here, exactly like [flavorLine] does.
        if (ctx?.somber == true) return ""
        val line = ctx?.mood?.let { Moods.ALL[it] }?.djLine.orEmpty()
        // PER-MOOD REGISTER (2026-06-13, diversity fix): the diction hint rides
        // right behind the mood djLine, so every prompt site picks it up once.
        // mix / null / unknown leave BOTH empty, keeping default prompts
        // byte-identical to the pre-diversity prompts.
        val djLine = if (line.isEmpty()) "" else "\n" + line
        return djLine + moodRegisterLine(ctx)
    }

    /**
     * NEW (feature 7, Israeli-calendar awareness): when the moment is
     * calendar-special ([DjContext.calendarNote]), let the DJ acknowledge it
     * briefly - AT MOST once, never preachy. SOMBER OVERRIDE: on somber
     * national days ([DjContext.somber]) this instead demands a quiet,
     * respectful, humor-free tone; that line outranks the flavor nudges
     * (see [flavorLine], which returns "" when somber). Returns "" when the
     * moment is ordinary, so default prompts stay byte-identical.
     */
    private fun calendarLine(ctx: DjContext?): String {
        if (ctx == null) return ""
        if (ctx.somber) {
            val head = if (ctx.calendarNote != null) {
                "היום ${ctx.calendarNote} - יום כבד ורציני."
            } else {
                "היום יום לאומי כבד ורציני."
            }
            return "\n" + head + " דבר בטון שקט, מכובד ומאופק: בלי הומור, " +
                "בלי משחקי מילים, בלי קריצות ובלי קלילות - רק חום שקט וכבוד."
        }
        val note = ctx.calendarNote ?: return ""
        return "\n" + "עכשיו $note. אם זה מתאים, אפשר לציין את זה בברכה קצרה " +
            "אחת - לכל היותר פעם אחת, בלי לחפור."
    }

    /**
     * NEW (feature 9, taste wink): when the next song is one of the listener's
     * very top taste tracks ([Song.tasteRank] < [TASTE_WINK_MAX_RANK]) AND the
     * caller opted in, tell the DJ it MAY - one short wink, never a formula -
     * acknowledge that this is one of the listener's big favorites. The CALLER
     * gates frequency (allowTasteWink defaults to false everywhere), so
     * default prompts stay byte-identical.
     */
    private fun tasteWinkLine(nxt: Song, allow: Boolean): String {
        val rank = nxt.tasteRank
        if (!allow || rank == null || rank >= TASTE_WINK_MAX_RANK) return ""
        return "\n" + "השיר הזה הוא מהשירים הכי אהובים על המאזין שלך. " +
            "אתה יכול ברמז אחד, בקריצה, להגיד שזה מהגדולים שלו - " +
            "בלי להגזים ובלי לחזור על זה. לא חובה."
    }

    // ---- per-beat prompts -----------------------------------------------

    private fun prompt(prev: Song?, nxt: Song, budget: Int, allowSkip: Boolean = false, ctx: DjContext? = null, allowTasteWink: Boolean = false): String {
        val prevLine = if (prev != null) {
            "השיר שהרגע התנגן: \"${prev.title}\" של ${prev.artist}."
        } else {
            "זו פתיחת השידור."
        }
        return personaLine() + "\n" +
            prevLine + "\n" +
            "עכשיו עומד להתנגן: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "כתוב קישור מדובר אל השיר הבא. ${depthLine(budget)}\n" +
            namingLine() + "\n" +
            witLine() + "\n" +
            oneListenerLine() + "\n" +
            maybeSkip(budget, allowSkip) +
            formatLine(budget) + moodLine(ctx) + calendarLine(ctx) +
            tasteWinkLine(nxt, allowTasteWink)
    }

    private fun weatherPrompt(nxt: Song, ctx: DjContext, budget: Int, allowSkip: Boolean = false): String =
        personaLine() + "\n" +
            "השעה ${ctx.timeStr}, ${ctx.partOfDay}. מזג האוויר עכשיו: ${ctx.weather}.\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "שלב את השעה/מזג האוויר וזרום אל השיר הבא. ${depthLine(budget)}\n" +
            witLine() + "\n" +
            oneListenerLine() + "\n" +
            maybeSkip(budget, allowSkip) +
            formatLine(budget) + moodLine(ctx) + calendarLine(ctx)

    private fun newsPrompt(nxt: Song, headline: String, budget: Int, allowSkip: Boolean = false, ctx: DjContext? = null): String =
        personaLine() + "\n" +
            "כותרת חדשות עכשווית: \"$headline\".\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "הזכר בקצרה את הכותרת בניסוח שלך (לא מילה במילה) והמשך לשיר. " +
            depthLine(budget) + "\n" +
            witLine() + "\n" +
            oneListenerLine() + "\n" +
            maybeSkip(budget, allowSkip) +
            formatLine(budget) + moodLine(ctx)

    private fun topicPrompt(nxt: Song, topic: String, headline: String, budget: Int, allowSkip: Boolean = false, ctx: DjContext? = null): String =
        personaLine() + " אתה אוהב את הנושא '$topic'.\n" +
            "כותרת עדכנית בנושא $topic: \"$headline\".\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "הזכר את החדשה בנושא $topic בניסוח שלך וזרום לשיר הבא. " +
            depthLine(budget) + "\n" +
            witLine() + "\n" +
            oneListenerLine() + "\n" +
            maybeSkip(budget, allowSkip) +
            formatLine(budget) + moodLine(ctx)

    /**
     * NEW (not in the Python; research finding 3): the very first words of a
     * broadcast session - a real radio OPENING. Greets by part of day (when ctx
     * has one), welcomes the listener into the broadcast, meets them inside
     * their moment, then flows into naming the first song. No SKIP option.
     */
    private fun openingPrompt(nxt: Song, ctx: DjContext, budget: Int): String {
        val momentLine = openingMomentLine(ctx)
        return personaLine() + "\n" +
            "אלו המילים הראשונות של השידור - פתיחה אמיתית של תוכנית רדיו.\n" +
            momentLine + "\n" +
            "קבל את פני המאזין אל השידור, והוסף שורה חמה אחת שפוגשת אותו בתוך " +
            "הרגע שלו - נסיעה בבוקר, קפה ראשון, סוף יום עבודה או שעת לילה " +
            "מאוחרת - לפי מה שמתאים לחלק היום.\n" +
            "ומשם זרום בטבעיות אל השיר הראשון: \"${nxt.title}\" של ${nxt.artist}.\n" +
            oneListenerLine() + "\n" +
            formatLine(budget) + moodLine(ctx) + calendarLine(ctx)
    }

    /**
     * The opening greeting-by-moment line, factored out of [openingPrompt]
     * verbatim so [recapOpeningPrompt] reuses the exact same fragments.
     */
    private fun openingMomentLine(ctx: DjContext): String = when {
        !ctx.partOfDay.isNullOrEmpty() && !ctx.timeStr.isNullOrEmpty() ->
            "השעה ${ctx.timeStr}, ${ctx.partOfDay}. פתח בברכה חמה שמתאימה בדיוק לשעה הזו ביום."
        !ctx.partOfDay.isNullOrEmpty() ->
            "עכשיו ${ctx.partOfDay}. פתח בברכה חמה שמתאימה לחלק הזה של היום."
        else ->
            "פתח בברכת שלום חמה וכללית."
    }

    // ---- output shaping --------------------------------------------------

    /**
     * Optional cheap second pass to sharpen the line. Robust: any failure or
     * empty/garbage result falls back to the original line. Python: _refine.
     */
    suspend fun refine(line: String, budget: Int, ctx: DjContext? = null): String {
        try {
            val p = personaLine() + "\n" +
                "הנה שורת קישור שכתבת: \"$line\".\n" +
                "חדד אותה: יותר שנונה וטבעית, רצוי עם משחק מילים על שם השיר/אמן, " +
                "עד $budget מילים, משפט שלם. החזר רק את השורה המשופרת בעברית " +
                "מדוברת, בלי מרכאות, אנגלית, עיצוב או הסברים.\n" +
                oneListenerLine() + moodLine(ctx)
            val out = finish(client.complete(p, PRECISE_TEMP), budget)
            if (out.isNotBlank()) return out
        } catch (e: Exception) {
            // fall through to the original line
        }
        return line
    }



    // ---- dangling-name guard (BUG 1: dangling song name, 2026-06-13) -------

    /**
     * NEW (2026-06-13, BUG 1 root cause was word-budget truncation in [finish]:
     * a naming clause that sits at the END of a single punctuation-free sentence
     * gets hard-cut to the budget, chopping the song/artist name and leaving a
     * dangling connector like "עם של" or a bare trailing של/עם/את). A pure,
     * regex-free trailing-token scan (no ICU regex - we only use indexOf / char
     * scanning per the platform constraint). Returns true when [line]:
     *   - ends on a standalone Hebrew connector word
     *     (עם / של / את / ה / ו / ב / ל / מ),
     *   - ends on (or is) the broken phrase "עם של" with nothing after it,
     *   - has an "עם של" adjacency ANYWHERE in the line (the token "עם"
     *     IMMEDIATELY followed by the token "של" - a correctly-named line is
     *     "עם <שם השיר> של <האמן>", so adjacent עם->של means the name is gone;
     *     the legit "<title> של <artist>" pattern, where של is preceded by a
     *     REAL name token rather than by עם, is NOT flagged - GAP 1 fix),
     *   - has ANY mid-line sentence segment (split on .!?…) that is itself
     *     end-dangling - catches a broken naming clause followed by a clean
     *     sentence, e.g. "...עם של. שבת שלום ותהנה." (GAP 1 fix),
     *   - ends on a bare "&" (a title that never substituted) or contains a
     *     standalone "&" token anywhere (e.g. left by "X & Y" where Y never
     *     filled), or
     *   - contains an empty "" quote pair (template that failed to fill).
     * Punctuation/quote chars are trimmed off the tail before the word check, so
     * "...עם של." and "...של!" are both caught.
     */
    internal fun danglingName(line: String?): Boolean {
        if (line == null) return false
        // empty-quote pair: a title slot that never got filled.
        if (line.contains("\"\"") || line.contains("''")) return true
        // a standalone "&" token (e.g. left by "X & Y" where Y never filled).
        val ampTokens = line.split(' ', '\t', '\n')
        if (ampTokens.any { it == "&" }) return true
        // GAP 1: "עם של" adjacency ANYWHERE - the token "עם" immediately
        // followed by the token "של" means the song name between them is gone.
        // We compare whole tokens (not substrings) so legit "<title> של <artist>"
        // is never flagged: its של is preceded by a real title token, not by עם.
        val wholeTokens = line.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
        for (k in 0 until wholeTokens.size - 1) {
            val a = wholeTokens[k].trim('.', '!', '?', '…', ',', ':', ';', '"', '\'', '`', '-', '–', '—')
            val b = wholeTokens[k + 1].trim('.', '!', '?', '…', ',', ':', ';', '"', '\'', '`', '-', '–', '—')
            if (a == "עם" && b == "של") return true
        }
        // whole-line end-dangling check.
        if (isEndDangling(line)) return true
        // GAP 1: per-sentence scan - flag if ANY segment is itself end-dangling,
        // catching a mid-line broken naming clause that a clean tail would hide.
        val segments = splitSentences(line)
        if (segments.size > 1) {
            for (seg in segments) if (isEndDangling(seg)) return true
        }
        return false
    }

    /**
     * NEW (GAP 1 helper): true when [text] ENDS on a dangling connector token
     * (עם / של / את / ה / ו / ב / ל / מ), on the broken "עם של" pair, or on a
     * bare "&". Pure char/indexOf scan, no ICU regex. Trailing punctuation /
     * quotes / dashes / whitespace are stripped first so "...עם של." and
     * "...של!" are both caught.
     */
    private fun isEndDangling(text: String): Boolean {
        val tail = " \t\r\n.!?…,:;\"'`-–—&"
        var end = text.length
        while (end > 0 && tail.indexOf(text[end - 1]) >= 0) end -= 1
        val trimmed = text.substring(0, end).trimEnd()
        if (trimmed.isEmpty()) {
            // nothing but punctuation - or a bare "&" was the whole tail.
            return text.trimEnd().endsWith("&")
        }
        val tokens = trimmed.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val last = tokens.last()
        if (last in DANGLING_CONNECTORS) return true
        // "עם של" pair left at the very end (name never followed).
        if (tokens.size >= 2 && tokens[tokens.size - 2] == "עם" && last == "של") return true
        return false
    }

    /**
     * NEW (GAP 1 / GAP 3 helper): split [text] into sentence segments on the
     * ender chars [.!?…], keeping the trailing ender with each segment. Pure
     * char scan (no ICU regex). Blank segments are dropped.
     */
    private fun splitSentences(text: String): List<String> {
        val enders = ".!?…"
        val out = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            if (enders.indexOf(text[i]) >= 0) {
                val seg = text.substring(start, i + 1).trim()
                if (seg.isNotEmpty()) out.add(seg)
                start = i + 1
            }
        }
        if (start < text.length) {
            val seg = text.substring(start).trim()
            if (seg.isNotEmpty()) out.add(seg)
        }
        return out
    }

    /**
     * NEW (BUG 1): repair a finished line that [danglingName] flagged, WITHOUT a
     * further LLM call - used as the last-resort fallback so we never air a
     * dangling connector. Prefer cutting back to the last sentence boundary that
     * leaves a clean, non-dangling line; otherwise strip the trailing connector
     * tokens (and a trailing "עם של") so the line ENDS cleanly, and drop any
     * standalone "&" token left ANYWHERE in the line (GAP 3a - e.g. an artist
     * like "The Mamas & The Papas" whose halves never substituted). Never
     * returns a line that still ends on a dangling connector or contains a
     * trailing "עם של"; combined with [deDangleNaming]'s re-check the aired line
     * is guaranteed non-dangling.
     */
    internal fun repairDangling(line: String): String {
        // 1) try cutting back to a sentence boundary that is itself clean.
        val enders = ".!?…"
        var i = line.length - 1
        while (i >= 0) {
            if (enders.indexOf(line[i]) >= 0) {
                val candidate = line.substring(0, i + 1).trim()
                if (candidate.isNotEmpty() && !danglingName(candidate)) return candidate
            }
            i -= 1
        }
        // 2) no clean sentence boundary - strip trailing dangling tokens.
        var tokens = line.split(' ', '\t', '\n').filter { it.isNotEmpty() }.toMutableList()
        while (tokens.isNotEmpty()) {
            var t = tokens.last()
            // strip trailing punctuation/quote/& off this token for the test.
            t = t.trimEnd('.', '!', '?', '…', ',', ':', ';', '"', '\'', '`', '-', '–', '—', '&', ' ')
            if (t.isEmpty() || t in DANGLING_CONNECTORS) {
                tokens.removeAt(tokens.size - 1)
            } else {
                break
            }
        }
        // GAP 3a: drop any standalone "&" token left ANYWHERE in the line (a "&"
        // with spaces around it, not part of a word) so it can never survive.
        tokens = tokens.filter { tok ->
            tok.trim('.', '!', '?', '…', ',', ':', ';', '"', '\'', '`', '-', '–', '—', '&', ' ').isNotEmpty()
        }.toMutableList()
        val joined = tokens.joinToString(" ").trim().trimEnd(',', '-', '–', '—', ':', ';', '&', ' ')
        return joined.trim()
    }

    /**
     * NEW (BUG 1): a sharper regeneration instruction appended on the ONE retry
     * when a naming-beat result came back dangling. Demands a complete sentence
     * with the full song + artist name and no trailing connector.
     */
    private fun regenNamingLine(): String =
        "\nכתוב משפט שלם שכולל את שם השיר המלא ואת שם האמן, אל תשאיר מילת חיבור " +
            "תלויה בסוף (כמו 'עם', 'של', 'את') ואל תכתוב 'עם של' בלי שם."

    /**
     * NEW (BUG 1): defensive wrapper for the naming beats. [out] is the already
     * finished line; if it is dangling, regenerate ONCE with the sharper
     * [regenNamingLine] appended to [basePrompt], re-finish, and if that is still
     * dangling fall back to [repairDangling]. Non-dangling lines pass straight
     * through unchanged, so the default (good) path is byte-identical to before.
     * Any LLM failure on the retry falls back to repairing the original line -
     * never throws.
     *
     * GUARANTEE (GAP 3b, 2026-06-13): the returned line is NEVER one for which
     * [danglingName] is true. [repairDangling] is re-checked; if it still
     * dangles, a final per-sentence pass ([finalDeDangle]) drops every dangling
     * sentence segment and keeps the clean ones, falling back to the longest
     * clean prefix - so a twice-dangling input (incl. a mid-line "עם של" or a
     * surviving standalone "&") still yields a clean, airable line.
     */
    private suspend fun deDangleNaming(out: String, basePrompt: String, budget: Int): String {
        if (!danglingName(out)) return out
        val retry = try {
            finish(client.complete(basePrompt + regenNamingLine(), PRECISE_TEMP), budget)
        } catch (e: Exception) {
            ""
        }
        if (retry.isNotBlank() && !danglingName(retry)) return retry
        val repaired = repairDangling(out)
        if (repaired.isNotBlank() && !danglingName(repaired)) return repaired
        // GAP 3b: repair still dangles (mid-line "עם של", surviving "&", ...) -
        // final safe per-sentence pass that GUARANTEES a non-dangling result.
        return finalDeDangle(if (repaired.isNotBlank()) repaired else out)
    }

    /**
     * NEW (GAP 3b): last-resort de-dangle that MUST return a non-dangling line.
     * Keeps only the sentence segments that are themselves clean (not
     * end-dangling and free of an "עם של" adjacency); if any survive, returns
     * them joined. If none do, walks back token-by-token to the longest clean
     * prefix. As a final guard, if even that dangles it strips trailing dangling
     * tokens via [repairDangling] one more time, returning "" only if nothing
     * clean remains (callers treat "" as "say nothing"). The invariant on exit:
     * danglingName(result) == false.
     */
    private fun finalDeDangle(line: String): String {
        // 1) keep only the clean sentence segments.
        val segments = splitSentences(line)
        val clean = segments.filter { !danglingName(it) }
        if (clean.isNotEmpty()) {
            val joined = clean.joinToString(" ").trim()
            if (joined.isNotEmpty() && !danglingName(joined)) return joined
        }
        // 2) no clean full segment - walk back to the longest clean token prefix.
        val tokens = line.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
        var n = tokens.size
        while (n > 0) {
            val prefix = tokens.subList(0, n).joinToString(" ").trim()
            if (prefix.isNotEmpty() && !danglingName(prefix)) return prefix
            n -= 1
        }
        // 3) final guard - strip trailing dangling tokens once more.
        val repaired = repairDangling(line)
        return if (repaired.isNotBlank() && !danglingName(repaired)) repaired else ""
    }

    // ---- public API ------------------------------------------------------

    /**
     * Python: write_intro. [ctx] (optional) contributes the mood line plus the
     * calendar/somber line. [allowTasteWink] (feature 9) lets the CALLER gate
     * how often the taste wink may fire; the default false keeps prompts
     * byte-identical to the pre-wink behavior.
     */
    suspend fun writeIntro(prev: Song?, nxt: Song, seconds: Double, ctx: DjContext? = null, allowTasteWink: Boolean = false): String {
        val budget = wordsForSeconds(seconds)
        val base = prompt(prev, nxt, budget, ctx = ctx, allowTasteWink = allowTasteWink) + flavorLine(ctx) + avoidLine()
        val out = deDangleNaming(finish(client.complete(base, CREATIVE_TEMP), budget), base, budget)
        remember(out)
        return out
    }

    /**
     * NEW (no Python counterpart): the session opening - the very first words
     * of the broadcast. Always speaks (no SKIP), honors the word budget.
     */
    suspend fun writeOpening(nxt: Song, ctx: DjContext, seconds: Double): String {
        val budget = wordsForSeconds(seconds)
        val base = openingPrompt(nxt, ctx, budget) + avoidLine()
        val out = deDangleNaming(finish(client.complete(base, CREATIVE_TEMP), budget), base, budget)
        remember(out)
        return out
    }

    /**
     * Python: write_break. Choose the weather/news/topic prompt when the beat
     * matches AND the ctx field is present; otherwise the song-handoff prompt
     * (which also honors allowSkip). If allowSkip and the model returns SKIP,
     * return null; else return the finished line.
     */
    suspend fun writeBreak(
        prev: Song?,
        nxt: Song,
        beat: String,
        ctx: DjContext,
        seconds: Double,
        topic: String? = null,
        allowSkip: Boolean = false,
    ): String? {
        val budget = wordsForSeconds(seconds)
        var isNaming = false
        val p = when {
            beat == "weather" && !ctx.weather.isNullOrEmpty() ->
                weatherPrompt(nxt, ctx, budget, allowSkip) + backAnnounceLine(prev)
            beat == "news" && !ctx.generalHeadline.isNullOrEmpty() ->
                newsPrompt(nxt, ctx.generalHeadline, budget, allowSkip, ctx) + backAnnounceLine(prev)
            beat == "topic" && topic != null && !ctx.topicHeadlines[topic].isNullOrEmpty() ->
                topicPrompt(nxt, topic, ctx.topicHeadlines.getValue(topic), budget, allowSkip, ctx) + backAnnounceLine(prev)
            else -> {
                // song beat / fallback -> witty handoff (honors allowSkip too).
                // This is the naming beat, so it gets the dangling-name guard.
                isNaming = true
                prompt(prev, nxt, budget, allowSkip, ctx)
            }
        }
        val base = p + flavorLine(ctx) + avoidLine()
        val raw = client.complete(base, CREATIVE_TEMP)
        if (allowSkip && isSkip(raw)) return null
        var out = finish(raw, budget)
        if (isNaming) out = deDangleNaming(out, base, budget)
        remember(out)
        return out
    }

    // ---- day-part handover (feature 8) ------------------------------------

    private fun handoverPrompt(ctx: DjContext, nxt: Song, budget: Int): String {
        val transitionLine = when {
            !ctx.partOfDay.isNullOrEmpty() && !ctx.timeStr.isNullOrEmpty() ->
                "השעה ${ctx.timeStr}, ומתחיל עכשיו חלק חדש של היום: ${ctx.partOfDay}."
            !ctx.partOfDay.isNullOrEmpty() ->
                "מתחיל עכשיו חלק חדש של היום: ${ctx.partOfDay}."
            else ->
                "מתחיל עכשיו חלק חדש של היום."
        }
        return personaLine() + "\n" +
            transitionLine + "\n" +
            "כתוב משפט חם ואינטימי אחד (לכל היותר שניים) שמסמן את חילופי הזמן " +
            "ביום - תזכיר את השעה או את התחושה של הרגע הזה, בלי להכריז על זה " +
            "כמו קריין. דוגמאות לתחושה (אל תצטט אותן): " +
            "\"עשר בלילה. העיר נרגעת - גם אנחנו.\" / " +
            "\"שש בערב. היום משחרר את האחיזה.\"\n" +
            "ומשם מסור אל השיר הבא בשמו: \"${nxt.title}\" של ${nxt.artist}.\n" +
            oneListenerLine() + "\n" +
            formatLine(budget) + moodLine(ctx) + calendarLine(ctx)
    }

    /**
     * NEW (feature 8): one warm Hebrew beat marking a day-part transition.
     * [DjContext.partOfDay] is the NEW part of day. Always speaks (no SKIP),
     * budget like [writeIntro], output remembered in the anti-repetition ring.
     */
    suspend fun writeHandover(ctx: DjContext, nxt: Song, seconds: Double = 8.0): String {
        val budget = wordsForSeconds(seconds)
        val base = handoverPrompt(ctx, nxt, budget) + avoidLine()
        val out = deDangleNaming(finish(client.complete(base, CREATIVE_TEMP), budget), base, budget)
        remember(out)
        return out
    }

    // ---- branded micro-segment: «משהו טוב לדרך» (feature 10) ---------------

    private fun goodThingPrompt(ctx: DjContext, nxt: Song, budget: Int): String {
        val headlines = buildList {
            ctx.generalHeadline?.takeIf { it.isNotEmpty() }?.let { add(it) }
            for (h in ctx.topicHeadlines.values) if (h.isNotEmpty()) add(h)
        }
        val factOption = "עובדה קלילה, אמיתית וניתנת לאימות על ${nxt.artist} - " +
            "האמן של השיר הבא - ורק עליו. אסור להמציא."
        val sourceLine = if (headlines.isEmpty()) {
            "אין כרגע כותרות, אז המקור היחיד המותר: " + factOption
        } else {
            "הכותרות שיש לך כרגע: " +
                headlines.joinToString(" | ") { "\"$it\"" } + ".\n" +
                "בחר את הדבר הכי חיובי ומרים באמת מהכותרות האלה, או לחלופין " +
                factOption
        }
        return personaLine() + "\n" +
            "פינה ממותגת וקצרצרה לפני השיר הבא: \"משהו טוב לדרך\" - דבר אחד " +
            "באמת טוב, חיובי או מרים.\n" +
            sourceLine + "\n" +
            "חובה לפתוח בדיוק במילים: \"$GOOD_THING_OPENER\" ומיד אחריהן התוכן עצמו.\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            noInventedFactsLine() + "\n" +
            oneListenerLine() + "\n" +
            skipLine() + "\n" +
            "והרף כאן גבוה במיוחד: אם אין באמת משהו טוב או מעניין - SKIP.\n" +
            formatLine(budget) + moodLine(ctx) + calendarLine(ctx)
    }

    /**
     * NEW (feature 10): the «משהו טוב לדרך» micro-segment - one genuinely
     * positive item (from the ctx headlines, or a light verifiable fact about
     * the NEXT song's artist only). The output always opens verbatim with
     * [GOOD_THING_OPENER] and is hard-capped at ~[GOOD_THING_WORD_CAP] words
     * regardless of [seconds]. SKIP has a high bar; returns "" on skip.
     */
    suspend fun writeGoodThing(ctx: DjContext, nxt: Song, seconds: Double = 15.0): String {
        val budget = minOf(wordsForSeconds(seconds), GOOD_THING_WORD_CAP)
        val raw = client.complete(goodThingPrompt(ctx, nxt, budget) + avoidLine(), CREATIVE_TEMP)
        if (isSkip(raw)) return ""
        var out = finish(raw, budget)
        if (out.isBlank()) return ""
        val brand = GOOD_THING_OPENER.substringBefore(" - ")
        if (!out.startsWith(brand)) out = GOOD_THING_OPENER + out
        remember(out)
        return out
    }

    // ---- two-voice banter (feature 11) -------------------------------------

    private fun banterPrompt(prev: Song?, nxt: Song, ctx: DjContext, budget: Int, sidekick: String = SIDEKICK_PERSONA): String {
        val prevLine = if (prev != null) {
            "השיר שהרגע התנגן: \"${prev.title}\" של ${prev.artist}.\n"
        } else {
            ""
        }
        return personaLine() + " אתה המגיש הראשי (A).\n" +
            "לידך באולפן B: $sidekick\n" +
            prevLine +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "כתבו קטע באנטר קצרצר בין A ל-B: 2-3 חילופי דברים לכל היותר, על " +
            "השיר הבא או על אבחנה קטנה אחת מהרגע - מצחיק-חם, בלי עוקצנות " +
            "אמיתית. A פותח, ו-A תמיד סוגר במסירה אל המוזיקה.\n" +
            "כל רפליקה עד 15 מילים, סך הכל עד $budget מילים, עברית מדוברת בלבד.\n" +
            oneListenerLine() + "\n" +
            "החזר אך ורק מערך JSON תקני, בלי שום טקסט אחר, בפורמט: " +
            "[{\"s\":\"A\",\"t\":\"...\"},{\"s\":\"B\",\"t\":\"...\"}]. " +
            "אם אין משהו באמת שנון להגיד - החזר מערך ריק [].\n" +
            moodLine(ctx) + calendarLine(ctx)
    }

    /**
     * First balanced JSON array in [text], found by a depth scan (NO regex)
     * and parsed with the kotlinx tree API. Null when nothing parses.
     */
    private fun firstJsonArray(text: String): JsonArray? {
        var depth = 0
        var start = -1
        for (i in text.indices) {
            when (text[i]) {
                '[' -> {
                    if (depth == 0) start = i
                    depth += 1
                }
                ']' -> if (depth > 0) {
                    depth -= 1
                    if (depth == 0 && start >= 0) {
                        try {
                            val el = json.parseToJsonElement(text.substring(start, i + 1))
                            if (el is JsonArray) return el
                        } catch (e: Exception) {
                            // not valid JSON; keep scanning for a later array
                        }
                        start = -1
                    }
                }
            }
        }
        return null
    }

    /**
     * Strict banter parse: a JSON array of {"s":"A"|"B","t":text} objects;
     * anything malformed -> emptyList() (skip the banter, never throw).
     * Overlong (> [BANTER_TURN_MAX_WORDS] words) or empty turns are dropped
     * together with everything after them - turns are dropped whole, words
     * are never cut. The list is then capped at [BANTER_MAX_TURNS] and
     * trimmed from the end until A speaks last (A hands to the music).
     * Fewer than 2 surviving turns is not banter -> emptyList().
     */
    private fun parseBanterTurns(raw: String): List<Pair<String, String>> =
        parseTurns(raw, BANTER_MAX_TURNS)

    /**
     * Shared two-voice JSON-array parser, used by banter, trivia and
     * two-truths-and-a-lie. A JSON array of {"s":"A"|"B","t":text} objects;
     * anything malformed -> emptyList() (skip, never throw). Overlong
     * (> [BANTER_TURN_MAX_WORDS] words) or empty turns are dropped together
     * with everything after them - turns are dropped whole, words are never
     * cut. The list is then capped at [maxTurns] and trimmed from the end
     * until A speaks last (A hands to the music). Fewer than 2 surviving
     * turns is not a bit -> emptyList().
     */
    private fun parseTurns(raw: String, maxTurns: Int): List<Pair<String, String>> {
        val arr = firstJsonArray(raw) ?: return emptyList()
        val turns = mutableListOf<Pair<String, String>>()
        for (el in arr) {
            val obj = el as? JsonObject ?: return emptyList()
            val s = (obj["s"] as? JsonPrimitive)?.takeIf { it.isString }
                ?.content?.trim()?.uppercase() ?: return emptyList()
            if (s != "A" && s != "B") return emptyList()
            val raw2 = (obj["t"] as? JsonPrimitive)?.takeIf { it.isString }
                ?.content ?: return emptyList()
            val spoken = clean(raw2)
            if (spoken.isEmpty()) break
            if (spoken.split(' ').count { it.isNotBlank() } > BANTER_TURN_MAX_WORDS) break
            turns.add(s to spoken)
        }
        var kept: List<Pair<String, String>> = turns.take(maxTurns)
        while (kept.isNotEmpty() && kept.last().first != "A") kept = kept.dropLast(1)
        if (kept.size < 2) return emptyList()
        return kept
    }

    /**
     * NEW (feature 11): a tiny two-voice exchange before the next song.
     * A = the main host persona, B = [SIDEKICK_PERSONA]. Returns speaker-tagged
     * turns [("A", ...), ("B", ...)]; an empty list means "skip the banter"
     * (model skipped, invalid/unparseable JSON, or too little survived the
     * trims). NEVER fires on somber days: returns emptyList() immediately,
     * without an LLM call. Never throws.
     */
    suspend fun writeBanter(prev: Song?, nxt: Song, ctx: DjContext, seconds: Double = 14.0): List<Pair<String, String>> =
        writeBanter(prev, nxt, ctx, seconds, sidekickIndex = 0)

    /**
     * NEW (2026-06-13, voice variety): banter with a chosen sidekick persona.
     * [sidekickIndex] selects from [SIDEKICK_PERSONAS] (wrapped modulo, so the
     * renderer can rotate freely); index 0 is byte-identical to the legacy
     * single-arg overload. Same 2-3 turn cap, somber-empties (no LLM call),
     * strict JSON parsing and ring-remember as before.
     */
    suspend fun writeBanter(prev: Song?, nxt: Song, ctx: DjContext, seconds: Double, sidekickIndex: Int): List<Pair<String, String>> {
        if (ctx.somber) return emptyList()
        val budget = wordsForSeconds(seconds)
        val sidekick = SIDEKICK_PERSONAS[Math.floorMod(sidekickIndex, SIDEKICK_PERSONAS.size)]
        val raw = try {
            client.complete(banterPrompt(prev, nxt, ctx, budget, sidekick) + avoidLine(), STRUCTURED_TEMP)
        } catch (e: Exception) {
            return emptyList()
        }
        if (isSkip(raw)) return emptyList()
        val turns = parseBanterTurns(raw)
        if (turns.isNotEmpty()) remember(turns.joinToString(" / ") { it.second })
        return turns
    }

    // ---- Friday weekly-recap opening (feature 12) ---------------------------

    private fun recapOpeningPrompt(nxt: Song, ctx: DjContext, budget: Int): String =
        personaLine() + "\n" +
            "אלו המילים הראשונות של השידור - פתיחה אמיתית של תוכנית רדיו.\n" +
            openingMomentLine(ctx) + "\n" +
            "זו פתיחת יום שישי מיוחדת: הסיכום השבועי האישי של המאזין שלך.\n" +
            "הנתונים של השבוע שלו: ${ctx.recapBrief}\n" +
            "קבל את פני המאזין אל השידור, ואז שזור בטבעיות שניים-שלושה מספרים " +
            "קונקרטיים מהנתונים - סיכום שבועי אישי שמוגש בקול של התחנה, הכל " +
            "בעברית בלבד, בלי אף מילה באנגלית. בחר נתון אחד מפתיע במיוחד " +
            "והבלט אותו בקריצה.\n" +
            "ומשם זרום בטבעיות אל השיר הראשון: \"${nxt.title}\" של ${nxt.artist}.\n" +
            oneListenerLine() + "\n" +
            formatLine(budget) + moodLine(ctx) + calendarLine(ctx)

    /**
     * NEW (feature 12): the Friday weekly-recap session opening. Greets like
     * [writeOpening] (same moment fragments), weaves 2-3 concrete numbers from
     * [DjContext.recapBrief] into the welcome (one surprising stat
     * highlighted), then flows into the first song. The caller gates by
     * recapBrief; when it is null/empty this falls back to the plain
     * [writeOpening]. No SKIP - an opening always speaks.
     */
    suspend fun writeRecapOpening(nxt: Song, ctx: DjContext, seconds: Double = 20.0): String {
        if (ctx.recapBrief.isNullOrEmpty()) return writeOpening(nxt, ctx, seconds)
        val budget = wordsForSeconds(seconds)
        // GAP 2: the recap opening is a LIVE naming path (its prompt ends naming
        // the first song "<title> של <artist>"), so it gets the same
        // dangling-name guard as its 4 opening/handover siblings - the plain
        // finish() alone shared the truncation root cause.
        val base = recapOpeningPrompt(nxt, ctx, budget) + avoidLine()
        val out = deDangleNaming(finish(client.complete(base, CREATIVE_TEMP), budget), base, budget)
        remember(out)
        return out
    }

    // ---- trivia game (2026-06-13, fun segment) -----------------------------

    private fun triviaPrompt(nxt: Song, ctx: DjContext, budget: Int): String =
        personaLine() + " אתה המגיש הראשי (A).\n" +
            "לידך באולפן B, שדרן משנה שמשתתף במשחק.\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            QUIZMASTER_FRAMING + "\n" +
            noInventedFactsLine() + "\n" +
            "שמור על זה קצר וכיפי - 2 עד 4 חילופי דברים בלבד, לא הרצאה. " +
            "כל רפליקה עד 15 מילים, סך הכל עד $budget מילים, עברית מדוברת בלבד. " +
            "A פותח בשאלה ו-A תמיד סוגר במסירה אל המוזיקה.\n" +
            oneListenerLine() + "\n" +
            "החזר אך ורק מערך JSON תקני, בלי שום טקסט אחר, בפורמט: " +
            "[{\"s\":\"A\",\"t\":\"...\"},{\"s\":\"B\",\"t\":\"...\"}]. " +
            "אם אין שאלה באמת כיפית להגיד - החזר מערך ריק [].\n" +
            moodLine(ctx) + calendarLine(ctx)

    /**
     * NEW (2026-06-13): a short two-host trivia bit before the next song. A is
     * the quizmaster ([QUIZMASTER_FRAMING]) and poses one light, fun question
     * restricted to the NEXT song's artist (to minimize hallucination); B
     * reacts/guesses; A answers or teases "נגלה אחרי השיר" and hands to music.
     * 2-4 turns. Returns speaker-tagged turns; emptyList() means "skip" (model
     * skipped, invalid/over-cap/empty JSON, or too little survived the trims).
     * NEVER fires on somber days: returns emptyList() immediately, no LLM call.
     * Never throws.
     */
    suspend fun writeTrivia(nxt: Song, ctx: DjContext, seconds: Double = 18.0): List<Pair<String, String>> {
        if (ctx.somber) return emptyList()
        val budget = wordsForSeconds(seconds)
        val raw = try {
            client.complete(triviaPrompt(nxt, ctx, budget) + avoidLine(), STRUCTURED_TEMP)
        } catch (e: Exception) {
            return emptyList()
        }
        if (isSkip(raw)) return emptyList()
        val turns = parseTurns(raw, TRIVIA_MAX_TURNS)
        if (turns.isNotEmpty()) remember(turns.joinToString(" / ") { it.second })
        return turns
    }

    // ---- micro-segment: listening cue (2026-06-13, fun segment) ------------

    private fun listeningCuePrompt(nxt: Song, ctx: DjContext, budget: Int): String =
        personaLine() + "\n" +
            "השיר שעולה עכשיו: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "כתוב שורה חמה אחת שמכוונת את האוזן של המאזין לרגע מגניב אחד בשיר " +
            "שעומד להתנגן - הדרופ, מעבר יפה, הרמוניה, סולו, רגע שקט - " +
            "\"שים לב לרגע ב...\". טבעי לגמרי, בלי קלישאות.\n" +
            oneListenerLine() + "\n" +
            skipLine() + "\n" +
            "והרף כאן גבוה: אם אין באמת רגע אמיתי ומעניין להצביע עליו - SKIP.\n" +
            formatLine(budget) + moodLine(ctx) + calendarLine(ctx)

    /**
     * NEW (2026-06-13): one warm single-voice line pointing the listener's ear
     * at a genuinely cool moment in the song about to play. Fully natural,
     * SKIP-gated (returns "" when there's nothing real to say, including on
     * somber days). Output remembered in the ring.
     */
    suspend fun writeListeningCue(nxt: Song, ctx: DjContext, seconds: Double = 10.0): String {
        if (ctx.somber) return ""
        val budget = wordsForSeconds(seconds)
        val raw = try {
            client.complete(listeningCuePrompt(nxt, ctx, budget) + avoidLine(), CREATIVE_TEMP)
        } catch (e: Exception) {
            return ""
        }
        if (isSkip(raw)) return ""
        val out = finish(raw, budget)
        if (out.isBlank()) return ""
        remember(out)
        return out
    }

    // ---- micro-segment: two truths and a lie (2026-06-13, fun segment) -----

    private fun twoTruthsLiePrompt(nxt: Song, ctx: DjContext, budget: Int): String =
        personaLine() + " אתה המגיש הראשי (A).\n" +
            "לידך באולפן B, שדרן משנה שמנחש.\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "פינת \"שתי אמיתות ושקר\" קצרצרה ומשעשעת על ${nxt.artist} - האמן " +
            "של השיר הבא בלבד: A זורק שלוש אמירות שאחת מהן שקר, B מנחש איזו, " +
            "ו-A חושף ומוסר אל המוזיקה. הגבל הכל לאמן הזה, ונסח את ה'עובדות' " +
            "ברוח קלילה ומשוערת - כך שגם ניחוש שגוי נשאר מקסים, בלי להציג " +
            "המצאה כעובדה ודאית.\n" +
            "2 עד 4 חילופי דברים, כל רפליקה עד 15 מילים, סך הכל עד $budget " +
            "מילים, עברית מדוברת בלבד. A פותח ו-A תמיד סוגר אל המוזיקה.\n" +
            oneListenerLine() + "\n" +
            "החזר אך ורק מערך JSON תקני, בלי שום טקסט אחר, בפורמט: " +
            "[{\"s\":\"A\",\"t\":\"...\"},{\"s\":\"B\",\"t\":\"...\"}]. " +
            "אם אין משהו באמת כיפי להגיד - החזר מערך ריק [].\n" +
            moodLine(ctx) + calendarLine(ctx)

    /**
     * NEW (2026-06-13): a short two-host "two truths and a lie" bit, restricted
     * to the NEXT song's artist and phrased speculatively so a wrong guess is
     * still charming (no invented fact presented as certain). A poses, B
     * guesses, A reveals and hands to music. 2-4 turns. Returns speaker-tagged
     * turns; emptyList() means "skip". NEVER fires on somber days (no LLM
     * call). Never throws.
     */
    suspend fun writeTwoTruthsLie(nxt: Song, ctx: DjContext, seconds: Double = 16.0): List<Pair<String, String>> {
        if (ctx.somber) return emptyList()
        val budget = wordsForSeconds(seconds)
        val raw = try {
            client.complete(twoTruthsLiePrompt(nxt, ctx, budget) + avoidLine(), STRUCTURED_TEMP)
        } catch (e: Exception) {
            return emptyList()
        }
        if (isSkip(raw)) return emptyList()
        val turns = parseTurns(raw, TWO_TRUTHS_MAX_TURNS)
        if (turns.isNotEmpty()) remember(turns.joinToString(" / ") { it.second })
        return turns
    }
}