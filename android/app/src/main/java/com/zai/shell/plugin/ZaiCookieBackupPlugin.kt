package com.zai.shell.plugin

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject

/**
 * ZaiCookieBackup - plugin para persistir sesion entre reinstalaciones
 *
 * Exporta:
 *  - exportSession: devuelve cookies + localStorage del dominio z.ai
 *  - importSession: restaura cookies + localStorage desde un string
 *  - clearSession: borra todo (logout total)
 *
 * El JS inyectado llama a estos metodos al arrancar y al cerrar sesion.
 */
@CapacitorPlugin(name = "ZaiCookieBackup")
class ZaiCookieBackupPlugin : Plugin() {

    companion object {
        private const val ZAI_DOMAIN = "https://chat.z.ai"
        private const val ZAI_DOMAIN_ALT = "https://z.ai"
    }

    /**
     * Exporta las cookies actuales del dominio z.ai
     */
    @PluginMethod
    fun exportCookies(call: PluginCall) {
        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(ZAI_DOMAIN) ?: ""
            val cookiesAlt = cookieManager.getCookie(ZAI_DOMAIN_ALT) ?: ""

            val result = JSObject()
            result.put("zai", cookies)
            result.put("zaiAlt", cookiesAlt)
            result.put("timestamp", System.currentTimeMillis())
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("Error exporting cookies: ${e.message}")
        }
    }

    /**
     * Importa cookies previamente exportadas
     */
    @PluginMethod
    fun importCookies(call: PluginCall) {
        try {
            val cookies = call.getString("zai") ?: ""
            val cookiesAlt = call.getString("zaiAlt") ?: ""

            val cookieManager = CookieManager.getInstance()

            // Las cookies vienen como "key=value; key2=value2; ..."
            if (cookies.isNotEmpty()) {
                val parts = cookies.split(";")
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed.isNotEmpty()) {
                        cookieManager.setCookie(ZAI_DOMAIN, trimmed)
                        cookieManager.setCookie(".z.ai", trimmed)
                    }
                }
            }
            if (cookiesAlt.isNotEmpty()) {
                val parts = cookiesAlt.split(";")
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed.isNotEmpty()) {
                        cookieManager.setCookie(ZAI_DOMAIN_ALT, trimmed)
                        cookieManager.setCookie(".z.ai", trimmed)
                    }
                }
            }

            cookieManager.flush()
            call.resolve(JSObject().put("ok", true))
        } catch (e: Exception) {
            call.reject("Error importing cookies: ${e.message}")
        }
    }

    /**
     * Borra todas las cookies (logout total)
     */
    @PluginMethod
    fun clearCookies(call: PluginCall) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            WebStorage.getInstance().deleteAllData()
            call.resolve(JSObject().put("ok", true))
        } catch (e: Exception) {
            call.reject("Error clearing cookies: ${e.message}")
        }
    }

    /**
     * Exporta el localStorage del WebView (lo llama el JS)
     * Devuelve un string JSON con todas las keys del localStorage de z.ai
     */
    @PluginMethod
    fun exportLocalStorage(call: PluginCall) {
        // El localStorage pertenece al JS del WebView, no al nativo.
        // Este metodo es un placeholder - la logica real la hace el JS inyectado
        // y llama a saveLocalStorage con el contenido.
        call.resolve(JSObject().put("ok", true).put("msg", "use saveLocalStorage instead"))
    }

    /**
     * Guarda un string de localStorage en Preferences (almacenamiento nativo)
     */
    @PluginMethod
    fun saveLocalStorage(call: PluginCall) {
        try {
            val data = call.getString("data") ?: ""
            val prefs = context.getSharedPreferences("zai_shell_storage", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("localstorage_backup", data).putLong("timestamp", System.currentTimeMillis()).apply()
            call.resolve(JSObject().put("ok", true).put("size", data.length))
        } catch (e: Exception) {
            call.reject("Error saving localStorage: ${e.message}")
        }
    }

    /**
     * Carga el string de localStorage desde Preferences
     */
    @PluginMethod
    fun loadLocalStorage(call: PluginCall) {
        try {
            val prefs = context.getSharedPreferences("zai_shell_storage", android.content.Context.MODE_PRIVATE)
            val data = prefs.getString("localstorage_backup", "") ?: ""
            val timestamp = prefs.getLong("timestamp", 0)
            val result = JSObject()
            result.put("data", data)
            result.put("timestamp", timestamp)
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("Error loading localStorage: ${e.message}")
        }
    }

    /**
     * Guarda cookies como backup en Preferences (para auto-login tras reinstalacion)
     */
    @PluginMethod
    fun saveCookieBackup(call: PluginCall) {
        try {
            val cookies = call.getString("cookies") ?: ""
            val prefs = context.getSharedPreferences("zai_shell_storage", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("cookies_backup", cookies).putLong("cookies_timestamp", System.currentTimeMillis()).apply()
            call.resolve(JSObject().put("ok", true).put("size", cookies.length))
        } catch (e: Exception) {
            call.reject("Error saving cookie backup: ${e.message}")
        }
    }

    /**
     * Carga el backup de cookies
     */
    @PluginMethod
    fun loadCookieBackup(call: PluginCall) {
        try {
            val prefs = context.getSharedPreferences("zai_shell_storage", android.content.Context.MODE_PRIVATE)
            val cookies = prefs.getString("cookies_backup", "") ?: ""
            val timestamp = prefs.getLong("cookies_timestamp", 0)
            val result = JSObject()
            result.put("cookies", cookies)
            result.put("timestamp", timestamp)
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("Error loading cookie backup: ${e.message}")
        }
    }
}
