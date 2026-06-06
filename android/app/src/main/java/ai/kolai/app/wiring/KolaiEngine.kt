package ai.kolai.app.wiring

import ai.kolai.station.BlockRenderer
import ai.kolai.station.DjBrain
import ai.kolai.station.DjContext
import ai.kolai.station.SetlistPlanner
import ai.kolai.voice.GeminiTextClient
import ai.kolai.voice.GeminiTtsSynth
import ai.kolai.voice.VoiceRenderer as VoiceVoiceRenderer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

/**
 * KolaiEngine -- the composition root that wires the engine modules into a ready
 * [BlockRenderer] (+ the [SetlistPlanner] / [GeminiTextClient] callers need).
 * This is the on-device counterpart of the Python StationEngine bootstrap: it
 * builds the Gemini text/TTS clients, the :voice VoiceRenderer, all the
 * [Adapters], DjBrain(persona), SetlistPlanner, and the BlockRenderer.
 *
 * Keys/models/voice are passed in (never hardcoded). The Ktor [HttpClient] uses
 * the production OkHttp engine.
 */
class KolaiEngine private constructor(
    val httpClient: HttpClient,
    val textClient: GeminiTextClient,
    val llm: GeminiLlmClient,
    val planner: SetlistPlanner,
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
         * @param ctx             DJ context (clock/weather/news); defaults empty.
         */
        fun build(
            geminiKeys: List<String>,
            llmModel: String,
            ttsModel: String,
            ttsVoice: String,
            cacheDir: File,
            blocksDir: File,
            ctx: DjContext = DjContext(),
        ): KolaiEngine {
            require(geminiKeys.isNotEmpty()) { "KolaiEngine needs at least one Gemini key" }
            cacheDir.mkdirs()
            blocksDir.mkdirs()

            val http = HttpClient(OkHttp)

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
            val planner = SetlistPlanner(llm)

            val blockRenderer = BlockRenderer(
                fetcher = fetcher,
                brain = brain,
                voice = voiceAdapter,
                ctx = ctx,
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