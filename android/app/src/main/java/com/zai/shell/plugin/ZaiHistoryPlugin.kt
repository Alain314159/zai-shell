package com.zai.shell.plugin

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject

/**
 * ZaiHistory - plugin para guardar conversaciones offline
 *
 * Guarda los mensajes en SharedPreferences como JSON.
 * Permite ver historial aunque no haya conexion.
 */
@CapacitorPlugin(name = "ZaiHistory")
class ZaiHistoryPlugin : Plugin() {

    companion object {
        private const val PREFS_NAME = "zai_shell_history"
        private const val KEY_CONVERSATIONS = "conversations"
        private const val MAX_CONVERSATIONS = 50
        private const val MAX_MESSAGES_PER_CONV = 200
    }

    /**
     * Guarda una conversacion completa
     * Recibe: { id, title, messages: [{role, content, timestamp}], url, timestamp }
     */
    @PluginMethod
    fun saveConversation(call: PluginCall) {
        try {
            val id = call.getString("id") ?: System.currentTimeMillis().toString()
            val title = call.getString("title") ?: "Sin titulo"
            val messages = call.getArray("messages") ?: JSONArray()
            val url = call.getString("url") ?: ""
            val timestamp = call.getLong("timestamp") ?: System.currentTimeMillis()

            // Limitar numero de mensajes guardados
            val trimmedMessages = JSONArray()
            val start = Math.max(0, messages.length() - MAX_MESSAGES_PER_CONV)
            for (i in start until messages.length()) {
                trimmedMessages.put(messages.get(i))
            }

            val conv = JSONObject()
            conv.put("id", id)
            conv.put("title", title)
            conv.put("messages", trimmedMessages)
            conv.put("url", url)
            conv.put("timestamp", timestamp)
            conv.put("messageCount", trimmedMessages.length())

            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val existing = JSONArray(prefs.getString(KEY_CONVERSATIONS, "[]") ?: "[]")

            // Buscar si ya existe y reemplazar, si no anadir al inicio
            var found = false
            for (i in 0 until existing.length()) {
                val e = existing.getJSONObject(i)
                if (e.getString("id") == id) {
                    existing.put(i, conv)
                    found = true
                    break
                }
            }
            if (!found) {
                // Anadir al inicio
                val newArr = JSONArray()
                newArr.put(conv)
                for (i in 0 until existing.length()) {
                    newArr.put(existing.get(i))
                }
                // Trimmear a MAX_CONVERSATIONS - crear nuevo JSONArray
                val trimmed = JSONArray()
                val end = Math.min(newArr.length(), MAX_CONVERSATIONS)
                for (i in 0 until end) {
                    trimmed.put(newArr.get(i))
                }
                // Reemplazar existing con trimmed (no se puede clear, hay que recrear)
                prefs.edit().putString(KEY_CONVERSATIONS, trimmed.toString()).apply()
                call.resolve(JSObject().put("ok", true).put("id", id))
                return
            }

            prefs.edit().putString(KEY_CONVERSATIONS, existing.toString()).apply()
            call.resolve(JSObject().put("ok", true).put("id", id))
        } catch (e: Exception) {
            call.reject("Error saving conversation: ${e.message}")
        }
    }

    /**
     * Lista todas las conversaciones guardadas (sin los mensajes, solo metadata)
     */
    @PluginMethod
    fun listConversations(call: PluginCall) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val existing = JSONArray(prefs.getString(KEY_CONVERSATIONS, "[]") ?: "[]")
            val result = JSONArray()
            for (i in 0 until existing.length()) {
                val conv = existing.getJSONObject(i)
                val meta = JSONObject()
                meta.put("id", conv.getString("id"))
                meta.put("title", conv.getString("title"))
                meta.put("timestamp", conv.getLong("timestamp"))
                meta.put("messageCount", conv.optInt("messageCount", 0))
                meta.put("url", conv.optString("url", ""))
                result.put(meta)
            }
            val ret = JSObject()
            ret.put("conversations", result)
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Error listing conversations: ${e.message}")
        }
    }

    /**
     * Carga una conversacion completa por id
     */
    @PluginMethod
    fun getConversation(call: PluginCall) {
        try {
            val id = call.getString("id") ?: return call.reject("id required")
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val existing = JSONArray(prefs.getString(KEY_CONVERSATIONS, "[]") ?: "[]")
            for (i in 0 until existing.length()) {
                val conv = existing.getJSONObject(i)
                if (conv.getString("id") == id) {
                    val ret = JSObject()
                    ret.put("conversation", conv)
                    call.resolve(ret)
                    return
                }
            }
            call.reject("Conversation not found")
        } catch (e: Exception) {
            call.reject("Error getting conversation: ${e.message}")
        }
    }

    /**
     * Borra una conversacion por id
     */
    @PluginMethod
    fun deleteConversation(call: PluginCall) {
        try {
            val id = call.getString("id") ?: return call.reject("id required")
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val existing = JSONArray(prefs.getString(KEY_CONVERSATIONS, "[]") ?: "[]")
            val newArr = JSONArray()
            for (i in 0 until existing.length()) {
                val conv = existing.getJSONObject(i)
                if (conv.getString("id") != id) {
                    newArr.put(conv)
                }
            }
            prefs.edit().putString(KEY_CONVERSATIONS, newArr.toString()).apply()
            call.resolve(JSObject().put("ok", true))
        } catch (e: Exception) {
            call.reject("Error deleting conversation: ${e.message}")
        }
    }

    /**
     * Borra todo el historial
     */
    @PluginMethod
    fun clearAll(call: PluginCall) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_CONVERSATIONS).apply()
            call.resolve(JSObject().put("ok", true))
        } catch (e: Exception) {
            call.reject("Error clearing history: ${e.message}")
        }
    }

    /**
     * Busca texto en todas las conversaciones guardadas
     */
    @PluginMethod
    fun searchHistory(call: PluginCall) {
        try {
            val query = call.getString("query") ?: ""
            if (query.isEmpty()) return call.reject("query required")
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val existing = JSONArray(prefs.getString(KEY_CONVERSATIONS, "[]") ?: "[]")
            val results = JSONArray()
            val lowerQuery = query.lowercase()

            for (i in 0 until existing.length()) {
                val conv = existing.getJSONObject(i)
                val messages = conv.optJSONArray("messages") ?: continue
                for (j in 0 until messages.length()) {
                    val msg = messages.getJSONObject(j)
                    val content = msg.optString("content", "").lowercase()
                    if (content.contains(lowerQuery)) {
                        val result = JSONObject()
                        result.put("conversationId", conv.getString("id"))
                        result.put("conversationTitle", conv.getString("title"))
                        result.put("messageIndex", j)
                        result.put("role", msg.optString("role", ""))
                        result.put("content", msg.optString("content", ""))
                        result.put("snippet", getSnippet(msg.optString("content", ""), lowerQuery))
                        result.put("timestamp", msg.optLong("timestamp", 0))
                        results.put(result)
                    }
                }
            }
            val ret = JSObject()
            ret.put("results", results)
            ret.put("total", results.length())
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Error searching history: ${e.message}")
        }
    }

    private fun getSnippet(content: String, query: String): String {
        val idx = content.lowercase().indexOf(query)
        if (idx < 0) return content.take(100)
        val start = Math.max(0, idx - 40)
        val end = Math.min(content.length, idx + query.length + 60)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < content.length) "..." else ""
        return prefix + content.substring(start, end) + suffix
    }
}
