package ai.kolai.analyze

import ai.kolai.core.TrackAnalysis
import ai.kolai.core.camelotFromKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns the native :dsp analysis JSON into a :core [TrackAnalysis], applying the
 * verified guards (octave-fold BPM, enharmonic key normalization, beat-confidence
 * gate).
 *
 * This class is device-INDEPENDENT and JVM-testable: the JSON producer is
 * injected as a seam ([analyzeJson]) so unit tests can feed canned JSON and never
 * touch the arm64-only native library. The default seam delegates to
 * [ai.kolai.dsp.KolaiDsp.analyzePcmJson], which the device-phase implementer will
 * exercise for real.
 *
 * Expected JSON shape (see [ai.kolai.dsp.KolaiDsp]):
 * {"bpm","beatConfidence","energy","keyTonic","keyScale","keyStrength",
 *  "sampleRate","beatTimes":[...]} or {"error":"..."} on native failure.
 *
 * Derived fields mirror the Python reference `backend/radioai/analyzer.py`:
 *   - durationS  = pcm.size / sr        (mono PCM; NOT read from JSON)
 *   - introEndS  = min(8.0, duration*0.1)
 *   - outroStartS= max(duration-8.0, duration*0.9)
 *   - vocalOnsetS= first beatTime > 1.0s else 0.0  (see approximation note below)
 */
class Analyzer(
    private val analyzeJson: (FloatArray, Int) -> String =
        { pcm, sr -> ai.kolai.dsp.KolaiDsp.analyzePcmJson(pcm, sr) },
) {

    /**
     * Analyze mono PCM [pcm] sampled at [sr] Hz and produce a [TrackAnalysis].
     *
     * @param path stored on the result for downstream identification; defaults to "".
     * @throws IllegalStateException if the native side returned an "error", or if
     *   a required numeric field (bpm/energy) is missing, null, or non-finite.
     *   Callers / BlockRenderer skip songs whose analysis fails.
     */
    fun analyze(pcm: FloatArray, sr: Int, path: String = ""): TrackAnalysis {
        require(sr > 0) { "sample rate must be positive, was $sr" }

        val raw = analyzeJson(pcm, sr)
        val root = parseObject(raw)

        // Native failure path: {"error": "..."}.
        (root["error"]?.jsonPrimitive?.contentOrNull)?.let { err ->
            throw IllegalStateException("analysis failed: $err")
        }

        // Required numerics: missing / null / non-finite -> fail (caller skips song).
        val rawBpm = requireFinite(root, "bpm")
        val energy = requireFinite(root, "energy")

        // Mono PCM: duration is sample count / sample rate (NOT taken from JSON).
        val durationS = pcm.size.toDouble() / sr

        // Intro/outro regions: exact port of analyzer.py.
        val introEndS = minOf(8.0, durationS * 0.1)
        val outroStartS = maxOf(durationS - 8.0, durationS * 0.9)

        // BPM octave-fold guard (half/double-time -> canonical ~70..180 band).
        val bpm = foldBpm(rawBpm)

        // Beats (seconds) from JSON; finite-only, robust to nulls.
        val rawBeats: List<Double> = (root["beatTimes"]?.jsonArray)
            ?.mapNotNull { it.jsonPrimitive.doubleOrNull }
            ?.filter { it.isFinite() }
            ?: emptyList()

        // Confidence guard: Essentia multifeature confidence ~0..5.32; below 1.5 is
        // unreliable. Drop beatTimes so downstream Dsp.startOnBeat is a no-op and the
        // mixer uses a plain crossfade. We KEEP the (folded) bpm. MVP doesn't
        // beat-match, but this keeps the guard faithful and future-proof.
        val beatConfidence = (root["beatConfidence"]?.jsonPrimitive?.doubleOrNull) ?: 0.0
        val beatTimes = if (beatConfidence < 1.5) emptyList() else rawBeats

        // vocalOnsetS APPROXIMATION: the Python heuristic used librosa onset
        // detection (first strong onset after 1.0s). Essentia returns BEATS, not
        // onsets, so we approximate with the first beat strictly after 1.0s, else
        // 0.0 -- matching the Python fallback. Uses the (possibly cleared) beats so
        // a low-confidence track reports 0.0.
        val vocalOnsetS = beatTimes.firstOrNull { it > 1.0 } ?: 0.0

        // Key: normalize Essentia spelling, then map via :core Camelot tables.
        val keyTonic = root["keyTonic"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("missing keyTonic in analysis JSON")
        val keyScale = root["keyScale"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("missing keyScale in analysis JSON")
        val keyCamelot = try {
            camelotFromKey(normalizeEnharmonic(keyTonic), keyScale)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("unresolvable key '$keyTonic' '$keyScale'", e)
        }

        return TrackAnalysis(
            path = path,
            durationS = durationS,
            bpm = bpm,
            beatTimes = beatTimes,
            keyCamelot = keyCamelot,
            energy = energy,
            introEndS = introEndS,
            outroStartS = outroStartS,
            vocalOnsetS = vocalOnsetS,
        )
    }

    private fun parseObject(raw: String): JsonObject =
        try {
            (LENIENT.parseToJsonElement(raw) as? JsonObject)
                ?: throw IllegalStateException("analysis JSON is not an object: $raw")
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("malformed analysis JSON", e)
        }

    /**
     * Read [key] as a finite Double; throw [IllegalStateException] if the field is
     * absent, JSON-null, non-numeric, or non-finite (NaN/Inf).
     */
    private fun requireFinite(obj: JsonObject, key: String): Double {
        val prim = obj[key] as? JsonPrimitive
            ?: throw IllegalStateException("missing required numeric '$key' in analysis JSON")
        val value = prim.doubleOrNull
            ?: throw IllegalStateException("'$key' is null or non-numeric in analysis JSON")
        if (!value.isFinite()) {
            throw IllegalStateException("'$key' is non-finite ($value) in analysis JSON")
        }
        return value
    }

    private companion object {
        private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}