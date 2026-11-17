package kr.co.jumprope.camera.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.co.jumprope.camera.detection.*
import kr.co.jumprope.camera.session.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GodotWorkoutFlowTest {
    private fun withConnection(test: (GodotConnection, LinkedBlockingQueue<JsonObject>, String) -> Unit) {
        val messages = LinkedBlockingQueue<JsonObject>()
        val server = MockWebServer()
        // Two upgrades also allow a reconnect in the same test.
        repeat(2) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    messages.add(JsonParser.parseString(text).asJsonObject)
                }
            }))
        }
        server.start()
        val connection = GodotConnection("android-test")
        val url = server.url("/").toString().replaceFirst("http://", "ws://")
        try { test(connection, messages, url) }
        finally { connection.close(); server.close() }
    }
    private fun LinkedBlockingQueue<JsonObject>.next(): JsonObject =
        poll(5, TimeUnit.SECONDS) ?: error("Expected a message")
    private fun ready(count: Int) = WorkoutState(phase = SessionPhase.COUNTING, jumpCount = count,
        detector = DetectorSnapshot(state = DetectorState.GROUND))

    @Test fun actualDetectorFlowSendsReadyOnceThenCompletedJumpsAndControlStates() = withConnection { connection, messages, url ->
        connection.connect(url)
        assertEquals("hello", messages.next()["type"].asString)
        val engine = WorkoutEngine("fixture", "utc", countdownMs = 0)
        connection.updateWorkout(engine.id, engine.state)
        fun frame(frame: kr.co.jumprope.camera.pose.PoseFrame) {
            val result = engine.frame(frame)
            connection.updateWorkout(engine.id, engine.state, result?.event)
        }
        DetectorFixtures.standing().forEach(::frame)
        DetectorFixtures.cycles(1530, 3).forEach(::frame)
        assertEquals(3, engine.state.jumpCount)
        assertEquals("preparing", messages.next()["state"].asString)
        assertEquals("ready", messages.next()["type"].asString)
        repeat(3) { index ->
            val jump = messages.next()
            assertEquals("jump", jump["type"].asString)
            assertEquals("android-test", jump["player_id"].asString)
            assertEquals(index + 1, jump["sequence"].asInt)
            assertEquals("Counting Up", jump["message"].asString)
        }
        assertNull(messages.poll(100, TimeUnit.MILLISECONDS))
        engine.pause(5000); connection.updateWorkout(engine.id, engine.state)
        assertEquals("paused", messages.next()["state"].asString)
        engine.resume(6000); connection.updateWorkout(engine.id, engine.state)
        assertEquals("preparing", messages.next()["state"].asString)
        DetectorFixtures.standing(6033).forEach(::frame)
        assertEquals("ready", messages.next()["type"].asString)
        engine.tick(9000); connection.updateWorkout(engine.id, engine.state)
        assertEquals("tracking_lost", messages.next()["state"].asString)
        engine.finish(9100, "end"); connection.updateWorkout(engine.id, engine.state)
        val finished = messages.next()
        assertEquals("finished", finished["state"].asString)
        assertEquals(3, finished["total_count"].asInt)
    }

    @Test fun reconnectRestoresCurrentCountWithoutReplayingPastJumps() = withConnection { connection, messages, url ->
        connection.updateWorkout("workout", ready(4))
        connection.connect(url)
        assertEquals("hello", messages.next()["type"].asString)
        assertEquals(4, messages.next()["total_count"].asInt)
        connection.disconnect()
        connection.updateWorkout("workout", ready(5), JumpEvent("workout:5", "workout", 2000, 5, 0.9, "test"))
        connection.connect(url)
        assertEquals("hello", messages.next()["type"].asString)
        val restored = messages.next()
        assertEquals("ready", restored["type"].asString)
        assertEquals(5, restored["total_count"].asInt)
        assertNull(messages.poll(150, TimeUnit.MILLISECONDS))
    }

    @Test fun readyAndJumpInSameFrameKeepTheNewJumpAvailableForAnimation() = withConnection { connection, messages, url ->
        connection.connect(url)
        messages.next()
        connection.updateWorkout("workout", ready(1), JumpEvent("workout:1", "workout", 1000, 1, 0.9, "test"))
        val ready = messages.next()
        assertEquals("ready", ready["type"].asString)
        assertEquals(0, ready["total_count"].asInt)
        assertEquals(1, messages.next()["sequence"].asInt)
        repeat(10) { connection.updateWorkout("workout", ready(1)) }
        assertNull(messages.poll(100, TimeUnit.MILLISECONDS))
    }
}
