package ai.kolai.station

/**
 * NewsRss - ported from backend/radioai/news.py `parse_news_rss`, which pulls
 * the `<title>` of every `<item>` out of a Google News RSS feed.
 *
 * Deviation from the Python: the Python uses xml.etree; :station deliberately
 * has NO XML-parser dependency (and Android's javax XML stack is unavailable to
 * plain JVM unit tests), so this is a small hand-rolled scanner instead:
 * plain indexOf scanning (no regex - Android's regex engine rejects
 * Pattern.UNICODE_CHARACTER_CLASS / (?U), so we avoid regex entirely):
 *  - find each `<item ...>` ... `</item>` region,
 *  - inside it take the FIRST `<title ...>` ... `</title>`,
 *  - unwrap an optional `<![CDATA[ ... ]]>`,
 *  - unescape the five named XML entities.
 * Malformed input never throws - unclosed regions are simply skipped, so the
 * result is empty/partial instead of an exception.
 */

/** Python: parse_news_rss(xml_text) -> list of trimmed item titles. */
fun parseNewsRss(xml: String): List<String> {
    val titles = ArrayList<String>()
    var pos = 0
    while (true) {
        val itemStart = findTagStart(xml, "item", pos) ?: break
        val itemOpenEnd = xml.indexOf('>', itemStart)
        if (itemOpenEnd < 0) break
        val itemEnd = xml.indexOf("</item>", itemOpenEnd + 1)
        if (itemEnd < 0) break
        val region = xml.substring(itemOpenEnd + 1, itemEnd)
        val title = extractFirstTitle(region)
        if (!title.isNullOrEmpty()) titles.add(title)
        pos = itemEnd + "</item>".length
    }
    return titles
}

/**
 * Index of the next `<name` whose tag name is EXACTLY [name] (the next char is
 * '>', whitespace, or '/'), so `<item` never matches e.g. `<items`.
 */
private fun findTagStart(xml: String, name: String, from: Int): Int? {
    var pos = from
    val needle = "<$name"
    while (true) {
        val at = xml.indexOf(needle, pos)
        if (at < 0) return null
        val after = at + needle.length
        if (after >= xml.length) return null
        val c = xml[after]
        if (c == '>' || c == '/' || c.isWhitespace()) return at
        pos = at + 1
    }
}

/** First `<title>` text inside an item region, CDATA-unwrapped + unescaped. */
private fun extractFirstTitle(region: String): String? {
    val tStart = findTagStart(region, "title", 0) ?: return null
    val tOpenEnd = region.indexOf('>', tStart)
    if (tOpenEnd < 0) return null
    val tEnd = region.indexOf("</title>", tOpenEnd + 1)
    if (tEnd < 0) return null
    var content = region.substring(tOpenEnd + 1, tEnd).trim()
    if (content.startsWith("<![CDATA[")) {
        content = content.removePrefix("<![CDATA[")
        val cdEnd = content.indexOf("]]>")
        if (cdEnd >= 0) content = content.substring(0, cdEnd)
    }
    return unescapeXml(content).trim()
}

/**
 * Unescape the five named XML entities. `&amp;` is replaced LAST so that
 * double-escaped text (e.g. `&amp;lt;`) decodes one level only, matching how
 * a real XML parser reads the wire text. Numeric entities are NOT handled
 * (Google News titles never use them).
 */
private fun unescapeXml(s: String): String = s
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")