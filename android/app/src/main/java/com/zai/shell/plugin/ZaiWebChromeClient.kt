package com.zai.shell.plugin

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.getcapacitor.Bridge

/**
 * ZaiWebChromeClient - controla popups, nuevas ventanas y descargas
 *
 *  - Bloquea window.open() y target=_blank (evita que documentos PDFs, ventanas de codigo, etc.
 *    reemplacen la interfaz de chat)
 *  - Las descargas (PDFs, imagenes) se mandan al DownloadManager de Android
 *  - Los console.log del JS se redirigen a Logcat para debug
 */
class ZaiWebChromeClient(private val bridge: Bridge) : WebChromeClient() {

    companion object {
        private const val TAG = "ZaiWebChromeClient"
    }

    /**
     * Bloquea la creacion de nuevas ventanas.
     * Esto evita:
     *  - Documentos que se abren solos (PDFs, imagenes en nueva pestaña)
     *  - "Terminal" o paneles de codigo que z.ai abre en popups
     *  - Cualquier window.open() o target=_blank
     */
    override fun onCreateWindow(
        view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
    ): Boolean {
        Log.i(TAG, "Bloqueado onCreateWindow (dialog=$isDialog, userGesture=$isUserGesture)")
        // Forzar a que el link se abra en el mismo WebView
        view?.context?.let { ctx ->
            // Si el WebView tiene un hitTestResult podemos ver la URL
            val hitTest = view.hitTestResult
            val url = hitTest?.extra
            if (url != null && url.startsWith("http")) {
                Log.i(TAG, "Redirigiendo URL al mismo WebView: $url")
                view.loadUrl(url)
            }
        }
        // Devolver false bloquea la nueva ventana
        return false
    }

    override fun onCloseWindow(window: WebView?) {
        Log.i(TAG, "onCloseWindow")
        super.onCloseWindow(window)
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
