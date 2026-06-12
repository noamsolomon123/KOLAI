package ai.kolai.app

import org.json.JSONObject
import java.io.File

/**
 * Weekly aired-songs stats parsed from the log (see [AiredLog.weeklyStats]).
 * The substrate for the Friday on-air recap.
 */
data class RecapStats(
    val totalSongs: Int,
    val topArtist: String?,
    val topArtistCount: Int,
    val distinctArtists: Int,
    /** Songs aired this week that NEVER appeared in the log before this week. */
    val firstSeenCount: Int,
    /** Most frequent mood key this week (e.g. "mix"). */
    val dominantMood: String,
)

/**
 * AiredLog - append-only JSONL log of every song that actually went ON AIR,
 * one object per line: {"ts":..,"title":..,"artist":..,"mood":..,"discovery":..}
 * at cacheDir/kolai/aired.jsonl.
 *
 * Writer: [KolaiMediaService]'s position poller appends exactly when the
 * on-air song CHANGES (same change gate as the lock-screen metadata), off the
 * main thread. Appends NEVER throw - a broken log must never take the station
 * down. The file is capped at ~[MAX_LINES] lines: when an append pushes it
 * past a cheap byte threshold the tail ([TRIM_TO] lines) is rewritten via a
 * tmp-file + rename.
 *
 * Reader: [weeklyStats] parses the last 7 days into [RecapStats] (null when
 * the week is too thin to brag about, < [MIN_SONGS] songs), and [recapBrief]
 * renders the compact Hebrew brief a later wave wires into
 * DjContext.recapBrief on Fridays.
 *
 * `discovery` is hardcoded false for now: Song does not carry a
 * taste-pool-vs-discovery flag yet. Once it does, the service passes it
 * through and [RecapStats.firstSeenCount] can switch to the real flag; until
 * then "first ever seen in this log" is the working definition of a תגלית.
 */
object AiredLog {
    private val lock = Any()
    private const val MAX_LINES = 4000
    private const val TRIM_TO = 3000
    // ~150 bytes/line * 4000 lines: only count lines once the file plausibly
    // exceeds the cap, so the steady-state append stays O(1).
    private const val TRIM_CHECK_BYTES = 600_000L
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val MIN_SONGS = 15

    /** Canonical log location: cacheDir/kolai/aired.jsonl. */
    fun file(cacheDir: File): File = File(File(cacheDir, "kolai"), "aired.jsonl")

    /** Append one aired song. Never throws; call off the main thread. */
    fun append(
        file: File,
        ts: Long,
        title: String,
        artist: String?,
        mood: String,
        discovery: Boolean = false,
    ) {
        try {
            synchronized(lock) {
                file.parentFile?.mkdirs()
                val line = JSONObject()
                    .put("ts", ts)
                    .put("title", title)
                    .put("artist", artist ?: "")
                    .put("mood", mood)
                    .put("discovery", discovery)
                    .toString()
                file.appendText(line + "\n")
                if (file.length() > TRIM_CHECK_BYTES) trim(file)
            }
        } catch (_: Throwable) {
            // best-effort log; losing a line is fine, crashing is not.
        }
    }

    /**
     * Stats over the last 7 days before [nowMs], or null when fewer than
     * [MIN_SONGS] songs aired (a recap over a near-empty week sounds silly).
     * Never throws (null on any parse/IO error).
     */
    fun weeklyStats(file: File, nowMs: Long = System.currentTimeMillis()): RecapStats? {
        val lines = try {
            synchronized(lock) { if (file.exists()) file.readLines() else return null }
        } catch (_: Throwable) {
            return null
        }
        val cutoff = nowMs - WEEK_MS

        val seenBefore = HashSet<String>()
        val weekKeys = ArrayList<String>()
        val artistCounts = HashMap<String, Int>()
        val moodCounts = HashMap<String, Int>()

        for (raw in lines) {
            if (raw.isBlank()) continue
            val obj = try { JSONObject(raw) } catch (_: Throwable) { continue }
            val ts = obj.optLong("ts", 0L)
            val title = obj.optString("title", "")
            if (title.isBlank()) continue
            val artist = obj.optString("artist", "")
            val key = (artist.trim() + "|" + title.trim()).lowercase()
            if (ts < cutoff) {
                seenBefore.add(key)
                continue
            }
            weekKeys.add(key)
            if (artist.isNotBlank()) artistCounts.merge(artist.trim(), 1, Int::plus)
            val mood = obj.optString("mood", "")
            if (mood.isNotBlank()) moodCounts.merge(mood, 1, Int::plus)
        }

        if (weekKeys.size < MIN_SONGS) return null
        val top = artistCounts.maxByOrNull { it.value }
        val firstSeen = weekKeys.toHashSet().count { it !in seenBefore }
        val dominantMood = moodCounts.maxByOrNull { it.value }?.key ?: "mix"
        return RecapStats(
            totalSongs = weekKeys.size,
            topArtist = top?.key,
            topArtistCount = top?.value ?: 0,
            distinctArtists = artistCounts.size,
            firstSeenCount = firstSeen,
            dominantMood = dominantMood,
        )
    }

    /**
     * Compact Hebrew brief for the DJ, e.g.:
     * "47 שירים השבוע; אמן השבוע: עומר אדם (6 השמעות); 9 תגליות חדשות; המצב השולט: מיקס"
     * A LATER wave injects this into DjContext.recapBrief on Fridays.
     */
    fun recapBrief(stats: RecapStats): String {
        val parts = ArrayList<String>(4)
        parts.add("${stats.totalSongs} שירים השבוע")
        if (stats.topArtist != null && stats.topArtistCount > 0) {
            val plays =
                if (stats.topArtistCount == 1) "השמעה אחת"
                else "${stats.topArtistCount} השמעות"
            parts.add("אמן השבוע: ${stats.topArtist} ($plays)")
        }
        if (stats.firstSeenCount == 1) {
            parts.add("תגלית חדשה אחת")
        } else if (stats.firstSeenCount > 1) {
            parts.add("${stats.firstSeenCount} תגליות חדשות")
        }
        val moodLabel = MOODS.firstOrNull { it.key == stats.dominantMood }?.label
            ?: stats.dominantMood
        parts.add("המצב השולט: $moodLabel")
        return parts.joinToString("; ")
    }

    private fun trim(file: File) {
        val lines = file.readLines()
        if (lines.size <= MAX_LINES) return
        val tail = lines.takeLast(TRIM_TO)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(tail.joinToString("\n") + "\n")
        // Same-dir rename replaces the original atomically on Android/Linux;
        // fall back to a direct rewrite if rename is refused.
        if (!tmp.renameTo(file)) {
            file.writeText(tail.joinToString("\n") + "\n")
            tmp.delete()
        }
    }
}