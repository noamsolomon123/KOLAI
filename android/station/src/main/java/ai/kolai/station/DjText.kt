package ai.kolai.station

/**
 * Top-level DJ text helpers, ported 1:1 from backend/radioai/djbrain.py
 * (words_for_seconds, _is_skip, _clean, _finish). DjBrain delegates all output
 * shaping here.
 *
 * Unicode note: the Python `re` patterns here do NOT rely on Unicode-aware \w/\b
 * — the only character-class that distinguishes scripts is the latin stripper
 * [A-Za-z]+, an explicit ASCII range on purpose (it removes leaked English while
 * leaving Hebrew + digits + punctuation untouched). So none of these compiled
 * regexes need (and none use) the inline (?U) flag, which is JDK-only and would
 * crash Android's ICU regex engine at class-load.
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
 * NEW (2026-06-13, findings-djtext-v3 ROOT-FIX of the mid-line dropped-name
 * class): reconcile the old blanket Latin strip with the dangling-name bug.
 *
 * The original code did LATIN.replace(t, " ") - it deleted EVERY Latin run so
 * the Hebrew TTS never tried to read English letters. But that also deleted the
 * NAME of an intl (Latin-titled) song the DJ was announcing, collapsing
 * "תגביר ל-Language של ..." into the dangling connector skeleton "תגביר ל- של".
 * The v3 250-song study traced ~9% of scripts to exactly this.
 *
 * TRADEOFF CHOSEN: keep a Latin run ONLY when it sits in the NAME slot of a
 * naming clause - i.e. the token is glued to a Hebrew maqaf-prefix ("ל-Language",
 * "ה-Beatles") OR its immediate neighbor is a strong Hebrew connector
 * (של / עם / את), where a real song/artist name belongs. A TTS reading an
 * English NAME aloud is far better than airing a meaningless "של של" skeleton.
 * STRAY leaked English (a word with no connector/prefix context, e.g. a leftover
 * "world", or a fully-English line) is still stripped, preserving the original
 * "don't read leaked English" intent. (Parenthetical English notes are removed
 * upstream in clean() before we get here.)
 *
 * Pure scan, no Unicode-aware regex (Android ICU constraint): we test chars
 * against the Hebrew block and the ASCII [A-Za-z] range directly, and only the
 * KEPT tokens are passed through untouched - all other tokens still go through
 * the original LATIN strip.
 */
private val STRONG_CONNECTOR_TOKENS = setOf("של", "עם", "את")
private val LATIN_PREFIX_LETTERS = "הובלמ"

private fun tokenHasLatin(tok: String): Boolean = tok.any { it in 'A'..'Z' || it in 'a'..'z' }

/** A token like "ל-Language" / "ה-Beatles": a Hebrew prefix, a maqaf/dash, then Latin. */
private fun isPrefixedLatinName(tok: String): Boolean {
    if (tok.length < 3) return false
    if (LATIN_PREFIX_LETTERS.indexOf(tok[0]) < 0) return false
    if (tok[1] != '-' && tok[1] != '–' && tok[1] != '—') return false
    return tok.substring(2).any { it in 'A'..'Z' || it in 'a'..'z' }
}

/** The bare word of a token, with edge punctuation/quotes/maqaf trimmed. */
private fun bareToken(tok: String): String =
    tok.trim('.', '!', '?', '…', ',', ':', ';', '"', '\'', '`', '-', '–', '—', '&', ' ')

private fun stripStrayLatin(t: String): String {
    val tokens = t.split(' ')
    val out = StringBuilder()
    for (i in tokens.indices) {
        if (i > 0) out.append(' ')
        val tok = tokens[i]
        if (tok.isEmpty() || !tokenHasLatin(tok)) {
            // no Latin in this token - emit verbatim (Hebrew / digits / punct).
            out.append(tok)
            continue
        }
        val prev = if (i > 0) bareToken(tokens[i - 1]) else ""
        val next = if (i + 1 < tokens.size) bareToken(tokens[i + 1]) else ""
        val isName = isPrefixedLatinName(tok) ||
            prev in STRONG_CONNECTOR_TOKENS ||
            next in STRONG_CONNECTOR_TOKENS
        // KEEP a Latin NAME token in the connector slot; STRIP stray English.
        out.append(if (isName) tok else LATIN.replace(tok, " "))
    }
    return out.toString()
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
    // remove leaked latin words / stray english ONLY on all-Latin lines; keep an
    // intl song/artist NAME embedded inside a Hebrew utterance (findings-djtext-v3
    // root-fix: deleting the name produced the dangling connector skeleton).
    t = stripStrayLatin(t)
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