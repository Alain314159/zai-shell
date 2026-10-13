package com.zai.shell.plugin

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.zai.shell.data.AppDatabase
import com.zai.shell.data.ConversationEntity
import com.zai.shell.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * ZaiHistory - plugin para guardar conversaciones offline
 *
 * GA1: Migrado de SharedPreferences (JSON O(N²)) a Room/SQLite (O(log N) con índices).
 *
 * Mejoras:
 *  - Búsquedas O(log N) en vez de O(N²)
 *  - Soporta miles de conversaciones sin degradación
 *  - Transacciones ACID
 *  - Mensajes en tabla separada con FK CASCADE delete
 */
@CapacitorPlugin(name = "ZaiHistory")
class ZaiHistoryPlugin : Plugin() {

    companion object {
        private const val MAX_CONVERSATIONS = 200
        private const val MAX_MESSAGES_PER_CONV = 500
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.getInstance(context).conversationDao() }

    /**
     * Guarda una conversación completa.
     * Recibe: { id, title, messages: [{role, content, timestamp}], url, timestamp }
     */
    @PluginMethod
    fun saveConversation(call: PluginCall) {
        val id = call.getString("id") ?: System.currentTimeMillis().toString()
        val title = call.getString("title") ?: "Sin titulo"
        val messages = call.getArray("messages") ?: JSONArray()
        val url = call.getString("url") ?: ""
        val timestamp = call.getLong("timestamp") ?: System.currentTimeMillis()

        ioScope.launch {
            try {
                // Trim messages if exceeds max
                val msgList = mutableListOf<MessageEntity>()
                val start = Math.max(0, messages.length() - MAX_MESSAGES_PER_CONV)
                for (i in start until messages.length()) {
                    val m = messages.getJSONObject(i)
                    msgList.add(
                        MessageEntity(
                            conversationId = id,
                            role = m.optString("role", "user"),
                            content = m.optString("content", ""),
                            timestamp = m.optLong("timestamp", timestamp),
                            seq = i
                        )
                    )
                }

                val conv = ConversationEntity(
                    id = id,
                    title = title,
                    url = url,
                    timestamp = timestamp,
                    messageCount = msgList.size
                )

                dao.saveConversationWithMessages(conv, msgList)

                // Trim oldest conversations if exceeds max
                val total = dao.count()
                if (total > MAX_CONVERSATIONS) {
                    dao.trimOldConversations(MAX_CONVERSATIONS)
                }

                activity?.runOnUiThread {
                    call.resolve(JSObject().put("ok", true).put("id", id))
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    call.reject("Error saving conversation: ${e.message}")
                }
            }
        }
    }

    /**
     * Lista conversaciones (metadata only, sin mensajes)
     */
    @PluginMethod
    fun listConversations(call: PluginCall) {
        ioScope.launch {
            try {
                val convs = dao.listConversations(MAX_CONVERSATIONS, 0)
                val arr = JSONArray()
                for (c in convs) {
                    val meta = JSONObject()
                    meta.put("id", c.id)
                    meta.put("title", c.title)
                    meta.put("timestamp", c.timestamp)
                    meta.put("messageCount", c.messageCount)
                    meta.put("url", c.url)
                    arr.put(meta)
                }
                val ret = JSObject()
                ret.put("conversations", arr)
                activity?.runOnUiThread { call.resolve(ret) }
            } catch (e: Exception) {
                activity?.runOnUiThread { call.reject("Error listing: ${e.message}") }
            }
        }
    }

    /**
     * Carga una conversación completa por id
     */
    @PluginMethod
    fun getConversation(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id required")
        ioScope.launch {
            try {
                val conv = dao.getConversation(id) ?: run {
                    activity?.runOnUiThread { call.reject("Conversation not found") }
                    return@launch
                }
                val messages = dao.getMessages(id)
                val msgArr = JSONArray()
                for (m in messages) {
                    val obj = JSONObject()
                    obj.put("role", m.role)
                    obj.put("content", m.content)
                    obj.put("timestamp", m.timestamp)
                    obj.put("seq", m.seq)
                    msgArr.put(obj)
                }
                val convObj = JSONObject()
                convObj.put("id", conv.id)
                convObj.put("title", conv.title)
                convObj.put("url", conv.url)
                convObj.put("timestamp", conv.timestamp)
                convObj.put("messageCount", conv.messageCount)
                convObj.put("messages", msgArr)

                val ret = JSObject()
                ret.put("conversation", convObj)
                activity?.runOnUiThread { call.resolve(ret) }
            } catch (e: Exception) {
                activity?.runOnUiThread { call.reject("Error: ${e.message}") }
            }
        }
    }

    /**
     * Borra una conversación por id (CASCADE borra sus mensajes)
     */
    @PluginMethod
    fun deleteConversation(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id required")
        ioScope.launch {
            try {
                dao.deleteConversation(id)
                activity?.runOnUiThread { call.resolve(JSObject().put("ok", true)) }
            } catch (e: Exception) {
                activity?.runOnUiThread { call.reject("Error: ${e.message}") }
            }
        }
    }

    /**
     * Borra todo el historial
     */
    @PluginMethod
    fun clearAll(call: PluginCall) {
        ioScope.launch {
            try {
                dao.clearAll()
                activity?.runOnUiThread { call.resolve(JSObject().put("ok", true)) }
            } catch (e: Exception) {
                activity?.runOnUiThread { call.reject("Error: ${e.message}") }
            }
        }
    }

    /**
     * Búsqueda full-text en TODAS las conversaciones.
     * Con índice SQLite es O(log N) en vez de O(N) scan.
     */
    @PluginMethod
    fun searchHistory(call: PluginCall) {
        val query = call.getString("query") ?: ""
        if (query.isEmpty()) return call.reject("query required")
        ioScope.launch {
            try {
                val results = dao.searchMessages(query, 100)
                val arr = JSONArray()
                for (r in results) {
                    val obj = JSONObject()
                    obj.put("conversationId", r.conversationId)
                    obj.put("conversationTitle", r.convTitle)
                    obj.put("messageIndex", r.seq)
                    obj.put("role", r.role)
                    obj.put("content", r.content)
                    obj.put("snippet", getSnippet(r.content, query))
                    obj.put("timestamp", r.timestamp)
                    arr.put(obj)
                }
                val ret = JSObject()
                ret.put("results", arr)
                ret.put("total", results.size)
                activity?.runOnUiThread { call.resolve(ret) }
            } catch (e: Exception) {
                activity?.runOnUiThread { call.reject("Error: ${e.message}") }
            }
        }
    }

    private fun getSnippet(content: String, query: String): String {
        val idx = content.lowercase().indexOf(query.lowercase())
        if (idx < 0) return content.take(100)
        val start = Math.max(0, idx - 40)
        val end = Math.min(content.length, idx + query.length + 60)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < content.length) "..." else ""
        return prefix + content.substring(start, end) + suffix
    }
}
