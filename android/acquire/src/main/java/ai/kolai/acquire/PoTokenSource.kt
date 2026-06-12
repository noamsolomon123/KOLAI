package ai.kolai.acquire

/**
 * Session-bound YouTube "proof-of-origin" tokens (PoTokens), mirroring
 * NewPipeExtractor's `org.schabi.newpipe.extractor.services.youtube.PoTokenResult`
 * field-for-field. YouTube's 2026 BotGuard wall withholds adaptive AUDIO-ONLY
 * formats (128-160 kbps m4a/opus) unless the player request carries a valid
 * poToken; without one the extractor only gets the lone muxed itag-18 (~360p mp4,
 * ~96 kbps AAC track). Supplying these tokens is what unlocks adaptive audio.
 *
 * @property visitorData          the visitor data the [streamingDataPoToken] is
 *                                bound to; may be null.
 * @property playerRequestPoToken token sent with the innertube *player* request
 *                                (bound to the videoId).
 * @property streamingDataPoToken token appended as `&pot=` to each stream URL
 *                                (bound to [visitorData]).
 */
data class PoTokens(
    val visitorData: String?,
    val playerRequestPoToken: String,
    val streamingDataPoToken: String,
)

/**
 * Source of YouTube [PoTokens]. The :acquire module is pure JVM and CANNOT run a
 * WebView, so the real implementation lives in :app (`WebViewPoTokenGenerator`)
 * and is injected into [NewPipeSource] through this interface. Keeping the
 * contract here keeps :acquire decoupled from Android.
 *
 * Implementations MUST be defensive: return null (never throw) on any failure --
 * timeout, broken WebView, BotGuard error -- so [NewPipeSource] can fall back to
 * the guaranteed muxed-360p floor.
 */
interface PoTokenSource {
    /**
     * Produce tokens for [videoId]. Returns null on ANY failure so the caller
     * falls back to the muxed path.
     *
     * @param videoId     the 11-char YouTube id (BotGuard binds the player token
     *                    to it).
     * @param visitorData optional pinned visitor data; null lets the impl mint /
     *                    reuse its own.
     */
    suspend fun poToken(videoId: String, visitorData: String?): PoTokens?
}