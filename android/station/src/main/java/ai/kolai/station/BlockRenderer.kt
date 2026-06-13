package ai.kolai.station

import ai.kolai.analyze.VocalOnset
import ai.kolai.analyze.refineOutroStart
import ai.kolai.core.Song
import ai.kolai.mix.Dsp
import ai.kolai.mix.StationIdent
import ai.kolai.mix.voiceBroadcastChain
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
 *  2) BANTER (feature 11, wave 3): the `want_banter` DECISION is kept intact
 *     (identical cadence / RNG / beat rotation), but the branch now asks
 *     brain.writeBanter for a real two-host script. Non-empty turns render
 *     through [VoiceRenderer.renderDialogue] when [voiceB] is configured (the
 *     :app adapter overrides it with multi-speaker TTS in wave 4; the seam's
 *     DEFAULT body stays single-voice via [joinDialogue]); an empty script
 *     falls back to the original single-voice substitute break.
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
 *  7) BROADCAST-DSP WIRING (2026-06-12, wave 2 - the Python has none of it):
 *     a) per-song trailing-silence trim at load + ANALYSIS-DRIVEN outro cuts
 *        and beat-snapped musical segues (feature 13, see the render loop);
 *     b) VOCAL-AWARE opening budget, tier 1 (feature 15, see
 *        [openingSeconds]);
 *     c) the broadcast voice chain + eased duck release (feature 16, see
 *        [prepareVoice] / [VOICE_DUCK_RELEASE_S]);
 *     d) PARALLEL song loads capped at [LOAD_CONCURRENCY] (feature 14b, see
 *        [loadTracks]).
 *  8) SHOW FORMATS (2026-06-12, wave 3 - none in the Python): planFor weaves
 *     the new DjBrain show formats in, all latched on the injectable [nowMs]
 *     clock (in-memory, per-renderer): the Friday RECAP opening (feature 12,
 *     block 0 when ctx.recapBrief is set), the once-per-transition DAY-PART
 *     handover (feature 8) which REPLACES an eligible break (never adds one),
 *     the TASTE WINK (feature 9, wink-enabled intros >= [TASTE_WINK_SPACING_MS]
 *     apart), the branded good-thing micro-segment (feature 10,
 *     >= [GOOD_THING_SPACING_MS] apart, never somber, never block 0; a SKIP
 *     does not consume the latch) and the CALENDAR mention gate (feature 7:
 *     when a talk went on air with ctx.calendarNote less than
 *     [CALENDAR_MENTION_SPACING_MS] ago, the brain gets a ctx copy with
 *     calendarNote = null - somber is NEVER stripped, it is a tone law).
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
    // injectable wall clock (ms) for the wave-3 show-format latches (deviation
    // 8). Tests pin/advance it; prod keeps the real clock.
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val analyzeFn: AnalyzeFn,
    private val loadFn: LoadFn,
    private val encoder: BlockEncoder? = null,
    private val write: Boolean = true,
    // ---- audio-quality knobs (defaults documented on the companion consts) --
    private val musicSegueS: Float = MUSIC_SEGUE_S,
    private val songTargetRms: Float = SONG_TARGET_RMS,
    private val voiceTargetRms: Float = VOICE_TARGET_RMS,
    // dispatcher for the parallel song loads (feature 14b); injectable so tests
    // can pin it, defaults to IO because fetch/decode are blocking I/O.
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // PER-MOOD VOICE resolver (2026-06-13): mood key -> the prebuilt Gemini
    // voice name for the main host. Default = the Moods spec voice; the :app
    // wiring overrides it with DevConfig.moodVoices so a per-mood voice picked
    // in kolai_dev.properties wins over the Moods default. Resolved for the
    // EFFECTIVE mood (mix included) so the host ALWAYS has a real voice.
    private val moodVoice: (String?) -> String = { Moods.spec(it).voiceName },
    // SIDEKICK voices (2026-06-13): rotated alongside the banter sidekick
    // PERSONA so successive two-host bits use a distinct co-host voice. Index
    // wraps modulo the list; empty -> always the single constructor [voiceB].
    private val sidekickVoices: List<String> = emptyList(),
    // VOCAL-ONSET seam (task 3, 2026-06-13): (conditioned mono PCM, sr) -> the
    // safe instrumental window (s) at the song's start. Defaults to the real
    // :analyze VocalOnset; injectable so tests pin a known window (e.g. ~0 to
    // assert the opener is suppressed) without crafting bespoke PCM.
    private val safeIntroFn: (FloatArray, Int) -> Double = { audio, sr ->
        VocalOnset.safeIntroWindowS(audio, sr)
    },
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
    // PER-MOOD VOICE for the CURRENT block (2026-06-13): the main-host Gemini
    // voice, set once per block in [planFor] from the EFFECTIVE mood (mix
    // included via [moodVoice]) so the DJ always has a real voice. Consumed by
    // the voice.render / renderDialogue call sites in [render].
    private var blockVoice: String? = null
    // BANTER sidekick rotation (2026-06-13): a per-renderer counter advanced
    // each time a banter actually airs, so successive banters rotate across
    // [DjBrain.sidekickPersonaCount] personas (and [sidekickVoices] if set).
    private var sidekickRot: Int = 0
    // last hourly-anchor beat that fired ("news"/"weather"), cleared once a beat
    // is chosen outside any anchor window -> each anchor fires at most once per
    // window (deviation 4 above).
    private var lastAnchor: String? = null

    // ---- show-format latches (deviation 8; [nowMs] timestamps, null = never)
    // partOfDay of the previous block's ctx snapshot: a change arms ONE
    // handover for the next block (feature 8). Updated whether or not a talk
    // fires - a silent transition is fine and respects sparse talk.
    private var lastPartOfDay: String? = null
    // last wink-ENABLED intro (feature 9). Recorded when the wink is allowed,
    // not on render success - simpler, and an unused allowance is a fine cost.
    private var lastWinkMs: Long? = null
    // last good-thing segment that actually aired (feature 10); SKIP ("") does
    // NOT consume the latch.
    private var lastGoodThingMs: Long? = null
    // last talk that went on air WITH ctx.calendarNote present (feature 7).
    private var lastCalendarMentionMs: Long? = null
    // banter turns planned for boundary i of the CURRENT block (feature 11):
    // planFor fills it, render consumes it; cleared at the top of planFor.
    private val pendingBanter = HashMap<Int, List<Pair<String, String>>>()
    // FUN-SEGMENT two-host turns planned for boundary i (2026-06-13): trivia
    // (beat "trivia"). Same render-time dialogue handling as banter; cleared
    // at the top of planFor.
    private val pendingDialogue = HashMap<Int, List<Pair<String, String>>>()
    // The sidekick voiceB chosen for the two-host bit at boundary i (banter
    // persona rotation, 2026-06-13): when [sidekickVoices] is set, banter
    // rotates the co-host voice alongside the persona; render reads it here.
    // Absent -> render uses the single constructor [voiceB].
    private val pendingSidekickVoice = HashMap<Int, String>()
    // last trivia bit that actually aired (2026-06-13); rare latch. A SKIP
    // (empty turns) does NOT consume it.
    private var lastTriviaMs: Long? = null
    // last listening-cue that actually aired (2026-06-13); rare latch. A SKIP
    // ("" cue) does NOT consume it.
    private var lastCueMs: Long? = null
    // PER-SONG SAFE-INTRO WINDOW (s), keyed by the loaded track's path (task 3,
    // 2026-06-13): the [VocalOnset.safeIntroWindowS] of each decoded song,
    // computed once in [loadTracks] (the only place the decoded PCM lives).
    // [planFor]'s opener (block 0 + back-announce) and [render]'s mid-block
    // intro talk-over both read it so the DJ never talks over a singer. Keyed
    // by path because paths are unique within a setlist and LoadedTrack is a
    // shared type we do not extend.
    // ConcurrentHashMap (not HashMap): [loadTracks] populates this from
    // parallel [Dispatchers.IO] coroutines (capped at [LOAD_CONCURRENCY]), so
    // concurrent put() must be safe - a plain HashMap can drop/lose an entry
    // under a colliding put, which would make a missing key read back as the
    // POSITIVE_INFINITY default and wrongly arm the opener (data race).
    private val safeIntroByPath = ConcurrentHashMap<String, Double>()

    /** The decoded song's safe instrumental window at its start (s); +inf when
     *  unknown so callers treat "no info" as not-a-constraint (the introEndS
     *  budget still governs). */
    private fun safeIntroFor(track: LoadedTrack): Double =
        safeIntroByPath[track.path] ?: Double.POSITIVE_INFINITY

    private fun latchOpen(lastMs: Long?, spacingMs: Long): Boolean =
        lastMs == null || nowMs() - lastMs >= spacingMs

    /**
     * Feature 7: the ctx actually handed to DjBrain. While the calendar gate
     * is CLOSED (a talk mentioned the note less than
     * [CALENDAR_MENTION_SPACING_MS] ago) the calendarNote is stripped so the
     * brain cannot repeat it. [DjContext.somber] is NEVER stripped - it is a
     * tone law, not a mention.
     */
    private fun gatedCtx(ctx: DjContext): DjContext =
        if (ctx.calendarNote != null && !latchOpen(lastCalendarMentionMs, CALENDAR_MENTION_SPACING_MS)) {
            ctx.copy(calendarNote = null)
        } else {
            ctx
        }

    /**
     * A talk event actually made it into the plan: when its prompt ctx still
     * carried the calendar note, the mention gate closes for
     * [CALENDAR_MENTION_SPACING_MS].
     */
    private fun recordTalk(usedCtx: DjContext) {
        if (usedCtx.calendarNote != null) lastCalendarMentionMs = nowMs()
    }

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
     *
     * PARALLEL LOADS (feature 14b): songs are fetched/decoded concurrently on
     * [loadDispatcher], capped at [LOAD_CONCURRENCY] in-flight loads (network +
     * decode dominate block latency; the cap keeps the device responsive).
     * `map { async } + awaitAll` preserves the setlist ORDER exactly, and the
     * Python skip-on-failure semantics are intact: a failed song resolves to
     * null and is filtered out while the others proceed. Cancellation is never
     * swallowed (rethrown before the generic catch).
     *
     * Per-song audio conditioning, in order:
     *  1. normalizeLoudness - YouTube masters differ by many dB; radio is
     *     loudness-consistent.
     *  2. trimTrailingSilence (feature 13a) - YouTube-muxed files often carry a
     *     long silent music-video tail; drop it (keeping 0.5 s of breath) so
     *     crossfades never blend into dead air.
     *  3. microFadeEdges - decoded-AAC boundaries never click (runs LAST so the
     *     trimmed edge is what gets faded to exactly zero).
     */
    suspend fun loadTracks(songs: List<Song>): List<LoadedTrack> = coroutineScope {
        // VOCAL-ONSET cache is per-load (task 3): clear so a path reused across
        // blocks never serves a stale window.
        safeIntroByPath.clear()
        val gate = Semaphore(LOAD_CONCURRENCY)
        songs.map { song ->
            async(loadDispatcher) {
                gate.withPermit {
                    try {
                        val path = fetcher.fetch(song)
                        val audio = Dsp.microFadeEdges(
                            Dsp.trimTrailingSilence(
                                Dsp.normalizeLoudness(loadFn(path), targetRms = songTargetRms, maxGain = LOUDNESS_MAX_GAIN),
                                sr = Dsp.SR,
                            )
                        )
                        // VOCAL-ONSET (task 3, 2026-06-13): measure the safe
                        // instrumental window at this song's start on the
                        // CONDITIONED PCM (the audio the listener hears), so the
                        // opener / intro talk-over never lands over a singer.
                        // Computed here because loadTracks is the only place the
                        // decoded PCM exists. SAFETY-biased inside VocalOnset.
                        safeIntroByPath[path] = safeIntroFn(audio, Dsp.SR)
                        LoadedTrack(song, analyzeFn(path), audio, path)
                    } catch (e: CancellationException) {
                        throw e // cooperative cancellation must propagate
                    } catch (e: Exception) {
                        // Python prints "  [skip] ..."; we silently drop (no stdout in lib).
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }
    // -------------------------------------------------------------------- plan
    /**
     * Python: _plan. Decide the timeline of events for this block. The opening
     * event (if any) precedes the song-0 event; every other talk event precedes
     * the song event for its boundary. This method only DECIDES - it calls
     * brain.writeBreak (cheap LLM) but does NOT touch audio. Public so cadence
     * tests can assert the event sequence directly.
     *
     * [openingStartS] is where the opening talk-over duck will start on the
     * assembled timeline (0.5 s; shifted right by the session ident on block 0)
     * - [render] passes the real value so the vocal-aware opening budget
     * ([openingSeconds], feature 15) measures the intro bed that is actually
     * left after the duck start.
     */
    suspend fun planFor(
        tracks: List<LoadedTrack>,
        prevTrack: LoadedTrack?,
        openingStartS: Double = 0.5,
    ): List<PlanEvent> {
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
        // VOICE + DELIVERY from the EFFECTIVE mood (task 1 + 2, 2026-06-13).
        // CRITICAL FIX: a null ctx.mood means "mix" (LiveDjContext maps the
        // default mood -> null), and the OLD code then left BOTH style and
        // voice null, so the default/daytime DJ had no delivery and no voice
        // (the "mix applies no style/voice" bug). We now resolve voice + style
        // from the effective mood, treating null AS "mix" for voice/style
        // ONLY, so the DJ always has a real (calm Algieba) voice + calm
        // delivery. The CADENCE path above is deliberately UNCHANGED: a null
        // mood still uses the constructor cadence defaults (which are
        // numerically identical to "mix": talkChance 0.5 / banterChance 0.2 /
        // maxSilence 4), and the DjBrain moodLine/cadence path stays exactly as
        // before for null mood (mix djLine == "").
        val effectiveMood = ctx.mood ?: Moods.DEFAULT
        blockVoice = moodVoice(effectiveMood)
        blockTtsStyle = Moods.spec(effectiveMood).ttsStyle.takeIf { it.isNotBlank() }
        val events = ArrayList<PlanEvent>()
        pendingBanter.clear()
        pendingDialogue.clear()
        pendingSidekickVoice.clear()

        // DAY-PART HANDOVER arming (feature 8): a partOfDay change vs the
        // previous block arms ONE handover for this block first
        // eligible-talk boundary. lastPartOfDay updates regardless of whether
        // a talk fires - a silent transition is consumed too.
        var handoverPending = lastPartOfDay != null && ctx.partOfDay != null &&
            ctx.partOfDay != lastPartOfDay
        if (ctx.partOfDay != null) lastPartOfDay = ctx.partOfDay

        // ---- block opening ------------------------------------------------
        // VOCAL-ONSET GUARD (task 3, 2026-06-13): the opener is placed OVER
        // song 0's intro (see [render]). If song 0 starts singing (almost)
        // immediately - safe instrumental window < [SAFE_INTRO_MIN_S] - the DJ
        // must NOT talk over its opening. There is no previous-song outro
        // inside THIS block to move the opener onto, so we SKIP the opener; the
        // first eligible boundary (which rides the OUTGOING song's outro, the
        // safe placement) still carries the DJ. recapBrief openings keep their
        // own budget but are guarded too. We never block the opener purely on
        // the introEndS budget - only the true vocal onset.
        val openerSafe = safeIntroFor(tracks[0]) >= SAFE_INTRO_MIN_S
        val firstSong = tracks[0].song
        if (prevTrack != null) {
            val prevSong = prevTrack.song
            val openCtx = gatedCtx(ctx)
            val text = if (openerSafe) brain.writeBreak(
                prev = prevSong, nxt = firstSong, beat = "song", ctx = openCtx,
                seconds = openingSeconds(tracks[0], openingStartS, defaultS = 8.0),
                topic = null, allowSkip = false,
            ) else null
            if (!text.isNullOrEmpty()) {
                events.add(PlanEvent(kind = "open", i = 0, text = text))
                recordTalk(openCtx)
                songsSinceTalk = 0
            }
        } else {
            // block 0: the session OPENING (deviation 3) - greet by part of
            // day, welcome the listener, flow into the first song. No SKIP.
            // RECAP OPENING (feature 12): when the snapshot carries the Friday
            // recap brief, the special recap opening speaks instead, with its
            // OWN longer budget (20 s) - the recap needs room for real
            // numbers, so the vocal-aware intro clamp (feature 15) is
            // deliberately not applied; the duck/timing math in [render]
            // already follows the actual clip duration.
            val openCtx = gatedCtx(ctx)
            val text = when {
                !openerSafe -> ""
                openCtx.recapBrief != null ->
                    brain.writeRecapOpening(nxt = firstSong, ctx = openCtx, seconds = 20.0)
                else -> brain.writeOpening(
                    nxt = firstSong, ctx = openCtx,
                    seconds = openingSeconds(tracks[0], openingStartS, defaultS = 10.0),
                )
            }
            if (text.isNotEmpty()) {
                events.add(PlanEvent(kind = "open", i = 0, text = text))
                recordTalk(openCtx)
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
            // 1) DAY-PART HANDOVER (feature 8): REPLACES this boundary break
            // (never adds one). One shot per transition - consumed even when
            // the line comes back blank.
            if (eligible && handoverPending) {
                handoverPending = false
                val hCtx = gatedCtx(ctx)
                val text = brain.writeHandover(ctx = hCtx, nxt = tracks[i].song)
                if (text.isNotEmpty()) {
                    events.add(PlanEvent(kind = "break", i = i, text = text, beat = "handover"))
                    recordTalk(hCtx)
                    didTalk = true
                }
            }
            // 2) GOOD THING (feature 10): the branded micro-segment, at most
            // once per [GOOD_THING_SPACING_MS], never on somber days and never
            // in the session first block. An empty result (the brain SKIP)
            // does NOT consume the latch and falls through to the normal beat
            // logic for this boundary.
            if (eligible && !didTalk && prevTrack != null && !ctx.somber &&
                latchOpen(lastGoodThingMs, GOOD_THING_SPACING_MS)
            ) {
                val gCtx = gatedCtx(ctx)
                val text = brain.writeGoodThing(ctx = gCtx, nxt = tracks[i].song)
                if (text.isNotEmpty()) {
                    events.add(PlanEvent(kind = "break", i = i, text = text, beat = "good_thing"))
                    recordTalk(gCtx)
                    lastGoodThingMs = nowMs()
                    didTalk = true
                }
            }
            // 3) TRIVIA (2026-06-13, fun segment): a rare two-host quiz bit
            // that REPLACES an eligible boundary break (never adds one), at
            // most once per [TRIVIA_SPACING_MS], never on somber days and never
            // in the session first block. Non-empty turns render through the
            // dialogue seam (voiceB) when configured, else joined single-voice;
            // an empty result (SKIP) does NOT consume the latch and falls
            // through to the normal beat logic. Subordinate to the sparse-talk
            // law: it only fires where a break was already eligible.
            if (eligible && !didTalk && prevTrack != null && !ctx.somber &&
                latchOpen(lastTriviaMs, TRIVIA_SPACING_MS)
            ) {
                val tCtx = gatedCtx(ctx)
                val turns = brain.writeTrivia(nxt = tracks[i].song, ctx = tCtx)
                if (turns.isNotEmpty()) {
                    pendingDialogue[i] = turns
                    events.add(PlanEvent(kind = "break", i = i, text = joinDialogue(turns), beat = "trivia"))
                    recordTalk(tCtx)
                    lastTriviaMs = nowMs()
                    didTalk = true
                }
            }
            if (eligible && !didTalk) {
                val wantBanter = (
                    (forced && talkCount % banterEvery == banterEvery - 1) ||
                        (!forced && rng.nextDouble() < banterChance)
                    )

                val topic = nextTopic(ctx)

                if (wantBanter) {
                    // TWO-HOST BANTER (feature 11): real banter turns. The
                    // branch still does NOT advance beatK (Python uses a fixed
                    // "banter" beat), keeping beat rotation in lockstep. An
                    // empty script (model skipped / somber / unparseable JSON)
                    // falls back to the pre-existing single-voice substitute
                    // break below.
                    val bCtx = gatedCtx(ctx)
                    // PERSONA ROTATION (2026-06-13): rotate the sidekick across
                    // [DjBrain.sidekickPersonaCount] so successive banters use a
                    // different co-host; the counter advances only when a banter
                    // actually airs (below). The persona TEXT rotation is the
                    // priority; the voiceB rotation ([sidekickVoices]) is best-
                    // effort (empty list -> the single constructor voiceB).
                    val sidekickIndex = sidekickRot
                    val turns = brain.writeBanter(
                        prev = tracks[i - 1].song, nxt = tracks[i].song,
                        ctx = bCtx, seconds = 14.0, sidekickIndex = sidekickIndex,
                    )
                    if (turns.isNotEmpty()) {
                        pendingBanter[i] = turns
                        if (sidekickVoices.isNotEmpty()) {
                            pendingSidekickVoice[i] =
                                sidekickVoices[Math.floorMod(sidekickIndex, sidekickVoices.size)]
                        }
                        sidekickRot += 1
                        events.add(PlanEvent(kind = "break", i = i, text = joinDialogue(turns), beat = "banter"))
                        recordTalk(bCtx)
                        didTalk = true
                    } else {
                        val seconds = if (forced) 16.0 else 12.0
                        val fCtx = gatedCtx(ctx)
                        val text = brain.writeBreak(
                            prev = tracks[i - 1].song, nxt = tracks[i].song, beat = "song",
                            ctx = fCtx, seconds = seconds, topic = topic,
                            allowSkip = !forced,
                        )
                        if (!text.isNullOrEmpty()) {
                            events.add(PlanEvent(kind = "break", i = i, text = text, beat = "song"))
                            recordTalk(fCtx)
                            didTalk = true
                        }
                    }
                } else {
                    val beat = nextBeat()
                    val seconds = if (forced) 18.0 else 9.0
                    val bCtx = gatedCtx(ctx)
                    // LISTENING CUE (2026-06-13, fun segment): occasionally a
                    // song-beat intro becomes a single warm "listen for this
                    // moment in the song" line - rare ([LISTENING_CUE_SPACING_MS]),
                    // not somber, not block 0, song-beat only. SKIP-gated in the
                    // brain ("" = nothing real to point at): a "" REPLACES
                    // nothing and falls through to the normal intro/wink/break
                    // below, and does NOT consume the latch. When it fires it
                    // REPLACES the normal song intro (does not add a break).
                    val cue = if (beat == "song" && prevTrack != null && !ctx.somber &&
                        latchOpen(lastCueMs, LISTENING_CUE_SPACING_MS)
                    ) {
                        brain.writeListeningCue(nxt = tracks[i].song, ctx = bCtx)
                    } else {
                        ""
                    }
                    if (cue.isNotEmpty()) {
                        lastCueMs = nowMs()
                        events.add(PlanEvent(kind = "break", i = i, text = cue, beat = "cue"))
                        recordTalk(bCtx)
                        didTalk = true
                    } else {
                        // TASTE WINK (feature 9): on a song-beat break, when the
                        // next song is a taste pick and no wink-enabled intro
                        // happened for [TASTE_WINK_SPACING_MS], use the intro path
                        // (writeIntro - no SKIP, a favorite is worth a word) with
                        // the wink allowed; the brain still gates
                        // rank < TASTE_WINK_MAX_RANK. The latch is recorded on
                        // ENABLING, not on render/rank success - simpler, fine.
                        val wink = beat == "song" && tracks[i].song.tasteRank != null &&
                            latchOpen(lastWinkMs, TASTE_WINK_SPACING_MS)
                        val text = if (wink) {
                            lastWinkMs = nowMs()
                            brain.writeIntro(
                                prev = tracks[i - 1].song, nxt = tracks[i].song,
                                seconds = seconds, ctx = bCtx, allowTasteWink = true,
                            )
                        } else {
                            brain.writeBreak(
                                prev = tracks[i - 1].song, nxt = tracks[i].song, beat = beat,
                                ctx = bCtx, seconds = seconds, topic = topic,
                                allowSkip = !forced,
                            )
                        }
                        if (!text.isNullOrEmpty()) {
                            events.add(PlanEvent(kind = "break", i = i, text = text, beat = beat))
                            recordTalk(bCtx)
                            didTalk = true
                        }
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

    /**
     * VOCAL-AWARE TALK-OVER, tier 1 (feature 15). The opening is the ONLY talk
     * placed over a song's INTRO; boundary breaks already ride the OUTGOING
     * song's outro (which IS the fallback placement). The rule:
     *
     *  - When song 0's analysis.introEndS is sane - finite, longer than
     *    [OPENING_INTRO_MIN_S] and inside the (post-trim) track - AND it
     *    leaves at least [OPENING_MIN_USABLE_S] of usable instrumental bed
     *    after the duck start (minus [OPENING_TAIL_GUARD_S] so the DJ finishes
     *    BEFORE the vocal enters), the `seconds` budget passed to the brain is
     *    clamped to that usable bed: seconds = min(default, introEnd - startS
     *    - 0.5).
     *  - When the intro is too short (< [OPENING_MIN_USABLE_S] usable) a
     *    talk-over simply cannot fit inside the intro: the intro-fitting is
     *    SKIPPED and the pre-existing default budget/placement is kept
     *    unchanged (an opening has no outgoing audio inside this block to fall
     *    back onto; the boundary breaks are already outro-placed).
     *
     * VOCAL-ONSET (task 3, 2026-06-13): the usable intro is the MIN of the
     * Essentia introEndS-based value AND the [VocalOnset.safeIntroWindowS] of
     * this song (carried in [safeIntroByPath]). The opener is only EMITTED at
     * all when the safe window >= [SAFE_INTRO_MIN_S] (gated in [planFor]), so
     * here we just additionally shorten the budget so the DJ finishes before
     * the (possibly earlier-than-introEnd) vocal entry.
     */
    private fun openingSeconds(track: LoadedTrack, startS: Double, defaultS: Double): Double {
        val introEnd = track.analysis.introEndS
        val durS = track.audio.size.toDouble() / Dsp.SR
        // The vocal onset bounds how much instrumental bed is REALLY available.
        val safe = safeIntroFor(track)
        if (!introEnd.isFinite() || introEnd <= OPENING_INTRO_MIN_S || introEnd >= durS) {
            // no usable introEnd hint: fall back to the safe-window clamp alone.
            if (!safe.isFinite()) return defaultS
            val usableV = safe - startS - OPENING_TAIL_GUARD_S
            return if (usableV < OPENING_MIN_USABLE_S) defaultS else minOf(defaultS, usableV)
        }
        // combine the introEnd hint with the vocal-onset safe window (whichever
        // is the tighter constraint on where the singer enters).
        val window = if (safe.isFinite()) minOf(introEnd, safe) else introEnd
        val usable = window - startS - OPENING_TAIL_GUARD_S
        if (usable < OPENING_MIN_USABLE_S) return defaultS
        return minOf(defaultS, usable)
    }

    // ------------------------------------------------------------------- voice
    /**
     * Load + condition one DJ/TTS clip (feature 16). Order:
     *  1. loadFn - decoded mono 44.1 kHz PCM.
     *     TODO(wave 4, :app adapter): the 24 kHz TTS -> 44.1 kHz resample
     *     happens inside the adapter's loadFn (Adapters.kt ->
     *     AudioDecoder.decodeToPcm -> resampleLinear), OUTSIDE BlockRenderer's
     *     reach. Switch THAT path to ai.kolai.analyze.resampleSinc;
     *     BlockRenderer only ever sees already-44.1 kHz samples here, so
     *     resampling again in this method would be wrong.
     *  2. trimSilence - speech starts immediately (pre-existing).
     *  3. voiceBroadcastChain - 90 Hz high-pass + presence shelf + soft-knee
     *     compressor: the on-air "broadcast" voice.
     *  4. normalizeLoudness to [voiceTargetRms] - kept LAST so the chain's
     *     gain changes can never shift the voice-over-bed level balance.
     *  5. microFadeEdges - no boundary clicks (pre-existing).
     */
    private fun prepareVoice(path: String): FloatArray =
        Dsp.microFadeEdges(
            Dsp.normalizeLoudness(
                voiceBroadcastChain(Dsp.trimSilence(loadFn(path)), Dsp.SR),
                targetRms = voiceTargetRms, maxGain = LOUDNESS_MAX_GAIN,
            )
        )

    // ---------------------------------------------- analysis-driven transitions
    /**
     * Samples to drop from the END of the OUTGOING song's used region at a
     * pure-music segue (feature 13a). The analyzer's outroStartS hint is
     * refined against the actual energy envelope ([refineOutroStart]) and the
     * cut lands at refined + [OUTRO_GRACE_S] of breath, bounded so a freak
     * analysis can never wreck the song:
     *  - the hint must be MEANINGFUL: finite, positive, and at least
     *    [OUTRO_MIN_TAIL_S] before the (post-trim) end - otherwise no cut;
     *  - never cut more than [OUTRO_MAX_CUT_S];
     *  - never keep less than [OUTRO_MIN_KEEP_FRAC] of the song.
     * Talk-over boundaries never call this: the duck look-back needs the tail.
     */
    private fun outroCutSamples(track: LoadedTrack): Int {
        val audio = track.audio
        if (audio.isEmpty()) return 0
        val durS = audio.size.toDouble() / Dsp.SR
        val hint = track.analysis.outroStartS
        if (!hint.isFinite() || hint <= 0.0 || hint >= durS - OUTRO_MIN_TAIL_S) return 0
        val refined = refineOutroStart(audio, Dsp.SR, hint)
        var cutAtS = refined + OUTRO_GRACE_S
        if (cutAtS < durS - OUTRO_MAX_CUT_S) cutAtS = durS - OUTRO_MAX_CUT_S
        if (cutAtS < durS * OUTRO_MIN_KEEP_FRAC) cutAtS = durS * OUTRO_MIN_KEEP_FRAC
        if (cutAtS >= durS) return 0
        val cut = audio.size - (cutAtS * Dsp.SR).toInt()
        return if (cut > 0) cut else 0
    }

    /**
     * Musical-segue overlap snapped to whole beats of the OUTGOING song
     * (feature 13b) when its bpm is sane (within [MIN_SNAP_BPM]..[MAX_SNAP_BPM];
     * TrackAnalysis carries no beat-confidence, so the tempo range is the
     * sanity gate). Insane bpm keeps the plain [musicSegueS].
     */
    private fun snappedMusicOverlap(outgoing: LoadedTrack): Float {
        val bpm = outgoing.analysis.bpm
        if (bpm < MIN_SNAP_BPM || bpm > MAX_SNAP_BPM) return musicSegueS
        return Dsp.snapOverlapToBeats(musicSegueS, bpm.toFloat())
    }

    /**
     * Trim the INCOMING song's head so its first beat at/after the crossfade
     * MIDPOINT lands exactly on the midpoint (feature 13b): the new groove
     * locks in right where the two songs are at equal power. Identity when
     * there are no beats, no beat at/after the midpoint, or the trim would
     * exceed [MAX_BEAT_ALIGN_SKIP_S] (mirrors Dsp.startOnBeat's maxSkipS guard
     * so a sparse/garbage beat grid can never eat the song's intro).
     */
    private fun alignEntryToBeat(audio: FloatArray, beatTimes: List<Double>, overlapS: Float): FloatArray {
        if (beatTimes.isEmpty()) return audio
        val mid = overlapS / 2.0
        val beat = Dsp.firstBeatAtOrAfter(beatTimes, mid) ?: return audio
        val skipS = beat - mid
        if (skipS <= 0.0 || skipS > MAX_BEAT_ALIGN_SKIP_S) return audio
        val n = (skipS * Dsp.SR).toInt()
        return if (n in 1 until audio.size) audio.copyOfRange(n, audio.size) else audio
    }
    // ----------------------------------------------------------------- render
    /** Python: render. */
    suspend fun render(songs: List<Song>, index: Int, prevTrack: LoadedTrack? = null): BlockResult {
        // 1. load + analyze each song IN PARALLEL (order kept), skipping failures
        val tracks = loadTracks(songs)
        if (tracks.isEmpty()) {
            throw IllegalArgumentException("No playable tracks in block")
        }

        // session ident (deviation 6): rendered BEFORE planning so the opening
        // duck start is known to planFor's vocal-aware budget (feature 15).
        // The opening DJ duck shifts right by (identDur - overlap) so the DJ
        // speaks just as the music takes over.
        var openingDuckStartS = 0.5
        var identAudio: FloatArray? = null
        val identFn = ident
        if (prevTrack == null && identFn != null) {
            identAudio = identFn()
            val identDur = identAudio.size.toDouble() / Dsp.SR
            openingDuckStartS = identDur - IDENT_OVERLAP_S + 0.5
        }

        // 2. plan the cadence
        val events = planFor(tracks, prevTrack, openingDuckStartS)

        // 3. assemble audio + collect timings
        var timeline = tracks[0].audio
        val songEvents = ArrayList<SongEvent>()
        val talk = ArrayList<TalkEntry>()

        // block 0 opens with the station petiach, crossfaded into song 0.
        if (identAudio != null) {
            timeline = Dsp.equalPowerCrossfade(identAudio, timeline, overlapS = IDENT_OVERLAP_S)
        }

        // opening talkover (if any) - DJ over the intro of song 0
        val opening = events.firstOrNull { it.kind == "open" }
        if (opening != null) {
            // PER-MOOD VOICE (task 1): the opening rides the block's mood voice.
            val slot = voice.render(opening.text!!, style = blockTtsStyle, voiceName = blockVoice)
            val djAudio = prepareVoice(slot.audioPath)
            val djDur = djAudio.size.toDouble() / Dsp.SR
            val startS = openingDuckStartS
            timeline = Dsp.duck(
                timeline, djAudio, startS = startS.toFloat(), attenuationDb = duckDb,
                easedReleaseS = VOICE_DUCK_RELEASE_S,
            )
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

            val talkEvent = events.firstOrNull { it.i == i && it.kind == "break" }

            // ANALYSIS-DRIVEN OUTRO CUT (feature 13a), pure-music segues only:
            // the timeline currently ends with the OUTGOING song's tail, so
            // dropping N samples off the timeline end == ending that song's
            // used region at its refined outro (+ grace). Talk-over boundaries
            // skip the cut - the duck look-back math below needs the tail.
            if (talkEvent == null) {
                val cut = outroCutSamples(tracks[i - 1])
                if (cut in 1 until timeline.size) {
                    timeline = timeline.copyOfRange(0, timeline.size - cut)
                }
            }

            // META PRECISION: the boundary is measured AFTER the outro cut (and
            // before the length-preserving duck), so SongEvent.startS - and the
            // Segment start/end the app's now-playing/skip depend on - always
            // matches the audio the listener actually hears, sample-accurate.
            val boundary = round2(timeline.size.toDouble() / Dsp.SR)

            if (talkEvent != null) {
                // TWO-HOST BANTER (feature 11): a "banter" beat carries the
                // planned turns in [pendingBanter]; with a configured [voiceB]
                // they render through the dialogue seam (the :app adapter
                // overrides it with multi-speaker TTS in wave 4 - the seam
                // DEFAULT body is still single-voice). Without a voiceB the
                // joined script (the event text) renders single-voice - the
                // pre-wave-3 substitute behavior.
                // A "banter" or "trivia" beat carries planned two-host turns
                // (banter in [pendingBanter], trivia in [pendingDialogue]); with
                // a configured co-host voice they render through the dialogue
                // seam, with the MAIN host on the block's mood voice ([voiceA] =
                // blockVoice) and the sidekick on the rotated voiceB (banter)
                // or the constructor [voiceB]. Without a voiceB the joined
                // script renders single-voice in the mood voice.
                val dialogueTurns = when (talkEvent.beat) {
                    "banter" -> pendingBanter.remove(i)
                    "trivia" -> pendingDialogue.remove(i)
                    else -> null
                }
                val sidekick = pendingSidekickVoice.remove(i) ?: voiceB
                val slot = if (dialogueTurns != null && sidekick != null) {
                    voice.renderDialogue(dialogueTurns, sidekick, style = blockTtsStyle, voiceA = blockVoice)
                } else {
                    voice.render(talkEvent.text!!, style = blockTtsStyle, voiceName = blockVoice)
                }
                val djAudio = prepareVoice(slot.audioPath)
                val djDur = djAudio.size.toDouble() / Dsp.SR
                // duck the DJ over the TAIL of the current timeline (talkover),
                // then crossfade into the next song.
                val duckStart = maxOf(
                    0.0, timeline.size.toDouble() / Dsp.SR - djDur - segueS - 0.3
                )
                timeline = Dsp.duck(
                    timeline, djAudio, startS = duckStart.toFloat(), attenuationDb = duckDb,
                    easedReleaseS = VOICE_DUCK_RELEASE_S,
                )
                talk.add(
                    TalkEntry(
                        beat = talkEvent.beat ?: "song", text = slot.text,
                        startS = round2(duckStart), endS = round2(duckStart + djDur),
                    )
                )
            }

            // segue into the next song: pure MUSICAL boundaries breathe with
            // the radio crossfade [musicSegueS] SNAPPED to whole beats of the
            // outgoing song (feature 13b), and the incoming song's entry is
            // aligned so a beat lands at the crossfade midpoint; talk-over
            // boundaries keep the tight [segueS] (and the raw incoming audio)
            // so the duckStart math above (which reserves segueS + 0.3 s after
            // the DJ) stays untouched.
            val overlap = if (talkEvent != null) segueS else snappedMusicOverlap(tracks[i - 1])
            val incoming = if (talkEvent != null) {
                track.audio
            } else {
                alignEntryToBeat(track.audio, track.analysis.beatTimes, overlap)
            }
            timeline = Dsp.equalPowerCrossfade(timeline, incoming, overlapS = overlap)
            songEvents.add(
                SongEvent(title = song.title, artist = song.artist, startS = boundary)
            )
        }

        // 4. soft-limit the assembled block: crossfade overlaps + ducked
        // voice + normalization gain can push instantaneous peaks past 1.0
        // (hard clip = audible crackle). softClip is bit-exact below 0.95 and
        // tanh-knees only the overshoot. Then write (.m4a / AAC) + meta.
        timeline = Dsp.softClip(timeline)
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

        /**
         * Song->song MUSICAL crossfade (s) for boundaries with no talk break.
         * Longer than the talk-over segue [segueS] (1.5 s default): pure
         * musical segues breathe at radio pace, while talk boundaries keep
         * the original tight overlap so DJ talk-over timing is untouched.
         */
        const val MUSIC_SEGUE_S: Float = 4.0f

        /**
         * Loudness targets (chosen 2026-06-12, audio-quality pass):
         *  - SONG_TARGET_RMS 0.08 (~ -22 dBFS RMS): every song's middle-60%
         *    RMS lands here - a comfortable streaming level that leaves
         *    crossfade/limiter headroom above it.
         *  - VOICE_TARGET_RMS 0.15 (~ -16.5 dBFS RMS): with the music bed
         *    ducked by duckDb (-15 dB default) to ~0.014 RMS, the DJ voice
         *    sits ~20 dB above the bed - classic intelligible radio
         *    talk-over, so duckDb itself needs no change.
         *  - LOUDNESS_MAX_GAIN 4 (+/-12 dB): bounds correction so broken or
         *    near-silent sources can't be boosted into mush.
         */
        const val SONG_TARGET_RMS: Float = 0.08f
        const val VOICE_TARGET_RMS: Float = 0.15f
        const val LOUDNESS_MAX_GAIN: Float = 4.0f

        /**
         * Eased duck release (s), feature 16: after the DJ line ends the music
         * bed SWELLS back to unity over 0.7 s (smoothstep) instead of snapping
         * - the broadcast "music comes back up under the voice tail" feel.
         */
        const val VOICE_DUCK_RELEASE_S: Float = 0.7f

        // ---- analysis-driven transitions (feature 13), chosen 2026-06-12 ----
        /** Breath kept after the refined outro start at a pure-music cut (s). */
        const val OUTRO_GRACE_S: Double = 1.0
        /** outroStartS hints closer than this to the end are noise: no cut (s). */
        const val OUTRO_MIN_TAIL_S: Double = 1.5
        /** Hard cap on how much tail an outro cut may drop (s). */
        const val OUTRO_MAX_CUT_S: Double = 12.0
        /** An outro cut may never keep less than this fraction of the song. */
        const val OUTRO_MIN_KEEP_FRAC: Double = 0.6
        /** Sane-tempo gate (bpm) for beat-snapping the musical overlap. */
        const val MIN_SNAP_BPM: Double = 60.0
        const val MAX_SNAP_BPM: Double = 200.0
        /** Max head trim (s) when beat-aligning the incoming song's entry. */
        const val MAX_BEAT_ALIGN_SKIP_S: Double = 4.0

        // ---- vocal-aware opening, tier 1 (feature 15) -----------------------
        /** introEndS at/below this is too short/noisy to be a usable hint (s). */
        const val OPENING_INTRO_MIN_S: Double = 4.0
        /** Minimum usable intro bed required to clamp the opening budget (s). */
        const val OPENING_MIN_USABLE_S: Double = 5.0
        /** The opening must END this long before the vocal comes in (s). */
        const val OPENING_TAIL_GUARD_S: Double = 0.5

        /** Max concurrent song loads (feature 14b). */
        const val LOAD_CONCURRENCY: Int = 3

        // ---- show-format latches (wave 3, 2026-06-12; deviation 8) ----------
        /** Min spacing (ms) between wink-ENABLED intros (feature 9). */
        const val TASTE_WINK_SPACING_MS: Long = 60L * 60_000L
        /** Min spacing (ms) between good-thing micro-segments (feature 10). */
        const val GOOD_THING_SPACING_MS: Long = 3L * 60L * 60_000L
        /** Min spacing (ms) between on-air calendar-note mentions (feature 7). */
        const val CALENDAR_MENTION_SPACING_MS: Long = 90L * 60_000L

        // ---- fun-segment latches (2026-06-13) -------------------------------
        /** Min spacing (ms) between trivia bits - rare (~2.5 h). */
        const val TRIVIA_SPACING_MS: Long = 150L * 60_000L
        /** Min spacing (ms) between listening-cue intros (~1.5 h). */
        const val LISTENING_CUE_SPACING_MS: Long = 90L * 60_000L

        // ---- vocal-onset talk-over guard (2026-06-13, task 3) ---------------
        /**
         * Minimum SAFE instrumental window (s) at a song's start for the DJ to
         * be allowed to open/intro OVER it. Below this, the song starts singing
         * (almost) immediately, so the opener falls back to the previous song's
         * outro or is skipped, and a mid-block intro talk-over is not placed
         * over this song's head. The :analyze VocalOnset is SAFETY-biased
         * (under-reports), so this threshold pairs with that bias.
         */
        const val SAFE_INTRO_MIN_S: Double = 5.0
    }
}