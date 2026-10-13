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

    private fun tryInjectWidgetPrompt() {
        val prompt = pendingWidgetPrompt ?: return
        val webView: WebView? = try {
            this.javaClass.superclass.getDeclaredField("bridge").let { f ->
                f.isAccessible = true
                val b = f.get(this)
                b?.javaClass?.getDeclaredField("webView")?.let { wf ->
                    wf.isAccessible = true
                    wf.get(b) as? WebView
                }
            }
        } catch (e: Exception) { null }

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
                        var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
                        setter.call(textarea, prompt);
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
            }
        } else {
            // Reintentar en 2s
            Log.w(TAG, "WebView not ready for widget prompt, retrying in 2s")
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
        try {
            val webView: WebView? = try {
                this.javaClass.superclass.getDeclaredField("bridge").let { f ->
                    f.isAccessible = true
                    val b = f.get(this)
                    b?.javaClass?.getDeclaredField("webView")?.let { wf ->
                        wf.isAccessible = true
                        wf.get(b) as? WebView
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not access bridge.webView: ${e.message}")
                null
            }

            webView?.let { wv ->
                wv.webViewClient = ZaiWebViewClient(bridge)
                wv.webChromeClient = ZaiWebChromeClient(bridge)

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
                        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        dm.enqueue(request)
                        Log.i(TAG, "Download enqueued: $fileName")
                    } catch (e: Exception) {
                        Log.e(TAG, "Download failed: ${e.message}", e)
                    }
                })

                val settings = wv.settings
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)

                Log.i(TAG, "Custom WebViewClient + WebChromeClient + DownloadListener hooked")
            } ?: run {
                webView?.post { hookWebViewClient() }
                Log.w(TAG, "WebView not yet available, will retry")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hooking WebViewClient: ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        ZaiForegroundService.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
