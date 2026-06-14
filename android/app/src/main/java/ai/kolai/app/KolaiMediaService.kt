package ai.kolai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import ai.kolai.app.wiring.KolaiEngine
import ai.kolai.app.wiring.CoverArt
import ai.kolai.app.wiring.LiveDjContext
import ai.kolai.app.wiring.PoTokenRegistry
import ai.kolai.app.wiring.WebViewPoTokenGenerator
import ai.kolai.station.BlockMeta
import ai.kolai.station.MoodSwitchWindow
import ai.kolai.station.RollingPlanner
import ai.kolai.station.StationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KOLAI playback service. A foreground [MediaLibraryService] (a
 * [androidx.media3.session.MediaSessionService] that also serves the Android
 * Auto browse tree) that owns ONE
 * [ExoPlayer] + [MediaSession] and bridges the endless [StationEngine] into the
 * player's playlist so KOLAI plays back-to-back, gapless, with screen off / in
 * the car / from the lock screen.
 *
 * SEGMENT MODEL (the crux for now-playing + skip):
 *  - Each ExoPlayer media item is a BLOCK (block_<n>.m4a) that stitches 2-3 songs
 *    with crossfades + DJ talk. The block's [BlockMeta.segments] give each song's
 *    startS/endS WITHIN the block; [BlockMeta.talk] gives each DJ talk span.
 *  - "Current song" and "skip" are therefore about POSITION WITHIN THE BLOCK, not
 *    the player's media-item index. A position poller maps
 *    (currentBlockIndex, positionSec) -> active segment / active talk and
 *    publishes the real current song + ON-AIR state to [KolaiState].
 *  - Skip (prev/next, incl. lock-screen / car buttons) is handled by a
 *    [SegmentSkipPlayer] ForwardingPlayer the MediaSession is built on: it seeks
 *    between segments within the block and only crosses to an adjacent block at a
 *    block boundary, mirroring the web onPrev/onNext.
 *
 * COLD-START LIFECYCLE (unchanged, do not regress):
 *  - On a TRUE first launch the first block cold-renders for minutes with NO
 *    playback. The playlist is EMPTY and the player stays IDLE. We NEVER
 *    prepare()/play() the empty player. We hold our OWN foreground notification
 *    so the process survives, and only prepare()+play() once the FIRST FED
 *    block is actually added. (On a warm relaunch the engine restores rendered
 *    blocks from disk, the first fed block is ready instantly, and the same
 *    rule starts playback within seconds.)
 */
class KolaiMediaService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var skipPlayer: SegmentSkipPlayer
    private var mediaSession: MediaLibrarySession? = null

    private lateinit var engine: KolaiEngine
    private lateinit var stationEngine: StationEngine
    private lateinit var rollingPlanner: RollingPlanner

    // Service-owned scope: survives player state changes; cancelled ONLY in
    // onDestroy. The render-ahead loop, feed loop, and position poller run here.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var feedJob: Job? = null
    private var posJob: Job? = null

    // Player ops must run on the player's application thread (main looper here).
    private val mainHandler = Handler(Looper.getMainLooper())

    // Block meta cache (index -> meta), populated off-thread and read by both the
    // position poller and the segment-aware skip player. Thread-safe.
    private val metaCache = ConcurrentHashMap<Int, BlockMeta>()

    @Volatile private var nextToAdd = 0
    @Volatile private var lastAdvanced = -1
    @Volatile private var startedForeground = false

    // PENDING-SKIP intent ("skip as soon as the next block is ready"):
    // SegmentSkipPlayer.doNext() used to SILENTLY no-op when the user skipped at
    // the END of the current block while the NEXT block had not been fed to the
    // player yet (most acute on block 0 -- a single song -- right after tune-in:
    // doNext finds no next segment in-block and hasNextMediaItem()==false). The
    // user perceived "skip doesn't work". Instead, doNext now records this intent
    // and the feed loop, the MOMENT it appends the next block, honours it by
    // seeking straight to that freshly-added block. This converts a dead button
    // into "skip the instant the next block lands" (a few seconds, shrinking with
    // the render-speed work in parallel) WITHOUT ever risking a silence gap: we
    // only auto-advance when a real, ready item has just been added. It is
    // cleared whenever the player naturally moves on (a transition supersedes the
    // request) so it can never fire stale, and it gracefully expires when the
    // engine genuinely has nothing more (last block / true frontier): no item is
    // ever added, so the intent simply never fires.
    @Volatile private var pendingSkip = false

    // Last song mirrored into the CURRENT media item's MediaMetadata (car /
    // lock screen). Mirrors KolaiState's idempotence: we only touch the player
    // when the active segment actually changes the song.
    @Volatile private var lastMetaSong: String? = null

    // "New station" re-entrancy guard: a retune in flight ignores new requests.
    private val retuneInFlight = AtomicBoolean(false)
    // Fast-mood-switch re-entrancy guard: a switch in flight collapses bursts.
    private val moodSwitchInFlight = AtomicBoolean(false)

    // BUG 2 (MEDIUM) FIX -- SINGLE shared transition gate. The retune collector
    // and the fast-mood-switch collector BOTH tear down feedJob
    // (cancelAndJoin), mutate engine+player state, and relaunch startFeedLoop().
    // Their in-flight flags are INDEPENDENT, so a near-simultaneous
    // "new station" tap + mood-chip tap could interleave the two sequences:
    // one collector's cancelAndJoin could target the OTHER collector's
    // freshly-launched feed loop, orphaning a feed loop that then races the
    // shared @Volatile nextToAdd + addMediaItem (duplicate/disordered playlist
    // entries that never self-heal). This Mutex serializes the ENTIRE
    // teardown+rebuild of BOTH collectors so they can never interleave; feedJob
    // is only ever touched while this lock is held. (Each collector still keeps
    // its own in-flight flag for burst-coalescing within its own type.)
    private val transitionMutex = Mutex()

    // SELF-HEAL bookkeeping: timestamps of recent auto-restarts per loop
    // (bounded to MAX_LOOP_RESTARTS_PER_HOUR) and of recent player-error
    // recoveries (bounded to MAX_PLAYER_RECOVERIES per window).
    private val feedRestarts = ArrayDeque<Long>()
    private val posRestarts = ArrayDeque<Long>()
    private val playerErrorTimes = ArrayDeque<Long>() // player thread (main) only
    private var lastErroredBlockId: String? = null    // player thread (main) only

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        createNotificationChannel()

        // The service can start without the activity (media resumption /
        // START_STICKY restart): load the persisted mood + auto flag here too
        // so the planner never runs on defaults the user already changed.
        KolaiMood.load(this)

        player = ExoPlayer.Builder(this)
            // Car-grade audio focus: declare ourselves as MEDIA/MUSIC and let
            // ExoPlayer handle focus, so KOLAI ducks for navigation prompts and
            // pauses/resumes around phone calls instead of talking over them.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            // Pause when the output route is yanked (headphones unplugged, car /
            // BT disconnect) instead of blasting the phone speaker.
            .setHandleAudioBecomingNoisy(true)
            // Hold a partial wake lock while playing so playback never stalls
            // with the screen off on long drives (blocks are LOCAL files, so no
            // wifi lock is needed -> WAKE_MODE_LOCAL, not NETWORK).
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(playerListener)
        // Segment-aware skip wrapper. The MediaSession is built on THIS so the UI
        // controller's seekToNext/Previous AND hardware/lock-screen NEXT/PREV all
        // route through segment-aware logic.
        skipPlayer = SegmentSkipPlayer(player)

        val sessionActivityPendingIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        // MediaLibrarySession (not plain MediaSession): Android Auto browses the
        // library tree via librarySessionCallback. Still built on skipPlayer so
        // wheel/lock-screen NEXT/PREV keep hitting the segment-aware skip.
        val builder = MediaLibrarySession.Builder(this, skipPlayer, librarySessionCallback)
        if (sessionActivityPendingIntent != null) {
            builder.setSessionActivity(sessionActivityPendingIntent)
        }
        mediaSession = builder.build()

        // Promote to foreground IMMEDIATELY so the process survives the entire
        // cold render even though nothing is playing yet (player stays IDLE until
        // block 0 lands). This is what keeps the OS/Media3 from killing us.
        promoteToForeground("מתחבר לתחנה…")

        // ADAPTIVE AUDIO (wave 4): install the WebView-based PoToken generator
        // BEFORE the engine is built, so NewPipeSource can attempt best-effort
        // adaptive audio-only streams. Strictly additive: any token failure
        // falls back to the guaranteed muxed-360p path.
        PoTokenRegistry.source = WebViewPoTokenGenerator(applicationContext)

        buildEngine()
        startFeedLoop()
        startPositionLoop()
    }

    /**
     * START_STICKY so the OS restarts the service if it is ever reclaimed. We
     * re-assert our foreground notification on every start command. We do NOT
     * wipe blocks here, so a restart resumes from already-rendered block files.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        promoteToForeground(currentNotifText())
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KOLAI Radio",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "KOLAI playback" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun currentNotifText(): String = when (KolaiState.state.value.status) {
        StationStatus.PLAYING -> KolaiState.state.value.nowPlaying ?: "מתנגן עכשיו"
        StationStatus.PAUSED -> KolaiState.state.value.nowPlaying ?: "מושהה"
        StationStatus.ERROR -> "תקלה בתחנה"
        else -> "מתחבר לתחנה…"
    }

    /** Post / refresh our own foreground notification (used during tuning). */
    private fun promoteToForeground(text: String) {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("קול AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else 0
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
            startedForeground = true
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun buildEngine() {
        try {
            val cfg = DevConfig.load(this)
            val cacheRoot = File(cacheDir, "kolai")
            val blocksDir = File(cacheRoot, "blocks")
            cacheRoot.mkdirs()
            blocksDir.mkdirs()

            // Live DJ context (time of day / weather / Hebrew headlines). The
            // engine's HttpClient is created INSIDE build(), so build() hands it
            // to this factory and we capture the provider for the eager warm-up
            // below. BlockRenderer calls it once per rendered block, so the
            // endless station always talks about the CURRENT time/weather/news.
            var liveCtx: LiveDjContext? = null
            engine = KolaiEngine.build(
                geminiKeys = cfg.geminiKeys,
                llmModel = cfg.llmModel,
                ttsModel = cfg.ttsModel,
                ttsVoice = cfg.ttsVoice,
                ttsVoiceB = cfg.ttsVoiceB,
                cacheDir = cacheRoot,
                blocksDir = blocksDir,
                // PER-MOOD VOICE overrides from kolai_dev.properties: an
                // override wins over the Moods default; empty keeps stock.
                moodVoices = cfg.moodVoices,
                // ARTIST BANLIST from kolai_dev.properties (bans.artists),
                // normalized so the picker compares lowercase/trimmed.
                bannedArtists = cfg.bannedArtists
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet(),
                ctxFactory = { http ->
                    val live = LiveDjContext(
                        http = http,
                        scope = serviceScope,
                        // Friday-recap substrate: the SAME aired log this
                        // service appends to in logAired (service cacheDir,
                        // AiredLog resolves <cacheDir>/kolai/aired.jsonl).
                        recapLog = AiredLog.file(cacheDir),
                    )
                    // Per-block mood for the DJ prompts/cadence/TTS. "mix" is
                    // mapped to null INSIDE LiveDjContext so default-mood
                    // blocks keep the exact pre-mood behaviour. EFFECTIVE mood:
                    // the broadcast clock while auto is on, else the chip pick.
                    live.moodProvider = { KolaiMood.effectiveMood.value }
                    liveCtx = live
                    live::current
                },
            )
            // Eager async warm-up so the very first block's opening likely has
            // real weather/news. Never blocks startup; failures just leave the
            // context fields null (DjBrain falls back gracefully).
            liveCtx?.refreshNow()

            // GROW-THE-POOL: wrap the real ~20-track taste in a decorator that
            // expands it to ~60-80 tracks via Deezer (free/keyless) so the
            // RollingPlanner no-repeat window (50) becomes satisfiable and play
            // stops hammering the same favourites. The expansion runs ONCE in
            // the background on serviceScope (off the cold-start path) and is
            // cached to disk; getProfile() serves the base 20 instantly until
            // the expanded pool is ready, then the planner's periodic
            // getProfile(useCache=false) refresh picks up the swap. Reuses the
            // engine's HttpClient (created inside KolaiEngine.build above).
            val taste = ExpandedTasteSource(
                base = SeededTasteSource(this),
                http = engine.httpClient,
                cacheDir = cacheRoot,
                scope = serviceScope,
            )
            rollingPlanner = RollingPlanner(
                tasteSource = taste,
                setlistPlanner = engine.planner,
                // Cross-launch no-repeat history: the planner keeps being told
                // what recently played, so a relaunch does NOT re-open with the
                // same favourite picks.
                persistFile = File(cacheRoot, "history.txt"),
                // SONG-FLOW COHESION: label the genres of played songs so the
                // planner's run-length easing knows how long the current genre
                // run already is (language is computed in-code, needs no source).
                genreSource = engine.genreSource,
            )
            // GENRE COHESION WARM-UP (2026-06-14): proactively warm the taste
            // pool's Deezer genres so the planner's genre cohesion has DATA.
            // Reactive warm-on-miss fired AFTER the pick it would have shaped,
            // so history.genres stayed empty and genre runs never formed.
            // Background, off the cold-start path, rate-limited.
            warmPoolGenres(taste, engine.genreSource, engine.bpmSource)

            stationEngine = StationEngine(
                nextSongs = rollingPlanner::nextSongs,
                renderBlock = engine.blockRenderer::render,
                // First-ever block is a SINGLE song so a true cold start tunes
                // in roughly twice as fast; steady state stays at 2 (smaller
                // blocks -> snappier renders + more DJ).
                songsPerBlock = { idx -> if (idx == 0) 1 else 2 },
                bufferAhead = 2,
                keepBehind = 2,
                blocksDir = blocksDir.absolutePath,
                scope = serviceScope,
            )
            // NOTE: deliberately NO reset() here -- the engine restores blocks
            // + state (block_N.meta.json + state.json) from disk, so a restart
            // resumes from already-rendered block files instead of re-rendering
            // from zero.
            stationEngine.start()
            // Mood + "new station" bridges: wired only AFTER a successful
            // build (a failed build leaves nothing for them to drive).
            startMoodCollector()
            startRetuneCollector()
            startMoodSwitchCollector()
            Log.i(TAG, "engine built; blocksDir=${blocksDir.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "engine build FAILED", e)
            KolaiState.setError("engine init failed: ${e.message}")
        }
    }

    /**
     * Proactive GENRE cache warm-up for the taste pool (2026-06-14). The
     * planner only applies genre cohesion when a candidate's genre is ALREADY
     * cached ([GenreSource.cachedGenre]); the reactive warm-on-miss fired AFTER
     * a pick (too late to shape it), so the pool's genres never populated and
     * runs (rap->rap, Mizrahi->Mizrahi) never formed. This walks the (expanding)
     * pool in the BACKGROUND -- off the cold-start path, rate-limited -- so
     * genres are warm before picks score cohesion. Purely additive: only fills
     * the cache, never blocks a pick, cancelled with serviceScope.
     */
    private fun warmPoolGenres(
        taste: ai.kolai.station.TasteSource,
        genre: ai.kolai.station.GenreSource,
        bpm: ai.kolai.station.BpmSource,
    ) {
        serviceScope.launch {
            try {
                // Let the cold-start opener + first block win the network first.
                kotlinx.coroutines.delay(20_000L)
                // Warm each track AT MOST ONCE: ~58/80 tracks have no Deezer BPM
                // (catalog gap), so a re-warm-on-null loop re-fetches them every
                // round forever -> runaway HTTP/heap pressure that tipped large
                // decodes into OOM. Dedupe by attempted key; the expanding pool's
                // NEW tracks are still picked up (not yet in `attempted`).
                val attempted = HashSet<String>()
                // A few rounds so the pool's background expansion (base ~20 ->
                // ~60-80) is also covered once it lands; stop early once warm.
                repeat(6) { round ->
                    val pool = try {
                        taste.getProfile(useCache = true).topTracks
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    var requested = 0
                    for (track in pool) {
                        if (track.artist.isBlank() || track.title.isBlank()) continue
                        if (!attempted.add((track.artist + "|" + track.title).lowercase())) continue
                        var fetched = false
                        if (genre.cachedGenre(track.artist, track.title) == null) {
                            genre.warm(track.artist, track.title); fetched = true
                        }
                        if (bpm.cachedBpm(track.artist, track.title) == null) {
                            bpm.warm(track.artist, track.title); fetched = true
                        }
                        if (fetched) {
                            requested++
                            kotlinx.coroutines.delay(400L) // gentle on Deezer
                        }
                    }
                    Log.i(TAG, "pool meta warm round $round: $requested fetches (pool=${pool.size})")
                    if (requested == 0 && round >= 1) return@launch
                    kotlinx.coroutines.delay(30_000L)
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // serviceScope cancelled on destroy -- expected.
            } catch (e: Throwable) {
                Log.w(TAG, "pool genre warm failed: ${e.message}")
            }
        }
    }

    /**
     * Feed loop: keep the player playlist fed from the engine, staying
     * ~BUFFER_AHEAD blocks ahead of the currently playing block. The loop's
     * START index comes from the engine: on a warm relaunch
     * [StationEngine.firstPlayableIndex] points at the blocks restored from
     * disk, so they enqueue instantly (no render) and playback starts in
     * seconds; on a true first launch it is the frontier (0) and the player is
     * left IDLE/empty through the long cold render -- only when the FIRST FED
     * block actually lands do we prepare()+play() (see the COLD-START
     * LIFECYCLE note above).
     */
    private fun startFeedLoop() {
        feedJob = serviceScope.launch {
            try {
                if (!::stationEngine.isInitialized) {
                    // engine build failed in onCreate: nothing to feed, and a
                    // restart cannot fix it -> stay down (ERROR already shown).
                    Log.e(TAG, "feed: engine never built; not feeding")
                    return@launch
                }
                // Resume point: lowest already-rendered block at/after the
                // engine's current position, else the render frontier.
                val engineFirst = stationEngine.firstPlayableIndex()
                // SELF-HEAL RESTART SAFETY: if the player ALREADY holds items
                // (this loop crashed mid-flight and was auto-restarted), never
                // re-add blocks it already has, and never re-run the
                // first-fed-block prepare()+play()/tuning path -- the COLD-START
                // LIFECYCLE invariant applies to an EMPTY player only. On a
                // cold start / retune the playlist is empty, so coldStart=true
                // keeps the original behaviour exactly.
                val maxQueued = withContext(Dispatchers.Main) {
                    (0 until player.mediaItemCount)
                        .mapNotNull { player.getMediaItemAt(it).mediaId.toIntOrNull() }
                        .maxOrNull()
                }
                val coldStart = maxQueued == null
                val first = if (maxQueued == null) engineFirst else maxOf(engineFirst, maxQueued + 1)
                nextToAdd = first
                if (coldStart) {
                    lastAdvanced = first - 1
                } else if (withContext(Dispatchers.Main) { player.isPlaying }) {
                    // restarted while playback survived: clear any stale ERROR.
                    KolaiState.setPlaying()
                }
                Log.i(TAG, "feed: starting at block $first (coldStart=$coldStart)")
                while (isActive) {
                    // Compare BLOCK ids (mediaId), not playlist positions:
                    // pruning removes leading items and a restored start is
                    // offset, so positions would desync the buffer window.
                    val currentBlock = withContext(Dispatchers.Main) {
                        player.currentMediaItem?.mediaId?.toIntOrNull() ?: first
                    }
                    if (nextToAdd - currentBlock > BUFFER_AHEAD) {
                        delay(300)
                        continue
                    }

                    val idx = nextToAdd
                    if (idx == first && coldStart) KolaiState.setTuning()
                    Log.i(TAG, "feed: requesting block $idx (cold render if not on disk)")
                    val path = withContext(Dispatchers.IO) { stationEngine.getBlockPath(idx) }
                    Log.i(TAG, "feed: block $idx ready -> $path")

                    // Cache this block's meta so the poller + skip player can map
                    // position -> segment synchronously.
                    val blockMeta = cacheMeta(idx)

                    // Seed the item with the block's FIRST song so Android Auto /
                    // lock screen show a real title+artist the moment the block
                    // starts (the position poller then live-updates the metadata
                    // as segments advance within the block).
                    val firstSeg = blockMeta?.segments?.firstOrNull()
                    val item = MediaItem.Builder()
                        .setMediaId(idx.toString())
                        .setUri(File(path).toUri())
                        .setMediaMetadata(
                            songMetadata(
                                firstSeg?.title ?: "KOLAI",
                                firstSeg?.artist,
                                firstSeg?.let { CoverArt.cachedCoverUrl(it.artist, it.title) },
                            ),
                        )
                        .build()

                    runOnPlayer {
                        player.addMediaItem(item)
                        if (idx == first && coldStart) {
                            // The FIRST FED block just landed (block 0 after the
                            // long cold render, or a restored block instantly on
                            // a warm relaunch). The player has been IDLE/empty
                            // until now; ONLY NOW (with a real item present) do
                            // we prepare + start, applying the user's remembered
                            // "tap Listen" intent.
                            player.prepare()
                            player.playWhenReady = true
                            player.play()
                            // The first fed block IS where playback starts, so any
                            // skip armed during the cold render is now satisfied.
                            pendingSkip = false
                            Log.i(TAG, "block $idx (first fed) added -> prepare()+play() (honoring tap)")
                        } else if (player.playbackState == Player.STATE_IDLE) {
                            // Defensive: never force play on a later block.
                            player.prepare()
                        } else if (player.playbackState == Player.STATE_ENDED) {
                            // BUG 1b SAFETY NET (drain recovery): the playlist had
                            // already DRAINED to STATE_ENDED before this block was
                            // appended (e.g. the playing block finished before a
                            // re-rendered next block returned, or any future
                            // playlist drain). ExoPlayer does NOT auto-resume into
                            // items appended to an already-ENDED playlist, so it
                            // would sit silent until a manual transport action.
                            // Seek to the JUST-ADDED item and resume.
                            //
                            // COLD-START INVARIANT PRESERVED: we addMediaItem()
                            // immediately above, so the player is provably
                            // NON-EMPTY here -- this can never run on an empty
                            // player. The added item is always the LAST one
                            // (addMediaItem appends), so its POSITION is
                            // mediaItemCount-1; we use that position, NOT the
                            // block id, so leading-item pruning never desyncs it.
                            val addedIndex = player.mediaItemCount - 1
                            Log.w(TAG, "block $idx added into ENDED playlist -> seekTo($addedIndex,0)+play() (drain recovery)")
                            player.seekTo(addedIndex, 0)
                            player.prepare()
                            player.play()
                            // The drain-recovery seek already lands on this new
                            // block, so it equally satisfies any armed skip.
                            pendingSkip = false
                        } else if (pendingSkip) {
                            // PENDING-SKIP FULFILMENT: playback is alive (not the
                            // cold-start first-fed case above, not STATE_IDLE, and
                            // the playlist has NOT drained to STATE_ENDED) and the
                            // user pressed NEXT earlier while THIS block was still
                            // rendering. The block just landed -> honour the skip
                            // now by seeking to it instead of letting the current
                            // song finish. The just-added item is always the LAST
                            // one (addMediaItem appends), so we seek by POSITION
                            // (mediaItemCount-1), never by block id, so leading
                            // pruning can never desync it -- mirroring the
                            // drain-recovery branch. We only reach here when a real,
                            // ready item was just added, so there is no silence gap.
                            pendingSkip = false
                            val addedIndex = player.mediaItemCount - 1
                            Log.i(TAG, "block $idx added -> honoring pending skip: seekTo($addedIndex,0)")
                            player.seekTo(addedIndex, 0)
                            // playWhenReady is already true here (we are mid-play);
                            // an explicit play() keeps us robust if the user had
                            // paused after arming the skip.
                            player.play()
                        }
                    }
                    if (idx == first && coldStart) {
                        KolaiState.setReady()
                        updateNowPlaying(first)
                    }
                    nextToAdd = idx + 1
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // SELF-HEAL: never stay dead on a long drive -- surface the
                // error, then auto-restart the loop (bounded per hour).
                Log.e(TAG, "feed loop CRASHED -- scheduling self-heal restart", e)
                KolaiState.setError("feed error: ${e.message}")
                scheduleLoopRestart("feed", feedRestarts, { feedJob?.isActive == true }, ::startFeedLoop)
            }
        }
    }

    // --- mood + retune bridges -------------------------------------------------

    /**
     * Keep the planner's mood in sync with the EFFECTIVE mood (the broadcast
     * clock while auto is on, else the chip pick). [KolaiMood.effectiveMood]
     * is already distinct-until-changed, so the clock's 10-min ticker only
     * reaches the planner on a REAL mood boundary; setMood affects FUTURE
     * planning only (no retune is ever triggered from here). The key is
     * passed AS-IS ("mix" included): [RollingPlanner] forwards it to the
     * setlist planner, which ignores null/"mix", so the default costs nothing.
     */
    private fun startMoodCollector() {
        serviceScope.launch {
            KolaiMood.effectiveMood.collect { value ->
                Log.i(TAG, "mood -> $value")
                rollingPlanner.setMood(value)
            }
        }
    }

    /**
     * "New station" handler: tear playback down to the cold-start shape
     * (player IDLE with an EMPTY playlist -- the COLD-START LIFECYCLE
     * invariant holds because the restarted feed loop only prepare()+play()s
     * once the new FIRST FED block actually lands), reset the engine to a
     * fresh generation at block 0, and restart the feed. The engine's run
     * loop self-heals after [StationEngine.reset] and re-renders block 0 with
     * a FRESH setlist + the current mood; block 0 is a single song, so the
     * new station starts in roughly one song-render. Requests arriving while
     * a retune is in flight are ignored ([retuneInFlight]).
     */
    private fun startRetuneCollector() {
        serviceScope.launch {
            KolaiMood.retune.collect {
                if (!retuneInFlight.compareAndSet(false, true)) {
                    Log.i(TAG, "retune already in flight; ignored")
                    return@collect
                }
                try {
                    Log.i(TAG, "retune requested")
                    // BUG 2 GATE: hold the SAME shared transition gate the
                    // fast-mood-switch collector uses, around the ENTIRE
                    // teardown+rebuild, so a retune and a mood switch can never
                    // interleave (each could otherwise cancelAndJoin the OTHER's
                    // freshly-launched feed loop). feedJob is only touched in here.
                    // Existing retune behaviour is otherwise UNCHANGED.
                    transitionMutex.withLock {
                        // 1) stop feeding; join so no stale block lands mid-teardown.
                        feedJob?.cancelAndJoin()
                        // 2) player back to the cold-start state: paused, playlist
                        //    cleared, stop() -> IDLE with an empty playlist. Must
                        //    run ON the player thread, and we must WAIT for it
                        //    (runOnPlayer is fire-and-forget), hence withContext.
                        withContext(Dispatchers.Main) {
                            player.pause()
                            player.clearMediaItems()
                            player.stop()
                        }
                        // 3) clear caches + UI state; show "tuning" right away.
                        // Drop any armed skip: it belonged to the OLD station.
                        pendingSkip = false
                        metaCache.clear()
                        lastMetaSong = null
                        KolaiState.reset()
                        KolaiState.setTuning()
                        withContext(Dispatchers.Main) { promoteToForeground("מתחבר לתחנה…") }
                        // 4) fresh engine generation: discards in-flight renders,
                        //    clears blocks + continuity, frontier = current = 0.
                        stationEngine.reset()
                        // 5) restart the feed. firstPlayableIndex() now returns
                        //    the post-reset frontier (0, registry is empty), so
                        //    the loop re-tunes from block 0 and its existing
                        //    first-fed-block path prepare()+play()s when it lands.
                        nextToAdd = 0
                        lastAdvanced = -1
                        startFeedLoop()
                    }
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "retune failed", e)
                    KolaiState.setError("retune failed: ${e.message}")
                } finally {
                    retuneInFlight.set(false)
                }
            }
        }
    }

    /**
     * FAST MOOD SWITCH (2026-06-13): when the user MANUALLY changes the mood
     * (a chip tap -> [KolaiMood.manualMoodChanges]), the pre-rendered buffer
     * still holds OLD-mood blocks, so without this the change would only be
     * heard after the whole buffer drains (up to BUFFER_AHEAD blocks). This
     * collector drops the buffered-but-UNPLAYED blocks beyond the currently
     * playing one and lets the engine re-render them with the new mood.
     *
     * It is DELIBERATELY LIGHTER than the retune ([startRetuneCollector]):
     *  - history / taste are NOT reset (this is not a new station);
     *  - the CURRENTLY-PLAYING block keeps playing (no stop(), no pause);
     *  - only player items AFTER the current one are removed, and the engine is
     *    asked to invalidate blocks > current ([StationEngine.invalidateFrom]),
     *    which discards their files + any in-flight render and rewinds the
     *    frontier so the feed loop re-requests them under the new mood (the
     *    per-block moodProvider reads the now-current effectiveMood).
     *
     * RESIDUAL LATENCY: the new mood is heard starting at the NEXT block
     * boundary (the playing block finishes in its old mood), and the first
     * re-rendered block must cold-render (~one block). The crossfade INTO that
     * first re-rendered block is lost (prevTrack continuity cannot be rebuilt
     * cheaply - same as a warm restart); this is an inaudible one-time seam, not
     * a regression. Auto-mode clock transitions still flow only through
     * [startMoodCollector] (no buffer drop) - only an explicit chip tap pays
     * the re-render cost.
     */
    private fun startMoodSwitchCollector() {
        serviceScope.launch {
            KolaiMood.manualMoodChanges.collect { mood ->
                if (!::stationEngine.isInitialized) return@collect
                if (!moodSwitchInFlight.compareAndSet(false, true)) {
                    Log.i(TAG, "mood switch already in flight; coalescing")
                    return@collect
                }
                try {
                    Log.i(TAG, "fast mood switch -> $mood")
                    // Make sure the planner is already on the new mood before we
                    // re-render (the effectiveMood collector also does this, but
                    // ordering between collectors is not guaranteed).
                    rollingPlanner.setMood(mood)

                    // BUG 2 GATE: hold the shared transition gate around the ENTIRE
                    // teardown+rebuild so this can never interleave with a retune
                    // (or another switch). feedJob is only touched inside here, and
                    // curBlock is read INSIDE the gate too, so a retune that ran
                    // just before us cannot make us act on a stale playing block.
                    transitionMutex.withLock {
                        // The block currently being PLAYED keeps playing; everything
                        // strictly after it is invalidated. If nothing is playing yet
                        // (cold render in progress, or a just-finished retune left the
                        // playlist empty), there is nothing buffered to drop - future
                        // blocks will already use the new mood. (withLock releases the
                        // gate on this non-local return.)
                        val curBlock = withContext(Dispatchers.Main) {
                            player.currentMediaItem?.mediaId?.toIntOrNull()
                        } ?: return@collect

                        // 1) stop feeding so no stale (old-mood) block lands mid-swap.
                        feedJob?.cancelAndJoin()
                        // Drop any armed skip: the buffer beyond the playing block
                        // is being rebuilt under the new mood, so an old "skip to
                        // the next block" intent should not fire against a freshly
                        // re-rendered block. The user can simply press NEXT again.
                        pendingSkip = false
                        // BUG 1a (PRIMARY -- avoid the STATE_ENDED drain entirely):
                        // do NOT strip the immediately-next ALREADY-RENDERED block.
                        // Keep the playing block N AND N+1 (already rendered, old
                        // mood) so playback continues seamlessly N -> N+1(old) ->
                        // N+2(new) with ZERO drain/silence, and invalidate the
                        // engine from N+2. If N+1 is not yet rendered (not in the
                        // playlist), keep only N and fall back on the feed-loop
                        // ENDED safety net (Bug 1b). keepThrough() is the pure,
                        // unit-tested rule (MoodSwitchWindow): it inspects PLAYLIST
                        // MEMBERSHIP by mediaId (offset-safe vs leading pruning),
                        // not positions.
                        val queuedIds = withContext(Dispatchers.Main) {
                            (0 until player.mediaItemCount)
                                .mapNotNull { player.getMediaItemAt(it).mediaId.toIntOrNull() }
                        }
                        val keepThrough = MoodSwitchWindow.keepThrough(curBlock, queuedIds)
                        // 2) remove player items ABOVE keepThrough (keep the playing
                        //    block, and N+1 if already rendered). Offset-safe by mediaId.
                        withContext(Dispatchers.Main) {
                            var i = 0
                            while (i < player.mediaItemCount) {
                                val id = player.getMediaItemAt(i).mediaId.toIntOrNull()
                                if (id != null && id > keepThrough) {
                                    player.removeMediaItem(i)
                                    // do not advance i; items shifted down into slot i
                                } else {
                                    i++
                                }
                            }
                        }
                        // 3) drop stale (old-mood) metas ABOVE keepThrough.
                        metaCache.keys.filter { it > keepThrough }.forEach { metaCache.remove(it) }
                        // 4) engine: discard rendered/in-flight blocks > keepThrough
                        //    and rewind the frontier so they re-render with the new
                        //    mood. invalidateFrom additionally clamps to current+1, so
                        //    the playing block is never invalidated.
                        stationEngine.invalidateFrom(keepThrough + 1)
                        // 5) restart the feed loop. The player still holds the playing
                        //    block (coldStart=false path), so it re-feeds from
                        //    keepThrough+1 WITHOUT re-running the first-fed
                        //    prepare()+play() and without touching the cold-start invariant.
                        startFeedLoop()
                    }
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "fast mood switch failed", e)
                    // best-effort: ensure the feed is running again so playback
                    // never stalls on a failed switch. Re-acquire the gate so this
                    // recovery cannot race a concurrent retune teardown either.
                    transitionMutex.withLock {
                        if (feedJob?.isActive != true) startFeedLoop()
                    }
                } finally {
                    moodSwitchInFlight.set(false)
                }
            }
        }
    }

    /**
     * Position poller: while the player is playing, map the player's current
     * position to the active segment + active talk of the CURRENT block and
     * publish the real current song + ON-AIR state to [KolaiState]. Polls ~500ms
     * while playing and idles (1s) while paused so it stays cheap.
     */
    private fun startPositionLoop() {
        posJob = serviceScope.launch {
            try {
                while (isActive) {
                    val playing = withContext(Dispatchers.Main) { player.isPlaying }
                    if (!playing) {
                        delay(1000)
                        continue
                    }
                    val (blockIndex, posSec) = withContext(Dispatchers.Main) {
                        val id = player.currentMediaItem?.mediaId?.toIntOrNull() ?: -1
                        id to (player.currentPosition / 1000.0)
                    }
                    if (blockIndex >= 0) {
                        val meta = metaCache[blockIndex] ?: cacheMeta(blockIndex)
                        if (meta != null) publishForPosition(blockIndex, meta, posSec)
                    }
                    delay(POLL_MS)
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // SELF-HEAL: a dead poller silently freezes now-playing /
                // ON-AIR; auto-restart it (bounded per hour). Playback itself
                // is unaffected meanwhile.
                Log.e(TAG, "position poller CRASHED -- scheduling self-heal restart", e)
                scheduleLoopRestart("position", posRestarts, { posJob?.isActive == true }, ::startPositionLoop)
            }
        }
    }

    /**
     * SELF-HEAL: restart a crashed service loop after [LOOP_RESTART_DELAY_MS],
     * bounded to [MAX_LOOP_RESTARTS_PER_HOUR] so a hard-broken loop cannot
     * spin. [isAlive] re-checks at fire time: if something else (e.g. a
     * retune) already restarted the loop, the pending restart is dropped so
     * two copies of a loop can never run at once.
     */
    private fun scheduleLoopRestart(
        name: String,
        restartTimes: ArrayDeque<Long>,
        isAlive: () -> Boolean,
        restart: () -> Unit,
    ) {
        val now = System.currentTimeMillis()
        synchronized(restartTimes) {
            while (restartTimes.isNotEmpty() && now - restartTimes.first() > RESTART_WINDOW_MS) {
                restartTimes.removeFirst()
            }
            if (restartTimes.size >= MAX_LOOP_RESTARTS_PER_HOUR) {
                Log.e(
                    TAG,
                    "SELF-HEAL: $name loop crashed ${restartTimes.size} times in the last hour" +
                        " -- giving up until the hourly window clears",
                )
                return
            }
            restartTimes.addLast(now)
        }
        Log.e(TAG, "SELF-HEAL: $name loop down -- auto-restart in ${LOOP_RESTART_DELAY_MS / 1000}s")
        serviceScope.launch {
            delay(LOOP_RESTART_DELAY_MS)
            if (isAlive()) {
                Log.i(TAG, "SELF-HEAL: $name loop already running again; skipping auto-restart")
                return@launch
            }
            Log.w(TAG, "SELF-HEAL: restarting $name loop NOW")
            restart()
        }
    }

    /**
     * Find the active segment + talk for [posSec], publish to the UI, and keep
     * the CURRENT media item's [MediaMetadata] in sync (car / lock screen).
     */
    private fun publishForPosition(blockIndex: Int, meta: BlockMeta, posSec: Double) {
        val seg = activeSegment(meta, posSec)
        if (seg != null) {
            KolaiState.setCurrentSong(seg.title, seg.artist.ifBlank { null })
            publishSegmentMetadata(blockIndex, seg)
        }
        val talk = meta.talk.firstOrNull { posSec >= it.startS && posSec < it.endS }
        KolaiState.setDj(onAir = talk != null, beat = talk?.beat)
    }

    /**
     * Mirror the active segment into the CURRENT media item's [MediaMetadata] so
     * Android Auto / the lock screen show the REAL current song as segments
     * advance within a block. ExoPlayer applies [Player.replaceMediaItem] as an
     * IN-PLACE metadata update (canUpdateMediaItem: URI unchanged) with no
     * playback interruption. [lastMetaSong] gates it to actual song changes, and
     * the mediaId guard skips stale polls where the player already moved on to
     * another block.
     */
    private fun publishSegmentMetadata(blockIndex: Int, seg: ai.kolai.station.Segment) {
        val key = "$blockIndex|${seg.title}|${seg.artist}"
        if (key == lastMetaSong) return
        lastMetaSong = key
        logAired(seg)
        // Publish immediately with whatever cover is already cached; when the
        // cover is unknown, fetch it async and re-publish ONLY if this segment
        // is still the live one (lastMetaSong unchanged).
        val cached = CoverArt.cachedCoverUrl(seg.artist, seg.title)
        applySegmentMetadata(blockIndex, seg, cached)
        if (cached == null) {
            serviceScope.launch {
                val url = CoverArt.coverUrl(seg.artist, seg.title) ?: return@launch
                if (lastMetaSong == key) applySegmentMetadata(blockIndex, seg, url)
            }
        }
    }

    private fun applySegmentMetadata(
        blockIndex: Int,
        seg: ai.kolai.station.Segment,
        artworkUrl: String?,
    ) {
        runOnPlayer {
            val cur = player.currentMediaItem ?: return@runOnPlayer
            if (cur.mediaId != blockIndex.toString()) return@runOnPlayer
            val updated = cur.buildUpon()
                .setMediaMetadata(songMetadata(seg.title, seg.artist, artworkUrl))
                .build()
            player.replaceMediaItem(player.currentMediaItemIndex, updated)
        }
    }

    /**
     * Append the song that just went ON AIR to the aired log (the Friday
     * recap substrate). Called exactly on [lastMetaSong] song-change edges,
     * so each aired song is logged once. Fire-and-forget on IO; [AiredLog]
     * itself never throws. `discovery` stays false until Song carries a
     * taste-pool-vs-discovery flag.
     */
    private fun logAired(seg: ai.kolai.station.Segment) {
        val logFile = AiredLog.file(cacheDir)
        val mood = KolaiMood.effectiveMood.value
        serviceScope.launch(Dispatchers.IO) {
            AiredLog.append(
                file = logFile,
                ts = System.currentTimeMillis(),
                title = seg.title,
                artist = seg.artist.ifBlank { null },
                mood = mood,
            )
        }
    }

    /** Car / lock-screen metadata for one song (playable music leaf). The
     *  https [artworkUrl] (Deezer album cover) is loaded by the session's
     *  default DataSourceBitmapLoader, so Auto + the media notification show
     *  real album art. */
    private fun songMetadata(title: String, artist: String?, artworkUrl: String? = null): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist?.ifBlank { null })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply { if (!artworkUrl.isNullOrBlank()) setArtworkUri(artworkUrl.toUri()) }
            .build()

    /** Segment whose [startS, endS) contains [posSec], clamped to first/last. */
    private fun activeSegment(meta: BlockMeta, posSec: Double): ai.kolai.station.Segment? {
        val segs = meta.segments
        if (segs.isEmpty()) return null
        for (s in segs) if (posSec >= s.startS && posSec < s.endS) return s
        return if (posSec >= segs.last().endS) segs.last() else segs.first()
    }

    /** Fetch + cache a block's meta (idempotent). Returns the cached meta. */
    private suspend fun cacheMeta(index: Int): BlockMeta? {
        metaCache[index]?.let { return it }
        val meta = try { stationEngine.getBlockMeta(index) } catch (e: Exception) { null }
        if (meta != null) metaCache[index] = meta
        return meta
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Any block transition (the player moved on by itself, by drain
            // recovery, or by the pending-skip seek we just issued) SUPERSEDES a
            // still-armed skip: the song the user wanted to skip is no longer the
            // one playing. Clearing here keeps a pending skip from firing stale on
            // the NEXT block append. (The feed loop already cleared it before its
            // own pending-skip seekTo, so this is a harmless no-op in that case.)
            pendingSkip = false
            val idStr = mediaItem?.mediaId ?: return
            val index = idStr.toIntOrNull() ?: return
            Log.i(TAG, "onMediaItemTransition -> block $index (reason=$reason)")
            updateNowPlaying(index)
            if (index > lastAdvanced) {
                lastAdvanced = index
                serviceScope.launch {
                    stationEngine.advance(index)   // forward-only; triggers prune
                    Log.i(TAG, "advance($index) done")
                    prunePlayedItems(index)
                    // drop stale metas below the keep window
                    metaCache.keys.filter { it < index - KEEP_BEHIND }
                        .forEach { metaCache.remove(it) }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.i(TAG, "onIsPlayingChanged=$isPlaying state=${player.playbackState}")
            if (isPlaying) KolaiState.setPlaying() else KolaiState.setPaused()
        }

        override fun onPlaybackStateChanged(state: Int) {
            Log.i(TAG, "onPlaybackStateChanged=$state")
        }

        /**
         * PLAYER ERROR RECOVERY (self-heal): an ExoPlayer error normally halts
         * playback for good. Blocks are LOCAL files, so errors are rare (a
         * corrupt encode, a pruned-under-our-feet file): re-prepare()+play()
         * and keep driving. If the SAME block errors twice in a row it is
         * treated as corrupt and removed so the station continues with the
         * next block. Bounded ([MAX_PLAYER_RECOVERIES] per window) so a
         * hard-broken player cannot loop. Runs on the player thread (main),
         * so direct player access is safe.
         */
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "onPlayerError: ${error.errorCodeName}", error)
            val now = System.currentTimeMillis()
            while (playerErrorTimes.isNotEmpty() && now - playerErrorTimes.first() > PLAYER_RECOVERY_WINDOW_MS) {
                playerErrorTimes.removeFirst()
            }
            if (playerErrorTimes.size >= MAX_PLAYER_RECOVERIES) {
                Log.e(TAG, "player error recovery limit reached; surfacing error")
                KolaiState.setError("playback error: ${error.errorCodeName}")
                return
            }
            playerErrorTimes.addLast(now)

            val curId = player.currentMediaItem?.mediaId
            if (curId != null && curId == lastErroredBlockId) {
                // second error on the SAME block -> corrupt file: drop it and
                // continue with whatever is queued behind it.
                Log.w(TAG, "SELF-HEAL: block $curId errored twice -- removing it from the playlist")
                val idx = player.currentMediaItemIndex
                if (idx in 0 until player.mediaItemCount) player.removeMediaItem(idx)
                lastErroredBlockId = null
            } else {
                lastErroredBlockId = curId
            }
            if (player.mediaItemCount > 0) {
                // NEVER prepare()/play() an empty player (cold-start invariant);
                // with items present, re-prepare + resume is safe.
                Log.w(TAG, "SELF-HEAL: re-preparing player after error")
                player.prepare()
                player.play()
            }
        }
    }

    /**
     * Remove played-and-pruned items below (current - KEEP_BEHIND) from the
     * player by mediaId. Offset-safe: we resolve the position from the mediaId at
     * removal time so engine-side pruning never desyncs the player playlist.
     */
    private fun prunePlayedItems(currentIndex: Int) {
        val lowest = currentIndex - KEEP_BEHIND
        if (lowest < 0) return
        runOnPlayer {
            var i = 0
            while (i < player.mediaItemCount) {
                val id = player.getMediaItemAt(i).mediaId.toIntOrNull()
                if (id != null && id < lowest) {
                    player.removeMediaItem(i)
                    // do not advance i; items shifted down into slot i
                } else {
                    i++
                }
            }
        }
    }

    /**
     * Coarse now-playing update on a block transition: publish the FIRST segment
     * of the block immediately (the position poller then refines it as playback
     * crosses segment boundaries). Also refreshes the notification text.
     */
    private fun updateNowPlaying(blockIndex: Int) {
        serviceScope.launch {
            val meta = cacheMeta(blockIndex) ?: return@launch
            val seg = meta.segments.firstOrNull() ?: return@launch
            KolaiState.setCurrentSong(seg.title, seg.artist.ifBlank { null })
            val text = if (seg.artist.isNotBlank()) "${seg.title} - ${seg.artist}" else seg.title
            withContext(Dispatchers.Main) { promoteToForeground(text) }
        }
    }

    // --- Android Auto browse tree + queue resolution ---------------------------

    /** Browse-tree root: a single browsable folder (not playable). */
    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("KOLAI")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    /** The ONE playable leaf: "the live station". Has NO URI on purpose. */
    private fun liveItem(): MediaItem = MediaItem.Builder()
        .setMediaId(LIVE_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("KOLAI — רדיו AI חי")
                .setArtist("תחנת הרדיו האישית שלך")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    /**
     * The player's existing playlist + position as a resume queue. MUST be
     * called on the main (player application) thread -- Media3 invokes the
     * session callbacks there. When the playlist is EMPTY we are mid cold
     * render: return an empty queue and DO NOT touch the player (the COLD-START
     * LIFECYCLE invariant: only the feed loop's first-fed-block path may
     * prepare()+play()). The tap is best-effort; playback starts the moment
     * block 0 lands.
     */
    private fun currentQueueSnapshot(): MediaSession.MediaItemsWithStartPosition {
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        return if (items.isNotEmpty()) {
            MediaSession.MediaItemsWithStartPosition(
                items,
                player.currentMediaItemIndex,
                player.currentPosition,
            )
        } else {
            MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        }
    }

    /**
     * Android Auto / browser session callback. Browse tree: ROOT_ID -> one
     * playable LIVE_ID leaf representing "the station". The leaf has no URI, so
     * onAddMediaItems/onSetMediaItems must NEVER forward it to the player;
     * instead a tap resolves to the station's REAL queue (the player's current
     * playlist) -- i.e. "play the live item" == "resume the station".
     */
    private val librarySessionCallback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            if (parentId == ROOT_ID) {
                Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.of(liveItem()), params),
                )
            } else {
                // Unknown parent: empty list (not an error) keeps Auto happy.
                Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.of(), params),
                )
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = when (mediaId) {
            ROOT_ID -> Futures.immediateFuture(LibraryResult.ofItem(rootItem(), null))
            LIVE_ID -> Futures.immediateFuture(LibraryResult.ofItem(liveItem(), null))
            else -> Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
            )
        }

        /** Browse items have no URI: never let any reach the player. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mutableListOf())

        /**
         * Tap on the LIVE leaf in the car: ignore the requested (URI-less)
         * items and resolve to the station's real queue -- the player's
         * existing playlist at its current position (seamless resume), or an
         * empty queue mid cold render (feed loop starts playback when block 0
         * lands).
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(currentQueueSnapshot())

        /** System/Auto playback resumption (reboot / app death): same queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(currentQueueSnapshot())
    }

    // --- segment-aware skip ---------------------------------------------------

    /**
     * Wraps ExoPlayer so NEXT/PREV (UI controller, lock screen, car, headset)
     * seek between SONG SEGMENTS within the current block, only crossing to an
     * adjacent block at a boundary. Mirrors the web App.tsx onPrev/onNext.
     */
    private inner class SegmentSkipPlayer(player: Player) : ForwardingPlayer(player) {

        override fun seekToNext() = doNext()
        override fun seekToNextMediaItem() = doNext()
        override fun seekToPrevious() = doPrev()
        override fun seekToPreviousMediaItem() = doPrev()

        // Advertise NEXT/PREV as always available so the session/UI enable them.
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> true
            else -> super.isCommandAvailable(command)
        }

        private fun doNext() {
            val blockIndex = currentMediaItem?.mediaId?.toIntOrNull() ?: return
            val meta = metaCache[blockIndex]
            val posSec = currentPosition / 1000.0
            if (meta != null) {
                val i = currentSegIndex(meta, posSec)
                if (i >= 0 && i + 1 < meta.segments.size) {
                    val next = meta.segments[i + 1]
                    seekTo((next.startS * 1000).toLong())
                    Log.i(TAG, "next -> segment ${i + 1} @ ${next.startS}s in block $blockIndex")
                    return
                }
            }
            // last segment (or unknown meta): advance to the next block.
            if (hasNextMediaItem()) {
                // A next block is already fed to the player: cross to it AND drop
                // any earlier pending-skip intent (we just satisfied it directly).
                pendingSkip = false
                seekToNextMediaItemRaw()
                Log.i(TAG, "next -> next block")
            } else {
                // NO next block is fed yet (engine still rendering it, or true
                // frontier). Previously this SILENTLY no-opped ("skip doesn't
                // work"). Instead record the intent: the feed loop auto-advances
                // to the next block the moment it lands (see startFeedLoop). If
                // this is genuinely the last block the engine will ever produce,
                // no item is ever added so the intent harmlessly never fires.
                pendingSkip = true
                Log.i(TAG, "next -> no next block fed yet; pending skip armed (advance when ready)")
            }
        }

        private fun doPrev() {
            val blockIndex = currentMediaItem?.mediaId?.toIntOrNull() ?: return
            val meta = metaCache[blockIndex]
            val posSec = currentPosition / 1000.0
            if (meta != null) {
                val i = currentSegIndex(meta, posSec)
                if (i >= 0) {
                    val cur = meta.segments[i]
                    // >3s into the song, or first segment -> restart current song.
                    if (posSec - cur.startS > 3.0 || i == 0) {
                        if (i == 0 && posSec - cur.startS <= 3.0 && hasPreviousMediaItem()) {
                            // at the very start of the block's first song -> go to
                            // the previous block.
                            seekToPreviousMediaItemRaw()
                            Log.i(TAG, "prev -> previous block")
                            return
                        }
                        seekTo((cur.startS * 1000).toLong())
                        Log.i(TAG, "prev -> restart segment $i @ ${cur.startS}s")
                    } else {
                        val prev = meta.segments[i - 1]
                        seekTo((prev.startS * 1000).toLong())
                        Log.i(TAG, "prev -> segment ${i - 1} @ ${prev.startS}s")
                    }
                    return
                }
            }
            // unknown meta: restart current item.
            seekTo(0)
        }

        // Use the wrapped player's real media-item seeks (not our overrides).
        private fun seekToNextMediaItemRaw() = wrappedPlayer.seekToNextMediaItem()
        private fun seekToPreviousMediaItemRaw() = wrappedPlayer.seekToPreviousMediaItem()

        private fun currentSegIndex(meta: BlockMeta, posSec: Double): Int {
            val segs = meta.segments
            if (segs.isEmpty()) return -1
            for (idx in segs.indices) {
                val s = segs[idx]
                if (posSec >= s.startS && posSec < s.endS) return idx
            }
            return if (posSec >= segs.last().endS) segs.size - 1 else 0
        }
    }

    // --- player-thread helpers ------------------------------------------------

    private fun runOnPlayer(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    // --- MediaSessionService lifecycle ---------------------------------------

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        feedJob?.cancel()
        posJob?.cancel()
        try { stationEngine.stop() } catch (_: Exception) {}
        serviceScope.cancel()
        // Memory hygiene: drop any queued player ops (runOnPlayer posts) so a
        // late-running block can never touch the released player, and detach
        // our listener explicitly before release.
        mainHandler.removeCallbacksAndMessages(null)
        try { player.removeListener(playerListener) } catch (_: Exception) {}
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        try { engine.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KolaiService"
        private const val BUFFER_AHEAD = 2
        private const val KEEP_BEHIND = 2
        private const val POLL_MS = 500L
        // Android Auto browse-tree ids: one root folder with one "live" leaf.
        private const val ROOT_ID = "kolai_root"
        private const val LIVE_ID = "kolai_live"
        private const val CHANNEL_ID = "kolai_playback"
        private const val NOTIF_ID = 1001
        // SELF-HEAL bounds: loop auto-restart delay + hourly cap, and the
        // player-error recovery cap per sliding window.
        private const val LOOP_RESTART_DELAY_MS = 30_000L
        private const val RESTART_WINDOW_MS = 60 * 60_000L
        private const val MAX_LOOP_RESTARTS_PER_HOUR = 5
        private const val PLAYER_RECOVERY_WINDOW_MS = 10 * 60_000L
        private const val MAX_PLAYER_RECOVERIES = 6
    }
}