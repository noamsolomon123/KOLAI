package ai.kolai.core

/**
 * Camelot wheel: maps a musical key to a code like "8A" (minor) / "8B" (major).
 *
 * Harmonic mixing: keys are compatible if equal, relative (same number, A<->B),
 * or adjacent on the wheel (number +/- 1 mod 12, same letter).
 *
 * Ported 1:1 from the Python reference `backend/radioai/keys.py`. The lookup
 * tables, Camelot codes, and relation strings ("same"/"relative"/"adjacent"/
 * "clash") match the Python exactly.
 */

private val MAJOR_TO_CAMELOT: Map<String, String> = mapOf(
    "C" to "8B", "G" to "9B", "D" to "10B", "A" to "11B", "E" to "12B", "B" to "1B",
    "F#" to "2B", "Gb" to "2B", "Db" to "3B", "C#" to "3B", "Ab" to "4B", "G#" to "4B",
    "Eb" to "5B", "D#" to "5B", "Bb" to "6B", "A#" to "6B", "F" to "7B",
)

private val MINOR_TO_CAMELOT: Map<String, String> = mapOf(
    "A" to "8A", "E" to "9A", "B" to "10A", "F#" to "11A", "Gb" to "11A",
    "C#" to "12A", "Db" to "12A", "G#" to "1A", "Ab" to "1A", "D#" to "2A", "Eb" to "2A",
    "A#" to "3A", "Bb" to "3A", "F" to "4A", "C" to "5A", "G" to "6A", "D" to "7A",
)

/**
 * @param tonic like "C", "F#"
 * @param mode "major" or "minor" (any value other than "major" uses the minor table,
 *   matching the Python `MAJOR if mode == "major" else MINOR`).
 * @return Camelot code, e.g. "8B".
 * @throws IllegalArgumentException if the tonic is unknown for the chosen table.
 */
fun camelotFromKey(tonic: String, mode: String): String {
    val table = if (mode == "major") MAJOR_TO_CAMELOT else MINOR_TO_CAMELOT
    return table[tonic]
        ?: throw IllegalArgumentException("Unknown tonic '$tonic' for mode '$mode'")
}

/** Splits a Camelot code into its (number, letter), e.g. "10A" -> (10, "A"). */
private fun parse(code: String): Pair<Int, String> {
    val letter = code.takeLast(1)
    val number = code.dropLast(1).toInt()
    return number to letter
}

/**
 * Harmonic compatibility: true if same key, relative major/minor, or wheel-adjacent.
 * Mirrors `are_keys_compatible`.
 */
fun areKeysCompatible(a: String, b: String): Boolean {
    val (na, la) = parse(a)
    val (nb, lb) = parse(b)
    if (a == b) return true
    if (na == nb && la != lb) return true // relative major/minor
    if (la == lb) {                       // adjacent on the wheel (1..12 wraps)
        val diff = kotlin.math.abs(na - nb)
        return diff == 1 || diff == 11
    }
    return false
}

/**
 * Classify the harmonic relationship between two Camelot codes.
 * Returns "same", "relative", "adjacent", or "clash" — matching `camelot_relation`.
 */
fun camelotRelation(a: String, b: String): String {
    val (na, la) = parse(a)
    val (nb, lb) = parse(b)
    if (a == b) return "same"
    if (na == nb && la != lb) return "relative"
    if (la == lb) {
        val diff = kotlin.math.abs(na - nb)
        if (diff == 1 || diff == 11) return "adjacent"
    }
    return "clash"
}
