package io.github.kvarflow.pcalc

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

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
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
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
     *  - other requests to this device (127.0.0.1 / localhost) go through;
     *  - the calculator's sprites (which it asks GitHub for) come from the APK too;
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
                in LOCAL_HOSTS -> if (url.port == SYNC_PORT) syncResponse(request) else null
                SPRITE_HOST -> bundledSprite(url.path.orEmpty())
                else -> empty(204, "Blocked")
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == ASSET_HOST) return false
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
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(ASSET_ORIGIN)) { _, message, _, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            try {
                val json = JSONObject(message.data ?: return@addWebMessageListener)
                if (json.optString("type") != "save") return@addWebMessageListener
                pendingSave = Base64.decode(json.getString("data"), Base64.DEFAULT)
                saveFile.launch(json.optString("name", "download") to json.optString("mime", "application/octet-stream"))
            } catch (e: Exception) {
                toast(getString(R.string.save_failed, e.message ?: e.javaClass.simpleName))
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, DOWNLOAD_SHIM, setOf(ASSET_ORIGIN))
        }
    }

    /**
     * The calculator loads sprites from raw.githubusercontent.com; the same images are packed in
     * the APK under sprites/ (see copySprites in app/build.gradle.kts), so they work offline.
     * A sprite the set doesn't have gets 404, and the calculator falls back as it does online.
     */
    private fun bundledSprite(path: String): WebResourceResponse {
        val asset = when {
            path.startsWith(POKEMON_SPRITES) -> "sprites/" + path.removePrefix(POKEMON_SPRITES)
            path == OAK_SPRITE -> "sprites/trainers/Oak.png"
            else -> return empty(404, "Not Found")
        }
        return try {
            WebResourceResponse("image/png", null, assets.open(asset))
        } catch (e: java.io.IOException) {
            empty(404, "Not Found")
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
            } catch (e: java.io.IOException) {
                text(503, "Service Unavailable", "Couldn't reach RetroArch: ${e.message}")
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
        private const val SPRITE_HOST = "raw.githubusercontent.com"
        // rr-calc's emulator sync (js/emulator_sync.js EMULATOR_URL).
        private const val SYNC_PORT = 31125
        private val SYNC_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store",
        )
        // The URLs rr-calc uses (box_controls.js SPRITE_BASE_URL, trainer_controls.js OAK_SPRITE_URL).
        private const val POKEMON_SPRITES = "/May8th1995/sprites/master/"
        private const val OAK_SPRITE = "/smogon/sprites/master/src/_uncategorized/canonical/trainers/gen3/firered-leafgreen/Oak.png"
        private const val BRIDGE_NAME = "PcalcNative"

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
