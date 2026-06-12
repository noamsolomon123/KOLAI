package ai.kolai.acquire

import ai.kolai.core.Song
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.security.MessageDigest

/**
 * On-device YouTube search + audio download, replacing the Python `yt-dlp`
 * fetcher (`backend/radioai/fetcher.py`). Task 0.4 / 2.2 spike.
 *
 * Flow (mirrors the Python fetcher 1:1 where it matters):
 *   1. `NewPipe.init(NewPipeDownloader)` once (lazy, idempotent).
 *   2. cache-key = sha1("{artist}-{title}".lower())[:16]  -> cache hit returns now.
 *   3. search query = `song.query ?: "${song.artist} ${song.title}"`.
 *   4. YouTube search -> map StreamInfoItems -> [Candidate] -> [pickBestCandidate].
 *   5. resolve the winner -> highest-bitrate [AudioStream].
 *   6. download that stream URL to `cacheDir/<key>.<ext>` and return the path.
 *
 * STANDALONE BY DESIGN: implements no cross-module interface (e.g. :station's
 * AudioFetcher). The app layer adapts this later, so :acquire stays decoupled.
 *
 * Throws a clear [FetchException] on every failure mode (no candidate / no audio
 * stream / download failure) so the caller (BlockRenderer) can skip the song,
 * exactly like the Python try/except.
 *
 * SPIKE FINDING (2026): on CPH2747 the search + stream-metadata extraction work,
 * but `getAudioStreams()` comes back EMPTY -- YouTube withholds playable adaptive
 * formats without a valid PO-token (BotGuard). See [StreamDiagnostics] /
 * [diagnose] which the test uses to capture the exact symptom.
 *
 * ADAPTIVE-AUDIO via PoToken (2026): pass a [poTokenSource] to register a
 * BotGuard PoToken provider with the extractor; the muxed itag-18 path remains
 * the GUARANTEED floor on any failure. IMPORTANT version caveat verified against
 * the NewPipeExtractor v0.26.2 artifact: `YoutubeStreamExtractor.onFetchPage()`
 * requests poTokens ONLY for the android/ios streaming clients
 * (`getAndroidClientPoToken` / `getIosClientPoToken`); the web client is
 * metadata-only. A WebView+BotGuard mints a *web* token, so on 0.26.2 the
 * registered web token is a harmless no-op for streams (floor preserved). The
 * web-token streaming path (`getWebClientPoToken` -> tvHtml5 streaming data)
 * existed in NewPipeExtractor <= v0.25.0 and was removed in 0.25.1. See the
 * report / [NewPipePoTokenAdapter] for the upgrade recommendation.
 */
class NewPipeSource(
    private val client: OkHttpClient = NewPipeDownloader.defaultClient(),
    // FETCH RESILIENCE: transient (IOException-caused) failures of the three
    // network steps (search / stream-resolve / download) retry with a short
    // backoff before surfacing. Deterministic failures (verified no-candidate,
    // PO-token wall, HTTP error codes) never retry -- see [TransientRetry].
    // Injectable for instant unit tests.
    private val transientRetries: Int = TransientRetry.DEFAULT_MAX_RETRIES,
    private val retryBackoffMs: LongArray = TransientRetry.DEFAULT_BACKOFF_MS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    // ADAPTIVE-AUDIO seam: when present, a PoToken provider is registered with the
    // YouTube extractor so it can request the BotGuard-gated adaptive audio-only
    // formats. Null (the default) preserves byte-identical legacy behaviour: no
    // provider is registered and the muxed itag-18 path is used. See class KDoc /
    // [NewPipePoTokenAdapter] for the v0.26.2 extractor-API caveat.
    private val poTokenSource: PoTokenSource? = null,
) {

    // Guards the one-time global registration of the static PoToken provider.
    @Volatile
    private var poTokenRegistered = false

    /**
     * Register [poTokenSource] (if any) with the YouTube extractor exactly once.
     * No-op when null -> the extractor keeps its null provider and behaviour is
     * byte-identical to before this feature. Defensive: a registration failure is
     * logged and swallowed (the muxed floor still works).
     */
    @Synchronized
    private fun ensurePoTokenRegistered() {
        if (poTokenRegistered) return
        poTokenRegistered = true
        val src = poTokenSource ?: return
        try {
            YoutubeStreamExtractor.setPoTokenProvider(
                NewPipePoTokenAdapter(src, log = ::logW),
            )
            logI("PoToken provider registered (adaptive-audio attempt enabled)")
        } catch (e: Throwable) {
            logW("PoToken provider registration failed: ${e.message} -- muxed fallback only")
        }
    }

    /** Failure of any search/extract/download step; caller skips the song. */
    class FetchException(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)

    /** Transient-aware retry around one named network step. */
    private fun <T> retryTransient(what: String, block: () -> T): T =
        TransientRetry.run(
            what = what,
            maxRetries = transientRetries,
            backoffMs = retryBackoffMs,
            sleeper = sleeper,
            log = ::logW,
            block = block,
        )

    /**
     * Resolve [song] to a local audio file path, downloading if not cached.
     *
     * @throws FetchException on no-candidate / no-audio-stream / download failure.
     */
    fun fetch(song: Song, cacheDir: File): String {
        ensureInit()
        ensurePoTokenRegistered()
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val key = cacheKey(song)

        // Cache hit: any file named "<key>.*" already downloaded. (Extension is
        // determined by the resolved stream, so we glob on the stem.)
        cacheDir.listFiles { f -> f.isFile && f.name.startsWith("$key.") }
            ?.firstOrNull { it.length() > 0L }
            ?.let { return it.absolutePath }

        val query = song.query?.takeIf { it.isNotBlank() }
            ?: "${song.artist} ${song.title}"

        val watchUrl = retryTransient("search '$query'") { resolveWatchUrl(song, query) }
        val picked = retryTransient("stream resolve for '$query'") { resolveBestStream(watchUrl) }

        val streamUrl = picked.url
            ?: throw FetchException("stream for '$query' has no URL/content")
        val outFile = File(cacheDir, "$key.${picked.ext}")

        retryTransient("download '$query'") { downloadTo(streamUrl, outFile, query) }
        return outFile.absolutePath
    }

    /**
     * Resolve a [song] only to its best-candidate watch URL (search side of the
     * pipeline). Exposed so the spike test can prove SEARCH works even when the
     * download side is blocked by the PO-token wall.
     */
    fun resolveSearchUrl(song: Song): String {
        ensureInit()
        val query = song.query?.takeIf { it.isNotBlank() }
            ?: "${song.artist} ${song.title}"
        return retryTransient("search '$query'") { resolveWatchUrl(song, query) }
    }

    /** Per-client / per-format stream counts for a watch URL (diagnostics). */
    data class StreamDiagnostics(
        val watchUrl: String,
        val audioCount: Int,
        val videoCount: Int,
        val videoOnlyCount: Int,
    )

    /**
     * Probe a watch URL and report how many streams of each kind YouTube handed
     * back. All-zero audio (+ usually all-zero video) is the PO-token wall.
     */
    fun diagnose(watchUrl: String): StreamDiagnostics {
        ensureInit()
        ensurePoTokenRegistered()
        val extractor = ServiceList.YouTube.getStreamExtractor(watchUrl)
        extractor.fetchPage()
        return StreamDiagnostics(
            watchUrl = watchUrl,
            audioCount = extractor.audioStreams?.size ?: 0,
            videoCount = extractor.videoStreams?.size ?: 0,
            videoOnlyCount = extractor.videoOnlyStreams?.size ?: 0,
        )
    }

    // ---- search ----------------------------------------------------------

    private fun resolveWatchUrl(song: Song, query: String): String {
        // Honor a pinned watch URL exactly like the Python fetcher.
        if (query.contains("youtube.com") || query.contains("youtu.be")) {
            return query
        }

        val items: List<StreamInfoItem> = try {
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
        } catch (e: Exception) {
            throw FetchException(
                "search failed for '$query': ${e.javaClass.simpleName}: ${e.message}",
                e,
            )
        }

        val candidates = items.map { item ->
            Candidate(
                title = item.name ?: "",
                durationS = item.duration.takeIf { it > 0 }?.toDouble(),
                viewCount = item.viewCount.takeIf { it >= 0 },
                id = item.url,
            )
        }

        val best = pickBestCandidate(song, candidates)
        if (best == null) {
            if (candidates.isEmpty()) {
                throw FetchException(
                    "no candidate: search returned nothing for " +
                        "'${song.artist} - ${song.title}' (query='$query')",
                )
            }
            // Results existed but none matched the requested title: this is the
            // hallucinated/unfindable-song path (verified pick rejected them all).
            val top3 = candidates
                .sortedByDescending { candidateScore(song, it) }
                .take(3)
                .joinToString(" | ") { it.title }
            logW(
                "rejected all ${candidates.size} results for " +
                    "'${song.artist} - ${song.title}' -- none matched the requested " +
                    "title (probable hallucinated/unfindable song); top-3: $top3",
            )
            throw FetchException(
                "no candidate: ${candidates.size} results for '${song.artist} - ${song.title}' " +
                    "(query='$query') but none matched the requested title " +
                    "(probable hallucinated/unfindable song)",
            )
        }
        logI(
            "picked '${best.title}' " +
                "(score=${"%.1f".format(candidateScore(song, best))}) " +
                "for '${song.artist} - ${song.title}'",
        )

        // candidate.id is the StreamInfoItem url (already a full watch URL).
        return if (best.id.startsWith("http")) {
            best.id
        } else {
            "https://www.youtube.com/watch?v=${best.id}"
        }
    }

    // ---- stream resolution ----------------------------------------------

    /** A chosen downloadable stream: its URL, file extension, and whether it is audio-only. */
    private data class PickedStream(val url: String?, val ext: String, val audioOnly: Boolean)

    /**
     * Resolve the best DOWNLOADABLE stream for a watch URL.
     *
     * Preference order:
     *   1. highest-bitrate ADAPTIVE audio-only stream (best quality, smallest file);
     *   2. FALLBACK: highest-resolution MUXED progressive (video+audio) stream.
     *
     * The fallback matters in 2026: without a PO-token YouTube hands back ZERO
     * adaptive audio streams but STILL serves the one legacy muxed itag-18 (~360p
     * mp4, which carries an AAC audio track). AudioDecoder.decodeToPcm reads that
     * audio track fine, so we can still get usable audio -- just at lower quality
     * and a larger download than an audio-only stream.
     */
    private fun resolveBestStream(watchUrl: String): PickedStream {
        val extractor = try {
            val ex = ServiceList.YouTube.getStreamExtractor(watchUrl)
            ex.fetchPage()
            ex
        } catch (e: Exception) {
            // This is where the 2026 bot-detection wall can also surface:
            // "sign in to confirm you're not a bot" / ContentNotAvailableException.
            throw FetchException(
                "stream extraction failed for '$watchUrl' " +
                    "(possible bot-detection / PO-token wall): " +
                    "${e.javaClass.simpleName}: ${e.message}",
                e,
            )
        }

        // Map NewPipe streams onto the pure, unit-tested selector. The adaptive
        // audio-only list is non-empty only when a valid PoToken unlocked it;
        // otherwise it is empty and we fall through to the muxed itag-18 floor.
        val audioOptions = (extractor.audioStreams ?: emptyList<AudioStream>()).map { a ->
            StreamSelection.Option(
                url = a.content,
                ext = a.format?.suffix?.takeIf { it.isNotBlank() } ?: "m4a",
                bitrate = a.averageBitrate,
            )
        }
        val muxedOptions = (extractor.videoStreams ?: emptyList<VideoStream>()).map { v ->
            StreamSelection.Option(
                url = v.content,
                ext = v.format?.suffix?.takeIf { it.isNotBlank() } ?: "mp4",
                bitrate = v.bitrate,
            )
        }

        val choice = StreamSelection.chooseStream(audioOptions, muxedOptions)
            ?: throw FetchException(
                "no audio or muxed streams for '$watchUrl' -- this is the PO-token / " +
                    "bot-detection wall (empty adaptive formats AND no progressive fallback).",
            )

        if (choice.audioOnly) {
            val kbps = (audioOptions.maxOfOrNull { it.bitrate } ?: 0) / 1000
            logI("adaptive ${kbps}kbps audio-only via PoToken for '$watchUrl'")
        } else {
            logW(
                "fallback muxed-360p for '$watchUrl' " +
                    "(no adaptive audio -- PoToken absent/empty or extractor did not consume it)",
            )
        }
        return PickedStream(url = choice.url, ext = choice.ext, audioOnly = choice.audioOnly)
    }

    // ---- download --------------------------------------------------------

    private fun downloadTo(url: String, outFile: File, query: String) {
        val tmp = File(outFile.parentFile, "${outFile.name}.part")
        try {
            val request = OkRequest.Builder()
                .url(url)
                .header("User-Agent", NewPipeDownloader.USER_AGENT)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw FetchException(
                        "download HTTP ${resp.code} for '$query' " +
                            "(${if (resp.code == 403) "403 = bot-detection / expired stream URL" else resp.message})",
                    )
                }
                val body = resp.body
                    ?: throw FetchException("empty download body for '$query'")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (tmp.length() <= 0L) {
                throw FetchException("downloaded 0 bytes for '$query'")
            }
            if (outFile.exists()) outFile.delete()
            if (!tmp.renameTo(outFile)) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }
        } catch (e: FetchException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw FetchException("download failed for '$query': ${e.message}", e)
        }
    }

    // android.util.Log is a no-op stub that THROWS on the plain JVM. Today only
    // androidTest exercises this class (CandidateScoringTest tests pure
    // functions), but guard anyway so a future JVM unit test cannot crash on a
    // log line.
    private fun logI(msg: String) {
        try { android.util.Log.i(LOG_TAG, msg) } catch (_: Throwable) { }
    }

    private fun logW(msg: String) {
        try { android.util.Log.w(LOG_TAG, msg) } catch (_: Throwable) { }
    }

    private companion object {
        const val LOG_TAG = "KolaiAcquire"

        @Volatile
        private var initialized = false

        @Synchronized
        fun ensureInit() {
            if (initialized) return
            NewPipe.init(NewPipeDownloader())
            initialized = true
        }

        /** sha1("{artist}-{title}".lower())[:16] -- identical to the Python fetcher. */
        fun cacheKey(song: Song): String {
            val key = "${song.artist}-${song.title}".lowercase()
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(key.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
        }
    }
}