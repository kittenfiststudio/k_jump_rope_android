package kr.co.jumprope.camera.network

import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.co.jumprope.camera.detection.JumpEvent
import okhttp3.*
import java.util.concurrent.TimeUnit

/** Debug transport only. Disconnected events are dropped; measurement never waits for the PC. */
class GodotConnection {
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val mutableStatus = MutableStateFlow("연결 안 됨")
    val status = mutableStatus.asStateFlow()
    private var socket: WebSocket? = null
    private var generation = 0L
    private var connected = false

    @Synchronized fun connect(address: String) {
        disconnect()
        val request = try {
            require(address.startsWith("ws://") || address.startsWith("wss://"))
            Request.Builder().url(address).build()
        } catch (_: IllegalArgumentException) {
            mutableStatus.value = "주소를 확인하세요: ws://PC주소:8080"
            return
        }
        val token = generation
        mutableStatus.value = "연결 중…"
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(this@GodotConnection) {
                if (token != generation) { webSocket.cancel(); return@synchronized }
                connected = true
                mutableStatus.value = "PC 연결됨 · 인사 전송 중"
                webSocket.send(gson.toJson(mapOf("protocol_version" to 1, "type" to "hello",
                    "message" to "Android 줄넘기 앱이 연결되었습니다.")))
                Unit
            }
            override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@GodotConnection) {
                if (token == generation) mutableStatus.value = "PC 응답: ${text.take(200)}"
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(this@GodotConnection) {
                if (token == generation) {
                    connected = false
                    mutableStatus.value = "연결 실패: ${t.message?.take(120)} · 다시 연결해 주세요."
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                synchronized(this@GodotConnection) {
                    if (token == generation) {
                        connected = false
                        mutableStatus.value = "PC 연결 종료 · 다시 연결해 주세요."
                    }
                }
            }
        })
    }

    @Synchronized fun sendJump(event: JumpEvent) {
        if (!connected) return
        val sent = socket?.send(gson.toJson(mapOf(
            "protocol_version" to 1, "type" to "jump", "event_id" to event.eventId,
            "workout_session_id" to event.sessionId, "sequence" to event.sequence,
            "timestamp_ms" to event.timestampMs, "message" to "점프 ${event.sequence}회 완료"
        ))) == true
        if (!sent) mutableStatus.value = "점프 전송 실패 · 측정은 계속됩니다."
    }

    @Synchronized fun disconnect() {
        generation++
        connected = false
        socket?.cancel(); socket = null
        mutableStatus.value = "연결 안 됨"
    }
    @Synchronized fun close() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
