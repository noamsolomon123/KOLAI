package ai.kolai.app

import ai.kolai.station.Moods
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Process-wide mood + "new station" (re-tune) bridge between the Compose UI
 * (writer: mood chips / the "tahana hadasha" button) and [KolaiMediaService]
 * (reader: planner mood + retune handler). Same singleton pattern as
 * [KolaiState]: the activity and the service live in one process, so a hot
 * [StateFlow] is all the plumbing needed.
 *
 * AUTO MODE (the broadcast clock): by default ([auto] = true) the station
 * follows [AutoMood.moodForClock] - morning shows in the morning, party on
 * Thu/Fri nights, late_night after midnight. Tapping any mood chip switches
 * to that MANUAL mood (auto off); the "אוטו" chip re-enables auto. Consumers
 * that drive the engine (the service's planner collector + the per-block
 * moodProvider) read [effectiveMood]; [mood] remains the raw manual pick.
 *
 * [effectiveMood] re-evaluates the clock rule on a 10-minute ticker WHILE
 * auto is on (flatMapLatest cancels the ticker in manual mode) and is
 * distinct-until-changed, so a tick that lands in the same clock window
 * re-emits NOTHING - downstream planning is only touched on a real mood
 * boundary, and mood changes only ever affect FUTURE blocks (no retune).
 *
 * Persistence is plain SharedPreferences ("kolai_prefs"/"mood" + "mood_auto"):
 * [MainActivity.onCreate] calls [load] BEFORE composing so the bar opens on
 * the remembered state, and every chip tap persists through
 * [setAndPersist] / [setAutoAndPersist].
 */
object KolaiMood {
    private const val PREFS = "kolai_prefs"
    private const val KEY_MOOD = "mood"
    private const val KEY_AUTO = "mood_auto"
    private const val CLOCK_TICK_MS = 10L * 60 * 1000

    private val _mood = MutableStateFlow(Moods.DEFAULT)

    /** The MANUAL mood pick (last chip tapped; "mix" until then). */
    val mood: StateFlow<String> = _mood.asStateFlow()

    private val _auto = MutableStateFlow(true)

    /** True when the broadcast clock (not a chip) decides the mood. */
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    // Tiny object-owned scope: only ever runs the effectiveMood share + its
    // 10-min ticker (cancelled whenever auto is off). Lives for the process.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The mood the station should ACTUALLY run right now:
     * auto ? clock-derived ([AutoMood]) : the manual pick. Hot + distinct -
     * see the AUTO MODE note above.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val effectiveMood: StateFlow<String> =
        combine(_auto, _mood) { isAuto, manual -> isAuto to manual }
            .flatMapLatest { (isAuto, manual) ->
                if (!isAuto) {
                    flowOf(manual)
                } else {
                    flow {
                        while (true) {
                            emit(AutoMood.now())
                            delay(CLOCK_TICK_MS)
                        }
                    }
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, AutoMood.now())

    // One-shot "re-tune the station" requests. extraBufferCapacity=1 so a
    // tryEmit from the UI thread never drops while the service collector is
    // between events; bursts conflate into at most one pending request (the
    // service additionally guards re-entrancy with an in-flight flag).
    private val _retune = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val retune: SharedFlow<Unit> = _retune.asSharedFlow()

    /**
     * Set the active MANUAL mood (auto turns off, in memory only). Unknown
     * keys (not in [Moods.ALL]) are ignored.
     */
    fun set(mood: String) {
        if (mood !in Moods.ALL) return
        _mood.value = mood
        _auto.value = false
    }

    /**
     * Manual chip tap: set + persist the mood AND drop out of auto (also
     * persisted). Unknown keys are ignored and not persisted.
     */
    fun setAndPersist(context: Context, mood: String) {
        if (mood !in Moods.ALL) return
        _mood.value = mood
        _auto.value = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MOOD, mood).putBoolean(KEY_AUTO, false).apply()
    }

    /** "אוטו" chip tap (or its inverse): toggle the broadcast clock + persist. */
    fun setAutoAndPersist(context: Context, enabled: Boolean) {
        _auto.value = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    /**
     * Load the persisted state (app/service start, before use). Auto defaults
     * to TRUE for users who never touched the bar.
     */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_MOOD, null)
        if (saved != null && saved in Moods.ALL) _mood.value = saved
        _auto.value = prefs.getBoolean(KEY_AUTO, true)
    }

    /** Ask the service to re-tune the station (fresh setlist from block 0). */
    fun requestRetune() {
        _retune.tryEmit(Unit)
    }
}