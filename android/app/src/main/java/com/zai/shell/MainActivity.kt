package com.zai.shell

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import com.getcapacitor.BridgeActivity
import com.zai.shell.plugin.ZaiForegroundService
import com.zai.shell.plugin.ZaiShellPlugin
import com.zai.shell.plugin.ZaiWebViewClient

class MainActivity : BridgeActivity() {
    private val TAG = "ZaiShellMainActivity"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Registrar el plugin nativo ANTES de super.onCreate
        registerPlugin(ZaiShellPlugin::class.java)
        super.onCreate(savedInstanceState)

        // Arrancar el foreground service para que Android no mate la app
        ZaiForegroundService.start(this)
        Log.i(TAG, "Foreground service started from MainActivity")

        // Hook del WebViewClient custom para inyectar JS cuando la pagina cargue
        hookWebViewClient()
    }

    /**
     * Reemplaza el WebViewClient del WebView de Capacitor por el nuestro
     * para poder inyectar JS en la URL remota (chat.z.ai).
     */
    private fun hookWebViewClient() {
        try {
            // El webView esta accesible via bridge despues de onCreate
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
                Log.w(TAG, "Could not access bridge.webView via reflection: ${e.message}")
                null
            }

            webView?.let { wv ->
                wv.webViewClient = ZaiWebViewClient(bridge)
                Log.i(TAG, "Custom WebViewClient hooked successfully")
            } ?: run {
                // Reintentar en el proximo loop del hilo principal
                webView?.post {
                    hookWebViewClient()
                }
                Log.w(TAG, "WebView not yet available, will retry")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hooking WebViewClient: ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-asegurar el foreground service si fue detenido
        ZaiForegroundService.start(this)
    }

    override fun onDestroy() {
        // No detenemos el foreground service en onDestroy para que sobreviva
        // a cambios de orientacion. Se detiene solo si el usuario lo pide.
        super.onDestroy()
    }
}
