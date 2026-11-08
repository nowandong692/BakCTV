package com.bakctv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * PeerJS 공개 연결 서버(0.peerjs.com)와 주고받는 부분.
 * 웹 버전이 쓰는 PeerJS 라이브러리와 같은 규칙으로 메시지를 보낸다.
 */
class PeerJsSignaling(private val myId: String, private val listener: Listener) {

    interface Listener {
        fun onOpen()
        fun onIdTaken()
        fun onServerMessage(type: String, src: String?, payload: JSONObject?)
        fun onDisconnected()
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private val token = (1..12).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
    @Volatile private var closedByUs = false

    fun connect() {
        closedByUs = false
        val url = "wss://0.peerjs.com/peerjs?key=peerjs&id=$myId&token=$token&version=1.5.4"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                when (val type = msg.optString("type")) {
                    "OPEN" -> listener.onOpen()
                    "ID-TAKEN" -> listener.onIdTaken()
                    else -> listener.onServerMessage(type, msg.optString("src", null), msg.optJSONObject("payload"))
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closedByUs) listener.onDisconnected()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closedByUs) listener.onDisconnected()
            }
        })
    }

    fun send(type: String, dst: String?, payload: JSONObject?) {
        val msg = JSONObject().put("type", type)
        if (dst != null) msg.put("dst", dst)
        if (payload != null) msg.put("payload", payload)
        ws?.send(msg.toString())
    }

    fun heartbeat() = send("HEARTBEAT", null, null)

    fun close() {
        closedByUs = true
        ws?.close(1000, "bye")
        ws = null
    }
}
