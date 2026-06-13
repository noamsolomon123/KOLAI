package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile
import ai.kolai.core.taste.profileFromJson
import ai.kolai.voice.GeminiTextClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * CORPUS GENERATOR (PHASE 1 of the 2026-06-13 generation study).
 *
 * A GUARDED JUnit4 test that drives the REAL engine code -- TastePoolPlanner +
 * RollingPlanner (history / artist-fatigue / epsilon tail floor) +
 * DeezerDiscovery + MoodCurator + DjBrain (real Gemini text) -- against the
 * listener''s real bundled taste.json, simulating ~250 songs of continuous
 * broadcast and logging every pick AND the DJ script around it to
 * docs/studies/corpus.jsonl. NO audio is rendered (phase 1 is text + features).
 *
 * GATE: runs ONLY when -Dkolai.corpus=1 is set on the JVM. :station uses JUnit4,
 * so the gate is org.junit.Assume.assumeTrue at the top of the @Test -- without
 * the flag the test is SKIPPED and :station:testDebugUnitTest stays green and
 * offline. Run explicitly:
 *   .\gradlew :station:testDebugUnitTest \
 *     --tests "ai.kolai.station.CorpusGenerator" -Dkolai.corpus=1 --console=plain
 *
 * WIRING NOTE: the production GeminiLlmClient + DeezerDiscovery adapters live in
 * :app (NOT on the :station test classpath). They are trivially thin, so
 * faithful equivalents are inlined ([HarnessLlmClient], [HarnessDeezerDiscovery]
 * -- the same algorithm as app/.../DeezerDiscovery.kt). Everything else is the
 * real engine.
 */
class CorpusGenerator {

    private val androidDir = File(System.getProperty("user.dir")).let {
        if (it.name == "station") it.parentFile else it
    }
    private val repoRoot = androidDir.parentFile
    private val tasteJson = File(androidDir, "app/src/main/assets/taste.json")
    private val envFile = File(repoRoot, "backend/.env")
    private val studiesDir = File(repoRoot, "docs/studies")
    private val corpusFile = File(studiesDir, "corpus.jsonl")
    private val summaryFile = File(studiesDir, "corpus-run-summary.json")

    @Test
    fun generateCorpus() {
        assumeTrue("set -Dkolai.corpus=1 to generate the corpus", System.getProperty("kolai.corpus") == "1")
        runBlocking { run() }
    }

    private class HarnessLlmClient(private val textClient: GeminiTextClient) : LlmClient {
        override suspend fun complete(prompt: String): String = textClient.complete(prompt)
    }
    private suspend fun run() {
        val startMs = System.currentTimeMillis()
        studiesDir.mkdirs()

        // 1. taste from the real bundled profile parser.
        require(tasteJson.exists()) { "taste.json not found at $tasteJson" }
        val taste: TasteProfile = profileFromJson(
            Json.parseToJsonElement(tasteJson.readText(Charsets.UTF_8)) as JsonObject
        )

        // 2. Gemini keys + model from backend/.env.
        val env = parseEnv(envFile)
        val keys = listOfNotNull(env["GEMINI_API_KEY"], env["GEMINI_API_KEY_2"], env["GEMINI_API_KEY_3"])
            .filter { it.isNotBlank() }
        require(keys.isNotEmpty()) { "no GEMINI_API_KEY* in $envFile" }
        val llmModel = env["GEMINI_LLM_MODEL"] ?: error("no GEMINI_LLM_MODEL in $envFile")

        // 3. real Ktor OkHttp client + real engine wiring.
        val http = HttpClient(OkHttp)
        val textClient = GeminiTextClient(apiKeys = keys, model = llmModel, httpClient = http)
        val llm: LlmClient = HarnessLlmClient(textClient)
        val discovery = HarnessDeezerDiscovery(http)
        val planner = TastePoolPlanner(discovery = discovery, curator = MoodCurator(llm))
        val persist = File.createTempFile("corpus_history", ".txt").also { it.deleteOnExit() }
        val rolling = RollingPlanner(
            tasteSource = object : TasteSource {
                override suspend fun getProfile(useCache: Boolean): TasteProfile = taste
            },
            setlistPlanner = planner,
            persistFile = persist,
        )
        val brain = DjBrain(client = llm, persona = "קול AI")

        val out = StringBuilder()
        var seq = 0
        var geminiFailures = 0
        var deezerHits = 0
        var deezerMisses = 0
        var emptyPicks = 0
        val perMood = HashMap<String, Int>()
        val perBeat = HashMap<String, Int>()
        var discoveryCount = 0
        var songCount = 0
        var bpmKnown = 0
        var quotaExhausted = false

        fun bumpBeat(b: String) { perBeat[b] = (perBeat[b] ?: 0) + 1 }

        fun emit(
            blockIndex: Int, mood: String, ctx: DjContext, beat: String,
            song: Song?, djScript: String?, error: String? = null,
            deezerBpm: Double? = null, deezerGain: Double? = null, deezerGenre: String? = null,
        ) {
            seq += 1
            bumpBeat(beat)
            val obj = buildString {
                append("{")
                append("\"seq\":").append(seq)
                append(",\"blockIndex\":").append(blockIndex)
                append(",\"mood\":").append(jstr(mood))
                append(",\"partOfDay\":").append(jstr(ctx.partOfDay))
                append(",\"timeStr\":").append(jstr(ctx.timeStr))
                append(",\"calendarNote\":").append(jstr(ctx.calendarNote))
                append(",\"somber\":").append(ctx.somber)
                append(",\"beat\":").append(jstr(beat))
                append(",\"songTitle\":").append(jstr(song?.title))
                append(",\"songArtist\":").append(jstr(song?.artist))
                append(",\"tasteRank\":").append(song?.tasteRank?.toString() ?: "null")
                append(",\"discovery\":").append(song != null && song.tasteRank == null)
                append(",\"language\":").append(jstr(song?.let { if (containsHebrew(it.title)) "he" else "intl" }))
                append(",\"deezerBpm\":").append(deezerBpm?.toString() ?: "null")
                append(",\"deezerGain\":").append(deezerGain?.toString() ?: "null")
                append(",\"deezerGenre\":").append(jstr(deezerGenre))
                append(",\"djScript\":").append(jstr(djScript))
                append(",\"djWordCount\":").append(wordCount(djScript))
                append(",\"error\":").append(jstr(error))
                append("}")
            }
            out.append(obj).append("\n")
        }
        val moodPlan = listOf("mix", "party", "late_night", "focus", "morning")
        val songsPerMood = 50
        val targetSongs = moodPlan.size * songsPerMood // 250

        val partsOfDay = listOf(
            Pair("morning", "07:40"),
            Pair("afternoon", "14:10"),
            Pair("evening", "18:30"),
            Pair("night", "23:15"),
        )

        var prevSong: Song? = null
        var blockIndex = 0
        var sidekickRot = 0
        var beatK = 0

        var banterSamples = 0
        var triviaSamples = 0
        var cueSamples = 0
        var handoverSamples = 0
        var goodThingSamples = 0
        val rareTarget = 16

        writeUtf8(corpusFile, "")  // truncate any prior corpus before appending
        try {
            outer@ while (songCount < targetSongs && !quotaExhausted) {
                val moodIdx = (songCount / songsPerMood).coerceAtMost(moodPlan.size - 1)
                val mood = moodPlan[moodIdx]
                rolling.setMood(if (mood == "mix") null else mood)

                val songIntoMood = songCount - moodIdx * songsPerMood
                val (partOfDay, timeStr) = partsOfDay[blockIndex % partsOfDay.size]
                val calendarNote = if (mood == "morning" && songIntoMood in 10..18) "ערב שבת" else null
                val somber = mood == "late_night" && songIntoMood in 20..28
                val recapBrief = if (mood == "focus" && songIntoMood < 2)
                    "השבוע האזנת ל-142 שירים, 11 שעות מוזיקה, האמן המוביל היה Avicii עם 9 השמעות, וגילית 6 שירים חדשים." else null
                val weather = if (blockIndex % 4 == 0) "18 מעלות, שמיים בהירים" else null
                val generalHeadline = if (blockIndex % 5 == 0) "ממשלת ישראל אישרה תקציב חדש לתחבורה ציבורית" else null
                val topicHeadlines = if (blockIndex % 6 == 0) mapOf("ספורט" to "מכבי תל אביב ניצחה אמש 92-88") else emptyMap()

                val ctx = DjContext(
                    timeStr = timeStr, partOfDay = partOfDay, weather = weather,
                    generalHeadline = generalHeadline, topicHeadlines = topicHeadlines,
                    mood = if (mood == "mix") null else mood,
                    calendarNote = calendarNote, somber = somber, recapBrief = recapBrief,
                )

                val nInBlock = if (blockIndex % 3 == 0) 1 else 2
                val songs = try {
                    rolling.nextSongs(nInBlock, seed = prevSong)
                } catch (e: Exception) { emptyList() }
                if (songs.isEmpty()) {
                    emptyPicks += 1
                    blockIndex += 1
                    if (blockIndex > targetSongs * 2) break
                    continue
                }

                for ((idxInBlock, song) in songs.withIndex()) {
                    if (songCount >= targetSongs) break@outer

                    val feat = try { deezerFeatures(http, song) } catch (e: Exception) { null }
                    if (feat == null) deezerMisses += 1 else {
                        deezerHits += 1
                        if (feat.bpm != null) bpmKnown += 1
                    }

                    val isOpening = blockIndex == 0 && idxInBlock == 0
                    val prev = prevSong
                    val talkResult: TalkResult = try {
                        when {
                            // RECAP OPENING (feature 12): the renderer calls writeRecapOpening
                            // whenever the snapshot carries a recapBrief. The recapBrief chunk
                            // is mid-broadcast (focus mood), never block 0, so this must NOT be
                            // gated on isOpening or the recap beat would never fire.
                            recapBrief != null && idxInBlock == 0 ->
                                TalkResult("recap", brain.writeRecapOpening(song, ctx, 20.0))
                            isOpening ->
                                TalkResult("open", brain.writeOpening(song, ctx, 10.0))
                            somber ->
                                TalkResult("song", brain.writeIntro(prev, song, 9.0, ctx))
                            calendarNote != null && idxInBlock == 0 ->
                                TalkResult("song", brain.writeIntro(prev, song, 9.0, ctx, allowTasteWink = song.tasteRank != null))
                            handoverSamples < rareTarget && ctx.partOfDay != null && idxInBlock == 0 -> {
                                handoverSamples += 1
                                TalkResult("handover", brain.writeHandover(ctx, song))
                            }
                            banterSamples < rareTarget -> {
                                banterSamples += 1
                                val turns = brain.writeBanter(prev, song, ctx, 14.0, sidekickIndex = sidekickRot)
                                sidekickRot += 1
                                if (turns.isEmpty()) TalkResult("banter", null, skipped = true)
                                else TalkResult("banter", turns.joinToString(" ") { "${it.first}: ${it.second}" })
                            }
                            triviaSamples < rareTarget -> {
                                triviaSamples += 1
                                val turns = brain.writeTrivia(song, ctx)
                                if (turns.isEmpty()) TalkResult("trivia", null, skipped = true)
                                else TalkResult("trivia", turns.joinToString(" ") { "${it.first}: ${it.second}" })
                            }
                            cueSamples < rareTarget -> {
                                cueSamples += 1
                                val cue = brain.writeListeningCue(song, ctx)
                                TalkResult("cue", cue.ifEmpty { null }, skipped = cue.isEmpty())
                            }
                            goodThingSamples < rareTarget -> {
                                goodThingSamples += 1
                                val g = brain.writeGoodThing(ctx, song)
                                TalkResult("good_thing", g.ifEmpty { null }, skipped = g.isEmpty())
                            }
                            weather != null && beatK % 4 == 1 -> {
                                beatK += 1
                                TalkResult("weather", brain.writeBreak(prev, song, "weather", ctx, 9.0))
                            }
                            generalHeadline != null && beatK % 4 == 2 -> {
                                beatK += 1
                                TalkResult("news", brain.writeBreak(prev, song, "news", ctx, 9.0))
                            }
                            else -> {
                                beatK += 1
                                val wink = song.tasteRank != null && beatK % 3 == 0
                                TalkResult("song", brain.writeIntro(prev, song, 9.0, ctx, allowTasteWink = wink))
                            }
                        }
                    } catch (e: Exception) {
                        val msg = e.message ?: e.toString()
                        if (isQuota(msg)) quotaExhausted = true
                        geminiFailures += 1
                        TalkResult("song", null, error = msg)
                    }

                    emit(
                        blockIndex = blockIndex, mood = mood, ctx = ctx,
                        beat = talkResult.beat, song = song, djScript = talkResult.script,
                        error = talkResult.error,
                        deezerBpm = feat?.bpm, deezerGain = feat?.gain, deezerGenre = feat?.genre,
                    )
                    songCount += 1
                    perMood[mood] = (perMood[mood] ?: 0) + 1
                    if (song.tasteRank == null) discoveryCount += 1
                    prevSong = song

                    if (out.length > 200_000) { appendUtf8(corpusFile, out.toString()); out.setLength(0) }
                    if (quotaExhausted) break@outer
                }
                blockIndex += 1
            }
        } finally {
            if (out.isNotEmpty()) appendUtf8(corpusFile, out.toString())
            http.close()
        }
        val wallMs = System.currentTimeMillis() - startMs
        val discoveryRate = if (songCount > 0) discoveryCount.toDouble() / songCount else 0.0
        val bpmCoverage = if (deezerHits > 0) bpmKnown.toDouble() / deezerHits else 0.0
        val summary = buildString {
            append("{\n")
            append("  \"totalSongs\": ").append(songCount).append(",\n")
            append("  \"totalItems\": ").append(seq).append(",\n")
            append("  \"targetSongs\": ").append(targetSongs).append(",\n")
            append("  \"perMood\": ").append(intMapJson(perMood)).append(",\n")
            append("  \"perBeat\": ").append(intMapJson(perBeat)).append(",\n")
            append("  \"discoveryCount\": ").append(discoveryCount).append(",\n")
            append("  \"discoveryRate\": ").append(round4(discoveryRate)).append(",\n")
            append("  \"geminiFailures\": ").append(geminiFailures).append(",\n")
            append("  \"quotaExhausted\": ").append(quotaExhausted).append(",\n")
            append("  \"deezerHits\": ").append(deezerHits).append(",\n")
            append("  \"deezerMisses\": ").append(deezerMisses).append(",\n")
            append("  \"deezerBpmKnown\": ").append(bpmKnown).append(",\n")
            append("  \"deezerBpmCoverage\": ").append(round4(bpmCoverage)).append(",\n")
            append("  \"emptyPicks\": ").append(emptyPicks).append(",\n")
            append("  \"wallClockMs\": ").append(wallMs).append("\n")
            append("}\n")
        }
        writeUtf8(summaryFile, summary)
        println("[corpus] wrote ${corpusFile.absolutePath} ($seq items, $songCount songs) in ${wallMs}ms")
    }

    private class TalkResult(
        val beat: String,
        val script: String?,
        val error: String? = null,
        val skipped: Boolean = false,
    )

    private class DeezerFeat(val bpm: Double?, val gain: Double?, val genre: String?)

    private suspend fun deezerFeatures(http: HttpClient, song: Song): DeezerFeat? {
        val q = "${song.artist} ${song.title}"
        val body = try {
            withTimeout(10_000L) {
                http.get("https://api.deezer.com/search") { url { parameters.append("q", q) } }.bodyAsText()
            }
        } catch (e: Exception) { return null }
        val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null } ?: return null
        val first = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val bpm = (first["bpm"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0.0 }
        val gain = (first["gain"] as? JsonPrimitive)?.doubleOrNull
        val genre = (first["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return DeezerFeat(bpm = bpm, gain = gain, genre = genre)
    }
    // Faithful inline copy of app/.../DeezerDiscovery.kt (same algorithm). It is
    // a :app class, not importable from a :station test; the logic depends only
    // on :station DeezerParse.kt helpers + Ktor, both on this classpath.
    private class HarnessDeezerDiscovery(
        private val http: HttpClient,
        private val rng: kotlin.random.Random = kotlin.random.Random.Default,
        private val recentArtistMemory: Int = 10,
    ) : DiscoverySource {
        private val idCache = ConcurrentHashMap<String, Long>()
        private val relatedCache = ConcurrentHashMap<Long, List<Pair<Long, String>>>()
        private val recentDiscoveryArtists = ArrayDeque<String>()
        @Volatile private var lastSeedLower: String? = null

        override suspend fun discover(
            taste: TasteProfile, excludeKeys: Set<String>, excludeArtists: Set<String>,
        ): Song? {
            val seeds = (taste.topArtists + taste.topTracks.map { it.artist })
                .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            if (seeds.isEmpty()) return null
            val excludeArtistsLower = excludeArtists.map { it.trim().lowercase() }.toSet()
            val tasteArtistsLower = seeds.map { it.lowercase() }.toSet()
            val last = lastSeedLower
            val attempts = seeds.shuffled(rng).sortedBy { it.lowercase() == last }.take(MAX_ATTEMPTS)
            for (seed in attempts) {
                val song = try {
                    discoverFromSeed(seed, excludeKeys, excludeArtistsLower, tasteArtistsLower)
                } catch (e: Exception) { null }
                if (song != null) {
                    lastSeedLower = seed.lowercase()
                    rememberDiscoveryArtist(song.artist)
                    return song
                }
            }
            return null
        }

        private suspend fun discoverFromSeed(
            seedName: String, excludeKeys: Set<String>,
            excludeArtistsLower: Set<String>, tasteArtistsLower: Set<String>,
        ): Song? {
            val seedId = resolveArtistId(seedName) ?: return null
            val related = relatedArtists(seedId)
                .filter { (_, name) -> name.trim().lowercase() !in excludeArtistsLower }
            if (related.isEmpty()) return null
            val recent = synchronized(recentDiscoveryArtists) { recentDiscoveryArtists.toSet() }
            val unmined = related.filter { (_, name) -> name.trim().lowercase() !in recent }.ifEmpty { related }
            val nonTaste = unmined.filter { (_, name) -> name.trim().lowercase() !in tasteArtistsLower }
            val (relatedId, _) = nonTaste.ifEmpty { unmined }.random(rng)
            val body = getText("$API_BASE/artist/$relatedId/top", mapOf("limit" to "10")) ?: return null
            return parseDeezerTopTracks(body).firstOrNull { s ->
                baseTitle(s.title) !in excludeKeys && s.artist.trim().lowercase() !in excludeArtistsLower
            }
        }

        private fun rememberDiscoveryArtist(artist: String) {
            val key = artist.trim().lowercase()
            if (key.isEmpty()) return
            synchronized(recentDiscoveryArtists) {
                recentDiscoveryArtists.remove(key)
                recentDiscoveryArtists.addLast(key)
                while (recentDiscoveryArtists.size > recentArtistMemory) recentDiscoveryArtists.removeFirst()
            }
        }

        private suspend fun resolveArtistId(name: String): Long? {
            val key = name.lowercase()
            idCache[key]?.let { return it }
            val body = getText("$API_BASE/search/artist", mapOf("q" to name)) ?: return null
            val id = parseDeezerArtistId(body) ?: return null
            idCache[key] = id
            return id
        }

        private suspend fun relatedArtists(artistId: Long): List<Pair<Long, String>> {
            relatedCache[artistId]?.let { return it }
            val body = getText("$API_BASE/artist/$artistId/related") ?: return emptyList()
            val related = parseDeezerRelatedArtists(body)
            if (related.isNotEmpty()) relatedCache[artistId] = related
            return related
        }

        private suspend fun getText(base: String, params: Map<String, String> = emptyMap()): String? =
            try {
                withTimeout(FETCH_TIMEOUT_MS) {
                    http.get(base) { url { params.forEach { (k, v) -> parameters.append(k, v) } } }.bodyAsText()
                }
            } catch (e: Exception) { null }

        private companion object {
            const val API_BASE = "https://api.deezer.com"
            const val FETCH_TIMEOUT_MS = 10_000L
            const val MAX_ATTEMPTS = 3
        }
    }
    private fun parseEnv(f: File): Map<String, String> {
        if (!f.exists()) return emptyMap()
        val map = HashMap<String, String>()
        for (raw in f.readLines(Charsets.UTF_8)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq).trim()
            var v = line.substring(eq + 1).trim()
            if (v.length >= 2 && ((v.first() == '"' && v.last() == '"') || (v.first() == '\'' && v.last() == '\''))) {
                v = v.substring(1, v.length - 1)
            }
            map[k] = v
        }
        return map
    }

    private fun isQuota(msg: String): Boolean {
        val m = msg.lowercase()
        return "429" in m || "quota" in m || "resource_exhausted" in m || ("rate" in m && "limit" in m)
    }

    private fun wordCount(s: String?): Int =
        s?.trim()?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0

    private fun jstr(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun intMapJson(m: Map<String, Int>): String =
        m.entries.joinToString(", ", "{", "}") { "${jstr(it.key)}: ${it.value}" }

    private fun round4(d: Double): String = String.format("%.4f", d)

    private fun appendUtf8(f: File, text: String) {
        f.parentFile?.mkdirs()
        f.appendText(text, Charsets.UTF_8)
    }

    private fun writeUtf8(f: File, text: String) {
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
    }
}