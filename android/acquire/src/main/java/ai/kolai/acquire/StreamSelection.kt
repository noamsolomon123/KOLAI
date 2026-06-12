package ai.kolai.acquire

/**
 * Pure, Android-free stream-preference logic, factored out of [NewPipeSource] so
 * it can be unit-tested on the JVM with fake stream lists (no network, no
 * NewPipe types).
 *
 * Preference order (the audio-fidelity ladder):
 *   1. highest-bitrate ADAPTIVE audio-only stream  -> best quality, smallest file
 *      (128-160 kbps m4a/opus, unlocked by a valid PoToken);
 *   2. FALLBACK: highest-bitrate MUXED progressive (video+audio) stream
 *      (the token-free itag-18 ~360p mp4 whose AAC track AudioDecoder can read).
 *
 * The link to PoTokens is indirect but total: WITHOUT a PoToken YouTube hands the
 * extractor an EMPTY adaptive-audio list, so [chooseStream] falls through to the
 * muxed branch (today's guaranteed floor). WITH a valid PoToken the adaptive list
 * is populated, so [chooseStream] returns the audio-only winner. The selection
 * code is identical either way -- only the inputs change.
 */
internal object StreamSelection {

    /** A candidate stream reduced to just what selection needs. */
    data class Option(
        val url: String?,
        val ext: String,
        val bitrate: Int,
    )

    /** The chosen stream plus whether it is audio-only (for logging). */
    data class Choice(
        val url: String?,
        val ext: String,
        val audioOnly: Boolean,
    )

    /**
     * Pick the best stream: highest-bitrate audio-only if any audio-only options
     * exist, else highest-bitrate muxed. Returns null when BOTH lists are empty
     * (the full PoToken / bot-detection wall: no streams at all).
     *
     * Ties / non-positive bitrates fall back to the first element so a stream is
     * still returned rather than dropped.
     */
    fun chooseStream(audioOnly: List<Option>, muxed: List<Option>): Choice? {
        if (audioOnly.isNotEmpty()) {
            val best = audioOnly.maxByOrNull { it.bitrate } ?: audioOnly.first()
            return Choice(url = best.url, ext = best.ext.ifBlank { "m4a" }, audioOnly = true)
        }
        if (muxed.isNotEmpty()) {
            val best = muxed.maxByOrNull { it.bitrate.coerceAtLeast(0) } ?: muxed.first()
            return Choice(url = best.url, ext = best.ext.ifBlank { "mp4" }, audioOnly = false)
        }
        return null
    }
}