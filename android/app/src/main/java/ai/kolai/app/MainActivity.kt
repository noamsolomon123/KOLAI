package ai.kolai.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * KOLAI entry screen. A faithful Compose port of the web "liquid-glass" design
 * (frontend/src): a top-anchored RTL/Hebrew stack on a living gradient-mesh --
 *   station-row pill (wordmark + LIVE) -> mood-bar chips -> free-standing gradient
 *   cover with a serif letter glyph -> serif title/artist -> an always-on "ON AIR"
 *   card -> three transport buttons with a glowing green play.
 *
 * Edge-to-edge: the mesh fills behind the system bars; content is inset with
 * statusBars/navigationBars padding. RTL is forced regardless of device language.
 *
 * Controller lifecycle: [bindController] runs from onCreate, so the activity is
 * always connected to the (possibly already-playing) [KolaiMediaService] session --
 * binding alone never starts the service or playback, it just makes prev/next and
 * play/pause work immediately when the app is reopened over a backgrounded station.
 * The first "listen" tap is what starts the foreground service; the service renders
 * block 0 for minutes and starts playback itself once block 0 lands.
 *
 * Cold-start invariant (do not regress): we never play()/prepare() the empty
 * player from here -- every resume is guarded by mediaItemCount > 0, and the bind
 * listener only resumes when an explicit user tap set [playWhenBound].
 */
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    @Volatile private var controller: MediaController? = null
    @Volatile private var autoStart = false

    /** Set by an explicit user tap while a bind is in flight; consumed exactly once
     *  by the bind listener (resume-if-items). Never set by onCreate's always-bind. */
    @Volatile private var playWhenBound = false

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* play regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoStart = intent?.getBooleanExtra("auto_start", false) ?: false

        // Edge-to-edge: draw the mesh under the system bars, keep them transparent
        // with light icons (the canvas is always dark).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                val ui by KolaiState.state.collectAsState()
                KolaiScreen(
                    status = ui.status,
                    nowPlaying = ui.nowPlaying,
                    error = ui.error,
                    dj = ui.dj,
                    onPlayPause = ::onPlayPauseTapped,
                    onPrev = ::onPrevTapped,
                    onNext = ::onNextTapped,
                )
            }
        }

        ensureNotifPermission()
        // Always bind to the session so the transport buttons work immediately when
        // the activity is (re)opened while the service already plays in the
        // background. Binding alone starts neither the service nor playback.
        bindController()
        if (autoStart) {
            Log.i(TAG, "auto_start extra set -> starting playback")
            onListenTapped()
        }
    }

    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Builds + binds a MediaController to the service session. Safe to call at any
     * time: binding starts neither the foreground service nor playback. The listener
     * only stores the controller; it resumes a paused queue solely when an explicit
     * user tap set [playWhenBound] while the bind was in flight (consumed exactly
     * once), and it never touches an empty player (mediaItemCount == 0 stays a
     * service-driven cold start).
     */
    private fun bindController() {
        if (controller != null) return
        if (controllerFuture != null) return // bind in progress

        val token = SessionToken(this, ComponentName(this, KolaiMediaService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (e: Exception) {
                Log.e(TAG, "controller bind failed", e); KolaiState.setError("bind failed: ${e.message}"); return@addListener
            }
            controller = c
            if (playWhenBound) { // explicit tap while binding -> resume-if-items
                playWhenBound = false
                if (c.mediaItemCount > 0 && !c.isPlaying) c.play()
            }
            Log.i(TAG, "controller bound (items=${c.mediaItemCount}); service drives cold start")
        }, MoreExecutors.directExecutor())
    }

    /** Explicit "listen" tap: tune, start the station service, resume once bound. */
    private fun onListenTapped() {
        KolaiState.setTuning()
        val intent = Intent(this, KolaiMediaService::class.java)
        ContextCompat.startForegroundService(this, intent)

        controller?.let { c ->
            if (c.mediaItemCount > 0 && !c.isPlaying) c.play()
            return
        }
        playWhenBound = true // the bind listener consumes this exactly once
        bindController()
    }

    /** play/pause toggle: pause if playing; resume if paused-with-items; else cold start. */
    private fun onPlayPauseTapped() {
        val c = controller
        when {
            c?.isPlaying == true -> c.pause()
            c != null && c.mediaItemCount > 0 -> c.play()
            else -> onListenTapped()
        }
    }

    private fun onPrevTapped() { controller?.let { if (it.mediaItemCount > 0) it.seekToPrevious() } }
    private fun onNextTapped() { controller?.let { if (it.mediaItemCount > 0) it.seekToNext() } }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KolaiUI"
    }
}

// ---------------------------------------------------------------------------
//  Shared UI constants + helpers (internal: visible to the sibling UI files)
// ---------------------------------------------------------------------------

/** On-air name shown in the wordmark (matches the web design). */
internal const val WORDMARK = "רדיו AI"

/** Serif display family (web uses Fraunces; the platform serif keeps it weightless). */
internal val Display = FontFamily.Serif

internal data class Mood(val key: String, val label: String, val emoji: String)

// RTL render order = first item rightmost; "mix" is the active default, mirroring
// frontend/src/components/MoodBar.tsx.
internal val MOODS = listOf(
    Mood("mix", "מיקס", "🎚️"),
    Mood("party", "מסיבה", "🎉"),
    Mood("late_night", "לילה", "🌙"),
    Mood("focus", "ריכוז", "🎯"),
    Mood("morning", "בוקר", "☀️"),
)

internal val BEAT_LABELS = mapOf(
    "song" to "הצגת שיר",
    "weather" to "מזג אוויר",
    "news" to "חדשות",
    "topic" to "על הפרק",
    "mashup" to "מאש-אפ",
)
internal val BEAT_ICONS = mapOf(
    "song" to "🎙️",
    "weather" to "🌤️",
    "news" to "📰",
    "topic" to "💡",
    "mashup" to "🎛️",
)

// Active mood-chip / accent hues, kept independent of the per-track palette so the
// selection + DJ accents read clearly even before a track is playing.
internal const val ACCENT_HUE_A = 265f
internal const val ACCENT_HUE_B = 210f

// Idle-screen palette: midnight violet/blue glows + a warm amber ember, the
// hue layout of the target design. Track-derived hues take over on playback.
internal val IdlePalette = KolaiPalette(248f, 205f, 28f)

/** Split the service's "Title - Artist" string into a (title, artist) pair. */
internal fun splitNowPlaying(nowPlaying: String?): Pair<String?, String?> {
    if (nowPlaying.isNullOrBlank()) return null to null
    val idx = nowPlaying.lastIndexOf(" - ")
    return if (idx > 0) {
        nowPlaying.substring(0, idx).trim() to nowPlaying.substring(idx + 3).trim()
    } else {
        nowPlaying.trim() to null
    }
}


// ---------------------------------------------------------------------------
//  Screen
// ---------------------------------------------------------------------------

@Composable
fun KolaiScreen(
    status: StationStatus,
    nowPlaying: String?,
    error: String?,
    onPlayPause: () -> Unit,
    dj: DjState = DjState(),
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    val (songTitle, songArtist) = splitNowPlaying(nowPlaying)

    val playing = status == StationStatus.PLAYING
    val displayTitle: String
    val displayArtist: String?
    when (status) {
        StationStatus.TUNING, StationStatus.READY -> {
            displayTitle = "מתחבר לתחנה…"; displayArtist = "מתכוונן לשידור החי"
        }
        StationStatus.ERROR -> {
            displayTitle = "תקלה בתחנה"; displayArtist = error
        }
        StationStatus.PLAYING, StationStatus.PAUSED -> {
            displayTitle = songTitle ?: "רדיו AI"; displayArtist = songArtist ?: "התחנה משדרת"
        }
        StationStatus.IDLE -> {
            displayTitle = "הרדיו האישי שלך"; displayArtist = "הקש כדי להתחיל"
        }
    }

    val palette = if (songTitle != null) paletteFor(songTitle) else IdlePalette

    Box(modifier = Modifier.fillMaxSize()) {
        MeshBackground(palette = palette, modifier = Modifier.fillMaxSize())

        // Single fixed-height column: NO scrolling. The cover absorbs whatever
        // vertical space remains (weight slot), so every component always fits
        // on screen, on any display.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StationRow(palette = palette)

            MoodBar()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CoverArtHero(
                    palette = palette,
                    seed = songTitle ?: WORDMARK,
                    playing = playing,
                )
            }

            AnimatedContent(
                targetState = status to (displayTitle to displayArtist),
                transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(240)) },
                label = "meta",
            ) { (st, ta) ->
                val (t, ar) = ta
                if (st == StationStatus.TUNING || st == StationStatus.READY) {
                    TuningMeta()
                } else {
                    MetaText(title = t, artist = ar, error = st == StationStatus.ERROR)
                }
            }

            DjOnAirCard(
                palette = palette,
                active = dj.onAir && status == StationStatus.PLAYING,
                beat = dj.beat,
            )

            TransportRow(
                status = status,
                onPrimary = onPlayPause,
                onPrev = onPrev,
                onNext = onNext,
            )
        }
    }
}