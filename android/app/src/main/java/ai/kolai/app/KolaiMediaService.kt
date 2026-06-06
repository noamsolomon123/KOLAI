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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ai.kolai.app.wiring.KolaiEngine
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

/**
 * KOLAI playback service. A foreground [MediaSessionService] that owns ONE
 * [ExoPlayer] + [MediaSession] and bridges the endless [StationEngine] into the
 * player's playlist so KOLAI plays back-to-back, gapless, with screen off / in
 * the car / from the lock screen.
 *
 * Architecture:
 *  - The engine (KolaiEngine.build) renders block_<n>.m4a files on a background
 *    coroutine. The "feed loop" pulls them in order (getBlockPath suspends until
 *    rendered) and appends each as a local-file MediaItem (mediaId = block index)
 *    on the player thread, staying ~bufferAhead ahead of the current item so the
 *    next block is always buffered for a gapless transition.
 *  - onMediaItemTransition -> parse the mediaId -> stationEngine.advance(index)
 *    (forward-only; triggers prune) -> remove now-stale played items from the
 *    player by mediaId (offset-safe: we look the index up by mediaId, never by a
 *    stored absolute position, so prune-shifting can't desync us).
 *  - Playback is LOCAL files, so nothing streams at playback time -- only the
 *    render is networked, and that runs ahead of playback.
 *
 * COLD-START LIFECYCLE (the crux):
 *  - KOLAI's FIRST block cold-renders for several minutes with NO playback yet.
 *    During that window the ExoPlayer playlist is EMPTY and the player stays
 *    IDLE. We NEVER call prepare()/play()/playWhenReady on the empty player --
 *    doing so makes ExoPlayer jump straight to STATE_ENDED, which makes the
 *    MediaSessionService look "done", drops its foreground/playing status, and
 *    lets the OS tear the service down mid-render (cancelling the render scope),
 *    so block 0 never finishes -> infinite "tuning". Instead we hold our OWN
 *    foreground notification ("connecting to the station") via startForeground()
 *    from onCreate, independent of player state, so the process survives the full
 *    cold render. ONLY when block 0 is actually added do we prepare()+play(),
 *    honoring the user's remembered "tap Listen" intent.
 *  - The render-ahead loop runs on [serviceScope] (SupervisorJob + Default),
 *    created in onCreate and cancelled ONLY in onDestroy, so it survives player
 *    state changes. We do NOT reset()/wipe the blocks dir on onCreate, so a
 *    genuine restart resumes from existing block files instead of re-rendering.
 */
class KolaiMediaService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null

    private lateinit var engine: KolaiEngine
    private lateinit var stationEngine: StationEngine

    // Service-owned scope: survives player state changes; cancelled ONLY in
    // onDestroy. The render-ahead loop and feed loop run here.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var feedJob: Job? = null

    // Player ops must run on the player's application thread (main looper here).
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var nextToAdd = 0
    @Volatile private var lastAdvanced = -1
    @Volatile private var startedForeground = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        createNotificationChannel()

        player = ExoPlayer.Builder(this).build()
        player.addListener(playerListener)

        val sessionActivityPendingIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        val builder = MediaSession.Builder(this, player)
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

            engine = KolaiEngine.build(
                geminiKeys = cfg.geminiKeys,
                llmModel = cfg.llmModel,
                ttsModel = cfg.ttsModel,
                ttsVoice = cfg.ttsVoice,
                cacheDir = cacheRoot,
                blocksDir = blocksDir,
            )

            val taste = SeededTasteSource(this)
            val rollingPlanner = RollingPlanner(
                tasteSource = taste,
                setlistPlanner = engine.planner,
            )

            stationEngine = StationEngine(
                nextSongs = rollingPlanner::nextSongs,
                renderBlock = engine.blockRenderer::render,
                songsPerBlock = 2,   // smaller blocks -> snappier cold start + more DJ
                bufferAhead = 2,
                keepBehind = 2,
                blocksDir = blocksDir.absolutePath,
                scope = serviceScope,
            )
            // NOTE: deliberately NO reset() here -- a restart resumes from any
            // block files already on disk instead of re-rendering from zero.
            stationEngine.start()
            Log.i(TAG, "engine built; blocksDir=${blocksDir.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "engine build FAILED", e)
            KolaiState.setError("engine init failed: ${e.message}")
        }
    }

    /**
     * Feed loop: keep the player playlist fed from the engine, staying
     * ~bufferAhead items ahead of the current item so the next block is buffered.
     * The player is left IDLE/empty until block 0 is actually ready; only then do
     * we prepare()+play() (see the COLD-START LIFECYCLE note above).
     */
    private fun startFeedLoop() {
        feedJob = serviceScope.launch {
            try {
                while (isActive) {
                    val currentItem = currentMediaIndexOnMain()
                    if (nextToAdd - currentItem > BUFFER_AHEAD) {
                        delay(300)
                        continue
                    }

                    val idx = nextToAdd
                    if (idx == 0) KolaiState.setTuning()
                    Log.i(TAG, "feed: requesting block $idx (cold render if first)")
                    val path = withContext(Dispatchers.IO) { stationEngine.getBlockPath(idx) }
                    Log.i(TAG, "feed: block $idx ready -> $path")

                    val item = MediaItem.Builder()
                        .setMediaId(idx.toString())
                        .setUri(File(path).toUri())
                        .build()

                    runOnPlayer {
                        player.addMediaItem(item)
                        if (idx == 0) {
                            // Block 0 just landed after the long cold render. The
                            // player has been IDLE/empty until now; ONLY NOW (with
                            // a real item present) do we prepare + start, applying
                            // the user's remembered "tap Listen" intent. We never
                            // drive an empty player, which would jump to STATE_ENDED
                            // and let the service be torn down mid-render.
                            player.prepare()
                            player.playWhenReady = true
                            player.play()
                            Log.i(TAG, "block 0 added -> prepare()+play() (honoring tap)")
                        } else if (player.playbackState == Player.STATE_IDLE) {
                            // Defensive: never force play on a later block.
                            player.prepare()
                        }
                    }
                    if (idx == 0) {
                        KolaiState.setReady()
                        updateNowPlaying(0)
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
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.i(TAG, "onIsPlayingChanged=$isPlaying state=${player.playbackState}")
            if (isPlaying) KolaiState.setPlaying()
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

    private fun updateNowPlaying(blockIndex: Int) {
        serviceScope.launch {
            val meta = try { stationEngine.getBlockMeta(blockIndex) } catch (e: Exception) { null }
            val title = meta?.segments?.firstOrNull()?.let { seg ->
                if (seg.artist.isNotBlank()) "${seg.title} - ${seg.artist}" else seg.title
            }
            if (title != null) {
                KolaiState.setNowPlaying(title)
                withContext(Dispatchers.Main) { promoteToForeground(title) }
            }
        }
    }

    // --- player-thread helpers ------------------------------------------------

    private fun runOnPlayer(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private suspend fun currentMediaIndexOnMain(): Int =
        withContext(Dispatchers.Main) { player.currentMediaItemIndex.coerceAtLeast(0) }

    // --- MediaSessionService lifecycle ---------------------------------------

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        feedJob?.cancel()
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
        private const val CHANNEL_ID = "kolai_playback"
        private const val NOTIF_ID = 1001
    }
}
