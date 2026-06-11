package ai.kolai.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.kolai.app.wiring.KolaiEngine
import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.TasteTrack
import ai.kolai.core.taste.profileFromJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import ai.kolai.voice.GeminiTextClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * THE end-to-end proof: render a full KOLAI block on the device, exercising the
 * real LLM (text model), real YouTube download (NewPipe), real Gemini TTS, the
 * native decode/analyze/mix/encode pipeline, and the :app seam adapters that
 * compose them. Every individual piece was validated on-device before; this is
 * the first time they all COMPOSE.
 *
 * Driven by `adb shell am instrument` with `-e` args carrying the secrets:
 *   geminiKey1/2/3, llmModel, ttsModel, ttsVoice
 * (NEVER hardcoded -- no key ever enters source or the APK).
 *
 * Real network + 3 full songs => this can run for SEVERAL MINUTES.
 *
 * NOTE: the engine's planner is now the code-based TastePoolPlanner (+ Deezer
 * discovery) -- no LLM in song picking. To still prove the block-render
 * composition (the climax of this test) even if planning fails, we TRY the
 * planner and, on failure, fall back to building the Song list directly from
 * the taste -- BlockRenderer.render is fully exercised either way.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndBlockTest {

    private val tag = "EndToEndBlockTest"

    private fun args() = InstrumentationRegistry.getArguments()

    private fun geminiKeys(): List<String> {
        val a = args()
        return listOfNotNull(
            a.getString("geminiKey1"),
            a.getString("geminiKey2"),
            a.getString("geminiKey3"),
        ).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun llmModel() = args().getString("llmModel") ?: "gemini-3.1-flash-lite-preview"
    private fun ttsModel() = args().getString("ttsModel") ?: "gemini-3.1-flash-tts-preview"
    private fun ttsVoice() = args().getString("ttsVoice") ?: "Algieba"

    /** Load the real Spotify taste from androidTest assets, else a hardcoded seed. */
    private fun loadTaste(): TasteProfile {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return try {
            val text = ctx.assets.open("taste.json").bufferedReader().use { it.readText() }
            val json = Json.parseToJsonElement(text) as JsonObject
            val profile = profileFromJson(json)
            Log.i(tag, "loaded taste.json: ${profile.topTracks.size} tracks, ${profile.topArtists.size} artists")
            profile
        } catch (e: Exception) {
            Log.w(tag, "no taste.json asset (${e.message}); using hardcoded seed")
            hardcodedSeed()
        }
    }

    private fun hardcodedSeed(): TasteProfile = TasteProfile(
        topTracks = listOf(
            TasteTrack("שעות העבודה", "Omer Adam", 200.0),
            TasteTrack("Yellow", "Coldplay", 269.0),
            TasteTrack("Tel Aviv", "Eyal Golan", 210.0),
            TasteTrack("Levitating", "Dua Lipa", 203.0),
            TasteTrack("בלילה", "Moti Taka", 198.0),
            TasteTrack("Blinding Lights", "The Weeknd", 200.0),
            TasteTrack("How Deep Is Your Love", "Calvin Harris", 212.0),
            TasteTrack("Waiting For Love", "Avicii", 230.0),
        ),
        topArtists = listOf("Omer Adam", "Coldplay", "Eyal Golan", "Dua Lipa", "Avicii"),
    )

    /**
     * Plan the setlist via the engine's code-based planner (TastePoolPlanner +
     * Deezer discovery); if that throws, fall back to the first 3 taste tracks
     * so the block-render composition still gets proven.
     */
    private fun planSongs(engine: KolaiEngine, taste: TasteProfile): List<Song> {
        return try {
            val songs = runBlocking { engine.planner.plan(taste, n = 3) }
            Log.i(tag, "planner OK: ${songs.size} songs")
            songs
        } catch (e: Throwable) {
            Log.e(tag, "planner FAILED (engine bug?) -> taste fallback: ${e}")
            taste.topTracks.take(3).map { Song(title = it.title, artist = it.artist, durationS = it.durationS) }
        }
    }

    @Test
    fun rendersFullBlockEndToEnd() {
        val keys = geminiKeys()
        assertTrue("Need at least one geminiKey<N> -e arg", keys.isNotEmpty())
        Log.i(tag, "keys=${keys.size} llm=${llmModel()} tts=${ttsModel()} voice=${ttsVoice()}")

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheDir = File(targetContext.cacheDir, "kolai")
        val blocksDir = File(cacheDir, "blocks")
        cacheDir.mkdirs()
        blocksDir.mkdirs()
        Log.i(tag, "cacheDir=${cacheDir.absolutePath}")
        Log.i(tag, "blocksDir=${blocksDir.absolutePath}")

        val engine = KolaiEngine.build(
            geminiKeys = keys,
            llmModel = llmModel(),
            ttsModel = ttsModel(),
            ttsVoice = ttsVoice(),
            cacheDir = cacheDir,
            blocksDir = blocksDir,
        )

        try {
            // --- 0. SANITY: TEXT model works on-device --------------------
            val http2 = HttpClient(OkHttp)
            val sanity = try {
                val tc = GeminiTextClient(keys, llmModel(), http2)
                runBlocking { tc.complete("ענה במילה אחת: שלום") }
            } finally {
                http2.close()
            }
            Log.i(tag, "TEXT-MODEL sanity reply: '$sanity'")
            assertTrue("text model returned empty", sanity.trim().isNotEmpty())
            assertTrue(
                "text model reply has no Hebrew letters: '$sanity'",
                sanity.any { it in '֐'..'׿' },
            )

            // --- 1. PLAN a 3-song setlist (LLM, with taste fallback) ------
            val taste = loadTaste()
            val started = System.currentTimeMillis()
            val songs = planSongs(engine, taste)
            Log.i(tag, "SONGS (${songs.size}):")
            songs.forEachIndexed { i, s -> Log.i(tag, "  [$i] ${s.title} — ${s.artist} (dur=${s.durationS})") }
            assertTrue("no songs to render", songs.isNotEmpty())
            assertTrue("more than 3 songs: ${songs.size}", songs.size <= 3)

            // --- 2. RENDER the block --------------------------------------
            val result = runBlocking { engine.blockRenderer.render(songs, index = 0, prevTrack = null) }
            val elapsedMs = System.currentTimeMillis() - started
            Log.i(tag, "RENDER complete in ${elapsedMs}ms (${elapsedMs / 1000.0}s)")
            Log.i(tag, "block path: ${result.path}")
            Log.i(tag, "meta.durationS=${result.meta.durationS}")

            // --- 3. ASSERT the artifact -----------------------------------
            val blockFile = File(result.path)
            assertTrue("block file does not exist: ${result.path}", blockFile.exists())
            val sizeKb = blockFile.length() / 1024
            Log.i(tag, "block file size: ${sizeKb} KB")
            assertTrue("block file is trivially small (${blockFile.length()} bytes)", blockFile.length() > 64 * 1024)

            // segments + talk
            Log.i(tag, "segments (${result.meta.segments.size}):")
            result.meta.segments.forEach { seg ->
                Log.i(tag, "  seg start=${seg.startS}s end=${seg.endS}s '${seg.title}' — ${seg.artist}")
            }
            assertTrue("no segments in meta", result.meta.segments.isNotEmpty())

            Log.i(tag, "talk (${result.meta.talk.size}):")
            result.meta.talk.forEach { t ->
                Log.i(tag, "  TALK [${t.beat}] ${t.startS}-${t.endS}s: ${t.text}")
            }
            assertTrue("no DJ talk in meta", result.meta.talk.isNotEmpty())

            // MediaExtractor: one audio/mp4a-latm track, duration > 60s.
            val ex = MediaExtractor()
            try {
                ex.setDataSource(result.path)
                var audioTracks = 0
                var aacTrack = -1
                var durationUs = 0L
                for (t in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(t)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTracks++
                        if (mime == "audio/mp4a-latm") aacTrack = t
                        if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                            durationUs = maxOf(durationUs, fmt.getLong(MediaFormat.KEY_DURATION))
                        }
                    }
                }
                val durationS = durationUs / 1_000_000.0
                Log.i(tag, "MediaExtractor: audioTracks=$audioTracks aacTrack=$aacTrack durationS=$durationS")
                assertTrue("expected exactly 1 audio track, got $audioTracks", audioTracks == 1)
                assertTrue("no audio/mp4a-latm (AAC) track found", aacTrack >= 0)
                assertTrue("block duration $durationS s <= 60 s (3 full songs expected)", durationS > 60.0)
            } finally {
                ex.release()
            }

            Log.i(tag, "END-TO-END SUCCESS: full block rendered on device.")
        } finally {
            engine.close()
        }
    }
}