// Terminal console socket (N6.4): a dedicated OkHttp WebSocket to the
// bridge's /pty endpoint, parallel to the ACP socket. Client->bridge JSON
// frames attach to a session workspace shell and carry base64 input /
// resizes / copyall requests; bridge->client mixes binary TerminalFrames
// with JSON events (exit, copyall reply, error).
//
// Reconnects up to MAX_ATTEMPTS times, one second apart, while the sheet is
// open — the bridge replays the current screen on reattach.

package com.devingo.app.ui.terminal

import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

internal class TerminalSession(
    private val httpBase: String,
    private val token: String?,
    private val sessionId: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onFrame(frame: TerminalFrame)
        fun onExit(code: Int)
        fun onCopyAll(data: ByteArray)
        /** Human-readable state for the sheet: connecting / retrying / dead. */
        fun onStatus(text: String)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wantOpen = false
    private var attempts = 0
    private var cols = 80
    private var rows = 24
    private var retryPosted = false

    private val wsUrl: String = httpBase
        .replaceFirst("http://", "ws://")
        .replaceFirst("https://", "wss://")
        .trimEnd('/') + "/pty"

    fun open(cols: Int, rows: Int) {
        this.cols = cols
        this.rows = rows
        wantOpen = true
        attempts = 0
        dial()
    }

    fun close() {
        wantOpen = false
        ws?.close(1000, null)
        ws = null
        http.dispatcher.executorService.shutdown()
    }

    fun sendInput(text: String) {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendJson(buildJsonObject {
            put("type", "input")
            put("data", b64)
        })
    }

    fun sendResize(c: Int, r: Int) {
        cols = c
        rows = r
        sendJson(buildJsonObject {
            put("type", "resize")
            put("cols", c)
            put("rows", r)
        })
    }

    fun sendCopyAll() {
        sendJson(buildJsonObject { put("type", "copyall") })
    }

    private fun sendJson(obj: JsonObject) {
        ws?.send(obj.toString())
    }

    private fun dial() {
        if (!wantOpen) return
        if (httpBase.isBlank()) {
            post { listener.onStatus("bridge 未连接") }
            return
        }
        post { listener.onStatus(if (attempts == 0) "连接中…" else "重连中 ($attempts/3)…") }
        val req = Request.Builder().url(wsUrl).apply {
            if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token")
        }.build()
        http.newWebSocket(req, SocketListener())
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
            attempts = 0
            post { listener.onStatus("") }
            sendJson(buildJsonObject {
                put("type", "attach")
                put("sessionId", sessionId)
                put("cols", cols)
                put("rows", rows)
            })
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = TerminalFrame.decode(bytes.toByteArray()) ?: return
            post { listener.onFrame(frame) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }
                .getOrNull() ?: return
            when (obj["type"]?.jsonPrimitive?.content) {
                "exit" -> {
                    // The bridge closes right after exit; don't reconnect.
                    wantOpen = false
                    post {
                        listener.onExit(obj["code"]?.jsonPrimitive?.intOrNull ?: 0)
                    }
                }
                "copyall" -> {
                    val data = Base64.decode(
                        (obj["data"] as? JsonPrimitive)?.content.orEmpty(),
                        Base64.DEFAULT,
                    )
                    post { listener.onCopyAll(data) }
                }
                "error" -> post {
                    listener.onStatus(obj["message"]?.jsonPrimitive?.content ?: "终端错误")
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (ws === webSocket) ws = null
            scheduleRetry("已断开")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (ws === webSocket) ws = null
            scheduleRetry(t.message ?: "连接失败")
        }
    }

    private fun scheduleRetry(reason: String) {
        if (!wantOpen || retryPosted) return
        if (attempts >= MAX_ATTEMPTS) {
            post { listener.onStatus("连接已断开：$reason") }
            return
        }
        attempts += 1
        retryPosted = true
        main.postDelayed({
            retryPosted = false
            if (wantOpen) dial()
        }, 1000)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
