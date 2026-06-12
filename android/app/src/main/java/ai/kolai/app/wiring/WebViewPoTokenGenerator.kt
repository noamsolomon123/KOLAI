package ai.kolai.app.wiring

import ai.kolai.acquire.PoTokenSource
import ai.kolai.acquire.PoTokens
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Offscreen-WebView BotGuard PoToken generator -- the :app implementation of
 * :acquire's [PoTokenSource]. PORTED (minimal core) from NewPipe's MIT-licensed
 * `PoTokenWebView` / `PoTokenProviderImpl` / `JavaScriptUtil`
 * (github.com/TeamNewPipe/NewPipe, app/src/main/java/.../util/potoken). The
 * BotGuard JS bootstrap lives in `app/src/main/assets/po_token.html` (also from
 * NewPipe, MIT).
 *
 * How it works: an offscreen [WebView] loads `po_token.html`, runs Google's
 * BotGuard interpreter (fetched from youtube.com/api/jnn/v1/Create + GenerateIT),
 * obtains an `integrityToken`, then mints session-bound poTokens via
 * `obtainPoToken(...)`. The integrity token is cached until expiry (~6h cap), so
 * tokens are generated cheaply after the first run.
 *
 * DEFENSIVE BY CONTRACT: every public path is wrapped so it returns null (never
 * throws) on timeout / broken WebView / network error, letting [NewPipeSource]
 * fall back to the guaranteed muxed-360p floor. Hard timeout: [TIMEOUT_MS].
 *
 * THREADING: the WebView is owned by the main thread (all WebView calls hop there
 * via [mainHandler]); the JS-interface callbacks arrive on a WebView binder
 * thread and complete coroutine [CompletableDeferred]s that the caller awaits.
 * [poToken] is suspend and is bridged onto NewPipe's synchronous PoTokenProvider
 * with runBlocking inside :acquire.
 *
 * ON-DEVICE VERIFICATION NEEDED: this whole class is compile-verified only -- a
 * WebView cannot run on a plain JVM. The BotGuard handshake, token validity, and
 * the `visitorData` fetch must be confirmed on the OnePlus 15. See the report.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewPoTokenGenerator(
    private val appContext: Context,
) : PoTokenSource {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    // WebView + BotGuard state (guarded by [lock] for writes; volatile reads).
    @Volatile private var webView: WebView? = null
    @Volatile private var integrityExpiry: Instant? = null
    @Volatile private var cachedVisitorData: String? = null
    @Volatile private var cachedStreamingPot: String? = null

    private var initDeferred: CompletableDeferred<Unit>? = null
    private val tokenEmitters = HashMap<String, CompletableDeferred<String>>()

    /**
     * Mint tokens for [videoId]. Returns null on ANY failure or after
     * [TIMEOUT_MS], so the caller falls back to the muxed path.
     */
    override suspend fun poToken(videoId: String, visitorData: String?): PoTokens? =
        try {
            withTimeoutOrNull(TIMEOUT_MS) {
                ensureInitialized()

                val vd = visitorData ?: cachedVisitorData ?: fetchVisitorData()?.also {
                    cachedVisitorData = it
                }

                // streamingDataPoToken is bound to visitorData and generated once.
                val streamingPot = if (vd != null) {
                    cachedStreamingPot ?: generate(vd).also { cachedStreamingPot = it }
                } else {
                    null
                }

                val playerPot = generate(videoId)

                PoTokens(
                    visitorData = vd,
                    playerRequestPoToken = playerPot,
                    // If we could not obtain a visitorData-bound streaming token,
                    // reuse the player token so the field is non-null; on-device
                    // verification will confirm whether YouTube accepts this.
                    streamingDataPoToken = streamingPot ?: playerPot,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "poToken failed -> muxed fallback: ${t.javaClass.simpleName}: ${t.message}")
            clearPendingEmitters(t)
            null
        }

    private fun isValid(): Boolean {
        val exp = integrityExpiry ?: return false
        return webView != null && Instant.now().isBefore(exp)
    }

    /** (Re)create the WebView and run the BotGuard handshake if needed. */
    private suspend fun ensureInitialized() {
        if (isValid()) return
        val deferred = CompletableDeferred<Unit>()
        synchronized(lock) { initDeferred = deferred }
        runOnMain { createWebViewAndLoad() }
        deferred.await()
    }

    private fun createWebViewAndLoad() {
        try {
            webView?.destroyQuietly()
            cachedStreamingPot = null
            val wv = WebView(appContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.userAgentString = USER_AGENT
            wv.settings.blockNetworkLoads = true // BotGuard JS runs locally; no net via WebView
            wv.addJavascriptInterface(this, JS_INTERFACE)
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.message().contains("Uncaught")) {
                        failInit(RuntimeException("BadWebView: ${m.message()} @ ${m.sourceId()}:${m.lineNumber()}"))
                    }
                    return super.onConsoleMessage(m)
                }
            }
            webView = wv

            val html = appContext.assets.open("po_token.html").bufferedReader().use { it.readText() }
            // Append the bootstrap call, exactly like NewPipe.
            val bootstrapped = html.replaceFirst(
                "</script>",
                "\n$JS_INTERFACE.downloadAndRunBotguard()</script>",
            )
            wv.loadDataWithBaseURL("https://www.youtube.com", bootstrapped, "text/html", "utf-8", null)
        } catch (t: Throwable) {
            failInit(t)
        }
    }

    // ---- JS interface callbacks (arrive on a WebView binder thread) ----------

    /** Called by the bootstrap JS once the page has loaded. */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        try {
            val response = botguardRequest(
                "https://www.youtube.com/api/jnn/v1/Create",
                "[ \"$REQUEST_KEY\" ]",
            )
            val challenge = parseChallengeData(response)
            runOnMain {
                webView?.evaluateJavascript(
                    """try {
                        data = $challenge
                        runBotGuard(data).then(function (result) {
                            this.webPoSignalOutput = result.webPoSignalOutput
                            $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                        }, function (error) {
                            $JS_INTERFACE.onJsInitializationError(error + "")
                        })
                    } catch (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "")
                    }""",
                    null,
                )
            }
        } catch (t: Throwable) {
            failInit(t)
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        failInit(RuntimeException("BotGuard JS init error: $error"))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        try {
            val response = botguardRequest(
                "https://www.youtube.com/api/jnn/v1/GenerateIT",
                "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
            )
            val (integrityTokenU8, durationSeconds) = parseIntegrityTokenData(response)
            // Cap at ~6h; subtract a 10-min safety margin like NewPipe.
            val ttl = minOf(durationSeconds - 600L, MAX_TTL_SECONDS).coerceAtLeast(60L)
            integrityExpiry = Instant.now().plusSeconds(ttl)
            runOnMain {
                webView?.evaluateJavascript("this.integrityToken = $integrityTokenU8") {
                    synchronized(lock) { initDeferred }?.complete(Unit)
                }
            }
        } catch (t: Throwable) {
            failInit(t)
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val emitter = popEmitter(identifier) ?: return
        try {
            emitter.complete(u8ToBase64(poTokenU8))
        } catch (t: Throwable) {
            emitter.completeExceptionally(t)
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        popEmitter(identifier)?.completeExceptionally(RuntimeException("obtainPoToken error: $error"))
    }

    // ---- token minting -------------------------------------------------------

    private suspend fun generate(identifier: String): String {
        val deferred = CompletableDeferred<String>()
        synchronized(tokenEmitters) { tokenEmitters[identifier] = deferred }
        val u8 = stringToU8(identifier)
        runOnMain {
            webView?.evaluateJavascript(
                """try {
                        identifier = "$identifier"
                        u8Identifier = $u8
                        poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "")
                    }""",
                null,
            )
        }
        return deferred.await()
    }

    // ---- helpers -------------------------------------------------------------

    private fun failInit(t: Throwable) {
        synchronized(lock) { initDeferred }?.completeExceptionally(t)
    }

    private fun popEmitter(identifier: String): CompletableDeferred<String>? =
        synchronized(tokenEmitters) { tokenEmitters.remove(identifier) }

    private fun clearPendingEmitters(t: Throwable) {
        synchronized(tokenEmitters) {
            tokenEmitters.values.forEach { it.completeExceptionally(t) }
            tokenEmitters.clear()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun WebView.destroyQuietly() = try {
        loadUrl("about:blank"); clearHistory(); removeAllViews(); destroy()
    } catch (_: Throwable) { }

    /**
     * Best-effort visitorData fetch via the web innertube player endpoint. Returns
     * null on any failure (the streaming token is then derived from the player
     * token; on-device verification needed).
     */
    private fun fetchVisitorData(): String? = try {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", "2.20240509.00.00")))
            .toString()
        val response = httpPostJson("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body)
        JSONObject(response).optJSONObject("responseContext")?.optString("visitorData")
            ?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        Log.w(TAG, "visitorData fetch failed: ${t.message}")
        null
    }

    private fun botguardRequest(url: String, payload: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json+protobuf")
            setRequestProperty("x-goog-api-key", GOOGLE_API_KEY)
            setRequestProperty("x-user-agent", "grpc-web-javascript/0.1")
        }
        return readResponse(conn, payload)
    }

    private fun httpPostJson(url: String, payload: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
        }
        return readResponse(conn, payload)
    }

    private fun readResponse(conn: HttpURLConnection, payload: String): String {
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---- JavaScriptUtil port (NewPipe, MIT) ----------------------------------

    private fun parseChallengeData(raw: String): String {
        val scrambled = JSONArray(raw)
        val challenge: JSONArray = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
            JSONArray(descramble(scrambled.getString(1)))
        } else {
            scrambled.getJSONArray(0)
        }
        val messageId = challenge.getString(0)
        val interpreterHash = challenge.getString(3)
        val program = challenge.getString(4)
        val globalName = challenge.getString(5)
        val clientExperimentsStateBlob = challenge.getString(7)
        val safeScript = firstString(challenge.optJSONArray(1))
        val trustedUrl = firstString(challenge.optJSONArray(2))

        val interpreterJs = JSONObject()
            .put("privateDoNotAccessOrElseSafeScriptWrappedValue", safeScript ?: JSONObject.NULL)
            .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", trustedUrl ?: JSONObject.NULL)
        return JSONObject()
            .put("messageId", messageId)
            .put("interpreterJavascript", interpreterJs)
            .put("interpreterHash", interpreterHash)
            .put("program", program)
            .put("globalName", globalName)
            .put("clientExperimentsStateBlob", clientExperimentsStateBlob)
            .toString()
    }

    private fun firstString(arr: JSONArray?): String? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v is String) return v
        }
        return null
    }

    private fun parseIntegrityTokenData(raw: String): Pair<String, Long> {
        val arr = JSONArray(raw)
        return base64ToU8(arr.getString(0)) to arr.getLong(1)
    }

    private fun descramble(scrambled: String): String =
        String(base64ToBytes(scrambled).map { (it + 97).toByte() }.toByteArray())

    private fun stringToU8(identifier: String): String =
        newUint8Array(identifier.toByteArray(Charsets.UTF_8))

    private fun base64ToU8(base64: String): String = newUint8Array(base64ToBytes(base64))

    private fun newUint8Array(bytes: ByteArray): String =
        "new Uint8Array([" + bytes.joinToString(",") { (it.toInt() and 0xFF).toString() } + "])"

    private fun u8ToBase64(poTokenU8: String): String {
        val bytes = poTokenU8.split(",").map { it.trim().toInt().toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
            .replace("+", "-").replace("/", "_")
    }

    private fun base64ToBytes(base64: String): ByteArray {
        val mod = base64.replace('-', '+').replace('_', '/').replace('.', '=')
        return Base64.decode(mod, Base64.DEFAULT)
    }

    companion object {
        private const val TAG = "KolaiPoToken"
        private const val TIMEOUT_MS = 15_000L
        private const val MAX_TTL_SECONDS = 6L * 60 * 60 // ~6h cache cap

        // Public BotGuard constants, copied verbatim from NewPipe (MIT).
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val JS_INTERFACE = "PoTokenWebView"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
    }
}