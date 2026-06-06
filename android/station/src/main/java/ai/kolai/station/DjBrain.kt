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
 */
class DjBrain(private val client: LlmClient, private val persona: String) {

    // ---- shared prompt fragments ----------------------------------------

    private fun personaLine(): String =
        "אתה $persona, שדרן רדיו ישראלי כריזמטי, שנון ומצחיק שמדבר כמו " +
            "בנאדם אמיתי ברדיו FM - חם, אנרגטי, זורם ואנושי."

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
            maybeSkip(budget, allowSkip) +
            formatLine(budget)
    }

    private fun weatherPrompt(nxt: Song, ctx: DjContext, budget: Int, allowSkip: Boolean = false): String =
        personaLine() + "\n" +
            "השעה ${ctx.timeStr}, ${ctx.partOfDay}. מזג האוויר עכשיו: ${ctx.weather}.\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "שלב את השעה/מזג האוויר וזרום אל השיר הבא. ${depthLine(budget)}\n" +
            witLine() + "\n" +
            maybeSkip(budget, allowSkip) +
            formatLine(budget)

    private fun newsPrompt(nxt: Song, headline: String, budget: Int, allowSkip: Boolean = false): String =
        personaLine() + "\n" +
            "כותרת חדשות עכשווית: \"$headline\".\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "הזכר בקצרה את הכותרת בניסוח שלך (לא מילה במילה) והמשך לשיר. " +
            depthLine(budget) + "\n" +
            witLine() + "\n" +
            maybeSkip(budget, allowSkip) +
            formatLine(budget)

    private fun topicPrompt(nxt: Song, topic: String, headline: String, budget: Int, allowSkip: Boolean = false): String =
        personaLine() + " אתה אוהב את הנושא '$topic'.\n" +
            "כותרת עדכנית בנושא $topic: \"$headline\".\n" +
            "השיר הבא: \"${nxt.title}\" של ${nxt.artist}.\n" +
            "הזכר את החדשה בנושא $topic בניסוח שלך וזרום לשיר הבא. " +
            depthLine(budget) + "\n" +
            witLine() + "\n" +
            maybeSkip(budget, allowSkip) +
            formatLine(budget)

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
                "מדוברת, בלי מרכאות, אנגלית, עיצוב או הסברים."
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