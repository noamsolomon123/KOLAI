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
        override fun render(text: String, style: String?): DJSlot {
            styles.add(style)
            return DJSlot(text = text, audioPath = "/voice/dj.wav", durationS = djSamples.toDouble() / Dsp.SR)
        }
    }

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
     *  the DJ voice path -> a loud djSamples-long array. */
    private fun fakeLoad(path: String): FloatArray =
        if (path.startsWith("/voice/")) FloatArray(djSamples) { 0.5f }
        else FloatArray(songSamples) { 0.3f }

    private fun songs(n: Int): List<Song> =
        (0 until n).map { Song(title = "S$it", artist = "A$it") }

    private fun newRenderer(
        fetcher: AudioFetcher = FakeFetcher(),
        client: LlmClient = FakeLlmClient(),
        voice: VoiceRenderer = FakeVoice(djSamples),
        ctx: DjContext = DjContext(),
        maxSilence: Int = 4,
        talkChance: Double = 0.5,
        banterChance: Double = 0.2,
        banterEvery: Int = 3,
        encoder: BlockEncoder? = null,
        write: Boolean = false,
        rng: Random = Random(7),
        minuteOfHour: () -> Int = { 15 },
        ident: (() -> FloatArray)? = null,
    ): BlockRenderer = BlockRenderer(
        fetcher = fetcher,
        brain = DjBrain(client, persona = "דני"),
        voice = voice,
        ctx = { ctx },
        blocksDir = "/tmp/blocks",
        voiceA = null,
        voiceB = null,
        maxSilence = maxSilence,
        banterEvery = banterEvery,
        talkChance = talkChance,
        banterChance = banterChance,
        rng = rng,
        minuteOfHour = minuteOfHour,
        ident = ident,
        analyzeFn = ::fakeAnalyze,
        loadFn = ::fakeLoad,
        encoder = encoder,
        write = write,
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
    fun render_passes_null_style_when_mood_null() = runTest {
        val voice = FakeVoice(djSamples)
        val r = newRenderer(voice = voice, talkChance = 0.0, ctx = DjContext())
        r.render(songs(3), index = 0, prevTrack = null)
        assertTrue(voice.styles.isNotEmpty())
        voice.styles.forEach { assertNull("style must be null without a mood", it) }
    }
}