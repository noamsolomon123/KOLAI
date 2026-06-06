package ai.kolai.acquire

import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed implementation of NewPipe's [Downloader] (Task 2.2 spike).
 *
 * NewPipe's extractor is transport-agnostic: it builds [Request]s (method,
 * headers, body) and delegates the actual HTTP to whatever [Downloader] is
 * registered via `NewPipe.init(...)`. This bridges those requests onto a single
 * shared [OkHttpClient].
 *
 * A desktop-ish User-Agent is forced on every request: YouTube's "innertube"
 * endpoints behave very differently (and are far more likely to hand back
 * playable progressive/adaptive audio streams) when they think they are talking
 * to a real desktop browser rather than a generic Java client. This is the same
 * trick yt-dlp / NewPipe use; it is the first line of defence against the 2026
 * bot-detection wall (the PO-token escalation is the second).
 *
 * Self-contained on purpose: no cross-module coupling, no DI framework. The app
 * adapts [NewPipeSource] (which owns this) to `:station`'s AudioFetcher later.
 */
class NewPipeDownloader(
    private val client: OkHttpClient = defaultClient(),
) : Downloader() {

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend: ByteArray? = request.dataToSend()

        val requestBody = dataToSend?.toRequestBody(null, 0, dataToSend.size)

        val builder = OkRequest.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        // Copy NewPipe-supplied headers verbatim. NewPipe sends each value as a
        // list (HTTP multi-value semantics); replace-then-add so a NewPipe header
        // wins over our default but multiple values are preserved.
        for ((name, values) in headers) {
            if (values.size > 1) {
                builder.removeHeader(name)
                for (value in values) {
                    builder.addHeader(name, value)
                }
            } else if (values.size == 1) {
                builder.header(name, values[0])
            } else {
                builder.removeHeader(name)
            }
        }

        client.newCall(builder.build()).execute().use { okResponse ->
            val responseBodyString = okResponse.body?.string()
            return Response(
                okResponse.code,
                okResponse.message,
                okResponse.headers.toMultimap(),
                responseBodyString,
                okResponse.request.url.toString(),
            )
        }
    }

    companion object {
        /**
         * Desktop Chrome UA. Kept current-ish; an obviously stale/odd UA is one of
         * the signals YouTube uses to gate the "sign in to confirm you're not a
         * bot" path. Bump if streams start coming back empty.
         */
        const val USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                // Some endpoints 301/302 to a different host for the actual media;
                // follow them so the stream download resolves.
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
    }
}