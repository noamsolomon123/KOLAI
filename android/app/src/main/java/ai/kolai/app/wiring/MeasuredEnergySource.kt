package ai.kolai.app.wiring

import java.util.concurrent.ConcurrentHashMap

/**
 * MeasuredEnergySource -- a persisted, MEASURED-ONLY cache of per-track ENERGY
 * (Essentia RMS over the whole signal, [ai.kolai.core.TrackAnalysis.energy]).
 *
 * WHY THIS EXISTS
 * ---------------
 * BPM is octave-AMBIGUOUS: Essentia routinely reads a slow ballad as double-time
 * (a slow Hebrew ballad measured 162 BPM), so a per-mood BPM window alone cannot
 * reliably keep bangers out of the calm moods -- and indeed "The Middle" (Zedd),
 * the LITERAL party-energy exemplar in [ai.kolai.station.Moods] late_night''s own
 * curationHint, leaked into a late_night set (2026-06-15). ENERGY (signal RMS)
 * has NO octave ambiguity: a loud, dense banger always reads HIGH and a sparse
 * ballad LOW, regardless of tempo. It is therefore the ROBUST axis for
 * energy-based mood-fit, the natural complement to the (noisy) BPM window.
 *
 * Energy is ONLY ever known AFTER on-device analysis -- the keyless Deezer
 * metadata carries no energy field -- so this cache is MEASURED-ONLY: no network,
 * no "warm", no resolve. It is fed from [ai.kolai.station.BlockRenderer]''s
 * onAnalyzed callback (every rendered song) and ACCUMULATES across runs on disk
 * (energy_cache.json), exactly like the measured-BPM half of [DeezerBpmSource].
 *
 * The read ([cachedEnergy]) is SYNCHRONOUS and non-blocking, so the planner can
 * use it as a soft, never-shrinking weight tilt (an unknown energy stays
 * NEUTRAL), the same contract as the BPM window. NEVER throws.
 *
 * NOTE (2026-06-15): wired for CAPTURE first. The planner mood-fit gate that
 * READS this cache is added in a follow-up once enough real RMS values have
 * accumulated to calibrate per-mood energy windows from data (not guesswork).
 */
class MeasuredEnergySource(
    /** Optional disk cache so measured energies survive app restarts and
     *  ACCUMULATE across runs, building an energy picture of the pool for
     *  vibe-match. Null = in-memory only. */
    private val persistFile: java.io.File? = null,
) {

    /** "artistLower|titleLower" -> measured energy (Essentia RMS, > 0). */
    private val cache = ConcurrentHashMap<String, Double>()

    private fun keyOf(artist: String, title: String): String =
        artist.trim().lowercase() + "|" + title.trim().lowercase()

    init { loadCache() }

    private fun loadCache() {
        val f = persistFile ?: return
        try {
            if (!f.exists()) return
            val obj = org.json.JSONObject(f.readText())
            val ks = obj.keys()
            while (ks.hasNext()) { val k = ks.next(); cache[k] = obj.getDouble(k) }
        } catch (_: Throwable) { }
    }

    @Synchronized private fun saveCache() {
        val f = persistFile ?: return
        try {
            val obj = org.json.JSONObject()
            for ((k, v) in cache) obj.put(k, v)
            f.parentFile?.mkdirs()
            f.writeText(obj.toString())
        } catch (_: Throwable) { }
    }

    /** Inject a MEASURED energy (on-device Essentia analysis) and persist it.
     *  Blank titles and non-finite / non-positive energies are ignored. */
    fun put(artist: String, title: String, energy: Double) {
        if (title.isBlank() || !energy.isFinite() || energy <= 0.0) return
        cache[keyOf(artist, title)] = energy
        saveCache()
    }

    /** SYNCHRONOUS cache-only read: the measured energy, or null on a cold miss.
     *  Never touches any network, never throws. */
    fun cachedEnergy(artist: String, title: String): Double? {
        if (title.isBlank()) return null
        return cache[keyOf(artist, title)]
    }
}