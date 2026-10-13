package com.zai.shell.plugin

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.getcapacitor.Bridge
import com.getcapacitor.Logger

/**
 * ZaiWebViewClient - intercepta la carga del WebView para:
 *  1. Inyectar el JS de optimizacion cuando la pagina termina de cargar
 *  2. Bloquear URLs externas (se abren en el navegador del sistema)
 *  3. Manejar descargas (PDFs, imagenes) via DownloadManager nativo
 */
class ZaiWebViewClient(private val bridge: Bridge) : WebViewClient() {

    companion object {
        private const val TAG = "ZaiWebViewClient"
        // Dominios permitidos - todo lo demas se abre en navegador externo
        private val ALLOWED_DOMAINS = listOf(
            "z.ai", "chat.z.ai", "bigmodel.cn", "zhipuai.cn", "zhipuai.cn",
            "localhost", "127.0.0.1"
        )
        // Patrones de URLs bloqueadas (telemetry/analytics/tracking)
        // Reduces bandwidth + improves privacy
        private val BLOCKED_PATTERNS = listOf(
            "google-analytics.com",
            "googletagmanager.com",
            "doubleclick.net",
            "facebook.net",
            "connect.facebook.net",
            "segment.io",
            "segment.com",
            "amplitude.com",
            "mixpanel.com",
            "hotjar.com",
            "sentry.io",
            "posthog.com",
            "fullstory.com",
            "logrocket.com",
            "clarity.ms",
            "bat.bing.com",
            "scorecardresearch.com",
            "quantserve.com",
            "taboola.com",
            "outbrain.com",
            "criteo.com",
            "adnxs.com",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "casalemedia.com",
            "matomo.cloud",
            "matomo.org",
            "tagmanager.google.com",
            "adservice.google.com",
            "googleadservices.com",
            "adsystem.com",
            "amazon-adsystem.com",
            "cloudflareinsights.com",
            "cloudflarebeacon.com",
            "newrelic.com",
            "nr-data.net",
            "rumcdn.com",
            "rumstatic.com",
            "cdn.mxpnl.com",
            "cdn.segment.com",
            "snap.licdn.com",
            "analytics.tiktok.com",
            "analytics.twitter.com",
            "ads.linkedin.com",
            "ads.twitter.com",
            "ads.facebook.com",
            "edge.adsymptotic.com",
            "heapanalytics.com",
            "kissmetrics.com",
            "wootric.com",
            "app.pendo.io",
            "dc.ads.linkedin.com"
        )
    }

    /**
     * Intercepta TODAS las requests (XHR, fetch, imagenes, scripts, etc.)
     * Bloquea telemetry/analytics para mejorar performance y privacidad
     *
     * Nota: usamos la sobrecarga con WebResourceRequest (API 21+) en vez de String
     * para evitar ambiguedad de overload resolution en Kotlin
     */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: android.webkit.WebResourceRequest?
    ): android.webkit.WebResourceResponse? {
        request ?: return super.shouldInterceptRequest(view, request)
        val url = request.url?.toString() ?: return super.shouldInterceptRequest(view, request)
        val lowerUrl = url.lowercase()
        for (pattern in BLOCKED_PATTERNS) {
            if (lowerUrl.contains(pattern)) {
                Log.i(TAG, "Bloqueado (telemetry): $url")
                return android.webkit.WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    java.io.ByteArrayInputStream(ByteArray(0))
                ).apply {
                    setStatusCodeAndReasonPhrase(204, "No Content (blocked by ZaiShell)")
                }
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Logger.info(TAG, "Page finished loading: $url")
        injectOptimizationScript(view)
    }

    /**
     * Intercepta URLs antes de cargarlas.
     * - URLs internas (z.ai, etc): se cargan en el WebView
     * - URLs externas: se abren en el navegador del sistema
     * - URLs con extension de archivo (.pdf, .doc, etc): se descargan
     */
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        url ?: return false
        Log.i(TAG, "shouldOverrideUrlLoading: $url")

        // Si es data: o blob: dejar que cargue
        if (url.startsWith("data:") || url.startsWith("blob:")) {
            return false
        }

        // Si es about:blank dejar pasar
        if (url.startsWith("about:")) {
            return false
        }

        // Verificar si es un dominio permitido
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        val isAllowed = ALLOWED_DOMAINS.any { host == it || host.endsWith(".$it") }

        // Si es archivo descargable (.pdf, .doc, .xls, .ppt, .zip, etc.) -> DownloadManager
        val lastSegment = uri.lastPathSegment ?: ""
        val downloadableExtensions = listOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".zip", ".rar", ".7z", ".tar", ".gz",
            ".mp3", ".mp4", ".avi", ".mov", ".wav", ".flac",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg",
            ".txt", ".csv", ".json", ".xml", ".md",
            ".apk", ".exe", ".dmg"
        )
        val isDownloadable = downloadableExtensions.any { lastSegment.lowercase().endsWith(it) }

        if (isDownloadable) {
            Log.i(TAG, "Descarga detectada, mandando a DownloadManager: $url")
            startDownload(url)
            return true // Bloquear que cargue en el WebView
        }

        if (!isAllowed) {
            Log.i(TAG, "URL externa bloqueada, abriendo en navegador: $url")
            // Abrir en el navegador del sistema
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                bridge.context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo abrir URL externa: ${e.message}")
            }
            return true
        }

        // URL permitida - dejar cargar en el WebView
        return false
    }

    /**
     * Descarga un archivo usando el DownloadManager nativo de Android
     */
    private fun startDownload(url: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            // Nombre del archivo
            val fileName = Uri.parse(url).lastPathSegment ?: "zai-shell-download"
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            request.allowScanningByMediaScanner()
            request.setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )

            val dm = bridge.context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Log.i(TAG, "Descarga encolada: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error encolando descarga: ${e.message}", e)
        }
    }

    private fun injectOptimizationScript(view: WebView?) {
        view ?: return
        try {
            val script = readAssetScript()
            if (script.isNotEmpty()) {
                view.evaluateJavascript(script, null)
                Logger.info(TAG, "Injected ZaiShell optimization script (${script.length} chars)")
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to inject script: ${e.message}", e)
        }
    }

    private fun readAssetScript(): String {
        return try {
            val assetManager = bridge.context.assets
            val possiblePaths = listOf(
                "public/js/zai-shell.js",
                "js/zai-shell.js",
                "zai-shell.js"
            )
            for (path in possiblePaths) {
                try {
                    val input = assetManager.open(path)
                    val content = input.bufferedReader().use { it.readText() }
                    input.close()
                    return content
                } catch (e: Exception) {
                    // Probar siguiente path
                }
            }
            getEmbeddedScript()
        } catch (e: Exception) {
            getEmbeddedScript()
        }
    }

    private fun getEmbeddedScript(): String {
        return """
            (function(){
                'use strict';
                if (window.__ZAI_SHELL_LOADED__) return;
                window.__ZAI_SHELL_LOADED__ = true;

                setInterval(function(){
                    try { fetch('/favicon.ico', {cache:'no-cors',mode:'no-cors'}).catch(function(){}); } catch(e){}
                }, 30000);

                try {
                    var s = document.createElement('style');
                    s.textContent = '[class*="message"]{content-visibility:auto;contain-intrinsic-size:auto 200px;}body{padding-top:env(safe-area-inset-top,0)!important;padding-bottom:env(safe-area-inset-bottom,0)!important;}ol,ul{padding-left:1.75rem!important;margin-left:0.25rem!important;}';
                    document.head.appendChild(s);
                } catch(e){}

                console.log('[ZaiShell] Embedded fallback script loaded');
            })();
        """.trimIndent()
    }
}
