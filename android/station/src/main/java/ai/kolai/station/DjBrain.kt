package ai.kolai.station

import ai.kolai.core.Song

/**
 * Single-host DJ line generation, ported 1:1 from backend/radioai/djbrain.py
 * (class DJBrain). All Hebrew prompt strings are verbatim from the Python --
 * output quality depends on them, so do NOT paraphrase.
 *
 * Scope: single-host MVP. The two-voice banter path (Python write_banter /
 * _banter_prompt, plus its _extract_json_array helper) is intentionally NOT
 * ported here.
 * TODO post-MVP: write_banter (two-host)
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
class DjBrain(private val client: LlmClient, private val persona: String) {

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
        if (allowSkip) "\n" + skipLine() + "\n" else ""

    // ---- per-beat prompts -----------------------------------------------

    private fun prompt(prev: Song?, nxt: Song, budget: Int, allowSkip: Boolean = false): String {
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
            formatLine(budget)
    }

    private fun weatherPrompt(nxt: Song, ctx: DjContext, budget: Int, allowSkip: Boolean = false): String =
        personaLine() + "\n" +
            "השעה ${ctx.timeStr}, ${ctx.partOfDay}. מזג האוויר עכשיו: ${ctx.weather}.\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "שלב את השעה/מזג האוויר וזרום אל השיר הבא. ${depthLine(budget)}\n" +
            witLine() + "\n" +
            oneListenerLine() + "\n" +
            maybeSkip(budget, allowSkip) +
            formatLine(budget)

    private fun newsPrompt(nxt: Song, headline: String, budget: Int, allowSkip: Boolean = false): String =
        personaLine() + "\n" +
            "כותרת חדשות עכשווית: \"$headline\".\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "הזכר בקצרה את הכותרת בניסוח שלך (לא מילה במילה) והמשך לשיר. " +
            depthLine(budget) + "\n" +
            witLine() + "\n" +
            oneListenerLine() + "\n" +
            maybeSkip(budget, allowSkip) +
            formatLine(budget)

    private fun topicPrompt(nxt: Song, topic: String, headline: String, budget: Int, allowSkip: Boolean = false): String =
        personaLine() + " אתה אוהב את הנושא '$topic'.\n" +
            "כותרת עדכנית בנושא $topic: \"$headline\".\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "הזכר את החדשה בנושא $topic בניסוח שלך וזרום לשיר הבא. " +
            depthLine(budget) + "\n" +
            witLine() + "\n" +
            oneListenerLine() + "\n" +
            maybeSkip(budget, allowSkip) +
            formatLine(budget)

    /**
     * NEW (not in the Python; research finding 3): the very first words of a
     * broadcast session - a real radio OPENING. Greets by part of day (when ctx
     * has one), welcomes the listener into the broadcast, meets them inside
     * their moment, then flows into naming the first song. No SKIP option.
     */
    private fun openingPrompt(nxt: Song, ctx: DjContext, budget: Int): String {
        val momentLine = when {
            !ctx.partOfDay.isNullOrEmpty() && !ctx.timeStr.isNullOrEmpty() ->
                "השעה ${ctx.timeStr}, ${ctx.partOfDay}. פתח בברכה חמה שמתאימה בדיוק לשעה הזו ביום."
            !ctx.partOfDay.isNullOrEmpty() ->
                "עכשיו ${ctx.partOfDay}. פתח בברכה חמה שמתאימה לחלק הזה של היום."
            else ->
                "פתח בברכת שלום חמה וכללית."
        }
        return personaLine() + "\n" +
            "אלו המילים הראשונות של השידור - פתיחה אמיתית של תוכנית רדיו.\n" +
            momentLine + "\n" +
            "קבל את פני המאזין אל השידור, והוסף שורה חמה אחת שפוגשת אותו בתוך " +
            "הרגע שלו - נסיעה בבוקר, קפה ראשון, סוף יום עבודה או שעת לילה " +
            "מאוחרת - לפי מה שמתאים לחלק היום.\n" +
            "ומשם זרום בטבעיות אל השיר הראשון: \"${nxt.title}\" של ${nxt.artist}.\n" +
            oneListenerLine() + "\n" +
            formatLine(budget)
    }

    // ---- output shaping --------------------------------------------------

    /**
     * Optional cheap second pass to sharpen the line. Robust: any failure or
     * empty/garbage result falls back to the original line. Python: _refine.
     */
    suspend fun refine(line: String, budget: Int): String {
        try {
            val p = personaLine() + "\n" +
                "הנה שורת קישור שכתבת: \"$line\".\n" +
                "חדד אותה: יותר שנונה וטבעית, רצוי עם משחק מילים על שם השיר/אמן, " +
                "עד $budget מילים, משפט שלם. החזר רק את השורה המשופרת בעברית " +
                "מדוברת, בלי מרכאות, אנגלית, עיצוב או הסברים.\n" +
                oneListenerLine()
            val out = finish(client.complete(p), budget)
            if (out.isNotBlank()) return out
        } catch (e: Exception) {
            // fall through to the original line
        }
        return line
    }

    // ---- public API ------------------------------------------------------

    /** Python: write_intro. */
    suspend fun writeIntro(prev: Song?, nxt: Song, seconds: Double): String {
        val budget = wordsForSeconds(seconds)
        val text = client.complete(prompt(prev, nxt, budget))
        return finish(text, budget)
    }

    /**
     * NEW (no Python counterpart): the session opening - the very first words
     * of the broadcast. Always speaks (no SKIP), honors the word budget.
     */
    suspend fun writeOpening(nxt: Song, ctx: DjContext, seconds: Double): String {
        val budget = wordsForSeconds(seconds)
        val text = client.complete(openingPrompt(nxt, ctx, budget))
        return finish(text, budget)
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
                weatherPrompt(nxt, ctx, budget, allowSkip)
            beat == "news" && !ctx.generalHeadline.isNullOrEmpty() ->
                newsPrompt(nxt, ctx.generalHeadline, budget, allowSkip)
            beat == "topic" && topic != null && !ctx.topicHeadlines[topic].isNullOrEmpty() ->
                topicPrompt(nxt, topic, ctx.topicHeadlines.getValue(topic), budget, allowSkip)
            else ->
                // song beat / fallback -> witty handoff (honors allowSkip too)
                prompt(prev, nxt, budget, allowSkip)
        }
        val raw = client.complete(p)
        if (allowSkip && isSkip(raw)) return null
        return finish(raw, budget)
    }
}