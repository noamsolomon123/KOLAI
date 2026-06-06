package ai.kolai.station

/**
 * Top-level DJ text helpers, ported 1:1 from backend/radioai/djbrain.py
 * (words_for_seconds, _is_skip, _clean, _finish). DjBrain delegates all output
 * shaping here.
 *
 * Unicode note (critical): Python `re` matches \w / \b against Unicode by
 * default, so Hebrew letters count as word characters. Kotlin/Java regex \w is
 * ASCII-only unless UNICODE_CHARACTER_CLASS is on; we set it inline with (?U)
 * anywhere \w / \b appears so Hebrew behaves exactly like the Python. The latin
 * stripper [A-Za-z]+ is an explicit ASCII range on purpose (it removes leaked
 * English while leaving Hebrew + digits + punctuation untouched).
 */

private const val HEBREW_WORDS_PER_SEC = 2.5

/**
 * A budget at/above this many words is "long" -> ask for richer content
 * (a fact, a small story, an angle) instead of a one-line quip.
 * Python: _RICH_BUDGET.
 */
internal const val RICH_BUDGET = 28

/** Python: _SKIP_TOKEN. */
internal const val SKIP_TOKEN = "SKIP"

/** Python: words_for_seconds. */
fun wordsForSeconds(seconds: Double): Int = (seconds * HEBREW_WORDS_PER_SEC).toInt()

/**
 * Lines the model sometimes leaks despite instructions: stage directions,
 * option markers, English notes. We scrub these from the spoken output.
 * Python: _DIRECTION_LINE (re.IGNORECASE). MULTILINE is irrelevant here because
 * we apply it per already-split line (Python splits lines first too).
 */
private val DIRECTION_LINE = Regex(
    "^\\s*(?:option|note|dj|host|intro|outro|aside|stage|" +
        "\\d+[\\.\\)]|[-*•])\\s*[:\\-]?\\s*",
    RegexOption.IGNORE_CASE,
)

// _clean sub-patterns, ported verbatim.
private val CODE_FENCE = Regex("```[a-zA-Z]*")
private val PARENTHETICAL = Regex("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]")
private val LATIN = Regex("[A-Za-z]+")
private val SPACE_BEFORE_PUNCT = Regex("\\s+([,\\.\\!\\?…:;])")
private val MULTISPACE = Regex("\\s{2,}")

// _is_skip stripper: whitespace + . ! ? ... " ' ` *
private val SKIP_STRIP = Regex("[\\s\\.\\!\\?…\"'`*]+")

// _finish: sentence-ending punctuation . ! ? ...
private val SENTENCE_END = Regex("[.!?…]")

/**
 * Python: _is_skip. None -> true; strip whitespace/quote/punct chars, uppercase,
 * compare to "SKIP".
 */
fun isSkip(text: String?): Boolean {
    if (text == null) return true
    val stripped = SKIP_STRIP.replace(text, "").uppercase()
    return stripped == SKIP_TOKEN
}

/**
 * Python: _clean. Scrub LLM cruft so only spoken Hebrew remains: code fences,
 * markdown, surrounding quotes, leaked stage directions / option lists,
 * parentheticals that carry English notes, and stray latin letters.
 */
fun clean(text: String?): String {
    if (text == null) return ""
    var t = text.trim()
    // strip code fences
    t = CODE_FENCE.replace(t, " ")
    // if the model returned an option list / multiple lines, keep the first
    // substantive line.
    val lines = t.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val picked = mutableListOf<String>()
    for (ln in lines) {
        val ln2 = DIRECTION_LINE.replace(ln, "").trim()
        if (ln2.isNotEmpty()) picked.add(ln2)
    }
    t = if (picked.isNotEmpty()) picked[0] else t.replace("\n", " ")
    // drop parentheticals / brackets (usually leaked directions or English notes)
    t = PARENTHETICAL.replace(t, " ")
    // strip markdown emphasis and quote characters
    t = t.replace("**", " ").replace("*", " ").replace("_", " ").replace("#", " ")
    t = t.replace("“", " ").replace("”", " ")
    t = t.replace("‘", " ").replace("’", "'")
    t = t.replace("\"", " ").replace("`", " ")
    // remove leaked latin words / stray english (keep Hebrew, digits, punctuation)
    t = LATIN.replace(t, " ")
    // tidy whitespace and dangling punctuation
    t = SPACE_BEFORE_PUNCT.replace(t) { m -> m.groupValues[1] }
    t = MULTISPACE.replace(t, " ").trim()
    t = t.trim(' ', '-', '–', '—', ':', ';', ',')
    return t.trim()
}

/**
 * Python: _finish. clean(), then if word count > budget, cut to budget words and
 * trim back to the last sentence-ending punctuation [.!?...]; if none, keep the
 * capped text.
 */
fun finish(text: String?, budget: Int): String {
    val cleaned = clean(text)
    val words = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size <= budget) return cleaned
    val capped = words.take(budget).joinToString(" ")
    val matches = SENTENCE_END.findAll(capped).toList()
    if (matches.isNotEmpty()) {
        return capped.substring(0, matches.last().range.last + 1).trim()
    }
    return capped
}