package kr.co.jumprope.camera.session

import kr.co.jumprope.camera.detection.*
import kr.co.jumprope.camera.pose.PoseFrame

enum class SessionPhase { IDLE, COUNTDOWN, CALIBRATING, COUNTING, PAUSED, FINISHED }
data class WorkoutSession(
    val id: String, val startedAtUtc: String, val endedAtUtc: String,
    val activeDurationMs: Long, val validTrackingDurationMs: Long,
    val jumpCount: Int, val averageJumpsPerMinute: Double?, val peakJumpsPerMinute: Double,
    val detectionSource: String = "CAMERA", val engineId: String,
    val modelId: String, val detectorVersion: String = JumpDetector.VERSION,
    val parameterVersion: String, val endReason: String
)
data class WorkoutState(
    val phase: SessionPhase = SessionPhase.IDLE, val jumpCount: Int = 0,
    val activeDurationMs: Long = 0, val validTrackingDurationMs: Long = 0,
    val currentJumpsPerMinute: Double = 0.0, val peakJumpsPerMinute: Double = 0.0,
    val countdownSeconds: Int = 3, val lastJumpIntervalMs: Long? = null,
    val detector: DetectorSnapshot = DetectorSnapshot()
)

/** Session time is supplied by the caller, independent from Android and callback arrival time. */
class WorkoutEngine(val id: String, private val startedAtUtc: String,
                    val config: DetectorConfig = DetectorConfig(), val countdownMs: Long = 3000) {
    private val detector = JumpDetector(config, id)
    var state = WorkoutState(phase = SessionPhase.COUNTDOWN); private set
    private var lastClock = 0L
    private var lastFrame: Long? = null
    private var lastAcceptedFrame = -1L
    private var minimumFrameTimestamp = countdownMs
    private var previousValid = false
    private var lastJump: Long? = null
    private val intervals = ArrayDeque<Long>()
    private var engineId = "mediapipe-tasks-vision-0.10.21"
    private var modelId = "pose_landmarker_lite-float16-v1"

    fun tick(now: Long) {
        if (now < lastClock || state.phase == SessionPhase.FINISHED) return
        val active = if (state.phase == SessionPhase.COUNTING) now - lastClock else 0
        lastClock = now
        state = state.copy(activeDurationMs = state.activeDurationMs + active)
        if (state.phase == SessionPhase.COUNTDOWN) {
            state = state.copy(countdownSeconds = ((countdownMs - now + 999).coerceAtLeast(0) / 1000).toInt())
            if (now >= countdownMs) state = state.copy(phase = SessionPhase.CALIBRATING)
        }
        if (lastFrame != null && now - lastFrame!! > config.trackingGapMs && previousValid) interrupt("추적 결과 시간 초과")
        if (lastJump == null || now - lastJump!! >= config.cadenceTimeoutMs) state = state.copy(currentJumpsPerMinute = 0.0)
    }

    fun frame(frame: PoseFrame): DetectorSnapshot? {
        if (frame.timestampMs <= lastAcceptedFrame || frame.timestampMs < minimumFrameTimestamp) return null
        lastAcceptedFrame = frame.timestampMs
        tick(frame.timestampMs)
        if (state.phase !in listOf(SessionPhase.CALIBRATING, SessionPhase.COUNTING)) return null
        engineId = frame.engineId; modelId = frame.modelId
        val result = detector.process(frame)
        val valid = result.validTracking
        val prior = lastFrame
        var duration = state.validTrackingDurationMs
        if (state.phase == SessionPhase.COUNTING && valid && previousValid && prior != null &&
            frame.timestampMs - prior <= config.trackingGapMs) duration += frame.timestampMs - prior
        if (!valid) breakCadence()
        lastFrame = frame.timestampMs; previousValid = valid
        val phase = if (state.phase == SessionPhase.CALIBRATING && valid) SessionPhase.COUNTING else state.phase
        val initialCatchUp = if (state.phase == SessionPhase.CALIBRATING && valid)
            (lastClock - frame.timestampMs).coerceAtLeast(0) else 0
        state = state.copy(phase = phase, detector = result, validTrackingDurationMs = duration,
            activeDurationMs = state.activeDurationMs + initialCatchUp)
        result.event?.let { event ->
            val interval = lastJump?.let { event.timestampMs - it }
            if (interval != null && interval in config.minimumCycleMs..config.cadenceTimeoutMs) {
                intervals.addLast(interval)
                while (intervals.size > config.intervalWindow) intervals.removeFirst()
            } else intervals.clear()
            val cadence = if (intervals.isEmpty()) 0.0 else 60_000.0 / intervals.average()
            lastJump = event.timestampMs
            state = state.copy(jumpCount = state.jumpCount + 1, currentJumpsPerMinute = cadence,
                peakJumpsPerMinute = maxOf(state.peakJumpsPerMinute, cadence), lastJumpIntervalMs = interval)
        }
        return result
    }

    fun interrupt(reason: String) {
        previousValid = false; lastFrame = null; breakCadence()
        state = state.copy(detector = detector.interrupt(reason))
    }
    private fun breakCadence() {
        lastJump = null; intervals.clear()
        state = state.copy(currentJumpsPerMinute = 0.0, lastJumpIntervalMs = null)
    }
    fun pause(now: Long) {
        tick(now)
        if (state.phase !in listOf(SessionPhase.COUNTDOWN, SessionPhase.CALIBRATING, SessionPhase.COUNTING)) return
        interrupt("일시정지"); state = state.copy(phase = SessionPhase.PAUSED)
    }
    fun resume(now: Long) {
        if (state.phase != SessionPhase.PAUSED) return
        tick(now); minimumFrameTimestamp = now; interrupt("재개 후 보정"); state = state.copy(phase = SessionPhase.CALIBRATING)
    }
    fun finish(now: Long, endedAtUtc: String, reason: String = "USER"): WorkoutSession {
        tick(now); state = state.copy(phase = SessionPhase.FINISHED)
        return WorkoutSession(id, startedAtUtc, endedAtUtc, state.activeDurationMs, state.validTrackingDurationMs,
            state.jumpCount, if (state.validTrackingDurationMs == 0L) null else state.jumpCount * 60_000.0 / state.validTrackingDurationMs,
            state.peakJumpsPerMinute, engineId = engineId, modelId = modelId, parameterVersion = config.version, endReason = reason)
    }
}
