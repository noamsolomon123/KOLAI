package ai.kolai.station

import ai.kolai.core.DJSlot
import ai.kolai.core.Song
import ai.kolai.core.TrackAnalysis
import ai.kolai.mix.Dsp
import ai.kolai.mix.StationIdent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random

/**
 * TDD for Task 5.3 (BlockRenderer + ShowMeta), ported 1:1 from
 * backend/radioai/block_renderer.py and backend/radioai/showmeta.py.
 *
 * No real audio I/O: every seam is faked. The DjBrain is driven by a fake
 * LlmClient. Determinism comes from a fixed-seed kotlin.random.Random(7) and
 * deterministic talk_chance values per test (0.0 to suppress coin-flips, 1.0 to
 * force them), so we assert the resulting event sequence exactly.
 *
 * Research deviations covered (2026-06-11): block 0 uses the session OPENING
 * prompt (writeOpening), block 0 prepends the session IDENT (newRenderer
 * passes ident = null by default so pre-ident timing assertions stay valid;
 * ident tests inject a known fake), and nextBeat()'s hourly anchors
 * (minuteOfHour is injected as a fixed { 15 } here so rotation tests stay
 * minute-independent).
 */
class BlockRendererTest {

    // ---- fakes -----------------------------------------------------------

    /** Returns a canned LLM response; records prompts; never SKIPs. */
    private class FakeLlmClient(private val response: String = "ברוכים הבאים לשידור") : LlmClient {
        var calls = 0
        val prompts = mutableListOf<String>()
        override suspend fun complete(prompt: String): String {
            calls++
            prompts.add(prompt)
            return response
        }
    }

    /** An LlmClient that always returns SKIP (so allow_skip breaks vanish). */
    private class SkipLlmClient : LlmClient {
        override suspend fun complete(prompt: String): String = "SKIP"
    }

    /** Routes prompts by a contained marker; [default] otherwise. Records all. */
    private class RoutedLlmClient(
        private val routes: List<Pair<String, String>>,
        private val default: String = "שורה רגילה של רדיו",
    ) : LlmClient {
        val prompts = mutableListOf<String>()
        override suspend fun complete(prompt: String): String {
            prompts.add(prompt)
            for ((marker, response) in routes) if (prompt.contains(marker)) return response
            return default
        }
    }

    /** fetch(song) -> a deterministic local path per song title. */
    private class FakeFetcher(private val failTitles: Set<String> = emptySet()) : AudioFetcher {
        override fun fetch(song: Song): String {
            if (song.title in failTitles) throw RuntimeException("fetch failed: ${song.title}")
            return "/fake/${song.title}.m4a"
        }
    }

    /** voice.render(text) -> a DJSlot whose audioPath maps to a known-length
     *  array. Records every style passed through the seam. */
    private class FakeVoice(private val djSamples: Int) : VoiceRenderer {
        val styles = mutableListOf<String?>()
        val texts = mutableListOf<String>()
        val voices = mutableListOf<String?>()
        // (turns, voiceB, style, voiceA)
        val dialogues = mutableListOf<DialogueCall>()
        override fun render(text: String, style: String?, voiceName: String?): DJSlot {
            styles.add(style)
            texts.add(text)
            voices.add(voiceName)
            return DJSlot(text = text, audioPath = "/voice/dj.wav", durationS = djSamples.toDouble() / Dsp.SR)
        }
        override fun renderDialogue(
            turns: List<Pair<String, String>>,
            voiceB: String,
            style: String?,
            voiceA: String?,
        ): DJSlot {
            dialogues.add(DialogueCall(turns, voiceB, style, voiceA))
            return DJSlot(text = joinDialogue(turns), audioPath = "/voice/dj.wav", durationS = djSamples.toDouble() / Dsp.SR)
        }
    }

    private data class DialogueCall(
        val turns: List<Pair<String, String>>,
        val voiceB: String,
        val style: String?,
        val voiceA: String?,
    )

    /** Random whose nextDouble() is ~0.5000000075 - deterministically BELOW the
     *  default talk chances (0.5 < 0.9) and ABOVE the focus ones (>= 0.2), so
     *  mood-cadence tests need no seed bookkeeping. */
    private class MidRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 1 shl (bitCount - 1)
    }

    /** Records every encode call (path + audio length). */
    private class FakeEncoder : BlockEncoder {
        val calls = mutableListOf<Pair<String, Int>>()
        override fun encode(path: String, audio: FloatArray) {
            calls.add(path to audio.size)
        }
    }

    private val songSamples = Dsp.SR * 10 // 10s songs
    private val djSamples = Dsp.SR * 2     // 2s DJ clips (loud, so trimSilence keeps them)

    private fun fakeAnalyze(path: String): TrackAnalysis = TrackAnalysis(
        path = path,
        durationS = songSamples.toDouble() / Dsp.SR,
        bpm = 120.0,
        beatTimes = listOf(0.0, 0.5, 1.0),
        keyCamelot = "8A",
        energy = 0.5,
        introEndS = 1.0,
        outroStartS = 9.0,
        vocalOnsetS = 1.0,
    )

    /** Songs -> full-amplitude arrays (so trimSilence keeps all of it);
     *  the DJ voice path -> DC 0.3 + 0.2 sine @ 440 Hz (loud everywhere:
     *  |x| >= 0.1, so trimSilence keeps the full 2 s). ADJUSTED for feature
     *  16: the pre-chain constant-0.5 fixture was pure DC, which the broadcast
     *  chain's 90 Hz high-pass would (correctly) erase; the DC component now
     *  serves as the chain TRACER (see the voice-chain test). */
    private fun fakeLoad(path: String): FloatArray =
        if (path.startsWith("/voice/")) FloatArray(djSamples) { i ->
            0.3f + 0.2f * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / Dsp.SR).toFloat()
        }
        else FloatArray(songSamples) { 0.3f }

    private fun songs(n: Int): List<Song> =
        (0 until n).map { Song(title = "S$it", artist = "A$it") }

    /** RMS of [audio] over [fromS, toS) seconds - level-relationship asserts. */
    private fun rms(audio: FloatArray, fromS: Double, toS: Double): Double {
        val a = (fromS * Dsp.SR).toInt()
        val b = minOf(audio.size, (toS * Dsp.SR).toInt())
        var sumSq = 0.0
        for (i in a until b) sumSq += audio[i].toDouble() * audio[i]
        return kotlin.math.sqrt(sumSq / (b - a))
    }

    /** Mean of [audio] over [fromS, toS) seconds - the DC-tracer assert. */
    private fun mean(audio: FloatArray, fromS: Double, toS: Double): Double {
        val a = (fromS * Dsp.SR).toInt()
        val b = minOf(audio.size, (toS * Dsp.SR).toInt())
        var sum = 0.0
        for (i in a until b) sum += audio[i]
        return sum / (b - a)
    }

    /** [fakeAnalyze] with selected fields overridden (feature 13/15 tests). */
    private fun analyzeWith(
        bpm: Double = 120.0,
        beatTimes: List<Double> = listOf(0.0, 0.5, 1.0),
        introEndS: Double = 1.0,
        outroStartS: Double = 9.0,
    ): AnalyzeFn = { path ->
        fakeAnalyze(path).copy(
            bpm = bpm, beatTimes = beatTimes,
            introEndS = introEndS, outroStartS = outroStartS,
        )
    }

    private fun newRenderer(
        fetcher: AudioFetcher = FakeFetcher(),
        client: LlmClient = FakeLlmClient(),
        voice: VoiceRenderer = FakeVoice(djSamples),
        voiceB: String? = null,
        ctx: DjContext = DjContext(),
        // overrides the fixed [ctx] when a test needs a PER-BLOCK context
        ctxProvider: (() -> DjContext)? = null,
        maxSilence: Int = 4,
        talkChance: Double = 0.5,
        banterChance: Double = 0.2,
        banterEvery: Int = 3,
        encoder: BlockEncoder? = null,
        write: Boolean = false,
        rng: Random = Random(7),
        minuteOfHour: () -> Int = { 15 },
        // pinned fake wall clock for the wave-3 show-format latches
        nowMs: () -> Long = { 0L },
        ident: (() -> FloatArray)? = null,
        analyze: AnalyzeFn = ::fakeAnalyze,
        load: LoadFn = ::fakeLoad,
        // PER-MOOD VOICE resolver (defaults to the Moods spec voice, as in prod).
        moodVoice: (String?) -> String = { Moods.spec(it).voiceName },
        sidekickVoices: List<String> = emptyList(),
        // VOCAL-ONSET seam. DEFAULT returns a generously-safe window so the
        // synthetic (DC/constant) song fixtures keep their opener exactly as
        // before; the vocal-onset tests inject a small window to assert the
        // talk-over guard fires.
        safeIntroFn: (FloatArray, Int) -> Double = { _, _ -> 60.0 },
    ): BlockRenderer = BlockRenderer(
        fetcher = fetcher,
        brain = DjBrain(client, persona = "דני"),
        voice = voice,
        ctx = ctxProvider ?: { ctx },
        blocksDir = "/tmp/blocks",
        voiceA = null,
        voiceB = voiceB,
        maxSilence = maxSilence,
        banterEvery = banterEvery,
        talkChance = talkChance,
        banterChance = banterChance,
        rng = rng,
        minuteOfHour = minuteOfHour,
        nowMs = nowMs,
        ident = ident,
        analyzeFn = analyze,
        loadFn = load,
        encoder = encoder,
        write = write,
        moodVoice = moodVoice,
        sidekickVoices = sidekickVoices,
        safeIntroFn = safeIntroFn,
    )

    private suspend fun loadTracks(r: BlockRenderer, songs: List<Song>): List<LoadedTrack> =
        r.loadTracks(songs)

    // ---- _plan cadence ---------------------------------------------------

    @Test
    fun plan_block0_opens_with_session_opening_naming_song0() = runTest {
        val r = newRenderer(talkChance = 0.0)
        val tracks = loadTracks(r, songs(5))
        val events = r.planFor(tracks, prevTrack = null)

        assertEquals("open", events.first().kind)
        assertEquals(0, events.first().i)
        // open is immediately followed by song 0
        assertEquals("song", events[1].kind)
        assertEquals(0, events[1].i)
    }

    @Test
    fun plan_block0_uses_opening_prompt_with_opening_text() = runTest {
        // Research deviation 3: block 0 goes through brain.writeOpening - the
        // dedicated session-opening prompt - and the "open" event carries the
        // opening text.
        val client = FakeLlmClient()
        val r = newRenderer(
            client = client, talkChance = 0.0,
            ctx = DjContext(timeStr = "08:00", partOfDay = "בוקר"),
        )
        val tracks = loadTracks(r, songs(3))
        val events = r.planFor(tracks, prevTrack = null)

        assertEquals("open", events.first().kind)
        assertEquals("ברוכים הבאים לשידור", events.first().text)
        val p = client.prompts.first()
        assertTrue("not the session opening prompt", p.contains("המילים הראשונות של השידור"))
        assertTrue("part of day not passed to opening", p.contains("בוקר"))
        assertTrue("one-listener fragment missing", p.contains("מאזין אחד"))
    }

    @Test
    fun plan_laterBlock_opens_with_back_announce() = runTest {
        // A non-null prevTrack -> opening break announces the previous song
        // (NOT the session opening prompt).
        val client = FakeLlmClient()
        val r = newRenderer(client = client, talkChance = 0.0)
        val prevSong = Song(title = "PREV", artist = "PA")
        val prevTrack = LoadedTrack(prevSong, fakeAnalyze("/fake/PREV.m4a"), fakeLoad("/fake/PREV.m4a"), "/fake/PREV.m4a")
        val tracks = loadTracks(r, songs(5))
        val events = r.planFor(tracks, prevTrack = prevTrack)
        assertEquals("open", events.first().kind)
        assertEquals(0, events.first().i)
        assertFalse(
            "later blocks must not reuse the session opening prompt",
            client.prompts.first().contains("המילים הראשונות של השידור"),
        )
    }

    @Test
    fun plan_no_talk_two_boundaries_in_a_row_and_forced_after_maxSilence() = runTest {
        // talk_chance 0.0 -> only FORCED breaks. With maxSilence=4 and the open
        // resetting since-talk to 0, boundaries 1,2,3 stay quiet and boundary 4
        // is forced. Deterministic regardless of rng.
        val r = newRenderer(maxSilence = 4, talkChance = 0.0)
        val tracks = loadTracks(r, songs(7))
        val events = r.planFor(tracks, prevTrack = null)

        // boundaries 1..3 produce only a song event each (no break)
        for (i in 1..3) {
            assertTrue("boundary $i should be quiet", events.none { it.i == i && it.kind == "break" })
        }
        // boundary 4 is forced -> a break event precedes song 4
        assertTrue("boundary 4 should be a forced break", events.any { it.i == 4 && it.kind == "break" })
        // and that resets the counter: boundaries 5,6 quiet again (4 not yet)
        for (i in 5..6) {
            assertTrue("boundary $i should be quiet after reset", events.none { it.i == i && it.kind == "break" })
        }
    }

    @Test
    fun plan_cross_block_state_persists() = runTest {
        // One renderer instance, two blocks. With talk_chance 0.0 the first block
        // (7 songs) forces a break at boundary 4 then resets; ending with
        // since-talk = 2 (boundaries 5,6). The SECOND block's open resets to 0,
        // so it again only forces at boundary 4. Assert both blocks force at 4.
        val r = newRenderer(maxSilence = 4, talkChance = 0.0)

        val t1 = loadTracks(r, songs(7))
        val e1 = r.planFor(t1, prevTrack = null)
        assertTrue(e1.any { it.i == 4 && it.kind == "break" })

        val t2 = loadTracks(r, songs(7))
        val prevTrack = t1.last()
        val e2 = r.planFor(t2, prevTrack = prevTrack)
        // second block opens with a back-announce (open) -> resets since-talk
        assertEquals("open", e2.first().kind)
        assertTrue(e2.any { it.i == 4 && it.kind == "break" })
    }

    @Test
    fun plan_deterministic_event_sequence_for_seed7() = runTest {
        // With talk_chance 1.0 every eligible boundary talks. Eligible means
        // since-talk >= 2 (open resets to 0). So boundaries 2,4,6,... talk and
        // 1,3,5 stay quiet (because each talk resets the counter to 0, and the
        // very next boundary is then only 1). Deterministic, rng-independent for
        // the break-vs-banter split we suppress with banter_chance 0.0.
        val r = newRenderer(maxSilence = 100, talkChance = 1.0, banterChance = 0.0)
        val tracks = loadTracks(r, songs(7))
        val events = r.planFor(tracks, prevTrack = null)

        val kinds = events.map { "${it.kind}:${it.i}" }
        assertEquals(
            listOf(
                "open:0", "song:0",
                "song:1",
                "break:2", "song:2",
                "song:3",
                "break:4", "song:4",
                "song:5",
                "break:6", "song:6",
            ),
            kinds,
        )
    }

    // ---- hourly anchors (research deviation 4) -----------------------------

    @Test
    fun plan_minute_anchor_prefers_news_at_top_of_hour() = runTest {
        // minute 0 is inside the round-hour news window [55..59] + [0..5].
        val r = newRenderer(
            maxSilence = 100, talkChance = 1.0, banterChance = 0.0,
            minuteOfHour = { 0 },
        )
        val tracks = loadTracks(r, songs(5))
        val events = r.planFor(tracks, prevTrack = null)
        val firstBreak = events.first { it.kind == "break" }
        assertEquals("news", firstBreak.beat)
    }

    @Test
    fun plan_minute_anchor_prefers_weather_at_half_hour() = runTest {
        // minute 30 is inside the mid-hour weather window [25..35].
        val r = newRenderer(
            maxSilence = 100, talkChance = 1.0, banterChance = 0.0,
            minuteOfHour = { 30 },
        )
        val tracks = loadTracks(r, songs(5))
        val events = r.planFor(tracks, prevTrack = null)
        val firstBreak = events.first { it.kind == "break" }
        assertEquals("weather", firstBreak.beat)
    }

    @Test
    fun plan_minute_anchor_normal_rotation_outside_windows() = runTest {
        // minute 15 is in no anchor window -> standard rotation, first beat is
        // beatForBreak(0) == "song".
        val r = newRenderer(
            maxSilence = 100, talkChance = 1.0, banterChance = 0.0,
            minuteOfHour = { 15 },
        )
        val tracks = loadTracks(r, songs(5))
        val events = r.planFor(tracks, prevTrack = null)
        val firstBreak = events.first { it.kind == "break" }
        assertEquals("song", firstBreak.beat)
    }

    @Test
    fun plan_minute_anchor_fires_once_per_window_without_advancing_rotation() = runTest {
        // The clock is pinned to minute 0 for the whole block: only the FIRST
        // break gets the news anchor; the rest resume the rotation from the
        // start ("song", "weather", ...) because the anchor did not consume a
        // rotation slot.
        val r = newRenderer(
            maxSilence = 100, talkChance = 1.0, banterChance = 0.0,
            minuteOfHour = { 0 },
        )
        val tracks = loadTracks(r, songs(7))
        val events = r.planFor(tracks, prevTrack = null)
        val beats = events.filter { it.kind == "break" }.map { it.beat }
        assertEquals(listOf("news", "song", "weather"), beats)
    }

    // ---- render ----------------------------------------------------------

    @Test
    fun render_write_false_builds_meta_with_monotonic_segments() = runTest {
        val r = newRenderer(talkChance = 0.0, write = false)
        val result = r.render(songs(4), index = 0, prevTrack = null)

        val starts = result.meta.segments.map { it.startS }
        // strictly monotonic (each song after the first starts later)
        for (k in 1 until starts.size) {
            assertTrue("segment starts must be increasing", starts[k] > starts[k - 1])
        }
        // talk entries: start_s <= end_s
        for (t in result.meta.talk) {
            assertTrue("talk start_s <= end_s", t.startS <= t.endS)
        }
        // 4 songs all played
        assertEquals(4, result.meta.segments.size)
    }

    @Test
    fun render_opening_dj_is_ducked_over_song0() = runTest {
        // The opening talkover ducks song 0 at start_s = 0.5. With a loud DJ clip
        // and a quiet song bed (0.3), the ducked+overlaid region differs from the
        // raw song; assert the first DJ talk entry exists at start ~0.5.
        val r = newRenderer(talkChance = 0.0, write = false)
        val result = r.render(songs(3), index = 0, prevTrack = null)
        assertTrue("opening talk entry expected", result.meta.talk.isNotEmpty())
        assertEquals(0.5, result.meta.talk.first().startS, 0.001)
        // the ducked region (around 0.6s) must differ from the raw 0.3 bed
        val idx = (0.6 * Dsp.SR).toInt()
        assertFalse("song0 should be ducked under the DJ", result.audio[idx] == 0.3f)
    }

    @Test
    fun render_skips_song_whose_fetch_throws() = runTest {
        val r = newRenderer(fetcher = FakeFetcher(failTitles = setOf("S1")), talkChance = 0.0, write = false)
        val result = r.render(songs(4), index = 0, prevTrack = null)
        val titles = result.meta.segments.map { it.title }
        assertFalse("failed fetch must be skipped", titles.contains("S1"))
        assertEquals(3, result.meta.segments.size)
    }

    @Test
    fun render_lastTrack_is_last_loaded_track() = runTest {
        val r = newRenderer(talkChance = 0.0, write = false)
        val result = r.render(songs(4), index = 0, prevTrack = null)
        assertEquals("S3", result.lastTrack.song.title)
    }

    @Test
    fun render_durationS_matches_timeline_length() = runTest {
        val r = newRenderer(talkChance = 0.0, write = false)
        val result = r.render(songs(3), index = 0, prevTrack = null)
        // Python: round(len(timeline)/SR, 2)
        val expected = Math.rint((result.audio.size.toDouble() / Dsp.SR) * 100.0) / 100.0
        assertEquals(expected, result.meta.durationS, 1e-9)
        assertEquals(0, result.meta.index)
    }

    @Test
    fun render_write_true_calls_encoder_with_m4a_path() = runTest {
        val encoder = FakeEncoder()
        val r = newRenderer(talkChance = 0.0, write = true, encoder = encoder)
        val result = r.render(songs(2), index = 5, prevTrack = null)
        assertEquals(1, encoder.calls.size)
        assertEquals("/tmp/blocks/block_5.m4a", encoder.calls.first().first)
        assertEquals("/tmp/blocks/block_5.m4a", result.path)
        // audio length passed to encoder matches the result timeline
        assertEquals(result.audio.size, encoder.calls.first().second)
    }

    @Test
    fun render_write_false_does_not_call_encoder() = runTest {
        val encoder = FakeEncoder()
        val r = newRenderer(talkChance = 0.0, write = false, encoder = encoder)
        r.render(songs(2), index = 0, prevTrack = null)
        assertTrue(encoder.calls.isEmpty())
    }

    @Test
    fun render_allowSkip_break_returns_no_talk_entry_for_that_boundary() = runTest {
        // SKIP client -> opening (no skip option) still produces text, but any
        // eligible non-forced break (allow_skip=true) returns null and is dropped.
        // With talk_chance 1.0 + skip client + large maxSilence, no break events
        // survive; only the opening talk entry remains.
        val r = newRenderer(client = SkipLlmClient(), maxSilence = 100, talkChance = 1.0, banterChance = 0.0, write = false)
        val result = r.render(songs(5), index = 0, prevTrack = null)
        // the opening has no SKIP escape -> finish("SKIP") is "SKIP" cleaned
        // text, still appended. So one talk entry (the open) at most; no
        // boundary breaks.
        assertTrue("no boundary breaks should survive skip", result.meta.talk.size <= 1)
    }

    // ---- session ident (research deviation 6) ------------------------------

    /** 1.0 s fake ident at constant 0.3 - the length math is then exact. */
    private val identSamples = Dsp.SR
    private fun fakeIdent(): FloatArray = FloatArray(identSamples) { 0.3f }

    @Test
    fun render_block0_prepends_ident_lengthening_timeline_by_ident_minus_overlap() = runTest {
        val withIdent = newRenderer(talkChance = 0.0, ident = ::fakeIdent)
            .render(songs(3), index = 0, prevTrack = null)
        val without = newRenderer(talkChance = 0.0, ident = null)
            .render(songs(3), index = 0, prevTrack = null)
        val overlapN = (BlockRenderer.IDENT_OVERLAP_S * Dsp.SR).toInt()
        assertEquals(identSamples - overlapN, withIdent.audio.size - without.audio.size)
    }

    @Test
    fun render_block0_opening_talk_starts_after_ident() = runTest {
        // duck start = identDur - overlap + 0.5 = 1.0 - 0.4 + 0.5 = 1.1
        val result = newRenderer(talkChance = 0.0, ident = ::fakeIdent)
            .render(songs(3), index = 0, prevTrack = null)
        assertEquals(1.1, result.meta.talk.first().startS, 0.001)
    }

    @Test
    fun render_block0_song0_segment_still_starts_at_zero_with_ident() = runTest {
        // The ident is part of the STATION opening, not a track: the UI's
        // now-playing must show song 0 from second zero.
        val result = newRenderer(talkChance = 0.0, ident = ::fakeIdent)
            .render(songs(3), index = 0, prevTrack = null)
        assertEquals(0.0, result.meta.segments.first().startS, 1e-9)
    }

    @Test
    fun render_laterBlocks_do_not_get_ident() = runTest {
        var identCalled = false
        val r = newRenderer(talkChance = 0.0, ident = { identCalled = true; fakeIdent() })
        val prevSong = Song(title = "PREV", artist = "PA")
        val prevTrack = LoadedTrack(prevSong, fakeAnalyze("/fake/PREV.m4a"), fakeLoad("/fake/PREV.m4a"), "/fake/PREV.m4a")
        val result = r.render(songs(3), index = 1, prevTrack = prevTrack)
        assertFalse("ident must only play at session start", identCalled)
        // and the opening duck stays at the pre-ident position
        assertEquals(0.5, result.meta.talk.first().startS, 0.001)
    }

    @Test
    fun render_default_ident_is_StationIdent() = runTest {
        // Constructed WITHOUT the ident param: the default must be the real
        // StationIdent, lengthening block 0 by (identLen - overlap) samples.
        val r = BlockRenderer(
            fetcher = FakeFetcher(),
            brain = DjBrain(FakeLlmClient(), persona = "DJ"),
            voice = FakeVoice(djSamples),
            ctx = { DjContext() },
            talkChance = 0.0,
            rng = Random(7),
            minuteOfHour = { 15 },
            analyzeFn = ::fakeAnalyze,
            loadFn = ::fakeLoad,
            write = false,
            // safe intro so the opener fires (this test is about ident length).
            safeIntroFn = { _, _ -> 60.0 },
        )
        val withDefault = r.render(songs(2), index = 0, prevTrack = null)
        val without = newRenderer(talkChance = 0.0, ident = null)
            .render(songs(2), index = 0, prevTrack = null)
        val identN = (StationIdent.DURATION_S * Dsp.SR).toInt()
        val overlapN = (BlockRenderer.IDENT_OVERLAP_S * Dsp.SR).toInt()
        assertEquals(identN - overlapN, withDefault.audio.size - without.audio.size)
    }

    // ---- mood-aware cadence + voice style (mood support) --------------------

    @Test
    fun plan_mood_null_keeps_constructor_cadence() = runTest {
        // MidRandom's ~0.5 is below the constructor talkChance (0.9): every
        // eligible boundary (since-talk >= 2) talks -> breaks at 2, 4, 6.
        val r = newRenderer(
            maxSilence = 100, talkChance = 0.9, banterChance = 0.0,
            rng = MidRandom(), ctx = DjContext(mood = null),
        )
        val tracks = loadTracks(r, songs(8))
        val events = r.planFor(tracks, prevTrack = null)
        val breakAt = events.filter { it.kind == "break" }.map { it.i }
        assertEquals(listOf(2, 4, 6), breakAt)
    }

    @Test
    fun plan_mood_focus_overrides_constructor_cadence() = runTest {
        // Same renderer config, but the block ctx carries mood = "focus"
        // (talkChance 0.2, maxSilence 6): MidRandom's ~0.5 >= 0.2 so no
        // coin-flip break ever fires, and the FIRST break is forced only at
        // boundary 6 (the mood's maxSilence), not the constructor's values.
        val r = newRenderer(
            maxSilence = 100, talkChance = 0.9, banterChance = 0.0,
            rng = MidRandom(), ctx = DjContext(mood = "focus"),
        )
        val tracks = loadTracks(r, songs(8))
        val events = r.planFor(tracks, prevTrack = null)
        val breakAt = events.filter { it.kind == "break" }.map { it.i }
        assertEquals(listOf(6), breakAt)
    }

    @Test
    fun plan_unknown_mood_falls_back_to_constructor_cadence() = runTest {
        val r = newRenderer(
            maxSilence = 100, talkChance = 0.9, banterChance = 0.0,
            rng = MidRandom(), ctx = DjContext(mood = "no_such_mood"),
        )
        val tracks = loadTracks(r, songs(8))
        val events = r.planFor(tracks, prevTrack = null)
        val breakAt = events.filter { it.kind == "break" }.map { it.i }
        assertEquals(listOf(2, 4, 6), breakAt)
    }

    @Test
    fun render_passes_mood_ttsStyle_to_voice_when_mood_set() = runTest {
        val voice = FakeVoice(djSamples)
        val r = newRenderer(voice = voice, talkChance = 0.0, ctx = DjContext(mood = "late_night"))
        r.render(songs(3), index = 0, prevTrack = null)
        assertTrue("voice should have rendered the opening", voice.styles.isNotEmpty())
        val expected = Moods.ALL.getValue("late_night").ttsStyle
        voice.styles.forEach { assertEquals(expected, it) }
    }

    @Test
    fun render_null_mood_resolves_mix_voice_and_calm_style() = runTest {
        // INTENTIONAL CHANGE (2026-06-13, task 2): the old behavior left a null
        // mood (the default/daytime "mix" case, LiveDjContext maps mix -> null)
        // with NO style and NO voice - the "mix applies no style/voice" bug. A
        // null mood now resolves voice + delivery from the EFFECTIVE mood
        // ("mix"), so the DJ always has a real calm voice + calm style.
        val voice = FakeVoice(djSamples)
        val r = newRenderer(voice = voice, talkChance = 0.0, ctx = DjContext())
        r.render(songs(3), index = 0, prevTrack = null)
        assertTrue(voice.styles.isNotEmpty())
        val mix = Moods.spec("mix")
        voice.styles.forEach { assertEquals("null mood must get mix calm style", mix.ttsStyle, it) }
        voice.voices.forEach { assertEquals("null mood must get mix voice", mix.voiceName, it) }
    }

    // ---- audio quality: loudness normalization, limiter, segues, fades ------

    @Test
    fun render_normalizes_song_bed_to_target_rms() = runTest {
        // fakeLoad songs are constant 0.3 -> middle-60% RMS = 0.3 -> gain
        // 0.08/0.3 (inside +/-4x) -> the bed sits at SONG_TARGET_RMS. Sample
        // at 4.5 s: after the opening talk-over (ends ~2.5 s) and before the
        // 4 s musical crossfade region (starts at 6 s of the 10 s song).
        val result = newRenderer(talkChance = 0.0)
            .render(songs(2), index = 0, prevTrack = null)
        val idx = (4.5 * Dsp.SR).toInt()
        assertEquals(BlockRenderer.SONG_TARGET_RMS, result.audio[idx], 1e-3f)
    }

    @Test
    fun render_voice_sits_clearly_above_ducked_bed() = runTest {
        // ADJUSTED (feature 16): the DJ clip now runs through
        // voiceBroadcastChain before normalization, so the old exact
        // sample-value assertion (constant voice + constant bed) no longer
        // holds. Assert the level RELATIONSHIP instead: over the steady
        // talk-over region (duck 0.5..2.5 s, 0.3 s ramp -> 0.9..2.3 s) the
        // RMS sits at ~VOICE_TARGET_RMS, far above the ducked
        // SONG_TARGET_RMS * 10^(-15/20) ~= 0.0142 bed.
        val result = newRenderer(talkChance = 0.0)
            .render(songs(2), index = 0, prevTrack = null)
        val bed = BlockRenderer.SONG_TARGET_RMS * Math.pow(10.0, -15.0 / 20.0)
        val voiceRms = rms(result.audio, 0.9, 2.3)
        assertEquals(BlockRenderer.VOICE_TARGET_RMS.toDouble(), voiceRms, 0.02)
        assertTrue("voice must dominate the ducked bed", voiceRms > 4.0 * bed)
    }

    @Test
    fun render_musical_segue_is_longer_when_no_talk_at_boundary() = runTest {
        // 2 songs x 10 s, no boundary talk -> ONE musical crossfade of
        // MUSIC_SEGUE_S (4 s) -> total 10 + 10 - 4 = 16 s.
        val result = newRenderer(talkChance = 0.0)
            .render(songs(2), index = 0, prevTrack = null)
        assertEquals(16.0, result.meta.durationS, 0.01)
    }

    @Test
    fun render_talk_boundary_keeps_short_segue() = runTest {
        // maxSilence = 1 forces a break at boundary 1 -> that boundary keeps
        // the tight talk-over segue (1.5 s) so DJ timing is untouched ->
        // total 10 + 10 - 1.5 = 18.5 s.
        val result = newRenderer(talkChance = 0.0, maxSilence = 1)
            .render(songs(2), index = 0, prevTrack = null)
        assertEquals(18.5, result.meta.durationS, 0.01)
    }

    @Test
    fun render_timeline_starts_and_ends_at_silence() = runTest {
        // Micro-fades: with no ident, the block's very first sample is song
        // 0's faded-in first sample and the very last is the final song's
        // faded-out tail -> both exactly 0, no edge clicks.
        val result = newRenderer(talkChance = 0.0)
            .render(songs(2), index = 0, prevTrack = null)
        assertEquals(0.0f, result.audio.first(), 1e-6f)
        assertEquals(0.0f, result.audio.last(), 1e-6f)
    }

    @Test
    fun render_final_block_stays_within_unity() = runTest {
        // The final assembled block is soft-limited: no sample may exceed
        // full scale (with ident + opening talk-over + a forced break).
        val result = newRenderer(talkChance = 0.0, maxSilence = 1, ident = ::fakeIdent)
            .render(songs(3), index = 0, prevTrack = null)
        for (v in result.audio) {
            assertTrue("sample must stay within [-1, 1]", abs(v) <= 1.0f)
        }
    }

    // ---- analysis-driven transitions (feature 13) ---------------------------

    @Test
    fun render_pure_music_boundary_cuts_outro_and_meta_matches_audio() = runTest {
        // outroStartS = 6.0 on a flat 10 s song: refineOutroStart keeps the
        // hint (energy runs to the end), the cut lands at 6.0 + OUTRO_GRACE_S
        // = 7.0 s, then the 4 s musical crossfade -> 7 + 10 - 4 = 13 s total,
        // and song 1's segment starts exactly at the cut boundary (7.0 s) -
        // the meta the app's now-playing/skip depends on.
        val result = newRenderer(talkChance = 0.0, analyze = analyzeWith(outroStartS = 6.0))
            .render(songs(2), index = 0, prevTrack = null)
        assertEquals(13.0, result.meta.durationS, 0.01)
        assertEquals(2, result.meta.segments.size)
        assertEquals(7.0, result.meta.segments[1].startS, 0.01)
    }

    @Test
    fun render_talk_boundary_skips_outro_cut() = runTest {
        // Same outro hint, but maxSilence = 1 forces a break at boundary 1:
        // talk-over boundaries keep the full tail (the duck look-back needs
        // it) -> 10 + 10 - 1.5 = 18.5 s, boundary meta at the full 10 s.
        val result = newRenderer(
            talkChance = 0.0, maxSilence = 1,
            analyze = analyzeWith(outroStartS = 6.0),
        ).render(songs(2), index = 0, prevTrack = null)
        assertEquals(18.5, result.meta.durationS, 0.01)
        assertEquals(10.0, result.meta.segments[1].startS, 0.01)
    }

    @Test
    fun render_musical_overlap_snaps_to_outgoing_beats() = runTest {
        // bpm 132 -> beat 0.4545 s; 4.0 s rounds to 9 beats = 4.0909 s. Sanity-
        // check the snap actually moved, then assert the block length follows
        // the SNAPPED overlap: 10 + 10 - snapped.
        val snapped = Dsp.snapOverlapToBeats(BlockRenderer.MUSIC_SEGUE_S, 132.0f)
        assertTrue("fixture bpm must move the snap", abs(snapped - BlockRenderer.MUSIC_SEGUE_S) > 0.05f)
        val result = newRenderer(talkChance = 0.0, analyze = analyzeWith(bpm = 132.0))
            .render(songs(2), index = 0, prevTrack = null)
        assertEquals(20.0 - snapped, result.meta.durationS, 0.01)
    }

    @Test
    fun render_insane_bpm_keeps_plain_music_segue() = runTest {
        // bpm 250 is outside [MIN_SNAP_BPM, MAX_SNAP_BPM]: no beat snapping,
        // the plain MUSIC_SEGUE_S (4 s) stays -> 10 + 10 - 4 = 16 s.
        val result = newRenderer(talkChance = 0.0, analyze = analyzeWith(bpm = 250.0))
            .render(songs(2), index = 0, prevTrack = null)
        assertEquals(16.0, result.meta.durationS, 0.01)
    }

    @Test
    fun render_incoming_entry_aligns_to_beat_at_crossfade_midpoint() = runTest {
        // Crossfade midpoint = 4.0 / 2 = 2.0 s; the incoming song's first beat
        // at/after it is 2.5 s -> its head is trimmed 0.5 s so that beat lands
        // ON the midpoint: 10 + 9.5 - 4 = 15.5 s.
        val result = newRenderer(
            talkChance = 0.0, analyze = analyzeWith(beatTimes = listOf(0.0, 2.5)),
        ).render(songs(2), index = 0, prevTrack = null)
        assertEquals(15.5, result.meta.durationS, 0.01)
    }

    @Test
    fun render_entry_alignment_skipped_when_trim_exceeds_cap() = runTest {
        // First beat after the midpoint is 7.0 s -> trimming 5 s would exceed
        // MAX_BEAT_ALIGN_SKIP_S (4 s) and eat the intro: identity, 16 s total.
        val result = newRenderer(
            talkChance = 0.0, analyze = analyzeWith(beatTimes = listOf(0.0, 7.0)),
        ).render(songs(2), index = 0, prevTrack = null)
        assertEquals(16.0, result.meta.durationS, 0.01)
    }

    // ---- vocal-aware opening budget, tier 1 (feature 15) --------------------

    @Test
    fun plan_opening_budget_clamped_to_intro_end() = runTest {
        // introEndS = 8.0 (sane: > 4 s, inside the track): the opening budget
        // clamps to the usable bed 8.0 - 0.5 (duck start) - 0.5 (tail guard)
        // = 7.0 s -> 17 words, instead of the 10 s / 25 words default.
        val client = FakeLlmClient()
        val r = newRenderer(client = client, talkChance = 0.0, analyze = analyzeWith(introEndS = 8.0))
        val tracks = loadTracks(r, songs(2))
        r.planFor(tracks, prevTrack = null)
        assertTrue(
            "budget must clamp to the intro bed",
            client.prompts.first().contains("עד 17 מילים"),
        )
    }

    @Test
    fun plan_opening_budget_keeps_default_when_intro_too_short() = runTest {
        // The fixture introEndS = 1.0 is below OPENING_INTRO_MIN_S: no usable
        // intro bed -> intro-fitting skipped, default 10 s -> 25 words stays.
        val client = FakeLlmClient()
        val r = newRenderer(client = client, talkChance = 0.0)
        val tracks = loadTracks(r, songs(2))
        r.planFor(tracks, prevTrack = null)
        assertTrue(
            "default budget must stay",
            client.prompts.first().contains("עד 25 מילים"),
        )
    }

    // ---- broadcast voice chain + eased duck release (feature 16) ------------

    @Test
    fun render_voice_chain_removes_dc_tracer_from_talk_over() = runTest {
        // The raw DJ fixture carries a 0.3 DC tracer; the broadcast chain's
        // 90 Hz high-pass must erase it on air. Without the chain the
        // normalized voice would keep ~0.13 of DC and the talk-over region's
        // mean would sit near 0.15; with it only the ducked bed (~0.014)
        // remains.
        val result = newRenderer(talkChance = 0.0)
            .render(songs(2), index = 0, prevTrack = null)
        val m = mean(result.audio, 0.9, 2.3)
        assertTrue("DC tracer must be removed by the broadcast chain (mean=$m)", abs(m) < 0.05)
    }

    @Test
    fun render_duck_release_swells_back_gradually() = runTest {
        // The opening talk ends at 2.5 s; with VOICE_DUCK_RELEASE_S = 0.7 the
        // bed half-way through the release (2.85 s) must sit strictly BETWEEN
        // the ducked (~0.014) and full (0.08) levels - a swell, not a snap.
        val result = newRenderer(talkChance = 0.0)
            .render(songs(2), index = 0, prevTrack = null)
        val v = result.audio[(2.85 * Dsp.SR).toInt()]
        assertTrue("bed must still be recovering (v=$v)", v > 0.02f)
        assertTrue("bed must not have snapped back (v=$v)", v < 0.07f)
    }

    // ---- parallel song loads (feature 14b) -----------------------------------

    @Test
    fun loadTracks_parallel_preserves_setlist_order() = runTest {
        // S0 is the SLOWEST load: with parallel loads a naive collect would
        // yield it last; map { async } + awaitAll must keep setlist order.
        val slowFirst = object : AudioFetcher {
            override fun fetch(song: Song): String {
                if (song.title == "S0") Thread.sleep(120)
                return "/fake/${song.title}.m4a"
            }
        }
        val tracks = newRenderer(fetcher = slowFirst).loadTracks(songs(4))
        assertEquals(listOf("S0", "S1", "S2", "S3"), tracks.map { it.song.title })
    }

    @Test
    fun loadTracks_concurrency_capped_at_LOAD_CONCURRENCY() = runTest {
        val active = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val gauge = object : AudioFetcher {
            override fun fetch(song: Song): String {
                val now = active.incrementAndGet()
                maxSeen.updateAndGet { maxOf(it, now) }
                Thread.sleep(60)
                active.decrementAndGet()
                return "/fake/${song.title}.m4a"
            }
        }
        newRenderer(fetcher = gauge).loadTracks(songs(8))
        assertTrue(
            "max in-flight loads (${maxSeen.get()}) must respect the cap",
            maxSeen.get() <= BlockRenderer.LOAD_CONCURRENCY,
        )
        assertTrue("loads must actually run in parallel", maxSeen.get() >= 2)
    }

    // ---- show formats (wave 3): recap opening + day-part handover -----------

    /** Songs whose tasteRank equals their index (all < TASTE_WINK_MAX_RANK). */
    private fun winkSongs(n: Int): List<Song> =
        (0 until n).map { Song(title = "S$it", artist = "A$it", tasteRank = it) }

    @Test
    fun plan_block0_recap_opening_used_when_recapBrief_set() = runTest {
        val client = FakeLlmClient()
        val r = newRenderer(
            client = client, talkChance = 0.0,
            ctx = DjContext(partOfDay = "בוקר", recapBrief = "112 שירים, אמן השבוע: עומר אדם"),
        )
        val tracks = loadTracks(r, songs(3))
        val events = r.planFor(tracks, prevTrack = null)
        assertEquals("open", events.first().kind)
        val prompt = client.prompts.first()
        assertTrue("recap prompt expected", prompt.contains("הסיכום השבועי"))
        assertTrue("the brief must be woven in", prompt.contains("עומר אדם"))
        // the recap gets its own 20 s budget -> 50 words (not the plain 25)
        assertTrue("recap budget expected", prompt.contains("עד 50 מילים"))
    }

    @Test
    fun plan_block0_plain_opening_when_recapBrief_null() = runTest {
        val client = FakeLlmClient()
        val r = newRenderer(client = client, talkChance = 0.0, ctx = DjContext(partOfDay = "בוקר"))
        r.planFor(loadTracks(r, songs(3)), prevTrack = null)
        assertFalse(client.prompts.first().contains("הסיכום השבועי"))
    }

    @Test
    fun render_recap_opening_duck_follows_actual_clip_duration() = runTest {
        // A 6 s recap clip (vs the usual 2 s fixture): the opening duck math
        // tracks the ACTUAL slot duration - talk end = duck start + clip len.
        val longLoad: LoadFn = { path ->
            if (path.startsWith("/voice/")) FloatArray(Dsp.SR * 6) { i ->
                0.3f + 0.2f * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / Dsp.SR).toFloat()
            } else FloatArray(songSamples) { 0.3f }
        }
        val r = newRenderer(talkChance = 0.0, ctx = DjContext(recapBrief = "סיכום"), load = longLoad)
        val result = r.render(songs(3), index = 0, prevTrack = null)
        val talk = result.meta.talk.first()
        assertEquals(0.5, talk.startS, 0.001)
        assertEquals(6.0, talk.endS - talk.startS, 0.05)
    }

    @Test
    fun plan_handover_fires_once_per_transition_and_replaces_the_break() = runTest {
        val client = FakeLlmClient()
        var part = "בוקר"
        val r = newRenderer(
            client = client, talkChance = 0.0, maxSilence = 4,
            ctxProvider = { DjContext(partOfDay = part) },
        )
        // block 0 establishes lastPartOfDay; no handover is possible yet.
        val t0 = loadTracks(r, songs(7))
        val e0 = r.planFor(t0, prevTrack = null)
        assertTrue(e0.none { it.beat == "handover" })

        // block 1 flips the day part: its single forced boundary becomes the
        // handover - REPLACING the break, never adding one.
        part = "צהריים"
        val t1 = loadTracks(r, songs(7))
        val e1 = r.planFor(t1, prevTrack = t0.last())
        val breaks1 = e1.filter { it.kind == "break" }
        assertEquals(listOf(4), breaks1.map { it.i })
        assertEquals(listOf("handover"), breaks1.map { it.beat })
        assertTrue(client.prompts.any { it.contains("חלק חדש של היום") })

        // block 2 keeps the same day part: the transition is spent.
        val t2 = loadTracks(r, songs(7))
        val e2 = r.planFor(t2, prevTrack = t1.last())
        assertTrue(e2.none { it.beat == "handover" })
    }

    @Test
    fun plan_handover_silent_transition_still_consumes_the_change() = runTest {
        var part = "בוקר"
        val r = newRenderer(
            talkChance = 0.0, maxSilence = 4,
            ctxProvider = { DjContext(partOfDay = part) },
        )
        val t0 = loadTracks(r, songs(3))
        r.planFor(t0, prevTrack = null)

        // block 1 flips the day part but has NO eligible boundary: the
        // transition passes silently - and is still consumed.
        part = "ערב"
        val t1 = loadTracks(r, songs(3))
        val e1 = r.planFor(t1, prevTrack = t0.last())
        assertTrue(e1.none { it.kind == "break" })

        // block 2 has a forced boundary, but no late handover may fire.
        val t2 = loadTracks(r, songs(7))
        val e2 = r.planFor(t2, prevTrack = t1.last())
        assertTrue("spent transitions never fire late", e2.none { it.beat == "handover" })
        assertTrue("the forced boundary still talks", e2.any { it.kind == "break" })
    }

    // ---- taste wink (feature 9) ---------------------------------------------

    @Test
    fun plan_taste_wink_needs_tasteRank_and_60min_spacing() = runTest {
        val wink = "הכי אהובים על המאזין"
        val client = FakeLlmClient()
        var now = 0L
        // 11 songs, every eligible boundary talks: breaks at 2,4,6,8,10 with
        // rotation beats song,weather,topic,news,song - two "song" chances.
        val r = newRenderer(
            client = client, maxSilence = 100, talkChance = 1.0,
            banterChance = 0.0, nowMs = { now },
        )
        r.planFor(loadTracks(r, winkSongs(11)), prevTrack = null)
        assertEquals(
            "only the FIRST song-beat inside the hour may wink",
            1, client.prompts.count { it.contains(wink) },
        )

        // 61 minutes later the latch re-arms: the next song-beat winks again.
        now = 61L * 60_000L
        r.planFor(loadTracks(r, winkSongs(11)), prevTrack = null)
        assertEquals(2, client.prompts.count { it.contains(wink) })
    }

    @Test
    fun plan_no_wink_without_tasteRank() = runTest {
        val client = FakeLlmClient()
        val r = newRenderer(client = client, maxSilence = 100, talkChance = 1.0, banterChance = 0.0)
        // default songs() carry no tasteRank -> the wink may never fire.
        r.planFor(loadTracks(r, songs(7)), prevTrack = null)
        assertTrue(client.prompts.none { it.contains("הכי אהובים על המאזין") })
    }

    // ---- good thing (feature 10) ---------------------------------------------

    @Test
    fun plan_good_thing_fires_once_per_3h_and_never_in_block0() = runTest {
        val client = FakeLlmClient()
        var now = 0L
        val r = newRenderer(client = client, talkChance = 0.0, maxSilence = 4, nowMs = { now })

        // block 0: never a good-thing, even at the forced boundary.
        val t0 = loadTracks(r, songs(7))
        val e0 = r.planFor(t0, prevTrack = null)
        assertTrue(e0.none { it.beat == "good_thing" })

        // block 1: the forced boundary becomes the branded micro-segment.
        val t1 = loadTracks(r, songs(7))
        val e1 = r.planFor(t1, prevTrack = t0.last())
        val gt = e1.filter { it.beat == "good_thing" }
        assertEquals(listOf(4), gt.map { it.i })
        assertTrue("branded opener required", gt.first().text!!.startsWith("ומשהו טוב לדרך"))

        // block 2, same clock: latch closed -> a NORMAL forced break instead.
        val t2 = loadTracks(r, songs(7))
        val e2 = r.planFor(t2, prevTrack = t1.last())
        assertTrue(e2.none { it.beat == "good_thing" })
        assertTrue(e2.any { it.i == 4 && it.kind == "break" })

        // 3 h later the latch re-arms.
        now = 3L * 60L * 60_000L
        val t3 = loadTracks(r, songs(7))
        val e3 = r.planFor(t3, prevTrack = t2.last())
        assertTrue(e3.any { it.beat == "good_thing" })
    }

    @Test
    fun plan_good_thing_skip_does_not_consume_latch() = runTest {
        val client = RoutedLlmClient(routes = listOf("משהו טוב לדרך" to "SKIP"))
        val r = newRenderer(client = client, talkChance = 0.0, maxSilence = 4)
        val t0 = loadTracks(r, songs(7))
        r.planFor(t0, prevTrack = null)

        // skipped -> the boundary falls through to a NORMAL forced break...
        val t1 = loadTracks(r, songs(7))
        val e1 = r.planFor(t1, prevTrack = t0.last())
        val b1 = e1.filter { it.kind == "break" }
        assertEquals(listOf(4), b1.map { it.i })
        assertFalse(b1.first().beat == "good_thing")

        // ...and the latch is NOT consumed: block 2 tries the segment again.
        val t2 = loadTracks(r, songs(7))
        r.planFor(t2, prevTrack = t1.last())
        assertEquals(2, client.prompts.count { it.contains("משהו טוב לדרך") })
    }

    @Test
    fun plan_good_thing_blocked_when_somber() = runTest {
        val client = FakeLlmClient()
        val r = newRenderer(
            client = client, talkChance = 0.0, maxSilence = 4,
            ctx = DjContext(somber = true),
        )
        val t0 = loadTracks(r, songs(7))
        r.planFor(t0, prevTrack = null)
        val t1 = loadTracks(r, songs(7))
        val e1 = r.planFor(t1, prevTrack = t0.last())
        assertTrue(client.prompts.none { it.contains("משהו טוב לדרך") })
        assertTrue(e1.none { it.beat == "good_thing" })
    }

    // ---- two-host banter (feature 11) ----------------------------------------

    /** A valid 3-turn A/B/A banter script (A hands over to the music). */
    private val banterJson =
        """[{"s":"A","t":"שמעת את זה"},{"s":"B","t":"שמעתי"},{"s":"A","t":"אז הנה השיר הבא"}]"""

    @Test
    fun render_banter_uses_renderDialogue_when_voiceB_set() = runTest {
        val client = RoutedLlmClient(routes = listOf("באנטר" to banterJson))
        val voice = FakeVoice(djSamples)
        // maxSilence 1 forces boundary 1; banterEvery 1 makes it banter.
        val r = newRenderer(
            client = client, voice = voice, voiceB = "Kore",
            talkChance = 0.0, maxSilence = 1, banterEvery = 1,
        )
        val result = r.render(songs(2), index = 0, prevTrack = null)

        assertEquals(1, voice.dialogues.size)
        val (turns, voiceB, _) = voice.dialogues.first()
        assertEquals("Kore", voiceB)
        assertEquals(3, turns.size)
        assertEquals("A" to "שמעת את זה", turns.first())

        val talk = result.meta.talk.first { it.beat == "banter" }
        assertTrue(talk.text.contains("A: שמעת את זה"))
        assertTrue(talk.text.contains("B: שמעתי"))
    }

    @Test
    fun render_banter_voiceB_null_joins_turns_single_voice() = runTest {
        val client = RoutedLlmClient(routes = listOf("באנטר" to banterJson))
        val voice = FakeVoice(djSamples)
        val r = newRenderer(
            client = client, voice = voice,
            talkChance = 0.0, maxSilence = 1, banterEvery = 1,
        )
        val result = r.render(songs(2), index = 0, prevTrack = null)
        assertTrue("no dialogue seam without a voiceB", voice.dialogues.isEmpty())
        assertTrue(voice.texts.any { it.contains("A: שמעת את זה") && it.contains("B: שמעתי") })
        assertTrue(result.meta.talk.any { it.beat == "banter" })
    }

    @Test
    fun plan_empty_banter_falls_back_to_single_voice_break() = runTest {
        val client = RoutedLlmClient(routes = listOf("באנטר" to "[]"))
        val r = newRenderer(client = client, talkChance = 0.0, maxSilence = 1, banterEvery = 1)
        val events = r.planFor(loadTracks(r, songs(2)), prevTrack = null)
        val brk = events.first { it.kind == "break" }
        assertEquals("the MVP substitute break, not banter", "song", brk.beat)
        assertNotNull(brk.text)
    }

    @Test
    fun seam_renderDialogue_default_body_is_single_voice() {
        // The seam DEFAULT must keep every existing single-voice adapter
        // working: it flattens the turns and delegates to render(text, style,
        // voiceName=voiceA) so the main host voice still carries through.
        val rendered = mutableListOf<Triple<String, String?, String?>>()
        val v = object : VoiceRenderer {
            override fun render(text: String, style: String?, voiceName: String?): DJSlot {
                rendered.add(Triple(text, style, voiceName))
                return DJSlot(text = text, audioPath = "/voice/dj.wav", durationS = 1.0)
            }
        }
        val slot = v.renderDialogue(listOf("A" to "שלום", "B" to "אהלן"), voiceB = "Kore", style = "calm", voiceA = "HostV")
        assertEquals(1, rendered.size)
        assertEquals(joinDialogue(listOf("A" to "שלום", "B" to "אהלן")), rendered.first().first)
        assertEquals("calm", rendered.first().second)
        assertEquals("HostV", rendered.first().third)
        assertEquals(rendered.first().first, slot.text)
    }

    // ---- calendar mention gate (feature 7) -------------------------------

    @Test
    fun plan_calendar_note_gated_to_once_per_90min() = runTest {
        val client = FakeLlmClient()
        var now = 0L
        val r = newRenderer(
            client = client, talkChance = 0.0, maxSilence = 4, nowMs = { now },
            ctx = DjContext(calendarNote = "ערב שבת"),
        )
        // the opening mentions the note and CLOSES the gate; the forced
        // boundary-4 break inside the same 90 min must see a stripped ctx.
        r.planFor(loadTracks(r, songs(7)), prevTrack = null)
        assertTrue("first talk carries the note", client.prompts.first().contains("ערב שבת"))
        assertTrue(
            "gated talks must not see the note",
            client.prompts.drop(1).none { it.contains("ערב שבת") },
        )

        // 91 minutes later the gate re-opens.
        now = 91L * 60_000L
        r.planFor(loadTracks(r, songs(2)), prevTrack = null)
        assertTrue(client.prompts.last().contains("ערב שבת"))
    }


    // ---- per-mood voice (task 1, 2026-06-13) --------------------------------

    @Test
    fun render_passes_mood_voice_to_render_when_mood_set() = runTest {
        val voice = FakeVoice(djSamples)
        val r = newRenderer(voice = voice, talkChance = 0.0, ctx = DjContext(mood = "party"))
        r.render(songs(3), index = 0, prevTrack = null)
        assertTrue(voice.voices.isNotEmpty())
        val expected = Moods.spec("party").voiceName
        voice.voices.forEach { assertEquals("party host voice expected", expected, it) }
    }

    @Test
    fun render_mix_resolves_algieba_voice_and_calm_style_not_null() = runTest {
        val voice = FakeVoice(djSamples)
        val r = newRenderer(voice = voice, talkChance = 0.0, ctx = DjContext())
        r.render(songs(3), index = 0, prevTrack = null)
        val mix = Moods.spec("mix")
        assertEquals("Algieba", mix.voiceName)
        assertTrue(voice.voices.isNotEmpty())
        voice.voices.forEach { assertEquals(mix.voiceName, it) }
        voice.styles.forEach {
            assertNotNull("mix must get a calm style, not null", it)
            assertEquals(mix.ttsStyle, it)
        }
    }

    @Test
    fun render_moodVoice_override_wins_over_moods_default() = runTest {
        val voice = FakeVoice(djSamples)
        val r = newRenderer(
            voice = voice, talkChance = 0.0, ctx = DjContext(mood = "party"),
            moodVoice = { mood -> if (mood == "party") "Custom" else Moods.spec(mood).voiceName },
        )
        r.render(songs(3), index = 0, prevTrack = null)
        voice.voices.forEach { assertEquals("Custom", it) }
    }

    @Test
    fun render_dialogue_carries_mood_voice_as_voiceA() = runTest {
        // mood "party" sets maxSilence=3; with talkChance 0 only the forced
        // boundary (3) talks and banterEvery 1 makes it a banter. block 0 has
        // no prevTrack so good-thing cannot pre-empt it -> deterministic banter.
        val client = RoutedLlmClient(routes = listOf("באנטר" to banterJson))
        val voice = FakeVoice(djSamples)
        val r = newRenderer(
            client = client, voice = voice, voiceB = "Kore",
            talkChance = 0.0, banterEvery = 1,
            ctx = DjContext(mood = "party"),
        )
        r.render(songs(4), index = 0, prevTrack = null)
        assertEquals(1, voice.dialogues.size)
        assertEquals(Moods.spec("party").voiceName, voice.dialogues.first().voiceA)
        assertEquals("Kore", voice.dialogues.first().voiceB)
    }

    // ---- vocal-onset talk-over guard (task 3, 2026-06-13) -------------------

    @Test
    fun plan_block0_skips_opener_when_safe_intro_window_too_short() = runTest {
        val r = newRenderer(talkChance = 0.0, safeIntroFn = { _, _ -> 0.0 })
        val tracks = loadTracks(r, songs(3))
        val events = r.planFor(tracks, prevTrack = null)
        assertTrue("opener must be suppressed", events.none { it.kind == "open" })
        assertEquals("song", events.first().kind)
    }

    @Test
    fun render_no_opening_talk_when_safe_intro_window_too_short() = runTest {
        val r = newRenderer(talkChance = 0.0, safeIntroFn = { _, _ -> 0.0 })
        val result = r.render(songs(3), index = 0, prevTrack = null)
        assertTrue("no talk over the opening", result.meta.talk.none { it.beat == "song" && it.startS < 1.0 })
    }

    @Test
    fun plan_laterBlock_skips_back_announce_when_safe_intro_too_short() = runTest {
        val prevSong = Song(title = "PREV", artist = "PA")
        val prevTrack = LoadedTrack(prevSong, fakeAnalyze("/fake/PREV.m4a"), fakeLoad("/fake/PREV.m4a"), "/fake/PREV.m4a")
        val unsafe = newRenderer(talkChance = 0.0, safeIntroFn = { _, _ -> 0.0 })
        val eUnsafe = unsafe.planFor(loadTracks(unsafe, songs(5)), prevTrack = prevTrack)
        assertTrue("unsafe suppresses opener", eUnsafe.none { it.kind == "open" })
        val safe = newRenderer(talkChance = 0.0, safeIntroFn = { _, _ -> 30.0 })
        val eSafe = safe.planFor(loadTracks(safe, songs(5)), prevTrack = prevTrack)
        assertEquals("open", eSafe.first().kind)
    }

    @Test
    fun plan_opening_budget_clamped_by_vocal_onset_below_introEnd() = runTest {
        val client = FakeLlmClient()
        val r = newRenderer(
            client = client, talkChance = 0.0,
            analyze = analyzeWith(introEndS = 8.0),
            safeIntroFn = { _, _ -> 6.0 },
        )
        r.planFor(loadTracks(r, songs(2)), prevTrack = null)
        assertTrue("clamp to vocal onset", client.prompts.first().contains("עד 12 מילים"))
    }

    // ---- fun segments: trivia (task 4, 2026-06-13) --------------------------

    private val triviaJson =
        """[{"s":"A","t":"מי כתב"},{"s":"B","t":"לא יודע"},{"s":"A","t":"נגלה אחרי השיר"}]"""

    @Test
    fun plan_trivia_fires_once_per_window_and_replaces_break() = runTest {
        val client = RoutedLlmClient(routes = listOf("משהו טוב לדרך" to "SKIP", "פינת טריוו" to triviaJson))
        var now = 0L
        val r = newRenderer(client = client, talkChance = 0.0, maxSilence = 4, nowMs = { now })
        val t0 = loadTracks(r, songs(7))
        val e0 = r.planFor(t0, prevTrack = null)
        assertTrue(e0.none { it.beat == "trivia" })
        val t1 = loadTracks(r, songs(7))
        val e1 = r.planFor(t1, prevTrack = t0.last())
        assertEquals(listOf(4), e1.filter { it.beat == "trivia" }.map { it.i })
        assertEquals(listOf(4), e1.filter { it.kind == "break" }.map { it.i })
        val t2 = loadTracks(r, songs(7))
        val e2 = r.planFor(t2, prevTrack = t1.last())
        assertTrue(e2.none { it.beat == "trivia" })
        assertTrue(e2.any { it.i == 4 && it.kind == "break" })
    }

    @Test
    fun plan_trivia_skip_does_not_consume_latch() = runTest {
        val client = RoutedLlmClient(routes = listOf("משהו טוב לדרך" to "SKIP", "פינת טריוו" to "[]"))
        val r = newRenderer(client = client, talkChance = 0.0, maxSilence = 4)
        val t0 = loadTracks(r, songs(7))
        r.planFor(t0, prevTrack = null)
        val t1 = loadTracks(r, songs(7))
        val e1 = r.planFor(t1, prevTrack = t0.last())
        assertTrue(e1.none { it.beat == "trivia" })
        assertTrue(e1.any { it.i == 4 && it.kind == "break" })
        // block 0 cannot attempt trivia (no prevTrack); blocks 1 AND 2 both
        // attempt it because the [] skip did NOT consume the latch.
        val t2 = loadTracks(r, songs(7))
        val e2 = r.planFor(t2, prevTrack = t1.last())
        assertTrue(e2.none { it.beat == "trivia" })
        assertEquals(2, client.prompts.count { it.contains("פינת טריוו") })
    }

    @Test
    fun render_trivia_uses_renderDialogue_when_voiceB_set() = runTest {
        val client = RoutedLlmClient(routes = listOf("משהו טוב לדרך" to "SKIP", "פינת טריוו" to triviaJson))
        val voice = FakeVoice(djSamples)
        val r = newRenderer(client = client, voice = voice, voiceB = "Kore", talkChance = 0.0, maxSilence = 1, nowMs = { 0L })
        val prevSong = Song(title = "PREV", artist = "PA")
        val prevTrack = LoadedTrack(prevSong, fakeAnalyze("/fake/PREV.m4a"), fakeLoad("/fake/PREV.m4a"), "/fake/PREV.m4a")
        val result = r.render(songs(2), index = 1, prevTrack = prevTrack)
        assertEquals(1, voice.dialogues.size)
        assertEquals("Kore", voice.dialogues.first().voiceB)
        assertTrue(result.meta.talk.any { it.beat == "trivia" })
    }

    // ---- fun segments: listening cue (task 4) -------------------------------

    @Test
    fun plan_listening_cue_replaces_song_intro_once_per_window() = runTest {
        // maxSilence 1 -> every boundary forced; on a fresh renderer beatK
        // starts 0 so boundary 1 of block 0 is a "song" beat. block 0 has no
        // prevTrack so the cue is ineligible (it needs a previous song); the
        // SECOND block (prevTrack set) is where the song-beat cue lands.
        val cueText = "שים לב לדרופ"
        val client = RoutedLlmClient(routes = listOf("משהו טוב לדרך" to "SKIP", "שים לב לרגע" to cueText))
        val r = newRenderer(client = client, talkChance = 0.0, maxSilence = 1, banterEvery = 1000, minuteOfHour = { 15 }, nowMs = { 0L })
        val prevSong = Song(title = "PREV", artist = "PA")
        val prevTrack = LoadedTrack(prevSong, fakeAnalyze("/fake/PREV.m4a"), fakeLoad("/fake/PREV.m4a"), "/fake/PREV.m4a")
        val e1 = r.planFor(loadTracks(r, songs(2)), prevTrack = prevTrack)
        val cues = e1.filter { it.beat == "cue" }
        assertEquals(listOf(1), cues.map { it.i })
        assertEquals(cueText, cues.first().text)
        assertEquals(listOf(1), e1.filter { it.kind == "break" }.map { it.i })
    }

    @Test
    fun plan_listening_cue_skip_falls_back_to_normal_intro() = runTest {
        // cue routed to SKIP -> the song-beat boundary keeps the NORMAL song
        // intro (beat "song"), the cue replaced nothing.
        val client = RoutedLlmClient(routes = listOf("משהו טוב לדרך" to "SKIP", "שים לב לרגע" to "SKIP"))
        val r = newRenderer(client = client, talkChance = 0.0, maxSilence = 1, banterEvery = 1000, minuteOfHour = { 15 })
        val prevSong = Song(title = "PREV", artist = "PA")
        val prevTrack = LoadedTrack(prevSong, fakeAnalyze("/fake/PREV.m4a"), fakeLoad("/fake/PREV.m4a"), "/fake/PREV.m4a")
        val e1 = r.planFor(loadTracks(r, songs(2)), prevTrack = prevTrack)
        val b = e1.filter { it.kind == "break" }
        assertEquals(listOf(1), b.map { it.i })
        assertEquals("song", b.first().beat)
        assertTrue(b.none { it.beat == "cue" })
    }

    // ---- fun segments: banter persona/voice rotation (task 4) ---------------

    @Test
    fun plan_banter_rotates_sidekick_persona_across_consecutive_banters() = runTest {
        val client = RoutedLlmClient(routes = listOf("משהו טוב לדרך" to "SKIP", "קטע באנטר" to banterJson))
        val r = newRenderer(client = client, talkChance = 0.0, maxSilence = 1, banterEvery = 1)
        var prev: LoadedTrack? = null
        repeat(3) {
            val t = loadTracks(r, songs(2))
            r.planFor(t, prevTrack = prev)
            prev = t.last()
        }
        val bp = client.prompts.filter { it.contains("קטע באנטר") }
        assertTrue("expected >=3 banter prompts", bp.size >= 3)
        assertTrue("persona 0 used", bp.any { it.contains(DjBrain.SIDEKICK_PERSONAS[0]) })
        assertTrue("persona 1 used", bp.any { it.contains(DjBrain.SIDEKICK_PERSONAS[1]) })
        assertTrue("persona 2 used", bp.any { it.contains(DjBrain.SIDEKICK_PERSONAS[2]) })
    }

    @Test
    fun render_banter_rotates_sidekick_voiceB_when_sidekickVoices_set() = runTest {
        val client = RoutedLlmClient(routes = listOf("משהו טוב לדרך" to "SKIP", "קטע באנטר" to banterJson))
        val voice = FakeVoice(djSamples)
        val r = newRenderer(client = client, voice = voice, voiceB = "Kore", talkChance = 0.0, maxSilence = 1, banterEvery = 1, sidekickVoices = listOf("VA", "VB", "VC"))
        var prev: LoadedTrack? = null
        repeat(3) {
            val t = loadTracks(r, songs(2))
            r.render(songs(2), index = it, prevTrack = prev)
            prev = t.last()
        }
        assertEquals(listOf("VA", "VB", "VC"), voice.dialogues.map { it.voiceB })
    }

    // ---- mood-driven banter/trivia/handover (study finding 8 VERIFICATION) ---
    //
    // The 2026-06-13 generation study (docs/studies/findings-djtext.md item 8)
    // reported banter/trivia/handover firing ONLY in "mix" and never in
    // party/focus/late_night/morning. That was a HARNESS ARTIFACT: the study's
    // CorpusGenerator drives beats off its own `<rareTarget` sample counters
    // (handoverSamples / banterSamples / triviaSamples), processes "mix" FIRST
    // for 50 songs, and fills those counters to the cap (16) entirely during the
    // mix phase before party/focus/etc. are ever reached - the counters are
    // neither per-mood nor reset. The REAL renderer (planFor) has NO mix-only
    // gate: banter is driven purely by the EFFECTIVE mood's banterChance
    // (party 0.4 > morning 0.25 > mix 0.2 > late_night 0.1 > focus 0.05) and the
    // sparse-talk eligibility law; trivia/handover ride the same `eligible`
    // boundary in any mood. These tests prove that directly.

    /** Random whose nextDouble() is ~0.01 - BELOW every mood's banterChance and
     *  talkChance, so a non-forced eligible boundary both qualifies and elects
     *  banter deterministically (no seed bookkeeping). */
    private class LowRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = 0.01
    }

    @Test
    fun plan_banter_fires_in_party_mood_via_banterChance() = runTest {
        // party: talkChance 0.7, banterChance 0.4. LowRandom (~0.01) clears both
        // the eligibility flip (0.01 < 0.7) and the banter flip (0.01 < 0.4) at
        // the FIRST non-forced eligible boundary (since-talk >= 2). This is the
        // NON-forced path - banter is driven purely by the mood's banterChance,
        // not by `mix`, and not by the forced/banterEvery rotation.
        val client = RoutedLlmClient(routes = listOf("משהו טוב לדרך" to "SKIP", "קטע באנטר" to banterJson))
        val r = newRenderer(
            client = client, maxSilence = 100, rng = LowRandom(),
            ctx = DjContext(mood = "party"),
        )
        val events = r.planFor(loadTracks(r, songs(6)), prevTrack = null)
        assertTrue(
            "party must banter on a non-forced boundary per its banterChance",
            events.any { it.beat == "banter" },
        )
    }

    @Test
    fun plan_banter_fires_in_late_night_mood_via_banterChance() = runTest {
        // late_night: talkChance 0.3, banterChance 0.1. LowRandom (~0.01) still
        // clears both (0.01 < 0.3 and 0.01 < 0.1) - banter is reachable in a
        // non-mix, low-chance mood, just rarer in practice.
        val client = RoutedLlmClient(routes = listOf("משהו טוב לדרך" to "SKIP", "קטע באנטר" to banterJson))
        val r = newRenderer(
            client = client, maxSilence = 100, rng = LowRandom(),
            ctx = DjContext(mood = "late_night"),
        )
        val events = r.planFor(loadTracks(r, songs(6)), prevTrack = null)
        assertTrue(
            "late_night must be able to banter per its banterChance",
            events.any { it.beat == "banter" },
        )
    }

    @Test
    fun plan_focus_mood_does_not_banter_on_nonforced_boundaries() = runTest {
        // focus: banterChance 0.05. MidRandom (~0.5) NEVER clears the banter flip
        // (0.5 > 0.05), so banter never fires on a non-forced boundary - the LOW
        // chance is respected (rarely/never), exactly as the mood intends. We set
        // banterEvery huge so the forced rotation never elects one either,
        // proving the chance alone governs the non-forced path.
        val client = RoutedLlmClient(routes = listOf("משהו טוב לדרך" to "SKIP", "קטע באנטר" to banterJson))
        val r = newRenderer(
            client = client, maxSilence = 100, rng = MidRandom(), banterEvery = 100_000,
            ctx = DjContext(mood = "focus"),
        )
        val events = r.planFor(loadTracks(r, songs(8)), prevTrack = null)
        assertTrue(
            "focus must not banter on non-forced boundaries given its tiny banterChance",
            events.none { it.beat == "banter" },
        )
    }

    @Test
    fun plan_party_banters_where_focus_does_not_at_the_same_threshold() = runTest {
        // SAME rng and SAME boundary structure, only the mood differs. A value in
        // (focus 0.05, party 0.4): party banters, focus does not - proving party
        // banters MORE than focus, governed by banterChance, not by `mix`.
        class BetweenRandom : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextDouble(): Double = 0.2
        }
        val routes = listOf("משהו טוב לדרך" to "SKIP", "קטע באנטר" to banterJson)

        val party = newRenderer(
            client = RoutedLlmClient(routes), maxSilence = 100, rng = BetweenRandom(),
            banterEvery = 100_000, ctx = DjContext(mood = "party"),
        )
        val partyEvents = party.planFor(loadTracks(party, songs(6)), prevTrack = null)
        assertTrue("party banters at chance 0.4 > 0.2", partyEvents.any { it.beat == "banter" })

        val focus = newRenderer(
            client = RoutedLlmClient(routes), maxSilence = 100, rng = BetweenRandom(),
            banterEvery = 100_000, ctx = DjContext(mood = "focus"),
        )
        val focusEvents = focus.planFor(loadTracks(focus, songs(6)), prevTrack = null)
        assertTrue("focus does NOT banter at chance 0.05 < 0.2", focusEvents.none { it.beat == "banter" })
    }

    @Test
    fun plan_trivia_fires_in_party_mood_not_just_mix() = runTest {
        // trivia rides an `eligible` boundary in ANY non-somber mood (no mix
        // gate). party here; block 1 has a forced boundary, latch open.
        val client = RoutedLlmClient(
            routes = listOf("משהו טוב לדרך" to "SKIP", "פינת טריוו" to triviaJson, "קטע באנטר" to "[]"),
        )
        val r = newRenderer(
            client = client, talkChance = 0.0, maxSilence = 4, nowMs = { 0L },
            ctx = DjContext(mood = "party"),
        )
        val t0 = loadTracks(r, songs(7))
        r.planFor(t0, prevTrack = null)
        val t1 = loadTracks(r, songs(7))
        val e1 = r.planFor(t1, prevTrack = t0.last())
        assertTrue(
            "trivia must be reachable in party mood (no mix-only gate)",
            e1.any { it.beat == "trivia" },
        )
    }

    @Test
    fun plan_handover_fires_in_late_night_mood_not_just_mix() = runTest {
        // handover replaces the first eligible boundary after a partOfDay change,
        // in ANY mood. Drive late_night across a day-part transition.
        val client = FakeLlmClient()
        var part = "ערב"
        val r = newRenderer(
            client = client, talkChance = 0.0, maxSilence = 4,
            ctxProvider = { DjContext(partOfDay = part, mood = "late_night") },
        )
        val t0 = loadTracks(r, songs(7))
        r.planFor(t0, prevTrack = null)
        part = "לילה"
        val t1 = loadTracks(r, songs(7))
        val e1 = r.planFor(t1, prevTrack = t0.last())
        assertEquals(
            "late_night handover must fire on the transition boundary",
            listOf("handover"), e1.filter { it.kind == "break" }.map { it.beat },
        )
    }

    @Test
    fun plan_somber_survives_the_calendar_gate() = runTest {
        val client = FakeLlmClient()
        val r = newRenderer(
            client = client, talkChance = 0.0, maxSilence = 4,
            ctx = DjContext(calendarNote = "יום הזיכרון", somber = true),
        )
        r.planFor(loadTracks(r, songs(7)), prevTrack = null)
        // opening: gate open -> the named note is in the prompt.
        assertTrue(client.prompts.first().contains("יום הזיכרון"))
        // boundary 4 (gate closed): the NAME is stripped, but the somber tone
        // law is intact - somber is never stripped.
        val boundary = client.prompts.last()
        assertFalse(boundary.contains("יום הזיכרון"))
        assertTrue("somber tone must survive", boundary.contains("יום לאומי כבד ורציני"))
    }
}
