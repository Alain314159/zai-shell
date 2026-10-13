package com.zai.shell.plugin

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ZaiSseBridge - mantiene conexion SSE viva en Kotlin nativo
 *
 * El WebView de Android pausa timers y fetches cuando pasa a background.
 * Esto hace que la conexion SSE del chat se caiga y la conversacion se "congele".
 *
 * Este plugin:
 *  1. Recibe el endpoint SSE + headers desde el JS
 *  2. Abre la conexion SSE en Kotlin usando OkHttp
 *  3. Mantiene la conexion viva aunque el WebView este pausado
 *  4. Pasa cada evento recibido al JS via callback
 *
 * El JS inyectado decide cuando llamar a startStream (interceptando fetch del WebView)
 * y que hacer con los eventos (inyectarlos en el DOM de z.ai).
 */
@CapacitorPlugin(name = "ZaiSseBridge")
class ZaiSseBridgePlugin : Plugin() {

    companion object {
        private const val TAG = "ZaiSseBridge"
    }

    private var client: OkHttpClient? = null
    private val activeStreams = mutableMapOf<String, EventSource>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getClient(): OkHttpClient {
        if (client == null) {
            client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // sin timeout para SSE
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS) // keepalive
                .retryOnConnectionFailure(true)
                .build()
        }
        return client!!
    }

    /**
     * Inicia una conexion SSE
     * Recibe: { id, url, method, headers, body }
     * Devuelve: { ok, id }
     * Eventos llegan via notifyListeners("sse:event", { id, data, event })
     */
    @PluginMethod
    fun startStream(call: PluginCall) {
        val streamId = call.getString("id") ?: return call.reject("id required")
        val url = call.getString("url") ?: return call.reject("url required")
        val method = call.getString("method") ?: "GET"
        val headers = call.getObject("headers") ?: JSONObject()
        val body = call.getString("body")

        // Cancelar stream previo con el mismo id
        activeStreams.remove(streamId)?.cancel()

        Log.i(TAG, "Starting SSE stream id=$streamId url=$url method=$method")

        try {
            val reqBuilder = Request.Builder().url(url)
            for (key in headers.keys()) {
                reqBuilder.header(key, headers.getString(key))
            }
            // Aceptar SSE
            reqBuilder.header("Accept", "text/event-stream")
            reqBuilder.header("Cache-Control", "no-cache")

            when (method.uppercase()) {
                "POST" -> {
                    val mediaType = headers.optString("Content-Type", "application/json").toMediaTypeOrNull()
                    val reqBody = (body ?: "").toRequestBody(mediaType)
                    reqBuilder.post(reqBody)
                }
                "GET" -> { /* default */ }
                else -> reqBuilder.method(method, null)
            }

            val request = reqBuilder.build()
            val factory = EventSources.createFactory(getClient())
            val es = factory.newEventSource(request, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.i(TAG, "SSE connected: $streamId (code=${response.code})")
                    mainHandler.post {
                        val data = JSObject()
                        data.put("id", streamId)
                        data.put("type", "open")
                        data.put("code", response.code)
                        notifyListeners("sse:event", data)
                    }
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    mainHandler.post {
                        val payload = JSObject()
                        payload.put("id", streamId)
                        payload.put("type", "event")
                        payload.put("eventType", type ?: "message")
                        payload.put("data", data)
                        if (id != null) payload.put("eventId", id)
                        notifyListeners("sse:event", payload)
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    Log.i(TAG, "SSE closed: $streamId")
                    mainHandler.post {
                        val payload = JSObject()
                        payload.put("id", streamId)
                        payload.put("type", "closed")
                        notifyListeners("sse:event", payload)
                    }
                    activeStreams.remove(streamId)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val respCode = try { response?.code ?: 0 } catch (e: Exception) { 0 }
                    Log.e(TAG, "SSE failed: $streamId - ${t?.message} code=$respCode")
                    mainHandler.post {
                        val payload = JSObject()
                        payload.put("id", streamId)
                        payload.put("type", "error")
                        payload.put("message", t?.message ?: "unknown error")
                        payload.put("code", respCode)
                        notifyListeners("sse:event", payload)
                    }
                    activeStreams.remove(streamId)
                    // Auto-reintentar en 3s si la app sigue activa
                    mainHandler.postDelayed({
                        if (bridge != null && activity != null && !activity!!.isFinishing) {
                            Log.i(TAG, "Auto-retry SSE: $streamId")
                            // Re-llamar a startStream con los mismos args
                            // (necesitamos guardarlos - por ahora el JS re-invoca)
                        }
                    }, 3000)
                }
            })

            activeStreams[streamId] = es
            call.resolve(JSObject().put("ok", true).put("id", streamId))
        } catch (e: Exception) {
            Log.e(TAG, "Error starting stream: ${e.message}", e)
            call.reject("Error: ${e.message}")
        }
    }

    /**
     * Cancela un stream especifico
     */
    @PluginMethod
    fun stopStream(call: PluginCall) {
        val streamId = call.getString("id") ?: return call.reject("id required")
        activeStreams.remove(streamId)?.cancel()
        Log.i(TAG, "Stream stopped: $streamId")
        call.resolve(JSObject().put("ok", true))
    }

    /**
     * Cancela todos los streams
     */
    @PluginMethod
    fun stopAllStreams(call: PluginCall) {
        for ((id, es) in activeStreams) {
            es.cancel()
            Log.i(TAG, "Stream stopped: $id")
        }
        activeStreams.clear()
        call.resolve(JSObject().put("ok", true))
    }

    /**
     * Lista streams activos
     */
    @PluginMethod
    fun listStreams(call: PluginCall) {
        val ids = JSObject()
        val arr = org.json.JSONArray()
        for (id in activeStreams.keys) arr.put(id)
        ids.put("streams", arr)
        call.resolve(ids)
    }

    override fun handleOnDestroy() {
        for ((_, es) in activeStreams) es.cancel()
        activeStreams.clear()
        super.handleOnDestroy()
    }
}
