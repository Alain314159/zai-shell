package com.zai.shell

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.WebView
import android.webkit.DownloadListener
import com.getcapacitor.BridgeActivity
import com.zai.shell.plugin.ZaiForegroundService
import com.zai.shell.plugin.ZaiCookieBackupPlugin
import com.zai.shell.plugin.ZaiShellPlugin
import com.zai.shell.plugin.ZaiWebChromeClient
import com.zai.shell.plugin.ZaiWebViewClient

class MainActivity : BridgeActivity() {
    private val TAG = "ZaiShellMainActivity"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(ZaiShellPlugin::class.java)
        registerPlugin(ZaiCookieBackupPlugin::class.java)
        super.onCreate(savedInstanceState)

        ZaiForegroundService.start(this)
        Log.i(TAG, "Foreground service started from MainActivity")

        hookWebViewClient()
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
                // Hook del WebViewClient custom
                wv.webViewClient = ZaiWebViewClient(bridge)

                // Hook del WebChromeClient custom (bloquea popups)
                wv.webChromeClient = ZaiWebChromeClient(bridge)

                // DownloadListener - captura descargas que no son interceptadas por shouldOverrideUrlLoading
                wv.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                    Log.i(TAG, "Download requested: $url (mime=$mimetype, disp=$contentDisposition)")
                    try {
                        val request = DownloadManager.Request(Uri.parse(url))
                        request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        // Extraer nombre del archivo
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

                // Configurar el WebView para no soportar multi-ventanas (bloquea popups a nivel nativo)
                val settings = wv.settings
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)

                Log.i(TAG, "Custom WebViewClient + WebChromeClient + DownloadListener hooked")
            } ?: run {
                // Reintentar en el proximo loop del hilo principal
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
