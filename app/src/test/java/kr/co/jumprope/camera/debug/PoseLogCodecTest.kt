package kr.co.jumprope.camera.debug

import kr.co.jumprope.camera.detection.*
import kr.co.jumprope.camera.session.WorkoutEngine
import org.junit.Assert.*
import org.junit.Test

class PoseLogCodecTest {
    private fun header() = LogHeader(sessionId = "fixture", startedAtUtc = "utc", device = "synthetic",
        osVersion = "test", engineId = "fixture", modelId = "fixture", detectorVersion = JumpDetector.VERSION,
        config = DetectorConfig(), countdownMs = 0)
    @Test fun replaysRawFramesAndIgnoresFabricatedStoredEvents() {
        val frames = DetectorFixtures.standing() + DetectorFixtures.cycles(1530, 3)
        val lines = listOf(PoseLogCodec.line("header", header())) +
            frames.map { PoseLogCodec.line("frame", it) } +
            listOf(PoseLogCodec.line("event", mapOf("sequence" to 999)))
        val report = PoseLogCodec.replay(lines.asSequence())
        assertEquals(3, report.jumpCount); assertEquals(frames.size, report.frameCount)
        assertFalse(report.complete)
    }
    @Test fun pauseControlsDiscardAnUnfinishedJump() {
        val lines = listOf(PoseLogCodec.line("header", header())) +
            DetectorFixtures.standing().map { PoseLogCodec.line("frame", it) } + listOf(
                PoseLogCodec.line("frame", DetectorFixtures.frame(1530, 0.05f)),
                PoseLogCodec.line("control", ControlRecord(1560, "pause")),
                PoseLogCodec.line("control", ControlRecord(2000, "resume"))) +
            DetectorFixtures.standing(2030).map { PoseLogCodec.line("frame", it) }
        assertEquals(0, PoseLogCodec.replay(lines.asSequence()).jumpCount)
    }
    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSchemaIsRejected() {
        PoseLogCodec.replay(sequenceOf(PoseLogCodec.line("header", header().copy(schemaVersion = 99))))
    }
    @Test fun nullConfidenceSurvivesSerialization() {
        val f = DetectorFixtures.frame(0).copy(landmarks = mapOf(kr.co.jumprope.camera.pose.Joint.LEFT_ANKLE to null))
        assertTrue(PoseLogCodec.line("frame", f).contains("\"LEFT_ANKLE\":null"))
    }
    @Test fun completeLiveTimelineReplaysIdenticalEventsAndMetrics() {
        val h = header()
        val e = WorkoutEngine(h.sessionId, h.startedAtUtc, h.config, h.countdownMs)
        val lines = mutableListOf(PoseLogCodec.line("header", h))
        val originalEvents = mutableListOf<Long>()
        fun feed(frames: List<kr.co.jumprope.camera.pose.PoseFrame>) {
            frames.forEach { frame ->
                // Live ticks may arrive before an older frame's inference callback.
                val tick = frame.timestampMs + 80
                lines += PoseLogCodec.line("control", ControlRecord(tick, "tick")); e.tick(tick)
                lines += PoseLogCodec.line("frame", frame)
                e.frame(frame)?.event?.let { event ->
                    originalEvents += event.timestampMs
                    lines += PoseLogCodec.line("event", event)
                }
            }
        }
        feed(DetectorFixtures.standing() + DetectorFixtures.cycles(1530, 2))
        lines += PoseLogCodec.line("control", ControlRecord(3200, "pause")); e.pause(3200)
        lines += PoseLogCodec.line("control", ControlRecord(8000, "resume")); e.resume(8000)
        feed(DetectorFixtures.standing(8030) + DetectorFixtures.cycles(9560, 2))
        lines += PoseLogCodec.line("control", ControlRecord(12_000, "finish"))
        val summary = e.finish(12_000, "end")
        lines += PoseLogCodec.line("summary", summary)
        val r = PoseLogCodec.replay(lines.asSequence())
        assertEquals(4, r.jumpCount)
        assertEquals(originalEvents, r.eventTimestamps)
        assertEquals(summary.jumpCount, r.recordedJumpCount)
        assertEquals(summary.validTrackingDurationMs, r.validTrackingDurationMs)
        assertEquals(summary.averageJumpsPerMinute!!, r.averageJumpsPerMinute!!, 0.0001)
        assertTrue(r.complete)
    }
}
