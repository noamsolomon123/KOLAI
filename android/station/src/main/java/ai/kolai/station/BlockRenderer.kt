package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.mix.Dsp
import kotlin.random.Random

/**
 * BlockRenderer - assemble one endless-engine "block" of songs + DJ talk with
 * natural, human radio pacing. Ported 1:1 from backend/radioai/block_renderer.py
 * (BEATS, beat_for_break, BlockRenderer._next_topic / _next_beat / _plan /
 * render and its CROSS-BLOCK cadence state).
 *
 * Two intentional Android deviations from the Python:
 *  1) Output is AAC: the block path is `block_{index}.m4a` (not .mp3) and the
 *     write goes through the injected [BlockEncoder] (MediaCodec, later task)
 *     instead of mixrenderer.write_mp3 / ffmpeg.
 *  2) BANTER (MVP substitution): the Python `want_banter` branch calls
 *     brain.write_banter + voice.render_banter. Two-host banter is deferred, so
 *     we KEEP the want_banter decision intact (identical cadence / RNG / beat
 *     rotation) but render a normal single-voice break in its place, marked as a
 *     `break` event. See the // TODO post-MVP marker below.
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
    private val ctx: DjContext,
    private val blocksDir: String = "cache/blocks",
    private val voiceA: String? = null,
    private val voiceB: String? = null,
    private val duckDb: Float = -15.0f,
    private val segueS: Float = 1.5f,
    private val maxSilence: Int = 4,
    private val banterEvery: Int = 3,
    private val talkChance: Double = 0.5,
    private val banterChance: Double = 0.2,
    private val rng: Random = Random(7),
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

    // ------------------------------------------------------------------ topics
    /** Python: _next_topic. Round-robin a topic key from ctx.topicHeadlines. */
    private fun nextTopic(): String? {
        val keys = ctx.topicHeadlines.keys.toList()
        if (keys.isEmpty()) return null
        val topic = keys[topicK % keys.size]
        topicK += 1
        return topic
    }

    /** Python: _next_beat. */
    private fun nextBeat(): String {
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
            // block 0: brief cold intro (name the very first song). Keep short.
            val text = brain.writeBreak(
                prev = firstSong, nxt = firstSong, beat = "song", ctx = ctx,
                seconds = 6.0, topic = null, allowSkip = false,
            )
            if (!text.isNullOrEmpty()) {
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

                val topic = nextTopic()

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

        // opening talkover (if any) - DJ over the intro of song 0
        val opening = events.firstOrNull { it.kind == "open" }
        if (opening != null) {
            val slot = voice.render(opening.text!!)
            val djAudio = Dsp.trimSilence(loadFn(slot.audioPath))
            val djDur = djAudio.size.toDouble() / Dsp.SR
            val startS = 0.5
            timeline = Dsp.duck(timeline, djAudio, startS = startS.toFloat(), attenuationDb = duckDb)
            talk.add(
                TalkEntry(
                    beat = "song", text = slot.text,
                    startS = round2(startS), endS = round2(startS + djDur),
                )
            )
        }

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
                val slot = voice.render(talkEvent.text!!)
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
}