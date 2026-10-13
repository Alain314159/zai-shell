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
     * Bloquea la creacion de nuevas ventanas.
     */
    override fun onCreateWindow(
        view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
    ): Boolean {
        Log.i(TAG, "Bloqueado onCreateWindow (dialog=$isDialog, userGesture=$isUserGesture)")
        view?.context?.let { ctx ->
            val hitTest = view.hitTestResult
            val url = hitTest?.extra
            if (url != null && url.startsWith("http")) {
                Log.i(TAG, "Redirigiendo URL al mismo WebView: $url")
                view.loadUrl(url)
            }
        }
        return false
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
