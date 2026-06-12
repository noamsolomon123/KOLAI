package ai.kolai.station

import ai.kolai.core.Song
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * LLM "mood curator": asks the LLM which of a GIVEN list of REAL songs fit a
 * vibe, and returns their indices.
 *
 * HALLUCINATION SAFETY: song picking was moved off the LLM because it invented
 * songs on air. This class re-admits the LLM for the one job it is good at --
 * judging vibe fit -- under a contract that makes hallucination structurally
 * impossible: the LLM only SELECTS from a numbered list of songs we hand it,
 * and its answer is a JSON array of indices into that list. It cannot add,
 * rename or invent a song; an out-of-range or malformed index is simply
 * dropped.
 *
 * GRACEFUL DEGRADATION: [fitIndices] NEVER throws. Any LLM failure, parse
 * failure, or missing array returns null, which callers treat as "no mood
 * bias" -- the station keeps playing taste picks exactly as before.
 *
 * CACHING: verdicts ("does song X fit vibe Y?") are cached in memory keyed by
 * `vibeHint.hashCode() + "|" + baseTitle(title)`, so repeated plan() calls in
 * the same mood only ask the LLM about songs it has not judged yet (zero LLM
 * calls when everything is already known). Verdicts survive a later LLM
 * failure: only successful replies write to the cache, and cached verdicts
 * stay usable on the next call.
 */
class MoodCurator(private val client: LlmClient) {

    /** Lenient JSON reader so quirky LLM arrays still parse. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** "vibeHash|baseTitle" -> fits-the-vibe verdict. */
    private val verdicts = ConcurrentHashMap<String, Boolean>()

    /**
     * Returns the indices (into [songs]) that fit [vibeHint], or null on any
     * failure (LLM error, unparseable reply, no JSON array). Cached verdicts
     * are resolved first; the LLM is only asked about songs with no cached
     * verdict for this vibe.
     */
    suspend fun fitIndices(vibeHint: String, songs: List<Song>): Set<Int>? {
        return try {
            if (songs.isEmpty()) return emptySet()
            val vibeKey = vibeHint.hashCode().toString()
            val keys = songs.map { "$vibeKey|${baseTitle(it.title)}" }

            // Resolve cached verdicts; collect the songs we still need to ask
            // about (original indices, in order).
            val fits = HashSet<Int>()
            val unknown = ArrayList<Int>()
            for (i in songs.indices) {
                when (verdicts[keys[i]]) {
                    true -> fits.add(i)
                    false -> Unit
                    null -> unknown.add(i)
                }
            }
            if (unknown.isEmpty()) return fits // fully cached: zero LLM calls

            // Ask ONLY about the unknown songs, renumbered 0..unknown.size-1;
            // the reply's indices are mapped back to the original positions.
            val reply = client.complete(prompt(vibeHint, unknown.map { songs[it] }))
            val fitAsked = parseIndices(reply, unknown.size) ?: return null

            unknown.forEachIndexed { askedIdx, originalIdx ->
                val fit = askedIdx in fitAsked
                verdicts[keys[originalIdx]] = fit
                if (fit) fits.add(originalIdx)
            }
            fits
        } catch (e: Exception) {
            null // NEVER throw: any failure means "no mood bias"
        }
    }

    /** English instruction (the LLM follows English best) + numbered real songs. */
    private fun prompt(vibeHint: String, songs: List<Song>): String {
        val listing = songs.mapIndexed { i, s -> "$i. ${s.artist} — ${s.title}" }
            .joinToString("\n")
        return "You are curating a radio block. Below is a numbered list of " +
            "real songs:\n" +
            listing + "\n\n" +
            "Return ONLY a JSON array of the indices of the songs that fit " +
            "this vibe: $vibeHint\n" +
            "No prose, no code fence, no explanations - just the JSON array " +
            "of numbers. If none fit, return []."
    }

    /**
     * Pull the first JSON array of indices out of [text], tolerating markdown
     * fences and prose around it: take the substring from the first '[' to its
     * matching ']' (plain index scan -- no regex; Android's ICU regex engine
     * rejects Unicode-class flags, so this module avoids regex where it can).
     * Indices outside 0 until [size] and non-int elements are dropped. Returns
     * null when no parseable array is found.
     */
    private fun parseIndices(text: String, size: Int): Set<Int>? {
        val start = text.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var end = -1
        for (i in start until text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return null
        val element = try {
            json.parseToJsonElement(text.substring(start, end + 1))
        } catch (e: Exception) {
            return null
        }
        if (element !is JsonArray) return null
        val out = HashSet<Int>()
        for (el in element) {
            val prim = el as? JsonPrimitive ?: continue
            val idx = prim.content.trim().toIntOrNull() ?: continue
            if (idx in 0 until size) out.add(idx)
        }
        return out
    }
}