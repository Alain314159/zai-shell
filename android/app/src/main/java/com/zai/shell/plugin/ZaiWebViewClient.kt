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
        // Incluye dominios de auth OAuth para que el login funcione dentro del WebView
        private val ALLOWED_DOMAINS = listOf(
            // z.ai / Zhipu
            "z.ai", "chat.z.ai", "bigmodel.cn", "zhipuai.cn", "chatglm.cn",
            "localhost", "127.0.0.1",
            // OAuth providers que z.ai puede usar para login
            "accounts.google.com", "google.com", "www.google.com",
            "github.com", "oauth.github.com",
            "login.microsoftonline.com", "login.live.com", "login.microsoft.com",
            "appleid.apple.com",
            "twitter.com", "api.twitter.com",
            "facebook.com", "www.facebook.com",
            // CDNs y assets que z.ai usa
            "artifacts-cdn.chatglm.site",
            "zcode.z.ai",
            "chatglm.site",
            // Auth callbacks comunes
            "auth.openai.com",  // por si acaso
            "auth0.com"
        )
        // Patrones adicionales de URLs de auth (regex)
        private val AUTH_URL_PATTERNS = listOf(
            Regex("(?i).*oauth.*"),
            Regex("(?i).*accounts\\..*"),
            Regex("(?i).*login.*"),
            Regex("(?i).*signin.*"),
            Regex("(?i).*auth.*"),
            Regex("(?i).*callback.*"),
            Regex("(?i).*redirect.*")
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
     * Nota: no llamamos a super.shouldInterceptRequest() porque hay ambiguedad
     * de overload en Kotlin entre las firmas (WebView, String) y (WebView, WebResourceRequest).
     * Devolver null es equivalente al comportamiento por defecto del super (no interceptar).
     */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: android.webkit.WebResourceRequest?
    ): android.webkit.WebResourceResponse? {
        if (request != null) {
            val url = request.url?.toString()
            if (url != null) {
                val lowerUrl = url.lowercase()
                for (pattern in BLOCKED_PATTERNS) {
                    if (lowerUrl.contains(pattern)) {
                        Log.i(TAG, "Bloqueado (telemetry): $url")
                        val resp = android.webkit.WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            java.io.ByteArrayInputStream(ByteArray(0))
                        )
                        resp.setStatusCodeAndReasonPhrase(204, "No Content (blocked by ZaiShell)")
                        return resp
                    }
                }
            }
        }
        // null = no interceptar, dejar que el WebView cargue normalmente
        return null
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Logger.info(TAG, "Page finished loading: $url")
        injectOptimizationScript(view)
    }

    /**
     * Intercepta URLs antes de cargarlas.
     *
     * Logica (menos agresiva para no romper auth):
     *  - data:, blob:, about: dejar pasar
     *  - Archivos descargables (.pdf, .doc, etc) -> DownloadManager
     *  - Dominios en ALLOWED_DOMAINS (z.ai, OAuth providers) -> WebView
     *  - URLs que parecen auth (oauth, login, callback) -> WebView
     *  - Todo lo demas -> tambien WebView (preferimos no abrir Chrome)
     *    A menos que sea un scheme distinto de http/https (mailto:, tel:, etc)
     */
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        url ?: return false
        Log.i(TAG, "shouldOverrideUrlLoading: $url")

        // data:, blob:, about: dejar pasar
        if (url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("about:")) {
            return false
        }

        // Schemes especiales (mailto, tel, sms, intent) -> abrir en app externa
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.i(TAG, "Scheme especial, abriendo en app externa: $url")
            val uri = Uri.parse(url)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                bridge.context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo abrir scheme especial: ${e.message}")
            }
            return true
        }

        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        val lastSegment = uri.lastPathSegment ?: ""

        // Archivos descargables -> DownloadManager
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
            return true
        }

        // Verificar si es dominio permitido
        val isAllowed = ALLOWED_DOMAINS.any { host == it || host.endsWith(".$it") }
        if (isAllowed) {
            Log.d(TAG, "URL permitida, cargando en WebView: $host")
            return false
        }

        // Verificar si parece URL de auth (no bloquear estas)
        val isAuthUrl = AUTH_URL_PATTERNS.any { it.matches(url) }
        if (isAuthUrl) {
            Log.i(TAG, "URL de auth detectada, cargando en WebView: $url")
            return false
        }

        // Default: cargar en el WebView (comportamiento anterior rompia auth)
        // Solo abrir en Chrome si explicitamente es un enlace externo clicado
        // con scheme http/https y NO es auth y NO es z.ai
        Log.d(TAG, "URL externa cargando en WebView (default): $host")
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
