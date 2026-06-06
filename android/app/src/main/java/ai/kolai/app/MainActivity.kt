package ai.kolai.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * KOLAI entry screen. Liquid-glass Compose UI (RTL/Hebrew):
 *  - a track-derived gradient-mesh background ([MeshBackground]),
 *  - a centered frosted now-playing card ([Modifier.liquidGlass]) with station
 *    name, gradient cover art, song title + artist, and a green play/pause
 *    transport that glows while playing.
 *
 * On the first tap it starts + binds the [KolaiMediaService] via a
 * [MediaController]. The engine begins rendering block 0 immediately. Playback is
 * NOT pushed from here against an (empty) player during the cold render -- that
 * would drive ExoPlayer to STATE_ENDED and let the service be killed mid-render.
 * Instead the service itself remembers the tap and starts playback the moment
 * block 0 lands. We only call play()/pause() on the controller once real items
 * exist (resume-from-pause). State comes from [KolaiState].
 *
 * Debug: launching with `--ez auto_start true` auto-taps Listen (used by adb to
 * verify play + advance with no human).
 */
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    @Volatile private var controller: MediaController? = null
    @Volatile private var autoStart = false

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* play regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoStart = intent?.getBooleanExtra("auto_start", false) ?: false

        setContent {
            // KOLAI is a Hebrew-first app: force RTL everywhere.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                val ui by KolaiState.state.collectAsState()
                KolaiScreen(
                    status = ui.status,
                    nowPlaying = ui.nowPlaying,
                    error = ui.error,
                    dj = ui.dj,
                    onListen = ::onListenTapped,
                    onPause = ::onPauseTapped,
                    onPrev = ::onPrevTapped,
                    onNext = ::onNextTapped,
                )
            }
        }

        ensureNotifPermission()
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
     * First tap: start the foreground service (it begins rendering block 0 and
     * will auto-start playback when block 0 lands) and bind a controller for
     * media controls + transport. We do NOT push play()/playWhenReady against the
     * empty player during the cold render -- that is the cold-start bug. We only
     * call play() when the player already has items (resume from pause).
     */
    private fun onListenTapped() {
        KolaiState.setTuning()
        // Ensure the service is created (it starts rendering block 0 in onCreate
        // and remembers the tap by auto-playing block 0 once it is ready).
        val intent = Intent(this, KolaiMediaService::class.java)
        ContextCompat.startForegroundService(this, intent)

        controller?.let { c ->
            // Player already exists: if items are loaded, this is a resume; if it
            // is still empty (cold render in progress), do NOT call play() -- the
            // service will start block 0 itself.
            if (c.mediaItemCount > 0) c.play()
            return
        }
        if (controllerFuture != null) return // bind in progress

        val token = SessionToken(this, ComponentName(this, KolaiMediaService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (e: Exception) {
                Log.e(TAG, "controller bind failed", e); KolaiState.setError("bind failed: ${e.message}"); return@addListener
            }
            controller = c
            // Do NOT set playWhenReady / play() here: during the cold render the
            // player is EMPTY, and play() on an empty player jumps to STATE_ENDED
            // (which gets the service killed). The service starts block 0 itself.
            // If the player already has items (controller bound to a live session),
            // resume playback.
            if (c.mediaItemCount > 0 && !c.isPlaying) c.play()
            Log.i(TAG, "controller bound (items=${c.mediaItemCount}); service drives cold start")
        }, MoreExecutors.directExecutor())
    }

    private fun onPauseTapped() {
        controller?.let { c ->
            if (c.isPlaying) c.pause() else if (c.mediaItemCount > 0) c.play()
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

private const val STATION_NAME = "קול AI" // "Kol AI"

/** Split the service's "Title - Artist" string into a (title, artist) pair. */
private fun splitNowPlaying(nowPlaying: String?): Pair<String, String?> {
    if (nowPlaying.isNullOrBlank()) return STATION_NAME to null
    val idx = nowPlaying.lastIndexOf(" - ")
    return if (idx > 0) {
        nowPlaying.substring(0, idx).trim() to nowPlaying.substring(idx + 3).trim()
    } else {
        nowPlaying.trim() to null
    }
}

@Composable
fun KolaiScreen(
    status: StationStatus,
    nowPlaying: String?,
    error: String?,
    onListen: () -> Unit,
    onPause: () -> Unit,
    dj: DjState = DjState(),
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    val (title, artist) = splitNowPlaying(nowPlaying)
    // Hues follow the current track; fall back to the station name when idle.
    val hueSeed = if (nowPlaying.isNullOrBlank()) STATION_NAME else title
    val palette = paletteFor(hueSeed)
    val playing = status == StationStatus.PLAYING

    Box(modifier = Modifier.fillMaxSize()) {
        MeshBackground(palette = palette, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = RoundedCornerShape(KolaiRadii.LG.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = STATION_NAME,
                    color = KolaiColors.TextDim,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(20.dp))

                CoverArt(palette = palette, playing = playing)

                Spacer(Modifier.height(22.dp))

                // DJ ON-AIR chip: shown only while a talk span is playing.
                if (dj.onAir && status == StationStatus.PLAYING) {
                    DjChip()
                    Spacer(Modifier.height(14.dp))
                }

                AnimatedContent(
                    targetState = status to (title to artist),
                    transitionSpec = {
                        (fadeIn(tween(400)) togetherWith fadeOut(tween(250)))
                    },
                    label = "nowPlaying",
                ) { (st, ta) ->
                    val (t, ar) = ta
                    when (st) {
                        StationStatus.TUNING, StationStatus.READY -> TuningBlock()
                        StationStatus.ERROR -> ErrorBlock(error = error)
                        StationStatus.PLAYING -> NowPlayingText(title = t, artist = ar)
                        StationStatus.IDLE -> NowPlayingText(
                            title = "הרדיו האישי שלך", // "your personal radio"
                            artist = "הקש כדי להתחיל", // "tap to start"
                        )
                    }
                }

                Spacer(Modifier.height(26.dp))

                TransportRow(
                    status = status,
                    palette = palette,
                    onPrimary = if (playing) onPause else onListen,
                    onPrev = onPrev,
                    onNext = onNext,
                )
            }
        }
    }
}

@Composable
private fun DjChip() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(KolaiRadii.PILL.dp))
            .background(KolaiColors.LiveTint)
            .border(1.dp, KolaiColors.LiveBorder, RoundedCornerShape(KolaiRadii.PILL.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(KolaiColors.Live),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "משדר עכשיו", // "on air"
            color = KolaiColors.LiveText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CoverArt(palette: KolaiPalette, playing: Boolean) {
    val shape = RoundedCornerShape(KolaiRadii.LG.dp)
    Box(
        modifier = Modifier
            .size(220.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(palette.a, palette.c)))
            // subtle glass overlay (top specular) + light border, no blur.
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(KolaiColors.Specular, Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.4f,
                    ),
                )
            }
            .border(1.dp, KolaiColors.GlassBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        // A simple music-note glyph (no network image).
        Text(
            text = "♪",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun NowPlayingText(title: String, artist: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            color = KolaiColors.Text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!artist.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = artist,
                color = KolaiColors.TextDim,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TuningBlock() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = KolaiColors.Green,
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "מתחבר לתחנה…", // "connecting to the station..."
            color = KolaiColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        // skeleton lines
        SkeletonLine(widthFraction = 0.7f)
        Spacer(Modifier.height(8.dp))
        SkeletonLine(widthFraction = 0.45f)
    }
}

@Composable
private fun SkeletonLine(widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(12.dp)
            .clip(RoundedCornerShape(KolaiRadii.PILL.dp))
            .background(KolaiColors.GlassStrong),
    )
}

@Composable
private fun ErrorBlock(error: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "תקלה בחיבור לתחנה", // "connection error"
            color = KolaiColors.Live,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                color = KolaiColors.TextDim,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TransportRow(
    status: StationStatus,
    palette: KolaiPalette,
    onPrimary: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val playing = status == StationStatus.PLAYING
    val busy = status == StationStatus.TUNING || status == StationStatus.READY

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // prev: seeks to the previous song segment (enabled only while playing)
        TransportGlyph(glyph = "⏮", enabled = playing, onClick = onPrev)
        Spacer(Modifier.width(28.dp))
        PlayPauseButton(
            playing = playing,
            busy = busy,
            palette = palette,
            onClick = onPrimary,
        )
        Spacer(Modifier.width(28.dp))
        // next: seeks to the next song segment (enabled only while playing)
        TransportGlyph(glyph = "⏭", enabled = playing, onClick = onNext)
    }
}

@Composable
private fun TransportGlyph(
    glyph: String,
    enabled: Boolean = false,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(KolaiColors.Glass)
            .border(1.dp, KolaiColors.GlassBorderSoft, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, color = KolaiColors.TextDim, fontSize = 18.sp)
    }
}

@Composable
private fun PlayPauseButton(
    playing: Boolean,
    busy: Boolean,
    palette: KolaiPalette,
    onClick: () -> Unit,
) {
    // Soft glow halo behind the button, brighter while playing (no blur).
    val glowAlpha by animateFloatAsState(
        if (playing) 0.55f else 0.0f,
        tween(600),
        label = "glow",
    )
    val haloSize by animateDpAsState(if (playing) 108.dp else 84.dp, tween(600), label = "halo")

    Box(contentAlignment = Alignment.Center) {
        // radial-gradient halo tinted by palette.glow
        Box(
            modifier = Modifier
                .size(haloSize)
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(
                                palette.glow.copy(alpha = glowAlpha),
                                Color.Transparent,
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension / 2f,
                        ),
                    )
                },
        )
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(KolaiColors.Green, KolaiColors.GreenDeep)))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = KolaiColors.OnGreen,
                    strokeWidth = 3.dp,
                )
            } else {
                // play (right-pointing) / pause glyph
                Text(
                    text = if (playing) "❚❚" else "▶",
                    color = KolaiColors.OnGreen,
                    fontSize = if (playing) 22.sp else 28.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}