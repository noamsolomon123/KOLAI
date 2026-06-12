package ai.kolai.app.wiring

import ai.kolai.acquire.NewPipeSource
import ai.kolai.acquire.PoTokenSource
import ai.kolai.core.DJSlot
import ai.kolai.core.Song
import ai.kolai.mix.AacEncoder
import ai.kolai.mix.Dsp
import ai.kolai.voice.GeminiTextClient
import ai.kolai.station.AudioFetcher as StationAudioFetcher
import ai.kolai.station.BlockEncoder as StationBlockEncoder
import ai.kolai.station.LlmClient as StationLlmClient
import ai.kolai.station.VoiceRenderer as StationVoiceRenderer
import ai.kolai.station.joinDialogue
import ai.kolai.voice.VoiceRenderer as VoiceVoiceRenderer
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Seam adapters: the SINGLE coupling point between the otherwise-decoupled engine
 * modules and the :station render seams (RenderSeams.kt / LlmClient.kt). Keeping
 * every cross-module adaptation here means :acquire / :analyze / :mix / :voice /
 * :station never depend on each other -- only :app stitches them together.
 *
 * These mirror the Python dependency-injection wiring in
 * backend/radioai (block_renderer / setlist / djbrain construction).
 */

/**
 * Adapts :voice [GeminiTextClient] (suspend complete) to the :station
 * [StationLlmClient] seam consumed by DjBrain + SetlistPlanner. Both are
 * `suspend fun complete(prompt): String`, so this is a straight delegate.
 */
class GeminiLlmClient(
    private val textClient: GeminiTextClient,
) : StationLlmClient {
    override suspend fun complete(prompt: String): String = textClient.complete(prompt)
}

/**
 * Adapts the :voice [VoiceVoiceRenderer] (suspend `render(text, voiceOverride?,
 * style?)`) to the :station [StationVoiceRenderer] seam.
 *
 * IMPORTANT SIGNATURE MISMATCH (verified by reading station/RenderSeams.kt):
 * the :station seam is NON-suspend `fun render(text, style?): DJSlot`, while the
 * :voice renderer is `suspend fun render(...)`. BlockRenderer always calls
 * voice.render(...) from inside its own `suspend render(...)` (i.e. already on a
 * coroutine / dispatcher), so we bridge with [runBlocking]: it blocks the calling
 * worker thread for the duration of the TTS network call, which is exactly the
 * sequential behaviour the renderer expects. (Post-MVP: make the :station seam
 * suspend to drop this bridge.) The mood [style] prefix passes straight through
 * to the :voice renderer (named arg: the middle voiceOverride param stays null).
 */
class VoiceRendererAdapter(
    private val inner: VoiceVoiceRenderer,
) : StationVoiceRenderer {
    override fun render(text: String, style: String?): DJSlot =
        runBlocking { inner.render(text, style = style) }

    /**
     * TWO-HOST DIALOGUE (wave 4): bridge the :station dialogue seam to the
     * :voice multi-speaker renderer (one Gemini multi-speaker TTS call), same
     * runBlocking bridge as [render]. On ANY failure (network, TTS refusal,
     * parse error, ...) fall back to the seam's DEFAULT single-voice behavior:
     * the turns flattened via [joinDialogue] and spoken through [render] - so
     * a broken dialogue call degrades to the pre-wave-3 substitute break
     * instead of killing the block render.
     */
    override fun renderDialogue(
        turns: List<Pair<String, String>>,
        voiceB: String,
        style: String?,
    ): DJSlot = try {
        runBlocking { inner.renderDialogue(turns, voiceB = voiceB, style = style) }
    } catch (e: Throwable) {
        render(joinDialogue(turns), style)
    }
}

/**
 * Adapts the acquire layer to the :station [StationAudioFetcher] seam. Tries each
 * `(Song, cacheDir) -> path` source in order, returning the first success; if all
 * fail it rethrows the LAST failure (so the caller / BlockRenderer skips the song
 * exactly like the Python try/except). For this first integration the chain is
 * just `[ NewPipeSource()::fetch ]`; a LocalFiles source is added later.
 */
class AudioFetcherAdapter(
    private val sources: List<(Song, File) -> String>,
    private val cacheDir: File,
) : StationAudioFetcher {
    override fun fetch(song: Song): String {
        require(sources.isNotEmpty()) { "AudioFetcherAdapter has no sources" }
        if (!cacheDir.exists()) cacheDir.mkdirs()
        var last: Throwable? = null
        for (source in sources) {
            try {
                return source(song, cacheDir)
            } catch (e: Throwable) {
                last = e
            }
        }
        throw RuntimeException(
            "all audio sources failed for '${song.artist} - ${song.title}'",
            last,
        )
    }
}

/**
 * Adapts :mix [AacEncoder] (object, `encode(outPath, pcm, sampleRate)`) to the
 * :station [StationBlockEncoder] seam. :mix deliberately does NOT implement the
 * :station interface (that would create a :mix <-> :station cycle), so the bridge
 * lives here. Encodes at [Dsp.SR] (44100), the canonical mix sample rate.
 */
class AacBlockEncoder : StationBlockEncoder {
    override fun encode(path: String, audio: FloatArray) {
        AacEncoder.encode(path, audio, Dsp.SR)
    }
}

/**
 * The :station analyze seam: decode the file to mono 44.1k PCM, then run the
 * :analyze Analyzer over it. Mirrors Python `analyze_fn`.
 */
val analyzeFn: (String) -> ai.kolai.core.TrackAnalysis = { path ->
    ai.kolai.analyze.Analyzer().analyze(
        ai.kolai.analyze.AudioDecoder.decodeToPcm(path, 44100),
        44100,
        path,
    )
}

/**
 * The :station load seam: decode the file to mono 44.1k PCM. Mirrors Python
 * `load_fn`.
 *
 * RESAMPLE QUALITY (wave 4): [ai.kolai.analyze.AudioDecoder.decodeToPcm] now
 * resamples with the windowed-sinc `resampleSinc` (was `resampleLinear`), so
 * BOTH paths through this seam get the quality resampler: the 24 kHz Gemini
 * TTS voice WAVs (the path the wave-2 TODO targeted - linear interpolation
 * audibly dulls speech consonants) AND any song decoded at a non-44.1k rate
 * (sinc is strictly better there too; 44.1k -> 44.1k input is returned
 * unchanged, so the common song case costs nothing).
 */
val loadFn: (String) -> FloatArray = { path ->
    ai.kolai.analyze.AudioDecoder.decodeToPcm(path, 44100)
}

/**
 * ADAPTIVE-AUDIO seam (wave-1 H): a process-wide holder for the [PoTokenSource]
 * implementation. [KolaiEngine.build] has no Android [android.content.Context]
 * (it is pure JVM wiring), but the WebView PoToken generator NEEDS one. So the
 * Android layer that DOES have a Context -- the foreground media service -- sets
 * this registry at startup, and [newPipeSource] threads it into [NewPipeSource].
 *
 * WAVE-4 / SERVICE WIRING (done): [KolaiMediaService.onCreate], before building
 * the engine, does:
 *
 *     PoTokenRegistry.source = WebViewPoTokenGenerator(applicationContext)
 *
 * When left null (default -- e.g. tests, or before the service sets it),
 * [NewPipeSource] registers NO provider and behaviour is byte-identical to the
 * pre-feature muxed-360p path. Setting it is therefore strictly additive.
 */
object PoTokenRegistry {
    @Volatile
    var source: PoTokenSource? = null
}

/**
 * Convenience: a NewPipe-backed fetch source `(Song, cacheDir) -> path`. Reads
 * [PoTokenRegistry.source] so that, when the service has installed a WebView
 * PoToken generator, [NewPipeSource] can attempt the adaptive audio-only streams;
 * otherwise it falls back to the muxed floor.
 */
fun newPipeSource(): (Song, File) -> String {
    val src = NewPipeSource(poTokenSource = PoTokenRegistry.source)
    return { song, cacheDir -> src.fetch(song, cacheDir) }
}