package ai.kolai.dsp

/**
 * Thin Kotlin handle over the native `libkolaidsp.so`.
 *
 * Wraps the Essentia-backed analyzer. [analyzePcmJson] runs on-device music
 * analysis (BPM + beat times, musical key, energy) over a mono float PCM buffer
 * and returns a small JSON string. The higher-level Analyzer (Task 3.1) parses
 * that JSON into a TrackAnalysis.
 *
 * Expected JSON shape:
 * {
 *   "bpm": Double,
 *   "beatConfidence": Double,
 *   "energy": Double,            // RMS over the whole signal
 *   "keyTonic": String,          // e.g. "C", "F#"
 *   "keyScale": String,          // "major" | "minor"
 *   "keyStrength": Double,
 *   "sampleRate": Int,
 *   "beatTimes": [Double, ...]   // beat positions in seconds
 * }
 * On failure the native side returns {"error": "..."}.
 */
object KolaiDsp {
    init {
        System.loadLibrary("kolaidsp")
    }

    /** Smoke-test entry point proving the native lib loads. */
    external fun nativeHello(): String

    /**
     * Analyze mono PCM [pcm] sampled at [sr] Hz. Returns a JSON string
     * (see class docs). Heavy/blocking; call off the main thread.
     */
    external fun analyzePcmJson(pcm: FloatArray, sr: Int): String
}