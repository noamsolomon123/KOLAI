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
 *
 * The two-host banter path (Python `voice.render_banter`) is deferred; banter is
 * substituted with a normal single-voice break for the MVP (see [BlockRenderer]).
 */
interface VoiceRenderer {
    fun render(text: String): DJSlot
}

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