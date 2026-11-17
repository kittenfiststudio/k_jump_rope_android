package kr.co.jumprope.camera.network

import com.google.gson.JsonParser
import kr.co.jumprope.camera.detection.JumpEvent
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GodotConnectionTest {
    @Test fun connectsWithoutJumpAndSendsCompletedEvent() {
        val messages = LinkedBlockingQueue<String>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.add(text)
                webSocket.send("Godot 수신 완료")
            }
        }))
        server.start()
        val connection = GodotConnection()
        try {
            connection.connect(server.url("/").toString().replaceFirst("http://", "ws://"))
            val hello = JsonParser.parseString(messages.poll(5, TimeUnit.SECONDS)).asJsonObject
            assertEquals("hello", hello["type"].asString)
            assertEquals(1, hello["protocol_version"].asInt)
            connection.sendJump(JumpEvent("workout:1", "workout", 1234, 1, 0.8, "test"))
            val jump = JsonParser.parseString(messages.poll(5, TimeUnit.SECONDS)).asJsonObject
            assertEquals("jump", jump["type"].asString)
            assertEquals("workout:1", jump["event_id"].asString)
            assertEquals("workout", jump["workout_session_id"].asString)
            assertEquals(1234, jump["timestamp_ms"].asInt)
            connection.disconnect()
            assertEquals("연결 안 됨", connection.status.value)
        } finally {
            connection.close()
            server.close()
        }
    }

    @Test fun disconnectedJumpIsNotReplayedAfterConnecting() {
        val messages = LinkedBlockingQueue<String>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { messages.add(text) }
        }))
        server.start()
        val connection = GodotConnection()
        try {
            connection.sendJump(JumpEvent("old:1", "old", 1000, 1, 0.8, "test"))
            connection.connect(server.url("/").toString().replaceFirst("http://", "ws://"))
            assertEquals("hello", JsonParser.parseString(messages.poll(5, TimeUnit.SECONDS)).asJsonObject["type"].asString)
            assertNull(messages.poll(250, TimeUnit.MILLISECONDS))
        } finally {
            connection.close()
            server.close()
        }
    }

    @Test fun invalidAddressShowsErrorWithoutThrowing() {
        val connection = GodotConnection()
        try {
            connection.connect("not a websocket address")
            assertTrue(connection.status.value.startsWith("주소를 확인하세요"))
        } finally { connection.close() }
    }
}
