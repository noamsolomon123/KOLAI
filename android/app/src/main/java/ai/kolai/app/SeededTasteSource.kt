package ai.kolai.app

import android.content.Context
import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.profileFromJson
import ai.kolai.station.TasteSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * DEV-ONLY [TasteSource]: loads the listener's Spotify-derived taste from the
 * bundled `taste.json` asset (the same real cached taste the EndToEndBlockTest
 * used) and parses it via :core [profileFromJson]. The profile is parsed once
 * and memoized; [useCache] is ignored because there is no live Spotify pull yet.
 *
 * TODO production: replace with a live Spotify PKCE TasteSource that learns +
 * force-refreshes from the user's real account (RollingPlanner already calls
 * getProfile(useCache=false) on its periodic refresh -- a live source would honor
 * that to re-learn).
 */
class SeededTasteSource(context: Context) : TasteSource {

    private val profile: TasteProfile by lazy {
        val text = appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
        val json = Json.parseToJsonElement(text) as JsonObject
        profileFromJson(json)
    }

    // Hold the application context (not an Activity) to avoid leaks; the service
    // owns this for its whole lifetime.
    private val appContext = context.applicationContext

    override suspend fun getProfile(useCache: Boolean): TasteProfile = profile

    companion object {
        private const val ASSET = "taste.json"
    }
}