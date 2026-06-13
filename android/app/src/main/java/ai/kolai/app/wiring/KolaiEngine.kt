package ai.kolai.app.wiring

import ai.kolai.station.BlockRenderer
import ai.kolai.station.DjBrain
import ai.kolai.station.DjContext
import ai.kolai.station.MoodCurator
import ai.kolai.station.Moods
import ai.kolai.station.GenreSource
import ai.kolai.station.SetlistSource
import ai.kolai.station.TastePoolPlanner
import ai.kolai.voice.GeminiTextClient
import ai.kolai.voice.GeminiTtsSynth
import ai.kolai.voice.VoiceRenderer as VoiceVoiceRenderer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

/**
 * KolaiEngine -- the composition root that wires the engine modules into a ready
 * [BlockRenderer] (+ the [SetlistSource] / [GeminiTextClient] callers need).
 * This is the on-device counterpart of the Python StationEngine bootstrap: it
 * builds the Gemini text/TTS clients, the :voice VoiceRenderer, all the
 * [Adapters], DjBrain(persona), the song picker, and the BlockRenderer.
 *
 * Song picking is CODE-BASED: [TastePoolPlanner] samples the listener's real
 * taste pool and sprinkles real-catalog discoveries via [DeezerDiscovery]
 * (zero LLM, so it cannot hallucinate a song). Gemini is used ONLY to write
 * and voice the DJ ([DjBrain] + TTS).
 *
 * Keys/models/voice are passed in (never hardcoded). The Ktor [HttpClient] uses
 * the production OkHttp engine.
 */
class KolaiEngine private constructor(
    val httpClient: HttpClient,
    val textClient: GeminiTextClient,
    val llm: GeminiLlmClient,
    val planner: SetlistSource,
    val blockRenderer: BlockRenderer,
    val blocksDir: File,
    val cacheDir: File,
    // SONG-FLOW COHESION source (Deezer genre), exposed so the RollingPlanner can
    // LABEL the genres of the songs it returns -- that genre history feeds the
    // planner's run-length easing (so a genre run ends organically). Same instance
    // wired into TastePoolPlanner below, so its cache is shared.
    val genreSource: GenreSource,
) {
    /** Release the underlying HTTP engine. */
    fun close() {
        httpClient.close()
    }

    companion object {
        /** KOLAI's Hebrew DJ persona (matches the backend default). */
        const val PERSONA = "קול AI"

        /**
         * Build a fully-wired engine.
         *
         * @param geminiKeys      ordered Gemini API keys (rotated on 429/5xx).
         * @param llmModel        text model id (e.g. gemini-3.1-flash-lite-preview).
         * @param ttsModel        TTS model id (e.g. gemini-3.1-flash-tts-preview).
         * @param ttsVoice        prebuilt voice name (e.g. Algieba) - host A.
         * @param ttsVoiceB       second-host voice for two-host banter (must
         *                        differ from [ttsVoice] to be audible as a
         *                        dialogue). Defaulted so existing callers
         *                        (e.g. the e2e instrumentation test) compile.
         * @param cacheDir        downloaded-track + voice cache root.
         * @param blocksDir       where block_<i>.m4a files are written.
         * @param ctxFactory      builds the per-block DJ-context PROVIDER from the
         *                        engine's own [HttpClient] (the client is created
         *                        INSIDE build, so a live provider - e.g.
         *                        [LiveDjContext] - cannot be constructed by the
         *                        caller beforehand; it receives the client here).
         *                        Defaults to an empty-context provider.
         */
        fun build(
            geminiKeys: List<String>,
            llmModel: String,
            ttsModel: String,
            ttsVoice: String,
            ttsVoiceB: String = "Iapetus",
            cacheDir: File,
            blocksDir: File,
            ctxFactory: (HttpClient) -> (() -> DjContext) = { { DjContext() } },
            // PER-MOOD VOICE overrides (2026-06-13): mood key -> prebuilt voice
            // name (DevConfig.moodVoices). An override WINS over the Moods
            // default; a missing key falls back to Moods.spec(mood).voiceName.
            // Empty (the default) keeps the stock per-mood voices. Backward
            // compatible: existing callers omit it.
            moodVoices: Map<String, String> = emptyMap(),
            // Sidekick co-host voices rotated alongside the banter persona
            // (2026-06-13). Empty -> the single ttsVoiceB is always used.
            sidekickVoices: List<String> = emptyList(),
        ): KolaiEngine {
            require(geminiKeys.isNotEmpty()) { "KolaiEngine needs at least one Gemini key" }
            cacheDir.mkdirs()
            blocksDir.mkdirs()

            val http = HttpClient(OkHttp)

            // Per-block DJ context provider (clock/weather/news), built from the
            // engine's HttpClient so live fetches reuse the one OkHttp engine.
            val ctxProvider: () -> DjContext = ctxFactory(http)

            // --- Gemini clients (text + TTS) -------------------------------
            val textClient = GeminiTextClient(
                apiKeys = geminiKeys,
                model = llmModel,
                httpClient = http,
            )
            val ttsSynth = GeminiTtsSynth(
                apiKeys = geminiKeys,
                model = ttsModel,
                voice = ttsVoice,
                style = "",
                httpClient = http,
            )

            // --- voice rendering -------------------------------------------
            val voiceDir = File(cacheDir, "voice")
            val voiceRenderer = VoiceVoiceRenderer(synth = ttsSynth, outDir = voiceDir)

            // --- station seams ---------------------------------------------
            val llm = GeminiLlmClient(textClient)
            val tracksCache = File(cacheDir, "tracks")
            val fetcher = AudioFetcherAdapter(
                sources = listOf(newPipeSource()),
                cacheDir = tracksCache,
            )
            val voiceAdapter = VoiceRendererAdapter(voiceRenderer)
            val encoder = AacBlockEncoder()

            // --- brains -----------------------------------------------------
            val brain = DjBrain(client = llm, persona = PERSONA)
            val discovery = DeezerDiscovery(http)
            // TEMPO AWARENESS (2026-06-13): a Deezer /track BPM source feeds the
            // planner's per-mood BPM window bias + seed/order smoothing (see
            // docs/studies/findings-bpm-v3.md). LONG-LIVED so its BPM cache
            // survives across plan() calls. Best-effort: unknown BPM (the
            // ~57-70% catalog gap) is neutral and never blocks a pick.
            val bpmSource = DeezerBpmSource(http)
            // SONG-FLOW COHESION (2026-06-13): a Deezer genre source feeds the
            // planner's language + genre cohesion (chaining picks into rap / jazz /
            // English RUNS), run-length-eased so a stretch ends organically.
            // LONG-LIVED so its genre cache survives across plan() calls.
            // Best-effort: unknown genre is neutral and never blocks a pick.
            val genreSource = DeezerGenreSource(http)
            // MoodCurator is LONG-LIVED on purpose: it keeps an in-memory
            // verdict cache, so one instance for the engine's lifetime means
            // repeated mood checks for the same songs cost zero LLM calls.
            val planner: SetlistSource = TastePoolPlanner(
                discovery = discovery,
                curator = MoodCurator(llm),
                bpm = bpmSource,
                genre = genreSource,
            )

            // PER-MOOD VOICE resolver: an override wins, else the Moods
            // default. Resolves for the EFFECTIVE mood (mix included) so the DJ
            // always has a real voice even in the default/daytime case.
            val moodVoiceResolver: (String?) -> String = { mood ->
                val key = mood ?: Moods.DEFAULT
                moodVoices[key] ?: Moods.spec(key).voiceName
            }

            val blockRenderer = BlockRenderer(
                fetcher = fetcher,
                brain = brain,
                voice = voiceAdapter,
                ctx = ctxProvider,
                blocksDir = blocksDir.absolutePath,
                voiceA = ttsVoice,
                // Non-null voiceB ENABLES the two-host banter path: planned
                // banter turns render through VoiceRendererAdapter.renderDialogue
                // (multi-speaker Gemini TTS) instead of the single-voice join.
                voiceB = ttsVoiceB,
                analyzeFn = analyzeFn,
                loadFn = loadFn,
                encoder = encoder,
                write = true,
                moodVoice = moodVoiceResolver,
                sidekickVoices = sidekickVoices,
            )

            return KolaiEngine(
                httpClient = http,
                textClient = textClient,
                llm = llm,
                planner = planner,
                blockRenderer = blockRenderer,
                blocksDir = blocksDir,
                cacheDir = cacheDir,
                genreSource = genreSource,
            )
        }
    }
}