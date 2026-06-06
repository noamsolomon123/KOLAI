package ai.kolai.analyze

/**
 * Key spelling guard for the Analyzer.
 *
 * Essentia emits a tonic spelling that may use flats (e.g. "Db", "Eb", "Gb",
 * "Ab", "Bb") OR sharps (e.g. "C#", "F#", "G#"). The :core Camelot tables
 * ([ai.kolai.core.camelotFromKey]) currently accept BOTH spellings, but to be
 * robust and future-proof we normalize to a single canonical form (sharps)
 * before lookup. This guarantees:
 *   - every Essentia tonic resolves without throwing, and
 *   - enharmonic pairs (Db/C#, Eb/D#, Gb/F#, Ab/G#, Bb/A#) collapse to the SAME
 *     Camelot code, so harmonic compatibility scoring is consistent.
 *
 * Naturals ("C","D","E","F","G","A","B") pass through unchanged. Any unexpected
 * spelling is returned as-is and left for [camelotFromKey] to accept or reject.
 */

/** Essentia flat spelling -> canonical sharp spelling used for Camelot lookup. */
private val FLAT_TO_SHARP: Map<String, String> = mapOf(
    "Db" to "C#",
    "Eb" to "D#",
    "Gb" to "F#",
    "Ab" to "G#",
    "Bb" to "A#",
)

/**
 * Normalize an Essentia [tonic] to the canonical (sharp) spelling accepted by
 * the :core Camelot tables. Flats map to their enharmonic sharp; sharps and
 * naturals pass through unchanged.
 */
fun normalizeEnharmonic(tonic: String): String =
    FLAT_TO_SHARP[tonic] ?: tonic