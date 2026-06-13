package ai.kolai.station

import ai.kolai.core.DJSlot
import ai.kolai.core.Song
import ai.kolai.core.TrackAnalysis

/**
 * Injected I/O seams for [BlockRenderer], ported from the dependency-injection
 * shape of backend/radioai/block_renderer.py. The Python BlockRenderer takes
 * `fetcher`, `voice`, `analyze_fn`, `load_fn`, and (implicitly) the mp3 writer;
 * here those become explicit Kotlin seams so the renderer is fully unit-testable
 * with fakes (no network / no real audio / no LLM).
 *
 * The real device implementations are LATER tasks:
 *   - AudioFetcher  -> NewPipe stream extractor + download to a local file.
 *   - VoiceRenderer -> Gemini TTS (:voice) synth to a WAV/PCM file.
 *   - analyzeFn     -> Essentia analysis (:analyze).
 *   - loadFn        -> MediaCodec decode to mono 44.1k FloatArray.
 *   - BlockEncoder  -> MediaCodec AAC encode (the .m4a block file).
 */

/**
 * Resolve a [Song] to a local audio file path. Mirrors the Python `fetcher`
 * (acquire layer). MUST throw on failure: [BlockRenderer.render] catches the
 * exception and skips that song, exactly like the Python try/except.
 */
interface AudioFetcher {
    fun fetch(song: Song): String
}

/**
 * Render a Hebrew DJ line to a voiced audio file. Mirrors
 * `voice.VoiceRenderer.render(text) -> DJSlot`. The returned [DJSlot] carries
 * the spoken text plus the path to (and duration of) the synthesized audio; the
 * renderer reloads that path via `loadFn` to get the PCM samples.
 */
interface VoiceRenderer {
    /**
     * [style] is an optional English TTS delivery prefix (a mood's
     * [MoodSpec.ttsStyle]); null keeps the implementation's default delivery
     * (and its caching behavior). Overrides must NOT redeclare the default.
     *
     * [voiceName] (2026-06-13, per-mood voice integration) is an optional
     * prebuilt Gemini voice name (a mood's [MoodSpec.voiceName], possibly
     * overridden per-mood in app config); null keeps the renderer's
     * constructor voice. [BlockRenderer] resolves the EFFECTIVE mood's voice
     * (mix included) and passes it here so the main-host voice tracks the
     * mood. Defaulted so existing fakes/adapters keep compiling; the cache key
     * already folds in the voice in the :voice renderer.
     */
    fun render(text: String, style: String? = null, voiceName: String? = null): DJSlot

    /**
     * Render a two-host DIALOGUE: speaker-tagged [turns] (e.g.
     * `[("A", line), ("B", line), ...]`, see DjBrain.writeBanter) where the
     * second host speaks with [voiceB] and the MAIN host (speaker A) with
     * [voiceA] (2026-06-13; null = the renderer's constructor voice). The
     * renderer passes the block's mood voice as [voiceA] so the lead host
     * tracks the mood while the sidekick keeps [voiceB].
     *
     * DEFAULT body (wave 3): single-voice fallback - the turns are flattened
     * via [joinDialogue] and spoken through [render] (carrying the mood
     * [voiceName] = [voiceA]), so every existing fake/adapter keeps compiling
     * and keeps the MVP single-voice banter behavior. The :app adapter
     * overrides this with true multi-speaker Gemini TTS; [BlockRenderer] only
     * calls it when a voiceB is configured. Overrides must NOT redeclare the
     * defaults.
     */
    fun renderDialogue(
        turns: List<Pair<String, String>>,
        voiceB: String,
        style: String? = null,
        voiceA: String? = null,
    ): DJSlot = render(joinDialogue(turns), style, voiceA)
}

/**
 * Canonical single-voice flattening of a two-host script: one `"A: line"` /
 * `"B: line"` turn per line. Shared by [VoiceRenderer.renderDialogue]'s default
 * body and [BlockRenderer]'s banter talk meta, so the spoken fallback and the
 * meta text always agree.
 */
fun joinDialogue(turns: List<Pair<String, String>>): String =
    turns.joinToString("\n") { (speaker, line) -> "$speaker: $line" }

/**
 * Encode the finished block timeline to a file. Mirrors
 * `mixrenderer.write_mp3`, except the Android output is AAC (.m4a). Injected so
 * the real MediaCodec encoder is a later device task, and so tests can pass a
 * no-op / recording fake (or skip entirely with `write = false`).
 */
interface BlockEncoder {
    fun encode(path: String, audio: FloatArray)
}

/** Mirrors Python `analyze_fn: (path) -> TrackAnalysis`. */
typealias AnalyzeFn = (String) -> TrackAnalysis

/** Mirrors Python `load_fn: (path) -> np.ndarray` (mono 44.1k samples). */
typealias LoadFn = (String) -> FloatArray