package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Setlist text-parsing helpers, ported 1:1 from backend/radioai/setlist.py
 * (_extract_json_array, _ALT_VERSION, _base_title, parse_setlist).
 *
 * JSON uses kotlinx-serialization-json at RUNTIME only (no @Serializable, no
 * serialization compiler plugin): [extractJsonArray] returns the parsed
 * List<JsonObject>, exactly like the Python returns a list of dicts.
 *
 * Unicode note: Python re matches \w against Unicode by default, so Hebrew
 * letters count as word chars. On the JVM, \w is ASCII-only by default, and BOTH
 * the inline (?U) token and the Pattern.UNICODE_CHARACTER_CLASS flag are rejected
 * by Android's ICU regex engine at class-load (PatternSyntaxException /
 * "UNICODE_CHARACTER_CLASS flag not supported"). To stay Unicode-aware on the JVM
 * AND load cleanly on Android, NON_WORD spells out the word class explicitly as
 * \p{L}\p{N}_ (Unicode letters + numbers + underscore — the exact definition of
 * Unicode \w), which both engines support with no flag. FEAT_SPLIT's \b
 * boundaries only ever wrap the ASCII keywords feat/ft/featuring/with, so plain
 * \b is identical on both engines for this case.
 */

/** Lenient JSON reader so quirky LLM arrays still parse. */
private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/** Greedy first-array match. Python: re.search(r"\[.*\]", text, re.DOTALL). */
private val GREEDY_ARRAY = Regex("\\[.*\\]", RegexOption.DOT_MATCHES_ALL)

/**
 * Pull the first JSON array out of an LLM reply, tolerating code fences, prose,
 * and trailing commentary. Tries the greedy match first, then the first
 * balanced array if the greedy parse fails. Mirrors Python _extract_json_array
 * (which returns a list of dicts -> here a List<JsonObject>).
 */
fun extractJsonArray(text: String?): List<JsonObject> {
    if (text == null) throw IllegalArgumentException("No JSON array found in LLM output")

    GREEDY_ARRAY.find(text)?.let { m ->
        parseObjects(m.value)?.let { return it }
    }

    var depth = 0
    var start = -1
    for (i in text.indices) {
        val ch = text[i]
        if (ch == '[') {
            if (depth == 0) start = i
            depth += 1
        } else if (ch == ']' && depth > 0) {
            depth -= 1
            if (depth == 0 && start >= 0) {
                val chunk = text.substring(start, i + 1)
                val parsed = parseObjects(chunk)
                if (parsed != null) return parsed
                start = -1
                // continue scanning (Python continue)
            }
        }
    }
    throw IllegalArgumentException("No JSON array found in LLM output")
}

/**
 * Parse [chunk] as a JSON array, returning its element objects. Returns null on
 * any parse failure OR if the top-level element is not an array (mirrors
 * json.loads raising, caught by the Python try/except). Non-object elements are
 * skipped here; parse_setlist likewise ignores non-dict items downstream.
 */
private fun parseObjects(chunk: String): List<JsonObject>? = try {
    val el = JSON.parseToJsonElement(chunk)
    if (el is JsonArray) el.mapNotNull { it as? JsonObject } else null
} catch (e: Exception) {
    null
}

/**
 * Hints that a "song" is actually an alternate version we never want to queue.
 * Python _ALT_VERSION (re.IGNORECASE), verbatim:
 *   remix|live|sped up|slowed|acoustic|instrumental|karaoke|cover|edit|
 *   reprise|demo|remaster(ed)|re-recorded|taylor's version|extended|
 *   radio edit|mix
 */
val ALT_VERSION: Regex = Regex(
    "\\b(remix|live|sped[\\s-]?up|slowed|acoustic|instrumental|karaoke|" +
        "cover|edit|reprise|demo|remaster(?:ed)?|re-?recorded|" +
        "taylor'?s version|extended|radio edit|mix)\\b",
    RegexOption.IGNORE_CASE,
)

// _base_title sub-patterns. NON_WORD must keep Unicode word chars (Hebrew): it
// uses the explicit Unicode class \p{L}\p{N}_ instead of \w so it is Unicode-aware
// on the JVM with no flag AND loads on Android's ICU engine (which rejects both
// (?U) and the UNICODE_CHARACTER_CLASS flag). FEAT_SPLIT's \b only wraps ASCII
// keywords, so plain \b behaves the same on both engines.
private val BRACKETED = Regex("[\\(\\[\\{].*?[\\)\\]\\}]")
private val DASH_SPLIT = Regex("\\s[-–—]\\s")
private val FEAT_SPLIT = Regex("\\b(?:feat|ft|featuring|with)\\b\\.?")
private val NON_WORD = Regex("[^\\p{L}\\p{N}_\\s]")
private val WHITESPACE = Regex("\\s+")

/**
 * Normalize a title for duplicate detection: drop bracketed/dashed qualifiers
 * like (Live), - Remix, feat. X so two versions of the same underlying song
 * collapse to one key. Mirrors Python _base_title.
 */
fun baseTitle(title: String): String {
    var t = title.lowercase().trim()
    t = BRACKETED.replace(t, " ")
    t = DASH_SPLIT.split(t)[0]
    t = FEAT_SPLIT.split(t)[0]
    t = NON_WORD.replace(t, " ")
    t = WHITESPACE.replace(t, " ").trim()
    return t
}

/**
 * Mirror of Python parse_setlist: extract the JSON array, dedup by exact
 * (title.lower, artist.lower) AND by (baseTitle, artist.lower), fill durationS
 * from a (title.lower, artist.lower) -> durationS map built from
 * taste.topTracks, cap at [n], and throw if nothing usable survives.
 */
fun parseSetlist(text: String, taste: TasteProfile, n: Int = 6): List<Song> {
    val data = extractJsonArray(text)
    val durations: Map<Pair<String, String>, Double> = taste.topTracks.associate { t ->
        Pair(t.title.lowercase(), t.artist.lowercase()) to t.durationS
    }
    val songs = mutableListOf<Song>()
    val seenExact = mutableSetOf<Pair<String, String>>()
    val seenBase = mutableSetOf<Pair<String, String>>()
    for (item in data) {
        // (item is already a JsonObject; Python isinstance(item, dict) guard is
        // enforced upstream in extractJsonArray, which only keeps objects.)
        val title = item.stringField("title").trim()
        val artist = item.stringField("artist").trim()
        if (title.isEmpty() || artist.isEmpty()) continue
        val key = Pair(title.lowercase(), artist.lowercase())
        if (key in seenExact) continue
        val baseKey = Pair(baseTitle(title), artist.lowercase())
        if (baseKey in seenBase) continue
        seenExact.add(key)
        seenBase.add(baseKey)
        songs.add(Song(title = title, artist = artist, durationS = durations[key]))
        if (songs.size >= n) break
    }
    if (songs.isEmpty()) throw IllegalArgumentException("SetlistPlanner produced no usable songs")
    return songs
}

/**
 * Python (item.get("title") or "") semantics: missing field, JSON null, or a
 * non-string value all collapse to "" (numbers/objects are not strings -> "").
 */
private fun JsonObject.stringField(key: String): String {
    val prim = this[key] as? JsonPrimitive ?: return ""
    if (!prim.isString) return ""
    return prim.content
}