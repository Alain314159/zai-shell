package com.zai.shell.plugin

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.ValueCallback
import com.getcapacitor.Bridge

/**
 * ZaiWebChromeClient - controla popups, nuevas ventanas, descargas y file chooser
 */
class ZaiWebChromeClient(private val bridge: Bridge) : WebChromeClient() {

    companion object {
        private const val TAG = "ZaiWebChromeClient"
        const val FILE_CHOOSER_REQUEST_CODE = 51426
    }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**
     * Maneja popups (window.open, target=_blank).
     *
     * En vez de bloquear, creamos un WebView temporal que captura la URL
     * y la carga en el WebView principal. Esto es critico para OAuth:
     * Google/GitHub login usa window.open() que debe quedarse en la app.
     */
    override fun onCreateWindow(
        view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
    ): Boolean {
        if (view == null || resultMsg == null) return false

        Log.i(TAG, "onCreateWindow (dialog=$isDialog, userGesture=$isUserGesture)")

        // Primero intentar con hitTestResult (funciona para clicks en links)
        val hitTest = view.hitTestResult
        val hitUrl = hitTest?.extra
        if (hitUrl != null && (hitUrl.startsWith("http://") || hitUrl.startsWith("https://"))) {
            Log.i(TAG, "Redirecting popup URL to main WebView: $hitUrl")
            view.loadUrl(hitUrl)
            return false
        }

        // Si hitTestResult no tiene URL (window.open from JS), crear WebView temporal
        // que capture la URL y la redirige al WebView principal
        val popupWebView = WebView(view.context)
        popupWebView.settings.javaScriptEnabled = true
        popupWebView.settings.domStorageEnabled = true

        popupWebView.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(popupView: WebView?, url: String?): Boolean {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    Log.i(TAG, "Popup URL captured, loading in main WebView: ${url.take(100)}")
                    // Cargar en el WebView principal (no en el popup)
                    view.loadUrl(url)
                    // Destruir el popup
                    popupView?.destroy()
                    return true
                }
                return false
            }

            override fun onPageFinished(popupView: WebView?, url: String?) {
                super.onPageFinished(popupView, url)
                // Si el popup cargo una pagina (no fue interceptada), cargarla en el main
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    Log.i(TAG, "Popup page loaded, redirecting to main: ${url?.take(100)}")
                    view.loadUrl(url)
                    popupView?.destroy()
                }
            }
        }

        val transport = resultMsg.obj as android.webkit.WebView.WebViewTransport
        transport.webView = popupWebView
        resultMsg.sendToTarget()

        return true
    }

    override fun onCloseWindow(window: WebView?) {
        Log.i(TAG, "onCloseWindow")
        super.onCloseWindow(window)
    }

    /**
     * FILE CHOOSER - permite que <input type="file"> funcione en el WebView.
     * Sin esto, el usuario no puede subir fotos/archivos a z.ai desde la app.
     */
    override fun onShowFileChooser(
        webView: WebView?,
        callback: ValueCallback<Array<Uri>>?,
        params: FileChooserParams?
    ): Boolean {
        // Cancelar callback previo si existe
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback

        try {
            val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Permitir seleccionar multiples archivos si z.ai lo pide
            if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }

            // HIGH #19: null check on activity to avoid NPE
            val act = bridge.activity
            if (act == null) {
                Log.e(TAG, "Activity is null, cannot launch file chooser")
                filePathCallback = null
                return false
            }

            Log.i(TAG, "Launching file chooser")
            act.startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error launching file chooser: ${e.message}", e)
            filePathCallback = null
            return false
        }
    }

    /**
     * Llamado desde MainActivity.onActivityResult para entregar el resultado
     * del file chooser al WebView.
     */
    fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = filePathCallback ?: return
        filePathCallback = null

        if (resultCode != Activity.RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return
        }

        val results: Array<Uri>? = when {
            data.data != null -> arrayOf(data.data!!)
            data.clipData != null -> {
                val clip = data.clipData
                val count = clip!!.itemCount
                Array(count) { i -> clip!!.getItemAt(i).uri }
            }
            else -> null
        }

        Log.i(TAG, "File chooser result: ${results?.size ?: 0} files")
        callback.onReceiveValue(results)
    }

    /**
     * Redirige console.log del JS a Logcat para debugging
     */
    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
        if (consoleMessage != null) {
            val msg = "[JS] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
            when (consoleMessage.messageLevel()) {
                android.webkit.ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, msg)
                android.webkit.ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, msg)
                else -> Log.d(TAG, msg)
            }
        }
        return true
    }
}
