package io.github.kvarflow.pcalc

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * Shows the calculator, which is packed in the APK's assets, full screen.
 *
 * The pages are served from https://appassets.androidplatform.net/calc/ (WebViewAssetLoader), a
 * secure origin of their own, so the calculator's saved box, settings and runs persist like they
 * do in a browser, and nothing is fetched from the internet to run it.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/calc/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSave: ByteArray? = null

    private val backups by lazy { BackupStore(this) }
    // Backup file work happens here, off the main thread; replies go back on the main thread.
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var folderReply: JavaScriptReplyProxy? = null
    private var lastAutoBackup: String? = null

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val reply = folderReply
        folderReply = null
        val error = if (uri == null) null else runCatching { backups.chooseFolder(uri) }.exceptionOrNull()?.let {
            "Couldn't use that folder: " + (it.message ?: it.javaClass.simpleName)
        }
        reply?.let { replyStatus(it, error) }
    }

    // While the calculator is open, an automatic backup every so often (it is also backed up on leaving).
    private val periodicBackup = object : Runnable {
        override fun run() {
            autoBackup()
            main.postDelayed(this, AUTO_BACKUP_EVERY_MS)
        }
    }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }

    private val saveFile = registerForActivityResult(CreateDocument()) { uri ->
        val bytes = pendingSave
        pendingSave = null
        if (uri == null || bytes == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            toast(getString(R.string.saved))
        } catch (e: Exception) {
            toast(getString(R.string.save_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullScreen()

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // The calculator has its own text size setting; don't let the system scale it twice.
            textZoom = 100
            // Lets the calculator tell it is running inside the app.
            userAgentString = "$userAgentString Pcalc/${BuildConfig.VERSION_NAME}"
        }
        webView.webViewClient = CalcClient()
        webView.webChromeClient = FilePicker()
        installDownloadBridge()

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(START_URL)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back (or a gamepad's B, which Android turns into Back) steps back a page; it never
                // closes the calculator, which would lose what is on screen.
                if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        main.removeCallbacks(periodicBackup)
        main.postDelayed(periodicBackup, AUTO_BACKUP_EVERY_MS)
    }

    override fun onPause() {
        main.removeCallbacks(periodicBackup)
        webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        // Leaving the app: back up what the calculator holds now.
        autoBackup()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(periodicBackup)
        webView.destroy()
        io.shutdown()
        super.onDestroy()
    }

    /** An automatic backup of everything the calculator keeps, unless nothing changed since the last. */
    private fun autoBackup() {
        webView.evaluateJavascript("window.pcalcBackupData ? window.pcalcBackupData() : null") { result ->
            // evaluateJavascript gives the value as JSON: a quoted string, or null.
            val text = runCatching { JSONArray("[$result]").optString(0, "") }.getOrDefault("")
            if (text.isEmpty() || text == "null") return@evaluateJavascript
            // The backup's own time changes every call: compare the data only.
            val data = runCatching { JSONObject(text).optJSONObject("data")?.toString() }.getOrNull()
            if (data == null || data == lastAutoBackup) return@evaluateJavascript
            lastAutoBackup = data
            io.execute { runCatching { backups.write(text, "auto") } }
        }
    }

    private fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Pages run in the WebView only if they are the calculator's. Requests go:
     *  - to the APK's assets for the calculator itself;
     *  - emulator sync (http://127.0.0.1:31125) is answered by the app itself, reading RetroArch;
     *  - other requests to this device (127.0.0.1 / localhost) are blocked;
     *  - (its images are its own files, in the APK with the rest of it);
     *  - nowhere else (analytics and other trackers get an empty answer).
     * Links to other sites open in the browser.
     */
    private inner class CalcClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            return when (url.host) {
                ASSET_HOST ->
                    if (url.path.orEmpty().endsWith("/sw.js")) empty(404, "Not Found")
                    else assetLoader.shouldInterceptRequest(url) ?: empty(404, "Not Found")
                // Only emulator sync on this device; nothing else here is the calculator's business.
                in LOCAL_HOSTS -> if (url.port == SYNC_PORT) syncResponse(request) else empty(204, "Blocked")
                else -> empty(204, "Blocked")
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == ASSET_HOST) return false
            // Only web and email links leave the app (to the browser / mail app); anything else is ignored.
            if (url.scheme?.lowercase() !in EXTERNAL_SCHEMES) return true
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.no_app_for_link))
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            // Older WebViews can't add the bridge script before the page's own; add it now instead.
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(DOWNLOAD_SHIM, null)
            }
        }
    }

    /** `<input type="file">` (the .sav import) opens Android's file picker. */
    private inner class FilePicker : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                // .sav/.srm have no registered MIME type, so any file can be picked.
                openFile.launch(arrayOf("*/*"))
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    /**
     * The calculator saves files (the frag sheet's CSV) with a `<a download>` link to a blob, which
     * a WebView can't save by itself. A small script catches those clicks and hands the file to the
     * app, which asks where to save it. Only the calculator's own origin can send it files.
     */
    private fun installDownloadBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(ASSET_ORIGIN)) { _, message, _, isMainFrame, reply ->
            if (!isMainFrame) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > MAX_MESSAGE) return@addWebMessageListener
            try {
                val json = JSONObject(raw)
                when (json.optString("type")) {
                    "save" -> {
                        pendingSave = Base64.decode(json.getString("data"), Base64.DEFAULT)
                        saveFile.launch(json.optString("name", "download") to json.optString("mime", "application/octet-stream"))
                    }
                    "backup:list" -> replyStatus(reply, null)
                    "backup:chooseFolder" -> {
                        folderReply = reply
                        chooseFolder.launch(null)
                    }
                    "backup:save" -> {
                        val reason = json.optString("reason")
                        val text = json.optString("data")
                        io.execute {
                            val result = runCatching { backups.write(text, reason) }
                            main.post {
                                reply.postMessage(JSONObject().apply {
                                    put("type", "backup:saved")
                                    result.onSuccess { put("name", it) }
                                    result.onFailure { put("error", it.message ?: it.javaClass.simpleName) }
                                }.toString())
                            }
                        }
                    }
                    "backup:read" -> {
                        val name = json.optString("name")
                        io.execute {
                            val result = runCatching { backups.read(name) }
                            main.post {
                                reply.postMessage(JSONObject().apply {
                                    put("type", "backup:file")
                                    put("name", name)
                                    result.onSuccess { put("data", it) }
                                    result.onFailure { put("error", it.message ?: it.javaClass.simpleName) }
                                }.toString())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                toast(getString(R.string.save_failed, e.message ?: e.javaClass.simpleName))
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, DOWNLOAD_SHIM, setOf(ASSET_ORIGIN))
        }
    }

    /** Where backups go, when the last was, and the backups there (newest first), for the page. */
    private fun replyStatus(reply: JavaScriptReplyProxy, error: String?) {
        io.execute {
            val files = runCatching { backups.list() }.getOrDefault(emptyList())
            val folder = runCatching { backups.folderName() }.getOrNull()
            val last = backups.lastBackup()
            main.post {
                reply.postMessage(JSONObject().apply {
                    put("type", "backup:status")
                    folder?.let { put("folder", it) }
                    if (last > 0) put("last", last)
                    error?.let { put("error", it) }
                    put("files", JSONArray().apply {
                        files.forEach { put(JSONObject().put("name", it.name).put("modified", it.modified)) }
                    })
                }.toString())
            }
        }
    }

    private val retroArch = RetroArchSync()

    /**
     * Emulator sync without a script: rr-calc polls http://127.0.0.1:31125/state, which on a computer
     * its mGBA or RetroArch script serves. In the app the request never leaves it; the app reads
     * RetroArch itself and answers as retroarch_sync.py would, so the calculator needs no change.
     * Runs on the WebView's network thread, so the UDP reads don't block the page.
     */
    private fun syncResponse(request: WebResourceRequest): WebResourceResponse {
        if (request.method == "OPTIONS") return text(204, "No Content", "")
        return when (request.url.path) {
            "/ping" -> text(200, "OK", "radicalred-sync ${RetroArchSync.SCRIPT_VERSION} (retroarch, built into Pcalc)")
            "/state" -> try {
                text(200, "OK", retroArch.stateJson(), "application/json")
            } catch (e: RetroArchSync.RetroArchException) {
                text(503, "Service Unavailable", e.message.orEmpty())
            } catch (e: Exception) {
                // Anything else (a reply that isn't RetroArch's, a socket error) is reported, never thrown:
                // an exception here would close the app.
                text(503, "Service Unavailable", "Couldn't read RetroArch: ${e.message ?: e.javaClass.simpleName}")
            }
            else -> text(404, "Not Found", "Not found")
        }
    }

    private fun text(status: Int, reason: String, body: String, type: String = "text/plain") =
        WebResourceResponse(type, "utf-8", status, reason, SYNC_HEADERS, ByteArrayInputStream(body.toByteArray()))

    private fun empty(status: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** ACTION_CREATE_DOCUMENT with the file's own name and type. */
    private class CreateDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>) =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.second.ifBlank { "application/octet-stream" })
                .putExtra(Intent.EXTRA_TITLE, input.first)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent.takeIf { resultCode == RESULT_OK }?.data
    }

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val ASSET_ORIGIN = "https://$ASSET_HOST"
        private const val START_URL = "$ASSET_ORIGIN/calc/index.html?app=1"
        private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost")
        // rr-calc's emulator sync (js/emulator_sync.js EMULATOR_URL).
        private const val SYNC_PORT = 31125
        private val SYNC_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store",
        )
        private const val BRIDGE_NAME = "PcalcNative"
        private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto")
        // A message from the page bigger than this (a backup or a saved file) is ignored.
        private const val MAX_MESSAGE = 30 * 1024 * 1024
        private const val AUTO_BACKUP_EVERY_MS = 10 * 60 * 1000L

        private val DOWNLOAD_SHIM = """
            (function () {
              if (window.__pcalcDownloads) return;
              window.__pcalcDownloads = true;
              document.addEventListener("click", function (ev) {
                var a = ev.target && ev.target.closest ? ev.target.closest("a[download]") : null;
                if (!a || !window.$BRIDGE_NAME || !/^(blob|data):/.test(a.href)) return;
                ev.preventDefault();
                var name = a.getAttribute("download") || "download";
                fetch(a.href).then(function (r) { return r.blob(); }).then(function (blob) {
                  var reader = new FileReader();
                  reader.onload = function () {
                    var url = String(reader.result);
                    $BRIDGE_NAME.postMessage(JSON.stringify({
                      type: "save", name: name, mime: blob.type || "application/octet-stream",
                      data: url.slice(url.indexOf(",") + 1)
                    }));
                  };
                  reader.readAsDataURL(blob);
                });
              }, true);
            })();
        """.trimIndent()
    }
}
