package ai.kolai.app.wiring

import ai.kolai.station.DjClock
import ai.kolai.station.DjContext
import ai.kolai.station.Moods
import ai.kolai.station.WeatherText
import ai.kolai.station.parseNewsRss
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LiveDjContext - the on-device port of the Python live-context wiring
 * (backend/radioai/clock.py + weather.py + news.py): the DJ's real time of
 * day, current weather (Open-Meteo, free, no key) and Hebrew headlines
 * (Google News RSS, free, no key).
 *
 * Designed as a PROVIDER for [ai.kolai.station.BlockRenderer]'s fresh-context
 * seam: [current] is called once per rendered block, so an endless station
 * never reads a stale startup snapshot.
 *
 * Threading contract: [current] NEVER blocks and NEVER does I/O on the calling
 * thread. It reads the device clock right now, returns the latest CACHED
 * weather/news (null/empty until the first fetch lands - DjBrain already falls
 * back gracefully), and opportunistically launches an async refresh on [scope]
 * when a cache is past its TTL (weather 30 min, news 20 min). A single
 * in-flight flag prevents concurrent duplicate refreshes; a failed fetch keeps
 * the previous cached value and is retried on a later [current] call (which
 * happens once per block - never a tight loop). JSON is parsed with
 * kotlinx-serialization tree navigation (runtime only, no @Serializable),
 * matching the rest of the app.
 */
class LiveDjContext(
    private val http: HttpClient,
    private val city: String = "Tel Aviv",
    private val topics: List<String> = listOf("טכנולוגיה", "מוזיקה"),
    private val scope: CoroutineScope,
) {
    // Cached live fields + their fetch timestamps (0 = never fetched).
    @Volatile private var weather: String? = null
    @Volatile private var weatherAtMs: Long = 0
    @Volatile private var generalHeadline: String? = null
    @Volatile private var topicHeadlines: Map<String, String> = emptyMap()
    @Volatile private var newsAtMs: Long = 0

    private val refreshing = AtomicBoolean(false)

    /**
     * Current mood key supplier (set by the service to read [KolaiMood]).
     * "mix" -- the default -- maps to a NULL [DjContext.mood] in [current] so
     * default-mood prompts/cadence/TTS stay byte-identical to pre-mood blocks.
     */
    @Volatile var moodProvider: () -> String? = { null }

    /** Fresh time-of-day NOW + the latest cached weather/news. Non-blocking. */
    fun current(): DjContext {
        val cal = Calendar.getInstance()
        val (timeStr, partOfDay) =
            DjClock.nowParts(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        maybeRefresh(force = false)
        return DjContext(
            timeStr = timeStr,
            partOfDay = partOfDay,
            weather = weather,
            generalHeadline = generalHeadline,
            topicHeadlines = topicHeadlines,
            mood = moodProvider()?.takeIf { it != Moods.DEFAULT },
        )
    }

    /** Eager warm-up (service start): kick an async fetch without blocking. */
    fun refreshNow() = maybeRefresh(force = true)

    private fun maybeRefresh(force: Boolean) {
        val now = System.currentTimeMillis()
        val weatherStale = force || now - weatherAtMs > WEATHER_TTL_MS
        val newsStale = force || now - newsAtMs > NEWS_TTL_MS
        if (!weatherStale && !newsStale) return
        if (!refreshing.compareAndSet(false, true)) return // one refresh at a time
        scope.launch {
            try {
                if (weatherStale) refreshWeather()
                if (newsStale) refreshNews()
            } finally {
                refreshing.set(false)
            }
        }
    }

    // ----------------------------------------------------------- weather
    /** Python: WeatherService.for_city (geocode city -> current forecast). */
    private suspend fun refreshWeather() {
        try {
            val geo = Json.parseToJsonElement(
                getText(GEO_URL, mapOf("name" to city, "count" to "1"))
            ).jsonObject
            val results = geo["results"]?.jsonArray
            check(results != null && results.isNotEmpty()) { "city not found: $city" }
            val first = results[0].jsonObject
            val lat = first["latitude"]!!.jsonPrimitive.double
            val lon = first["longitude"]!!.jsonPrimitive.double

            val fc = Json.parseToJsonElement(
                getText(
                    FORECAST_URL,
                    mapOf(
                        "latitude" to lat.toString(),
                        "longitude" to lon.toString(),
                        "current" to "temperature_2m,weather_code",
                    ),
                )
            ).jsonObject
            // Python parse_weather defaults: temperature 0.0 / code 0.
            val cur = fc["current"]?.jsonObject
            val tempC = cur?.get("temperature_2m")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val code = cur?.get("weather_code")?.jsonPrimitive?.intOrNull ?: 0

            weather = WeatherText.weatherLine(tempC, code)
            weatherAtMs = System.currentTimeMillis()
            Log.i(TAG, "weather refreshed: $weather")
        } catch (e: Exception) {
            Log.w(TAG, "weather refresh failed (keeping '$weather'): $e")
        }
    }

    // -------------------------------------------------------------- news
    /** Python: NewsService.headlines() + top_for_topics(topics). */
    private suspend fun refreshNews() {
        var anyOk = false
        try {
            val head = parseNewsRss(getText(NEWS_BASE, NEWS_TAIL)).firstOrNull()
            if (head != null) {
                generalHeadline = head
                anyOk = true
                Log.i(TAG, "news refreshed: $head")
            }
        } catch (e: Exception) {
            Log.w(TAG, "news refresh failed (keeping '$generalHeadline'): $e")
        }
        // Python top_for_topics: each topic best-effort; a failed topic keeps
        // its previously cached headline instead of vanishing.
        val out = topicHeadlines.toMutableMap()
        for (topic in topics) {
            try {
                val title = parseNewsRss(
                    getText("$NEWS_BASE/search", NEWS_TAIL + ("q" to topic))
                ).firstOrNull()
                if (title != null) {
                    out[topic] = title
                    anyOk = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "topic '$topic' refresh failed: $e")
            }
        }
        topicHeadlines = out
        if (anyOk) newsAtMs = System.currentTimeMillis()
    }

    // ------------------------------------------------------------ plumbing
    /** GET [base] with query [params] (Ktor handles URL-encoding), 10s cap. */
    private suspend fun getText(base: String, params: Map<String, String>): String =
        withTimeout(FETCH_TIMEOUT_MS) {
            http.get(base) {
                url { params.forEach { (k, v) -> parameters.append(k, v) } }
            }.bodyAsText()
        }

    companion object {
        private const val TAG = "KolaiCtx"
        private const val FETCH_TIMEOUT_MS = 10_000L
        private const val WEATHER_TTL_MS = 30L * 60 * 1000
        private const val NEWS_TTL_MS = 20L * 60 * 1000

        // Python: weather._GEO_URL / _FORECAST_URL, news._BASE / _TAIL.
        private const val GEO_URL = "https://geocoding-api.open-meteo.com/v1/search"
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val NEWS_BASE = "https://news.google.com/rss"
        private val NEWS_TAIL = mapOf("hl" to "he", "gl" to "IL", "ceid" to "IL:he")
    }
}