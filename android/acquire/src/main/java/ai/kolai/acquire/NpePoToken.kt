package ai.kolai.acquire

import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider as NpePoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult as NpePoTokenResult

/**
 * Bridges our pure-JVM [PoTokenSource] onto NewPipeExtractor's
 * `PoTokenProvider` interface, which `YoutubeStreamExtractor.setPoTokenProvider`
 * consumes.
 *
 * EXTRACTOR-API REALITY (verified against the NewPipeExtractor v0.26.2 artifact):
 * `YoutubeStreamExtractor.onFetchPage()` requests poTokens for the STREAMING
 * clients only via `getAndroidClientPoToken` and (when iOS fetch is enabled)
 * `getIosClientPoToken`. The web/embed methods exist on the interface but are NOT
 * consumed for stream extraction in 0.26.x (the web client is metadata-only).
 *
 * A WebView+BotGuard mints a *web-client* token. Routing a web token into the
 * android/ios player request risks YouTube rejecting the response and DEGRADING
 * today's guaranteed muxed floor, so by default we expose the token through the
 * web methods (semantically correct, floor-preserving) and return null for the
 * android/ios methods. On 0.26.2 this is a harmless no-op for streams; it becomes
 * a real adaptive-audio unlock the moment the extractor consumes web poTokens for
 * the tvHtml5/web streaming client (the path present in <= v0.25.0, removed in
 * 0.25.1). See [NewPipeSource] KDoc for the full version analysis.
 *
 * To let on-device experiments try the android mapping WITHOUT touching wiring,
 * [mapToAndroidClient] flips the same token onto getAndroidClientPoToken. Leave it
 * false unless deliberately testing -- it can regress the floor.
 *
 * Each per-call hop bridges the suspend [PoTokenSource] with [runBlocking]; the
 * extractor calls these methods synchronously on its fetch thread.
 */
internal class NewPipePoTokenAdapter(
    private val source: PoTokenSource,
    private val mapToAndroidClient: Boolean = false,
    private val log: (String) -> Unit = {},
) : NpePoTokenProvider {

    private fun fetch(videoId: String): NpePoTokenResult? =
        toNpeResult(log) { runBlocking { source.poToken(videoId, null) } }

    override fun getWebClientPoToken(videoId: String): NpePoTokenResult? = fetch(videoId)

    override fun getWebEmbedClientPoToken(videoId: String): NpePoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): NpePoTokenResult? =
        if (mapToAndroidClient) fetch(videoId) else null

    override fun getIosClientPoToken(videoId: String): NpePoTokenResult? = null

    internal companion object {
        /**
         * Pure mapping: run [fetch], convert [PoTokens] -> NewPipe `PoTokenResult`,
         * swallow ALL failures to null. Extracted so the null / value / throwing
         * cases are unit-testable without a WebView or network.
         */
        fun toNpeResult(
            log: (String) -> Unit = {},
            fetch: () -> PoTokens?,
        ): NpePoTokenResult? = try {
            val t = fetch()
            // The extractor's PoTokenResult requires a NON-NULL visitorData and
            // playerRequestPoToken (streamingDataPoToken may be null). Without a
            // visitorData the token is unusable, so fall back (return null).
            val vd = t?.visitorData
            if (t == null || vd == null) {
                if (t != null) log("KolaiAcquire: poToken missing visitorData -- fallback")
                null
            } else {
                NpePoTokenResult(vd, t.playerRequestPoToken, t.streamingDataPoToken)
            }
        } catch (e: Throwable) {
            log("KolaiAcquire: poToken fetch failed (${e.javaClass.simpleName}: ${e.message}) -- fallback")
            null
        }
    }
}