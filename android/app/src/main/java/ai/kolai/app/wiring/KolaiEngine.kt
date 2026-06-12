package ai.kolai.app.wiring

import ai.kolai.station.BlockRenderer
import ai.kolai.station.DjBrain
import ai.kolai.station.DjContext
import ai.kolai.station.MoodCurator
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
         * @param ttsVoice        prebuilt voice name (e.g. Algieba).
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
            cacheDir: File,
            blocksDir: File,
            ctxFactory: (HttpClient) -> (() -> DjContext) = { { DjContext() } },
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
            // MoodCurator is LONG-LIVED on purpose: it keeps an in-memory
            // verdict cache, so one instance for the engine's lifetime means
            // repeated mood checks for the same songs cost zero LLM calls.
            val planner: SetlistSource = TastePoolPlanner(
                discovery = discovery,
                curator = MoodCurator(llm),
            )

            val blockRenderer = BlockRenderer(
                fetcher = fetcher,
                brain = brain,
                voice = voiceAdapter,
                ctx = ctxProvider,
                blocksDir = blocksDir.absolutePath,
                voiceA = ttsVoice,
                voiceB = null,
                analyzeFn = analyzeFn,
                loadFn = loadFn,
                encoder = encoder,
                write = true,
            )

            return KolaiEngine(
                httpClient = http,
                textClient = textClient,
                llm = llm,
                planner = planner,
                blockRenderer = blockRenderer,
                blocksDir = blocksDir,
                cacheDir = cacheDir,
            )
        }
    }
}