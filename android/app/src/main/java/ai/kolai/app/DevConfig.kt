package ai.kolai.app

import android.content.Context
import java.util.Properties

/**
 * DEV-ONLY config loader. Reads the bundled Gemini keys / models / voice from
 * the gitignored assets file `kolai_dev.properties`. This is the on-device
 * counterpart of the `am instrument -e` args the EndToEndBlockTest used: the
 * keys never enter source code, only an asset that is excluded from git.
 *
 * TODO production: on-device key-entry screen (EncryptedSharedPreferences)
 * instead of bundled dev keys. This whole class is the DEV bootstrap and will be
 * replaced by a real key-entry / OAuth flow before any public build.
 */
data class DevConfig(
    val geminiKeys: List<String>,
    val llmModel: String,
    val ttsModel: String,
    val ttsVoice: String,
    /** Second-host TTS voice for two-host banter (`gemini.tts.voice.b`). */
    val ttsVoiceB: String,
    /**
     * OPTIONAL per-mood voice overrides, keyed by mood key
     * (mix / party / late_night / focus / morning), read from the optional
     * `gemini.tts.voice.<mood>` properties. Empty when none are set; a missing
     * key means "use the Moods.spec(mood).voiceName default". Lets us re-tune a
     * mood's voice on-device by editing kolai_dev.properties WITHOUT
     * recompiling Moods.kt.
     *
     * INTEGRATION WAVE: apply these over MoodSpec.voiceName (override wins when
     * present), and thread the resolved voiceName into VoiceRenderer.render at
     * the BlockRenderer call sites.
     */
    val moodVoices: Map<String, String>,
    /**
     * OPTIONAL artist BANLIST (ans.artists, comma-separated). Any song whose
     * artist matches (case-insensitive, trimmed) is NEVER picked -- from the taste
     * pool OR discovery. Empty when unset. Edit kolai_dev.properties to ban/unban
     * without recompiling the picker.
     */
    val bannedArtists: List<String>,
) {
    companion object {
        private const val ASSET = "kolai_dev.properties"

        /** Mood keys that accept a `gemini.tts.voice.<mood>` override. */
        private val MOOD_KEYS = listOf("mix", "party", "late_night", "focus", "morning")

        /**
         * Load + validate the dev config from assets. Throws a clear error if the
         * asset is missing (build forgot to drop kolai_dev.properties) or has no
         * usable keys, so misconfiguration fails loudly rather than silently
         * producing an engine with zero keys.
         */
        fun load(context: Context): DevConfig {
            val props = Properties()
            // UTF-8 reader (2026-06-14 BUG FIX): Properties.load(InputStream)
            // decodes ISO-8859-1, which MANGLED the Hebrew bans.artists value
            // ("טאקי") on read, so the artist ban never matched and a banned
            // artist aired. The Reader overload honors the charset.
            context.assets.open(ASSET).use { stream ->
                java.io.InputStreamReader(stream, Charsets.UTF_8).use { reader -> props.load(reader) }
            }

            val keys = listOfNotNull(
                props.getProperty("gemini.key.1"),
                props.getProperty("gemini.key.2"),
                props.getProperty("gemini.key.3"),
            ).map { it.trim() }.filter { it.isNotEmpty() }

            require(keys.isNotEmpty()) {
                "kolai_dev.properties has no gemini.key.* values"
            }

            // OPTIONAL per-mood voice overrides: only moods with a non-blank
            // gemini.tts.voice.<mood> property land in the map; everything else
            // falls back to the Moods default downstream.
            val moodVoices = MOOD_KEYS.mapNotNull { mood ->
                props.getProperty("gemini.tts.voice.$mood")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { mood to it }
            }.toMap()

            // OPTIONAL artist banlist: comma-separated ans.artists. Trimmed,
            // blanks dropped; lowercase normalization happens at the picker boundary.
            val bannedArtists = (props.getProperty("bans.artists") ?: "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            return DevConfig(
                geminiKeys = keys,
                llmModel = props.getProperty("gemini.llm.model")?.trim()
                    ?: "gemini-3.1-flash-lite-preview",
                ttsModel = props.getProperty("gemini.tts.model")?.trim()
                    ?: "gemini-3.1-flash-tts-preview",
                ttsVoice = props.getProperty("gemini.tts.voice")?.trim() ?: "Algieba",
                // OPTIONAL key: the gitignored properties file does NOT need it
                // (missing/blank -> default), so existing dev installs keep
                // working and banter just uses the stock co-host voice.
                ttsVoiceB = props.getProperty("gemini.tts.voice.b")?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "Iapetus",
                moodVoices = moodVoices,
                bannedArtists = bannedArtists,
            )
        }
    }
}