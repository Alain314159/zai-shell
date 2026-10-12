package com.zai.shell.plugin

import android.webkit.WebView
import android.webkit.WebViewClient
import com.getcapacitor.Bridge
import com.getcapacitor.Logger

/**
 * ZaiWebViewClient - intercepta la carga del WebView para inyectar el JS de optimizacion
 *
 * Como chat.z.ai se carga como URL remota (capacitor.config.server.url),
 * necesitamos un WebViewClient custom que espere a que la pagina termine de cargar
 * y entonces inyecte el script de cleanup.
 */
class ZaiWebViewClient(private val bridge: Bridge) : WebViewClient() {

    companion object {
        private const val TAG = "ZaiWebViewClient"
        // El JS se carga desde assets para que sea facil de mantener
        private const val INJECT_SCRIPT_PATH = "public/js/zai-shell.js"
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Logger.info(TAG, "Page finished loading: $url")
        injectOptimizationScript(view)
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
            // Intentar multiples paths - Capacitor puede ponerlo en diferentes lugares
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
            // Si no encuentra el archivo, usar script embebido de fallback
            getEmbeddedScript()
        } catch (e: Exception) {
            getEmbeddedScript()
        }
    }

    /**
     * Script embebido de fallback - version minima si el archivo no se encuentra
     */
    private fun getEmbeddedScript(): String {
        return """
            (function(){
                'use strict';
                if (window.__ZAI_SHELL_LOADED__) return;
                window.__ZAI_SHELL_LOADED__ = true;

                // Keep-alive ping
                setInterval(function(){
                    try { fetch('/favicon.ico', {cache:'no-cors',mode:'no-cors'}).catch(function(){}); } catch(e){}
                }, 30000);

                // CSS minimo
                try {
                    var s = document.createElement('style');
                    s.textContent = '[class*="message"]{content-visibility:auto;contain-intrinsic-size:auto 200px;}body{padding-top:env(safe-area-inset-top,0)!important;padding-bottom:env(safe-area-inset-bottom,0)!important;}';
                    document.head.appendChild(s);
                } catch(e){}

                console.log('[ZaiShell] Embedded fallback script loaded');
            })();
        """.trimIndent()
    }
}
