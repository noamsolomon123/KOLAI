package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.mix.Dsp
import ai.kolai.mix.StationIdent
import kotlin.random.Random

/**
 * BlockRenderer - assemble one endless-engine "block" of songs + DJ talk with
 * natural, human radio pacing. Ported 1:1 from backend/radioai/block_renderer.py
 * (BEATS, beat_for_break, BlockRenderer._next_topic / _next_beat / _plan /
 * render and its CROSS-BLOCK cadence state).
 *
 * Intentional Android deviations from the Python:
 *  1) Output is AAC: the block path is `block_{index}.m4a` (not .mp3) and the
 *     write goes through the injected [BlockEncoder] (MediaCodec, later task)
 *     instead of mixrenderer.write_mp3 / ffmpeg.
 *  2) BANTER (MVP substitution): the Python `want_banter` branch calls
 *     brain.write_banter + voice.render_banter. Two-host banter is deferred, so
 *     we KEEP the want_banter decision intact (identical cadence / RNG / beat
 *     rotation) but render a normal single-voice break in its place, marked as a
 *     `break` event. See the // TODO post-MVP marker below.
 *  3) SESSION OPENING (research deviation, 2026-06-11, finding 3): block 0
 *     (prevTrack == null) opens with brain.writeOpening - a real radio opening
 *     (greeting by part of day, welcoming the listener, into the first song) -
 *     instead of the Python's cold write_break "song" intro. Event kind stays
 *     "open" and the songsSinceTalk reset is unchanged.
 *  4) HOURLY ANCHORS (research deviation, 2026-06-11, findings 4+5): nextBeat()
 *     consults the wall-clock minute (injectable [minuteOfHour]): near the
 *     round hour (minute 55-59 or 0-5) it prefers "news"; near the half hour
 *     (minute 25-35) it prefers "weather" - WITHOUT advancing beatK, so the
 *     normal beat rotation is undisturbed, and at most once per anchor window
 *     (DjBrain falls back to the song prompt if the ctx data is missing). The
 *     Python has no minute awareness.
 *  5) FRESH CONTEXT (endless-station deviation): the Python builds a DJContext
 *     once per process; here [ctx] is a PROVIDER (`() -> DjContext`) invoked
 *     ONCE at the top of [planFor], so every rendered block sees the CURRENT
 *     time / weather / headlines instead of a startup snapshot going stale
 *     over hours of playback.
 *  6) SESSION IDENT (research deviation, 2026-06-11, finding 9): block 0
 *     (prevTrack == null) PREPENDS the station's signature sonic open - the
 *     code-generated [StationIdent] (a petiach, like classic Israeli shows) -
 *     equal-power-crossfaded ([IDENT_OVERLAP_S]) into song 0. The opening
 *     talkover duck shifts from 0.5 to (identDur - overlap + 0.5) so the DJ
 *     speaks just as the music takes over, classic radio. [ident] is
 *     injectable/nullable for tests. The Python has no ident.
 *
 * `_plan` and `render` are `suspend` because DjBrain.writeBreak is suspend.
 *
 * The DSP is NOT re-ported: trimSilence / duck / equalPowerCrossfade / SR come
 * from :mix Dsp, called exactly where the Python uses mixrenderer.* (mx.*).
 */

/** DJ "beat" rotation. Mirrors block_renderer.BEATS. */
val BEATS: List<String> = listOf("song", "weather", "topic", "news")

/** Python: beat_for_break. */
fun beatForBreak(k: Int): String = BEATS[((k % BEATS.size) + BEATS.size) % BEATS.size]

class BlockRenderer(
    private val fetcher: AudioFetcher,
    private val brain: DjBrain,
    private val voice: VoiceRenderer,
    private val ctx: () -> DjContext = { DjContext() },
    private val blocksDir: String = "cache/blocks",
    private val voiceA: String? = null,
    private val voiceB: String? = null,
    private val duckDb: Float = -15.0f,
    private val segueS: Float = 1.5f,
    // session ident generator (deviation 6); null disables the petiach.
    private val ident: (() -> FloatArray)? = { StationIdent.render() },
    private val maxSilence: Int = 4,
    private val banterEvery: Int = 3,
    private val talkChance: Double = 0.5,
    private val banterChance: Double = 0.2,
    private val rng: Random = Random(7),
    private val minuteOfHour: () -> Int = { java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE) },
    private val analyzeFn: AnalyzeFn,
    private val loadFn: LoadFn,
    private val encoder: BlockEncoder? = null,
    private val write: Boolean = true,
) {
    // CROSS-BLOCK cadence state. Start "since talk" high so block 0 opens with a
    // DJ intro and the first real boundary is eligible to talk. Python uses
    // 10 ** 9; Int.MAX_VALUE is the Kotlin equivalent (it only ever increments
    // after being reset to 0, so saturation never matters in practice). We guard
    // the increment against overflow.
    private var songsSinceTalk: Int = Int.MAX_VALUE
    private var talkCount: Int = 0
    // rotate beats / topics across the whole station, not just one block
    private var beatK: Int = 0
    private var topicK: Int = 0
    // TTS delivery style for the CURRENT block's voice lines: set once per
    // block in [planFor] from the ctx snapshot's mood (null when the mood is
    // null / unknown -> default delivery, existing behavior). Consumed by the
    // voice.render call sites in [render].
    private var blockTtsStyle: String? = null
    // last hourly-anchor beat that fired ("news"/"weather"), cleared once a beat
    // is chosen outside any anchor window -> each anchor fires at most once per
    // window (deviation 4 above).
    private var lastAnchor: String? = null

    // ------------------------------------------------------------------ topics
    /** Python: _next_topic. Round-robin a topic key from [ctx].topicHeadlines. */
    private fun nextTopic(ctx: DjContext): String? {
        val keys = ctx.topicHeadlines.keys.toList()
        if (keys.isEmpty()) return null
        val topic = keys[topicK % keys.size]
        topicK += 1
        return topic
    }

    /**
     * Python: _next_beat, plus the hourly-anchor preference (deviation 4):
     * minute 55-59 / 0-5 -> "news", minute 25-35 -> "weather", each at most
     * once per window and WITHOUT advancing beatK; otherwise normal rotation.
     */
    private fun nextBeat(): String {
        val minute = minuteOfHour()
        val anchor = when {
            minute >= 55 || minute <= 5 -> "news"
            minute in 25..35 -> "weather"
            else -> null
        }
        if (anchor == null) {
            lastAnchor = null
        } else if (anchor != lastAnchor) {
            lastAnchor = anchor
            return anchor // anchored beat: rotation (beatK) is NOT advanced
        }
        val beat = beatForBreak(beatK)
        beatK += 1
        return beat
    }

    // ----------------------------------------------------------------- loading
    /**
     * Load + analyze each song, skipping any that fail (Python render() step 1).
     * Exposed (vs. inlined in render) so cadence tests can build a deterministic
     * track list without re-running the whole render. Mirrors the Python
     * try/except: a fetch/analyze/load failure drops the song.
     */
    fun loadTracks(songs: List<Song>): List<LoadedTrack> {
        val tracks = ArrayList<LoadedTrack>()
        for (song in songs) {
            try {
                val path = fetcher.fetch(song)
                tracks.add(LoadedTrack(song, analyzeFn(path), loadFn(path), path))
            } catch (e: Exception) {
                // Python prints "  [skip] ..."; we silently drop (no stdout in lib).
            }
        }
        return tracks
    }

    // -------------------------------------------------------------------- plan
    /**
     * Python: _plan. Decide the timeline of events for this block. The opening
     * event (if any) precedes the song-0 event; every other talk event precedes
     * the song event for its boundary. This method only DECIDES - it calls
     * brain.writeBreak (cheap LLM) but does NOT touch audio. Public so cadence
     * tests can assert the event sequence directly.
     */
    suspend fun planFor(tracks: List<LoadedTrack>, prevTrack: LoadedTrack?): List<PlanEvent> {
        // Fresh context for THIS block (deviation 5): snapshot the provider ONCE
        // so all of this block's talk shares one coherent time/weather/news view.
        val ctx = ctx()
        // Mood-aware cadence (Android addition): when this block's ctx carries
        // a mood that resolves to a [MoodSpec], ITS talkChance / banterChance /
        // maxSilence govern this block; a null mood keeps the constructor
        // values (the existing defaults). The mood's ttsStyle is remembered for
        // this block's voice.render calls (null when no mood).
        val moodSpec = ctx.mood?.let { Moods.ALL[it] }
        val maxSilence = moodSpec?.maxSilence ?: this.maxSilence
        val talkChance = moodSpec?.talkChance ?: this.talkChance
        val banterChance = moodSpec?.banterChance ?: this.banterChance
        blockTtsStyle = moodSpec?.ttsStyle?.takeIf { it.isNotBlank() }
        val events = ArrayList<PlanEvent>()

        // ---- block opening ------------------------------------------------
        val firstSong = tracks[0].song
        if (prevTrack != null) {
            val prevSong = prevTrack.song
            val text = brain.writeBreak(
                prev = prevSong, nxt = firstSong, beat = "song", ctx = ctx,
                seconds = 8.0, topic = null, allowSkip = false,
            )
            if (!text.isNullOrEmpty()) {
                events.add(PlanEvent(kind = "open", i = 0, text = text))
                songsSinceTalk = 0
            }
        } else {
            // block 0: the session OPENING (deviation 3) - greet by part of
            // day, welcome the listener, flow into the first song. No SKIP.
            val text = brain.writeOpening(nxt = firstSong, ctx = ctx, seconds = 10.0)
            if (text.isNotEmpty()) {
                events.add(PlanEvent(kind = "open", i = 0, text = text))
                songsSinceTalk = 0
            }
        }

        events.add(PlanEvent(kind = "song", i = 0))

        // ---- per-boundary cadence ----------------------------------------
        for (i in 1 until tracks.size) {
            songsSinceTalk = if (songsSinceTalk == Int.MAX_VALUE) Int.MAX_VALUE else songsSinceTalk + 1
            val forced = songsSinceTalk >= maxSilence
            // never talk two boundaries in a row (>= 2 since last talk) unless
            // forced; otherwise it's a coin-flip.
            val eligible = forced || (songsSinceTalk >= 2 && rng.nextDouble() < talkChance)

            var didTalk = false
            if (eligible) {
                val wantBanter = (
                    (forced && talkCount % banterEvery == banterEvery - 1) ||
                        (!forced && rng.nextDouble() < banterChance)
                    )

                val topic = nextTopic(ctx)

                if (wantBanter) {
                    // TODO post-MVP: two-host banter here (brain.writeBanter +
                    // voice.renderBanter). For the MVP we substitute a normal
                    // single-voice break so the cadence/timing is identical.
                    // NOTE: the banter branch does NOT advance beatK (Python uses
                    // a fixed "banter" beat), so we keep beat rotation in lockstep
                    // with the Python by using "song" here without nextBeat().
                    val seconds = if (forced) 16.0 else 12.0
                    val text = brain.writeBreak(
                        prev = tracks[i - 1].song, nxt = tracks[i].song, beat = "song",
                        ctx = ctx, seconds = seconds, topic = topic,
                        allowSkip = !forced,
                    )
                    if (!text.isNullOrEmpty()) {
                        events.add(PlanEvent(kind = "break", i = i, text = text, beat = "song"))
                        didTalk = true
                    }
                } else {
                    val beat = nextBeat()
                    val seconds = if (forced) 18.0 else 9.0
                    val text = brain.writeBreak(
                        prev = tracks[i - 1].song, nxt = tracks[i].song, beat = beat,
                        ctx = ctx, seconds = seconds, topic = topic,
                        allowSkip = !forced,
                    )
                    if (!text.isNullOrEmpty()) {
                        events.add(PlanEvent(kind = "break", i = i, text = text, beat = beat))
                        didTalk = true
                    }
                }
            }

            if (didTalk) {
                songsSinceTalk = 0
                talkCount += 1
            }

            events.add(PlanEvent(kind = "song", i = i))
        }

        return events
    }

    // ----------------------------------------------------------------- render
    /** Python: render. */
    suspend fun render(songs: List<Song>, index: Int, prevTrack: LoadedTrack? = null): BlockResult {
        // 1. load + analyze each song, skipping any that fail
        val tracks = loadTracks(songs)
        if (tracks.isEmpty()) {
            throw IllegalArgumentException("No playable tracks in block")
        }

        // 2. plan the cadence
        val events = planFor(tracks, prevTrack)

        // 3. assemble audio + collect timings
        var timeline = tracks[0].audio
        val songEvents = ArrayList<SongEvent>()
        val talk = ArrayList<TalkEntry>()

        // session ident (deviation 6): block 0 opens with the station petiach,
        // crossfaded into song 0. The opening DJ duck shifts right by
        // (identDur - overlap) so the DJ speaks just as the music takes over.
        var openingDuckStartS = 0.5
        val identFn = ident
        if (prevTrack == null && identFn != null) {
            val identAudio = identFn()
            val identDur = identAudio.size.toDouble() / Dsp.SR
            timeline = Dsp.equalPowerCrossfade(identAudio, timeline, overlapS = IDENT_OVERLAP_S)
            openingDuckStartS = identDur - IDENT_OVERLAP_S + 0.5
        }

        // opening talkover (if any) - DJ over the intro of song 0
        val opening = events.firstOrNull { it.kind == "open" }
        if (opening != null) {
            val slot = voice.render(opening.text!!, style = blockTtsStyle)
            val djAudio = Dsp.trimSilence(loadFn(slot.audioPath))
            val djDur = djAudio.size.toDouble() / Dsp.SR
            val startS = openingDuckStartS
            timeline = Dsp.duck(timeline, djAudio, startS = startS.toFloat(), attenuationDb = duckDb)
            talk.add(
                TalkEntry(
                    beat = "song", text = slot.text,
                    startS = round2(startS), endS = round2(startS + djDur),
                )
            )
        }

        // Song 0's segment stays at startS = 0.0 even when the ident is
        // prepended: segments drive the UI's now-playing, and the ident is part
        // of the STATION opening, not a track - the UI should show the first
        // song from second zero (a separate "ident" segment would complicate
        // the UI for a ~2 s sting).
        songEvents.add(
            SongEvent(title = tracks[0].song.title, artist = tracks[0].song.artist, startS = 0.0)
        )

        // walk boundary events for songs 1..n-1
        for (i in 1 until tracks.size) {
            val track = tracks[i]
            val song = track.song
            val audio = track.audio
            val boundary = round2(timeline.size.toDouble() / Dsp.SR)

            val talkEvent = events.firstOrNull { it.i == i && it.kind == "break" }

            if (talkEvent != null) {
                // BANTER substitution: a single-voice break (see planFor TODO).
                val slot = voice.render(talkEvent.text!!, style = blockTtsStyle)
                val djAudio = Dsp.trimSilence(loadFn(slot.audioPath))
                val djDur = djAudio.size.toDouble() / Dsp.SR
                // duck the DJ over the TAIL of the current timeline (talkover),
                // then crossfade into the next song.
                val duckStart = maxOf(
                    0.0, timeline.size.toDouble() / Dsp.SR - djDur - segueS - 0.3
                )
                timeline = Dsp.duck(timeline, djAudio, startS = duckStart.toFloat(), attenuationDb = duckDb)
                talk.add(
                    TalkEntry(
                        beat = talkEvent.beat ?: "song", text = slot.text,
                        startS = round2(duckStart), endS = round2(duckStart + djDur),
                    )
                )
            }

            // musical (or post-talkover) segue into the next song
            timeline = Dsp.equalPowerCrossfade(timeline, audio, overlapS = segueS)
            songEvents.add(
                SongEvent(title = song.title, artist = song.artist, startS = boundary)
            )
        }

        // 4. write the block (.m4a / AAC) + build meta
        val totalS = round2(timeline.size.toDouble() / Dsp.SR)
        val path = "$blocksDir/block_$index.m4a"
        if (write && encoder != null) {
            encoder.encode(path, timeline)
        }

        val segments = buildSegments(songEvents, totalS)
        val meta = BlockMeta(index = index, durationS = totalS, segments = segments, talk = talk)

        return BlockResult(audio = timeline, meta = meta, path = path, lastTrack = tracks.last())
    }

    companion object {
        /** Equal-power overlap (s) between the session ident and song 0. */
        const val IDENT_OVERLAP_S: Float = 0.4f
    }
}