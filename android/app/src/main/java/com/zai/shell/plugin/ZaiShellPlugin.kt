package com.zai.shell.plugin

import android.content.Context
import android.os.PowerManager
import android.net.Uri
import android.webkit.WebView
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * ZaiShell - Plugin nativo para potenciar el WebView de chat.z.ai
 *
 * Funciones:
 *  - injectJS: inyecta JavaScript en el WebView remoto
 *  - injectCSS: inyecta CSS en el WebView remoto
 *  - isBatteryOptimized: verifica si la app esta excluida del Doze
 *  - requestIgnoreBatteryOptimization: pide permiso al usuario
 *  - isKeepAwakeActive: estado del wake lock nativo
 */
@CapacitorPlugin(name = "ZaiShell")
class ZaiShellPlugin : Plugin() {

    @PluginMethod
    fun injectJS(call: PluginCall) {
        val code = call.getString("code") ?: ""
        if (code.isEmpty()) {
            call.reject("code is required")
            return
        }
        activity?.runOnUiThread {
            val webView = bridge?.webView
            if (webView != null) {
                webView.evaluateJavascript(code, null)
                call.resolve(JSObject().put("ok", true))
            } else {
                call.reject("webview not available")
            }
        }
    }

    @PluginMethod
    fun injectCSS(call: PluginCall) {
        val css = call.getString("css") ?: ""
        if (css.isEmpty()) {
            call.reject("css is required")
            return
        }
        val js = """
            (function(){
                try {
                    var style = document.createElement('style');
                    style.type = 'text/css';
                    style.id = 'zai-shell-injected-css';
                    var existing = document.getElementById('zai-shell-injected-css');
                    if (existing) existing.remove();
                    style.appendChild(document.createTextNode(${jsString(css)}));
                    document.head.appendChild(style);
                    return true;
                } catch(e){ return false; }
            })();
        """.trimIndent()
        activity?.runOnUiThread {
            val webView = bridge?.webView
            if (webView != null) {
                webView.evaluateJavascript(js, null)
                call.resolve(JSObject().put("ok", true))
            } else {
                call.reject("webview not available")
            }
        }
    }

    @PluginMethod
    fun isBatteryOptimized(call: PluginCall) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val pkg = context.packageName
            val ignored = pm.isIgnoringBatteryOptimizations(pkg)
            call.resolve(JSObject().put("ignored", ignored))
        } catch (e: Exception) {
            call.reject(e.message ?: "error checking battery optimization")
        }
    }

    @PluginMethod
    fun requestIgnoreBatteryOptimization(call: PluginCall) {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            )
            intent.data = Uri.parse("package:" + context.packageName)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            call.resolve(JSObject().put("ok", true))
        } catch (e: Exception) {
            call.reject(e.message ?: "error requesting battery optimization")
        }
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:" + context.packageName)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            call.resolve(JSObject().put("ok", true))
        } catch (e: Exception) {
            call.reject(e.message ?: "error opening settings")
        }
    }

    @PluginMethod
    fun getAppInfo(call: PluginCall) {
        try {
            val info = JSObject()
            // MEDIUM #21: read version from BuildConfig instead of hardcoded
            info.put("version", com.zai.shell.BuildConfig.VERSION_NAME)
            info.put("package", context.packageName)
            info.put("androidVersion", android.os.Build.VERSION.RELEASE)
            info.put("sdk", android.os.Build.VERSION.SDK_INT)
            info.put("manufacturer", android.os.Build.MANUFACTURER)
            info.put("model", android.os.Build.MODEL)
            call.resolve(info)
        } catch (e: Exception) {
            call.reject(e.message ?: "error getting app info")
        }
    }

    /** Helpers */
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
}
