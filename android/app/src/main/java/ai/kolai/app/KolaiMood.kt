package ai.kolai.app

import ai.kolai.station.Moods
import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide mood + "new station" (re-tune) bridge between the Compose UI
 * (writer: mood chips / the "tahana hadasha" button) and [KolaiMediaService]
 * (reader: planner mood + retune handler). Same singleton pattern as
 * [KolaiState]: the activity and the service live in one process, so a hot
 * [StateFlow] is all the plumbing needed.
 *
 * Persistence is plain SharedPreferences ("kolai_prefs"/"mood"):
 * [MainActivity.onCreate] calls [load] BEFORE composing so the bar opens on
 * the remembered mood, and [setAndPersist] saves on every chip tap.
 */
object KolaiMood {
    private const val PREFS = "kolai_prefs"
    private const val KEY_MOOD = "mood"

    private val _mood = MutableStateFlow(Moods.DEFAULT)
    val mood: StateFlow<String> = _mood.asStateFlow()

    // One-shot "re-tune the station" requests. extraBufferCapacity=1 so a
    // tryEmit from the UI thread never drops while the service collector is
    // between events; bursts conflate into at most one pending request (the
    // service additionally guards re-entrancy with an in-flight flag).
    private val _retune = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val retune: SharedFlow<Unit> = _retune.asSharedFlow()

    /** Set the active mood. Unknown keys (not in [Moods.ALL]) are ignored. */
    fun set(mood: String) {
        if (mood !in Moods.ALL) return
        _mood.value = mood
    }

    /** Set + persist (chip tap). Unknown keys are ignored and not persisted. */
    fun setAndPersist(context: Context, mood: String) {
        if (mood !in Moods.ALL) return
        _mood.value = mood
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MOOD, mood).apply()
    }

    /** Load the persisted mood (app start, before composing). */
    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MOOD, null)
        if (saved != null && saved in Moods.ALL) _mood.value = saved
    }

    /** Ask the service to re-tune the station (fresh setlist from block 0). */
    fun requestRetune() {
        _retune.tryEmit(Unit)
    }
}