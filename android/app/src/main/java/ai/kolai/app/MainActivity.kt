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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * KOLAI entry screen. Minimal Compose UI:
 *  - station name
 *  - a Hebrew status line (tuning -> now-playing song title)
 *  - a play/pause (Listen) button
 *
 * On the first tap it starts + binds the [KolaiMediaService] via a
 * [MediaController]. The engine begins rendering block 0 immediately; playback
 * is gated on the user's tap (mobile autoplay rules) and begins once block 0 is
 * ready. State comes from [KolaiState].
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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val ui by KolaiState.state.collectAsState()
                    KolaiScreen(
                        status = ui.status,
                        nowPlaying = ui.nowPlaying,
                        error = ui.error,
                        onListen = ::onListenTapped,
                        onPause = ::onPauseTapped,
                    )
                }
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

    /** First tap: start the service, bind a controller, and play once ready. */
    private fun onListenTapped() {
        KolaiState.setTuning()
        // Ensure the service is created (it starts rendering block 0 in onCreate).
        val intent = Intent(this, KolaiMediaService::class.java)
        ContextCompat.startForegroundService(this, intent)

        if (controller != null) {
            controller?.play()
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
            // playWhenReady so playback starts the moment block 0 is buffered.
            c.playWhenReady = true
            c.play()
            Log.i(TAG, "controller bound; play() requested (playWhenReady=true)")
        }, MoreExecutors.directExecutor())
    }

    private fun onPauseTapped() {
        controller?.let { c ->
            if (c.isPlaying) c.pause() else c.play()
        }
    }

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

@Composable
fun KolaiScreen(
    status: StationStatus,
    nowPlaying: String?,
    error: String?,
    onListen: () -> Unit,
    onPause: () -> Unit,
) {
    val statusLine = when (status) {
        StationStatus.IDLE -> "הקש כדי להתחבר לתחנה"
        StationStatus.TUNING -> "מתחבר לתחנה… (כדקה-שתיים בפעם הראשונה)"
        StationStatus.READY -> "התחנה מוכנה…"
        StationStatus.PLAYING -> nowPlaying ?: "מתנגן עכשיו"
        StationStatus.ERROR -> "תקלה: ${error ?: "לא ידועה"}"
    }
    val buttonLabel = when (status) {
        StationStatus.PLAYING -> "השהה"
        StationStatus.TUNING, StationStatus.READY -> "מתחבר…"
        else -> "האזן"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "קול AI",   // "קול AI"
            fontSize = 56.sp,
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "הרדיו האישי שלך",  // "הרדיו האישי שלך"
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(40.dp))
        Text(
            text = statusLine,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = if (status == StationStatus.PLAYING) onPause else onListen,
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp),
            colors = ButtonDefaults.buttonColors(),
        ) {
            Text(text = buttonLabel, fontSize = 20.sp)
        }
    }
}