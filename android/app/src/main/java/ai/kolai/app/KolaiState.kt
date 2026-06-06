package ai.kolai.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coarse station status the UI renders. */
enum class StationStatus { IDLE, TUNING, READY, PLAYING, ERROR }

/** Immutable UI snapshot of the station. */
data class StationUiState(
    val status: StationStatus = StationStatus.IDLE,
    val nowPlaying: String? = null,
    val error: String? = null,
)

/**
 * Process-wide station state bridge between [KolaiMediaService] (writer) and the
 * Compose UI (reader). A plain object singleton is enough here: the service and
 * the activity live in the same process, and a [StateFlow] gives the UI a hot,
 * conflated stream of the latest snapshot. (A bound-service callback or a shared
 * ViewModel would also work; this keeps the wiring minimal as the task asks.)
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

    fun setNowPlaying(title: String) {
        _state.value = _state.value.copy(nowPlaying = title)
    }

    fun setError(message: String) {
        _state.value = _state.value.copy(status = StationStatus.ERROR, error = message)
    }

    /** Reset to idle (e.g. on a fresh "new station"). */
    fun reset() {
        _state.value = StationUiState()
    }
}