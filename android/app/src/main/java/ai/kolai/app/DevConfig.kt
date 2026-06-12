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
) {
    companion object {
        private const val ASSET = "kolai_dev.properties"

        /**
         * Load + validate the dev config from assets. Throws a clear error if the
         * asset is missing (build forgot to drop kolai_dev.properties) or has no
         * usable keys, so misconfiguration fails loudly rather than silently
         * producing an engine with zero keys.
         */
        fun load(context: Context): DevConfig {
            val props = Properties()
            context.assets.open(ASSET).use { props.load(it) }

            val keys = listOfNotNull(
                props.getProperty("gemini.key.1"),
                props.getProperty("gemini.key.2"),
                props.getProperty("gemini.key.3"),
            ).map { it.trim() }.filter { it.isNotEmpty() }

            require(keys.isNotEmpty()) {
                "kolai_dev.properties has no gemini.key.* values"
            }

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
            )
        }
    }
}