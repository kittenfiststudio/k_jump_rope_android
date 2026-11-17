package kr.co.jumprope.camera.network

import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.co.jumprope.camera.detection.JumpEvent
import kr.co.jumprope.camera.session.SessionPhase
import kr.co.jumprope.camera.session.WorkoutState
import java.util.UUID
import okhttp3.*
import java.util.concurrent.TimeUnit

/** Debug transport only. Disconnected events are dropped; measurement never waits for the PC. */
class GodotConnection(private val playerId: String = "android-${UUID.randomUUID().toString().take(8)}") {
    private data class WorkoutUpdate(val sessionId: String, val state: String, val count: Int)
    private var currentWorkout: WorkoutUpdate? = null
    private var lastSentWorkout: Pair<String, String>? = null
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
                    "player_id" to playerId, "source" to "android", "message" to "연결 되었습니다")))
                sendWorkoutIfChanged()
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

    /** Publish status transitions, then the completed jump. Reconnect restores status, never past jumps. */
    @Synchronized fun updateWorkout(sessionId: String, state: WorkoutState, event: JumpEvent? = null) {
        val status = when (state.phase) {
            SessionPhase.IDLE -> return
            SessionPhase.COUNTDOWN, SessionPhase.CALIBRATING -> "preparing"
            SessionPhase.COUNTING -> if (state.detector.validTracking) "ready" else "tracking_lost"
            SessionPhase.PAUSED -> "paused"
            SessionPhase.FINISHED -> "finished"
        }
        // The status snapshot precedes this frame's jump, so the PC can animate that jump once.
        val priorCount = (state.jumpCount - if (event != null) 1 else 0).coerceAtLeast(0)
        currentWorkout = WorkoutUpdate(sessionId, status, priorCount)
        sendWorkoutIfChanged()
        event?.let(::sendJump)
        currentWorkout = WorkoutUpdate(sessionId, status, state.jumpCount)
    }

    private fun sendWorkoutIfChanged() {
        if (!connected) return
        val workout = currentWorkout ?: return
        val key = workout.sessionId to workout.state
        if (lastSentWorkout == key) return
        val packet = mapOf("protocol_version" to 1,
            "type" to if (workout.state == "ready") "ready" else "workout_state",
            "player_id" to playerId, "workout_session_id" to workout.sessionId,
            "state" to workout.state, "total_count" to workout.count,
            "message" to when (workout.state) {
                "ready" -> "Ready"; "preparing" -> "준비 중"; "paused" -> "일시정지"
                "finished" -> "운동 종료"; else -> "추적 재확인 중"
            })
        if (socket?.send(gson.toJson(packet)) == true) lastSentWorkout = key
        else mutableStatus.value = "상태 전송 실패 · 측정은 계속됩니다."
    }

    @Synchronized fun sendJump(event: JumpEvent) {
        if (!connected) return
        val sent = socket?.send(gson.toJson(mapOf(
            "protocol_version" to 1, "type" to "jump", "player_id" to playerId, "event_id" to event.eventId,
            "workout_session_id" to event.sessionId, "sequence" to event.sequence,
            "timestamp_ms" to event.timestampMs, "message" to "Counting Up"
        ))) == true
        if (!sent) mutableStatus.value = "점프 전송 실패 · 측정은 계속됩니다."
    }

    @Synchronized fun disconnect() {
        generation++
        connected = false
        lastSentWorkout = null
        socket?.cancel(); socket = null
        mutableStatus.value = "연결 안 됨"
    }
    @Synchronized fun close() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
