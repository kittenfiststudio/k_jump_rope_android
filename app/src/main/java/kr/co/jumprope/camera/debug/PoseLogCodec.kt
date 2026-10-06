package kr.co.jumprope.camera.debug

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.co.jumprope.camera.detection.DetectorConfig
import kr.co.jumprope.camera.pose.PoseFrame
import kr.co.jumprope.camera.session.WorkoutEngine
import kr.co.jumprope.camera.session.WorkoutSession

data class LogHeader(
    val schemaVersion: Int = 1, val sessionId: String, val startedAtUtc: String,
    val device: String, val osVersion: String, val engineId: String, val modelId: String,
    val detectorVersion: String, val config: DetectorConfig, val countdownMs: Long = 3000
)
data class ControlRecord(val timestampMs: Long, val action: String, val reason: String? = null)
data class ReplayReport(val frameCount: Int, val jumpCount: Int, val recordedJumpCount: Int?,
                        val eventTimestamps: List<Long>, val validTrackingDurationMs: Long,
                        val averageJumpsPerMinute: Double?, val complete: Boolean)

object PoseLogCodec {
    // Preserve null/missing confidence. Nonfinite SDK coordinates become null, not clamped points.
    val gson = GsonBuilder().serializeNulls().create()
    fun line(type: String, value: Any): String = gson.toJson(JsonObject().apply {
        addProperty("type", type); add("data", gson.toJsonTree(value))
    })
    fun clean(frame: PoseFrame) = frame.copy(landmarks = frame.landmarks.mapValues { (_, p) ->
        p?.takeIf { it.x.isFinite() && it.y.isFinite() }?.let {
            it.copy(visibility = it.visibility?.takeIf(Float::isFinite), presence = it.presence?.takeIf(Float::isFinite))
        }
    })

    /** Recomputes events from raw PoseFrames, ignoring recorded events and detector transitions. */
    fun replay(lines: Sequence<String>): ReplayReport {
        var engine: WorkoutEngine? = null
        var frames = 0
        var recorded: Int? = null
        var complete = false
        val times = mutableListOf<Long>()
        lines.forEachIndexed { index, line ->
            require(line.length <= 100_000 && index <= 100_000) { "로그 크기 제한 초과" }
            val record = JsonParser.parseString(line).asJsonObject
            val type = record.get("type").asString
            val data = record.get("data")
            if (index == 0) require(type == "header") { "로그 header 누락" }
            when (type) {
                "header" -> {
                    require(index == 0 && engine == null) { "중복 header" }
                    val h = gson.fromJson(data, LogHeader::class.java)
                    require(h.schemaVersion == 1 && h.countdownMs in 0..10_000) { "지원하지 않는 로그 버전" }
                    h.config.validate()
                    engine = WorkoutEngine(h.sessionId, h.startedAtUtc, h.config, h.countdownMs)
                }
                "frame" -> {
                    val frame = gson.fromJson(data, PoseFrame::class.java)
                    require(frame.width > 0 && frame.height > 0 && frame.timestampMs >= 0) { "잘못된 포즈 프레임" }
                    frames++
                    engine!!.frame(frame)?.event?.let { times += it.timestampMs }
                }
                "control" -> {
                    val c = gson.fromJson(data, ControlRecord::class.java)
                    when (c.action) {
                        "pause" -> engine!!.pause(c.timestampMs)
                        "resume" -> engine!!.resume(c.timestampMs)
                        "interruption" -> engine!!.interrupt(c.reason ?: "추적 중단")
                        "tick" -> engine!!.tick(c.timestampMs)
                        "finish" -> engine!!.finish(c.timestampMs, "replay")
                        else -> error("지원하지 않는 control")
                    }
                }
                "summary" -> {
                    val s = gson.fromJson(data, WorkoutSession::class.java)
                    recorded = s.jumpCount; complete = true
                }
                "transition", "event" -> Unit
                else -> error("지원하지 않는 로그 항목")
            }
        }
        val state = requireNotNull(engine) { "빈 로그" }.state
        return ReplayReport(frames, state.jumpCount, recorded, times, state.validTrackingDurationMs,
            if (state.validTrackingDurationMs == 0L) null else state.jumpCount * 60_000.0 / state.validTrackingDurationMs, complete)
    }
}
