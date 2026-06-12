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

    private fun remember(line: String?) {
        if (line.isNullOrBlank()) return
        synchronized(memoryLock) {
            recentLines.addLast(line)
            while (recentLines.size > MEMORY_SIZE) recentLines.removeFirst()
        }
    }

    /** Snapshot for tests/debugging; newest line last. */
    internal fun recentLinesSnapshot(): List<String> =
        synchronized(memoryLock) { recentLines.toList() }

    private fun avoidLine(): String {
        val recent = synchronized(memoryLock) { recentLines.toList() }
        if (recent.isEmpty()) return ""
        val items = recent.takeLast(AVOID_COUNT)
            .joinToString(" | ") { "\"" + it.take(AVOID_TRUNC) + "\"" }
        return "\n" + "אל תחזור על הפתיחים/הניסוחים האלה מהשורות האחרונות שלך: " +
            items + ". תפתח ותנסח אחרת לגמרי."
    }

    /**
     * NEW: one short randomized angle-nudge per spoken line, so consecutive
     * links don't all share the same flavor. Chosen via the injectable
     * [random] (tests pass a seeded Random). Nudges are deliberately short
     * and respect the no-over-talk law.
     */
    private fun flavorLine(ctx: DjContext? = null): String =
        if (ctx?.somber == true) "" // somber days outrank flavor: no fun-angle nudge at all
        else "\n" + FLAVOR_NUDGES[random.nextInt(FLAVOR_NUDGES.size)]

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
        const val MEMORY_SIZE = 8
        const val AVOID_COUNT = 3
        const val AVOID_TRUNC = 60
        val FLAVOR_NUDGES = listOf(
            "הפעם אפשר זווית אישית קטנה.",
            "הפעם משפט אחד בלבד, חד ויפה.",
            "הפעם אפשר פאן קטן על השיר הבא.",
            "הפעם בלי שאלה רטורית - רק אמירה.",
            "הפעם תיכנס ישר לעניין, בלי חימום.",
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

        /** The second voice (B) in banter: ONE fixed trait, always the same. */
        const val SIDEKICK_PERSONA =
            "שדרן משנה יבש ולקוני, ספקן חביב - עונה קצר, בלי התלהבות מופרזת, " +
                "אבל בעומק רואים שהוא נהנה."
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
            "\"המאזינים\", \"כל מי שמאזין\", \"אתם\", \"לכולם\" או \"חברים\". " +
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
            "לפעמים את שניהם, ולפעמים סתם זורם - שזה יישמע אנושי ולא רובוטי."

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
        val line = ctx?.mood?.let { Moods.ALL[it] }?.djLine.orEmpty()
        return if (line.isEmpty()) "" else "\n" + line
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
            val out = finish(client.complete(p), budget)
            if (out.isNotBlank()) return out
        } catch (e: Exception) {
            // fall through to the original line
        }
        return line
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
        val text = client.complete(prompt(prev, nxt, budget, ctx = ctx, allowTasteWink = allowTasteWink) + flavorLine(ctx) + avoidLine())
        val out = finish(text, budget)
        remember(out)
        return out
    }

    /**
     * NEW (no Python counterpart): the session opening - the very first words
     * of the broadcast. Always speaks (no SKIP), honors the word budget.
     */
    suspend fun writeOpening(nxt: Song, ctx: DjContext, seconds: Double): String {
        val budget = wordsForSeconds(seconds)
        val text = client.complete(openingPrompt(nxt, ctx, budget) + avoidLine())
        val out = finish(text, budget)
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
        val p = when {
            beat == "weather" && !ctx.weather.isNullOrEmpty() ->
                weatherPrompt(nxt, ctx, budget, allowSkip) + backAnnounceLine(prev)
            beat == "news" && !ctx.generalHeadline.isNullOrEmpty() ->
                newsPrompt(nxt, ctx.generalHeadline, budget, allowSkip, ctx) + backAnnounceLine(prev)
            beat == "topic" && topic != null && !ctx.topicHeadlines[topic].isNullOrEmpty() ->
                topicPrompt(nxt, topic, ctx.topicHeadlines.getValue(topic), budget, allowSkip, ctx) + backAnnounceLine(prev)
            else ->
                // song beat / fallback -> witty handoff (honors allowSkip too)
                prompt(prev, nxt, budget, allowSkip, ctx)
        }
        val raw = client.complete(p + flavorLine(ctx) + avoidLine())
        if (allowSkip && isSkip(raw)) return null
        val out = finish(raw, budget)
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
        val text = client.complete(handoverPrompt(ctx, nxt, budget) + avoidLine())
        val out = finish(text, budget)
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
        val raw = client.complete(goodThingPrompt(ctx, nxt, budget) + avoidLine())
        if (isSkip(raw)) return ""
        var out = finish(raw, budget)
        if (out.isBlank()) return ""
        val brand = GOOD_THING_OPENER.substringBefore(" - ")
        if (!out.startsWith(brand)) out = GOOD_THING_OPENER + out
        remember(out)
        return out
    }

    // ---- two-voice banter (feature 11) -------------------------------------

    private fun banterPrompt(prev: Song?, nxt: Song, ctx: DjContext, budget: Int): String {
        val prevLine = if (prev != null) {
            "השיר שהרגע התנגן: \"${prev.title}\" של ${prev.artist}.\n"
        } else {
            ""
        }
        return personaLine() + " אתה המגיש הראשי (A).\n" +
            "לידך באולפן B: $SIDEKICK_PERSONA\n" +
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
    private fun parseBanterTurns(raw: String): List<Pair<String, String>> {
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
        var kept: List<Pair<String, String>> = turns.take(BANTER_MAX_TURNS)
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
    suspend fun writeBanter(prev: Song?, nxt: Song, ctx: DjContext, seconds: Double = 14.0): List<Pair<String, String>> {
        if (ctx.somber) return emptyList()
        val budget = wordsForSeconds(seconds)
        val raw = try {
            client.complete(banterPrompt(prev, nxt, ctx, budget) + avoidLine())
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
        val text = client.complete(recapOpeningPrompt(nxt, ctx, budget) + avoidLine())
        val out = finish(text, budget)
        remember(out)
        return out
    }
}