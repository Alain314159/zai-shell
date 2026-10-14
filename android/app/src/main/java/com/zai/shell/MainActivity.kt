package com.zai.shell

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.WebView
import android.webkit.DownloadListener
import com.zai.shell.BuildConfig
import com.getcapacitor.BridgeActivity
import com.zai.shell.plugin.ZaiForegroundService
import com.zai.shell.plugin.ZaiCookieBackupPlugin
import com.zai.shell.plugin.ZaiHistoryPlugin
import com.zai.shell.plugin.ZaiSseBridgePlugin
import com.zai.shell.plugin.ZaiShellPlugin
import com.zai.shell.plugin.ZaiWebChromeClient
import com.zai.shell.plugin.ZaiWebViewClient

class MainActivity : BridgeActivity() {
    private val TAG = "ZaiShellMainActivity"
    private var pendingWidgetPrompt: String? = null
    private var zaiWebChromeClient: ZaiWebChromeClient? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(ZaiShellPlugin::class.java)
        registerPlugin(ZaiCookieBackupPlugin::class.java)
        registerPlugin(ZaiHistoryPlugin::class.java)
        registerPlugin(ZaiSseBridgePlugin::class.java)
        super.onCreate(savedInstanceState)

        ZaiForegroundService.start(this)
        Log.i(TAG, "Foreground service started from MainActivity")

        hookWebViewClient()

        // Si viene del widget con un prompt, guardarlo para inyectar
        handleWidgetIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        intent ?: return
        val prompt = intent.getStringExtra("extra_prompt")
        if (!prompt.isNullOrEmpty()) {
            pendingWidgetPrompt = prompt
            Log.i(TAG, "Widget prompt received: ${prompt.take(50)}...")
            tryInjectWidgetPrompt()
        }
    }

    private var widgetPromptRetries = 0
    private val MAX_WIDGET_PROMPT_RETRIES = 30

    private fun tryInjectWidgetPrompt() {
        val prompt = pendingWidgetPrompt ?: return
        // HIGH #16: cap retries to prevent infinite loop / battery drain
        if (widgetPromptRetries >= MAX_WIDGET_PROMPT_RETRIES) {
            Log.w(TAG, "Max retries reached for widget prompt, giving up")
            pendingWidgetPrompt = null
            widgetPromptRetries = 0
            return
        }
        widgetPromptRetries++

        // HIGH #10: use bridge.webView directly instead of reflection
        val webView: WebView? = try { bridge?.webView } catch (e: Exception) { null }

        if (webView != null) {
            val js = """
                (function(){
                    var prompt = ${jsString(prompt)};
                    var attempts = 0;
                    function tryFill() {
                        var textarea = document.querySelector('textarea#chat-input, textarea[placeholder*="Message" i], textarea[placeholder*="Send" i], textarea');
                        if (!textarea) {
                            if (++attempts < 30) setTimeout(tryFill, 500);
                            return;
                        }
                        var desc = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value');
                        if (desc && desc.set) {
                            desc.set.call(textarea, prompt);
                        } else {
                            textarea.value = prompt;
                        }
                        textarea.dispatchEvent(new Event('input', { bubbles: true }));
                        textarea.dispatchEvent(new Event('change', { bubbles: true }));
                        setTimeout(function(){
                            var sendBtn = document.querySelector('button[type="submit"], div[role="button"][aria-label*="Send" i] button, div[role="button"][aria-label*="Send" i]');
                            if (sendBtn) sendBtn.click();
                        }, 500);
                    }
                    tryFill();
                })();
            """.trimIndent()
            webView.post {
                webView.evaluateJavascript(js, null)
                Log.i(TAG, "Widget prompt injected")
                pendingWidgetPrompt = null
                widgetPromptRetries = 0
            }
        } else {
            Log.w(TAG, "WebView not ready for widget prompt, retry ${widgetPromptRetries}/$MAX_WIDGET_PROMPT_RETRIES")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                tryInjectWidgetPrompt()
            }, 2000)
        }
    }

    private fun jsString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 32) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun hookWebViewClient() {
        // HIGH #10: use bridge.webView directly instead of reflection
        // HIGH #15: fixed dead retry logic - use Handler with postDelayed
        val webView: WebView? = try {
            bridge?.webView
        } catch (e: Exception) {
            Log.w(TAG, "Could not access bridge.webView: ${e.message}")
            null
        }

        if (webView == null) {
            // Retry on next loop (fixed: actually retries now)
            Log.w(TAG, "WebView not yet available, will retry in 500ms")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                hookWebViewClient()
            }, 500)
            return
        }

        try {
            val wv = webView
            wv.webViewClient = ZaiWebViewClient(bridge)
            val chromeClient = ZaiWebChromeClient(bridge)
            zaiWebChromeClient = chromeClient
            wv.webChromeClient = chromeClient

            // CRITICAL #1: WebView debugging only in debug builds
            try {
                if (BuildConfig.DEBUG) {
                    android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not set WebView debugging: ${e.message}")
            }

            wv.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                Log.i(TAG, "Download requested: $url (mime=$mimetype, disp=$contentDisposition)")
                try {
                    val request = DownloadManager.Request(Uri.parse(url))
                    request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    var fileName = Uri.decode(url.substringAfterLast('/', "zai-shell-download"))
                    if (contentDisposition != null && contentDisposition.contains("filename=")) {
                        val idx = contentDisposition.indexOf("filename=")
                        if (idx >= 0) {
                            val rest = contentDisposition.substring(idx + 9).trim()
                            fileName = rest.trim('"', ' ', ';')
                        }
                    }
                    request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName
                    )
                    request.allowScanningByMediaScanner()
                    request.setAllowedNetworkTypes(
                        DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                    )
                    if (mimetype != null) {
                        request.setMimeType(mimetype)
                    }
                    // HIGH #17: attach WebView cookies to authenticated downloads
                    try {
                        val cookies = android.webkit.CookieManager.getInstance().getCookie(url)
                        if (cookies != null) {
                            request.addRequestHeader("Cookie", cookies)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not attach cookies: ${e.message}")
                    }
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Log.i(TAG, "Download enqueued: $fileName")
                } catch (e: Exception) {
                    Log.e(TAG, "Download failed: ${e.message}", e)
                }
            })

            val settings = wv.settings
            settings.javaScriptCanOpenWindowsAutomatically = false
            // OAuth fix: must be true so onCreateWindow fires for window.open()
            settings.setSupportMultipleWindows(true)

            Log.i(TAG, "Custom WebViewClient + WebChromeClient + DownloadListener hooked")
        } catch (e: Exception) {
            Log.e(TAG, "Error hooking WebViewClient: ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        ZaiForegroundService.start(this)
    }

    /**
     * Maneja el resultado del file chooser para que <input type="file">
     * funcione en el WebView (subir fotos/archivos a z.ai)
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ZaiWebChromeClient.FILE_CHOOSER_REQUEST_CODE) {
            zaiWebChromeClient?.handleFileChooserResult(resultCode, data)
        }
    }

    override fun onDestroy() {
        // HIGH #18: cancel pending file chooser callback to avoid WebView hang
        zaiWebChromeClient?.handleFileChooserResult(android.app.Activity.RESULT_CANCELED, null)
        super.onDestroy()
    }
}
