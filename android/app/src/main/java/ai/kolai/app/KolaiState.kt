package ai.kolai.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coarse station status the UI renders. */
enum class StationStatus { IDLE, TUNING, READY, PLAYING, ERROR }

/**
 * The DJ "ON AIR" state for the current playback position. [onAir] is true while
 * the player position is inside one of the current block's talk spans; [beat]
 * carries the talk beat type (song/weather/news/topic/mashup) so the UI can show
 * a matching label, mirroring the web DJChip.
 */
data class DjState(
    val onAir: Boolean = false,
    val beat: String? = null,
)

/** Immutable UI snapshot of the station. */
data class StationUiState(
    val status: StationStatus = StationStatus.IDLE,
    // The current SONG within the current block (position-derived), not a fixed
    // per-block title. [nowPlaying] keeps the legacy "Title - Artist" string for
    // the notification; [title]/[artist] are the structured fields the UI binds.
    val nowPlaying: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val dj: DjState = DjState(),
    val error: String? = null,
)

/**
 * Process-wide station state bridge between [KolaiMediaService] (writer) and the
 * Compose UI (reader). A plain object singleton is enough here: the service and
 * the activity live in the same process, and a [StateFlow] gives the UI a hot,
 * conflated stream of the latest snapshot.
 */
object KolaiState {
    private val _state = MutableStateFlow(StationUiState())
    val state: StateFlow<StationUiState> = _state.asStateFlow()

    fun setTuning() {
        if (_state.value.status == StationStatus.PLAYING) return
        _state.value = _state.value.copy(status = StationStatus.TUNING, error = null)
    }

    fun setReady() {
        if (_state.value.status == StationStatus.PLAYING) return
        _state.value = _state.value.copy(status = StationStatus.READY, error = null)
    }

    fun setPlaying() {
        _state.value = _state.value.copy(status = StationStatus.PLAYING, error = null)
    }

    /**
     * Publish the current SONG (position-derived). Updates the structured
     * title/artist the UI binds and the legacy notification string. Idempotent:
     * skips the emit if nothing changed so the position poller stays idle-cheap.
     */
    fun setCurrentSong(title: String, artist: String?) {
        val s = _state.value
        if (s.title == title && s.artist == artist) return
        val combined = if (!artist.isNullOrBlank()) "$title - $artist" else title
        _state.value = s.copy(nowPlaying = combined, title = title, artist = artist)
    }

    /** Legacy single-string setter (kept for the very first block 0 hint). */
    fun setNowPlaying(title: String) {
        if (_state.value.nowPlaying == title) return
        _state.value = _state.value.copy(nowPlaying = title)
    }

    /** Publish the DJ on-air state for the current position. Idempotent. */
    fun setDj(onAir: Boolean, beat: String?) {
        val s = _state.value
        if (s.dj.onAir == onAir && s.dj.beat == beat) return
        _state.value = s.copy(dj = DjState(onAir = onAir, beat = beat))
    }

    fun setError(message: String) {
        _state.value = _state.value.copy(status = StationStatus.ERROR, error = message)
    }

    /** Reset to idle (e.g. on a fresh "new station"). */
    fun reset() {
        _state.value = StationUiState()
    }
}