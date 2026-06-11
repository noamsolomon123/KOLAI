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
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ai.kolai.app.wiring.KolaiEngine
import ai.kolai.app.wiring.LiveDjContext
import ai.kolai.station.BlockMeta
import ai.kolai.station.RollingPlanner
import ai.kolai.station.StationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * KOLAI playback service. A foreground [MediaSessionService] that owns ONE
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
class KolaiMediaService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var skipPlayer: SegmentSkipPlayer
    private var mediaSession: MediaSession? = null

    private lateinit var engine: KolaiEngine
    private lateinit var stationEngine: StationEngine

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

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        createNotificationChannel()

        player = ExoPlayer.Builder(this).build()
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
        val builder = MediaSession.Builder(this, skipPlayer)
        if (sessionActivityPendingIntent != null) {
            builder.setSessionActivity(sessionActivityPendingIntent)
        }
        mediaSession = builder.build()

        // Promote to foreground IMMEDIATELY so the process survives the entire
        // cold render even though nothing is playing yet (player stays IDLE until
        // block 0 lands). This is what keeps the OS/Media3 from killing us.
        promoteToForeground("מתחבר לתחנה…")

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
                cacheDir = cacheRoot,
                blocksDir = blocksDir,
                ctxFactory = { http ->
                    val live = LiveDjContext(http = http, scope = serviceScope)
                    liveCtx = live
                    live::current
                },
            )
            // Eager async warm-up so the very first block's opening likely has
            // real weather/news. Never blocks startup; failures just leave the
            // context fields null (DjBrain falls back gracefully).
            liveCtx?.refreshNow()

            val taste = SeededTasteSource(this)
            val rollingPlanner = RollingPlanner(
                tasteSource = taste,
                setlistPlanner = engine.planner,
                // Cross-launch no-repeat history: the planner keeps being told
                // what recently played, so a relaunch does NOT re-open with the
                // same favourite picks.
                persistFile = File(cacheRoot, "history.txt"),
            )

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
            Log.i(TAG, "engine built; blocksDir=${blocksDir.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "engine build FAILED", e)
            KolaiState.setError("engine init failed: ${e.message}")
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
                // Resume point: lowest already-rendered block at/after the
                // engine's current position, else the render frontier.
                val first = stationEngine.firstPlayableIndex()
                nextToAdd = first
                lastAdvanced = first - 1
                Log.i(TAG, "feed: starting at block $first")
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
                    if (idx == first) KolaiState.setTuning()
                    Log.i(TAG, "feed: requesting block $idx (cold render if not on disk)")
                    val path = withContext(Dispatchers.IO) { stationEngine.getBlockPath(idx) }
                    Log.i(TAG, "feed: block $idx ready -> $path")

                    // Cache this block's meta so the poller + skip player can map
                    // position -> segment synchronously.
                    cacheMeta(idx)

                    val item = MediaItem.Builder()
                        .setMediaId(idx.toString())
                        .setUri(File(path).toUri())
                        .build()

                    runOnPlayer {
                        player.addMediaItem(item)
                        if (idx == first) {
                            // The FIRST FED block just landed (block 0 after the
                            // long cold render, or a restored block instantly on
                            // a warm relaunch). The player has been IDLE/empty
                            // until now; ONLY NOW (with a real item present) do
                            // we prepare + start, applying the user's remembered
                            // "tap Listen" intent.
                            player.prepare()
                            player.playWhenReady = true
                            player.play()
                            Log.i(TAG, "block $idx (first fed) added -> prepare()+play() (honoring tap)")
                        } else if (player.playbackState == Player.STATE_IDLE) {
                            // Defensive: never force play on a later block.
                            player.prepare()
                        }
                    }
                    if (idx == first) {
                        KolaiState.setReady()
                        updateNowPlaying(first)
                    }
                    nextToAdd = idx + 1
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "feed loop error", e)
                KolaiState.setError("feed error: ${e.message}")
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
                    if (meta != null) publishForPosition(meta, posSec)
                }
                delay(POLL_MS)
            }
        }
    }

    /** Find the active segment + talk for [posSec] and publish to the UI. */
    private fun publishForPosition(meta: BlockMeta, posSec: Double) {
        val seg = activeSegment(meta, posSec)
        if (seg != null) {
            KolaiState.setCurrentSong(seg.title, seg.artist.ifBlank { null })
        }
        val talk = meta.talk.firstOrNull { posSec >= it.startS && posSec < it.endS }
        KolaiState.setDj(onAir = talk != null, beat = talk?.beat)
    }

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
                seekToNextMediaItemRaw()
                Log.i(TAG, "next -> next block")
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        feedJob?.cancel()
        posJob?.cancel()
        try { stationEngine.stop() } catch (_: Exception) {}
        serviceScope.cancel()
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
        private const val CHANNEL_ID = "kolai_playback"
        private const val NOTIF_ID = 1001
    }
}