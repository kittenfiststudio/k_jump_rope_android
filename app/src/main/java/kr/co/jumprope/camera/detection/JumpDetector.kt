package kr.co.jumprope.camera.detection

import kr.co.jumprope.camera.pose.Joint
import kr.co.jumprope.camera.pose.Landmark
import kr.co.jumprope.camera.pose.PoseFrame
import kotlin.math.*

data class DetectorConfig(
    val version: String = "hip-sensitive-2",
    val calibrationMs: Long = 1000,
    val confidenceThreshold: Float = 0.5f,
    val stabilityRange: Double = 0.025,
    val filterTauMs: Double = 45.0,
    val baselineTauMs: Double = 4000.0,
    val risingLift: Double = 0.0036,
    val minimumHipLift: Double = 0.007,
    val risingVelocity: Double = 0.016,
    val fallingVelocity: Double = -0.01,
    // A lower return tolerance would make landing stricter, so retain it.
    val landingHipLift: Double = 0.015,
    val landingHoldMs: Long = 45,
    val minimumCycleMs: Long = 180,
    val maximumCycleMs: Long = 1800,
    val trackingGapMs: Long = 250,
    val positionJumpLimit: Double = 0.35,
    val sizeChangeLimit: Double = 0.25,
    val standingKneeAngleDegrees: Double = 150.0,
    val intervalWindow: Int = 5,
    val cadenceTimeoutMs: Long = 2500
) {
    fun validate() {
        require(calibrationMs in 100..10_000 && trackingGapMs in 50..2000)
        require(confidenceThreshold in 0f..1f && intervalWindow in 1..20)
        require(listOf(stabilityRange, filterTauMs, baselineTauMs, risingLift, minimumHipLift,
            risingVelocity, landingHipLift,
            positionJumpLimit, sizeChangeLimit).all { it.isFinite() && it > 0 })
        require(fallingVelocity.isFinite() && fallingVelocity < 0 && minimumHipLift > risingLift)
        require(minimumCycleMs > 0 && maximumCycleMs > minimumCycleMs && landingHoldMs > 0)
        require(cadenceTimeoutMs > maximumCycleMs && standingKneeAngleDegrees in 90.0..180.0)
    }
}

enum class DetectorState { CALIBRATING, GROUND, RISING, FALLING, LANDING, LOST }
data class JumpEvent(val eventId: String, val sessionId: String, val timestampMs: Long,
                     val sequence: Int, val qualityScore: Double, val detectorVersion: String)
data class DetectorSnapshot(
    val state: DetectorState = DetectorState.CALIBRATING,
    val qualityScore: Double = 0.0,
    val baselineHip: Double? = null,
    val baselineAnkle: Double? = null,
    val bodyScale: Double? = null,
    val hipLift: Double = 0.0,
    val hipVelocity: Double = 0.0,
    val ankleLift: Double = 0.0,
    val calibrationProgress: Double = 0.0,
    val interruption: String? = null,
    val ignoredTimestamp: Boolean = false,
    val event: JumpEvent? = null
) {
    val validTracking: Boolean get() = state in listOf(DetectorState.GROUND, DetectorState.RISING,
        DetectorState.FALLING, DetectorState.LANDING) && !ignoredTimestamp
}

/** Pure Kotlin. Coordinates are used as image-space signals, never as physical height. */
class JumpDetector(val config: DetectorConfig = DetectorConfig(), private val sessionId: String) {
    companion object { const val VERSION = "jump-detector-2.0.0" }
    private data class Sample(val hip: Double, val leftAnkle: Double, val rightAnkle: Double,
                              val centerX: Double, val scale: Double, val standing: Boolean, val quality: Double)
    private var state = DetectorState.CALIBRATING
    private var lastTimestamp: Long? = null
    private var previousSample: Sample? = null
    private var baseline: Sample? = null
    private val calibration = ArrayDeque<Pair<Long, Sample>>()
    private var hipSignal = 0.0
    private var leftSignal = 0.0
    private var rightSignal = 0.0
    private var cycleStarted = 0L
    private var landingStarted = 0L
    private var peakHip = 0.0
    private var lastJump: Long? = null
    private var sequence = 0
    var snapshot = DetectorSnapshot(); private set

    init { config.validate() }

    /** Reset tracking/calibration and discard partial cycles; keep event identity/sequence. */
    fun interrupt(reason: String): DetectorSnapshot {
        state = DetectorState.LOST; baseline = null; calibration.clear(); previousSample = null
        hipSignal = 0.0; leftSignal = 0.0; rightSignal = 0.0
        snapshot = DetectorSnapshot(state = state, interruption = reason)
        return snapshot
    }

    fun process(frame: PoseFrame): DetectorSnapshot {
        val t = frame.timestampMs
        val previousTime = lastTimestamp
        if (t < 0 || (previousTime != null && t <= previousTime)) {
            return snapshot.copy(event = null, ignoredTimestamp = true)
        }
        lastTimestamp = t
        val dt = previousTime?.let { t - it } ?: 0
        val sample = sample(frame) ?: return interrupt("필수 관절 신호 누락")
        var reason: String? = null
        if (dt > config.trackingGapMs) { interrupt("프레임 간격 초과"); reason = "프레임 간격 초과" }
        previousSample?.let { previous ->
            if (abs(sample.centerX - previous.centerX) / previous.scale > config.positionJumpLimit ||
                abs(sample.hip - previous.hip) / previous.scale > config.positionJumpLimit) {
                interrupt("대상 위치 급변"); reason = "대상 위치 급변"
            }
        }
        previousSample = sample
        val base = baseline
        if (base != null && (abs(sample.scale / base.scale - 1) > config.sizeChangeLimit ||
                abs(sample.centerX - base.centerX) / base.scale > config.positionJumpLimit)) {
            interrupt("위치 또는 몸 크기 변경"); previousSample = sample; reason = "위치 또는 몸 크기 변경"
        }
        if (baseline == null) return calibrate(t, sample, reason)

        val b = baseline!!
        val alpha = 1 - exp(-dt.toDouble() / config.filterTauMs)
        val oldHip = hipSignal
        hipSignal += alpha * ((b.hip - sample.hip) / b.scale - hipSignal)
        leftSignal += alpha * ((b.leftAnkle - sample.leftAnkle) / b.scale - leftSignal)
        rightSignal += alpha * ((b.rightAnkle - sample.rightAnkle) / b.scale - rightSignal)
        val velocity = if (dt > 0) (hipSignal - oldHip) * 1000 / dt else 0.0
        val ankles = min(leftSignal, rightSignal)
        var event: JumpEvent? = null
        if (state != DetectorState.GROUND && t - cycleStarted > config.maximumCycleMs) {
            return interrupt("점프 주기 시간 초과")
        }
        when (state) {
            DetectorState.GROUND -> {
                if (hipSignal >= config.risingLift && velocity >= config.risingVelocity) {
                    state = DetectorState.RISING; cycleStarted = t
                    peakHip = hipSignal
                } else if (sample.standing && abs(hipSignal) <= config.landingHipLift &&
                    abs(velocity) < config.risingVelocity) {
                    val a = 1 - exp(-dt.toDouble() / config.baselineTauMs)
                    fun blend(old: Double, new: Double) = old + a * (new - old)
                    baseline = b.copy(hip = blend(b.hip, sample.hip),
                        leftAnkle = blend(b.leftAnkle, sample.leftAnkle), rightAnkle = blend(b.rightAnkle, sample.rightAnkle),
                        centerX = blend(b.centerX, sample.centerX), scale = blend(b.scale, sample.scale))
                }
            }
            DetectorState.RISING -> {
                peakHip = max(peakHip, hipSignal)
                if (velocity <= config.fallingVelocity) state = DetectorState.FALLING
            }
            DetectorState.FALLING -> {
                if (hipSignal <= config.landingHipLift) {
                    if (peakHip >= config.minimumHipLift) {
                        state = DetectorState.LANDING; landingStarted = t
                    } else { state = DetectorState.GROUND }
                }
            }
            DetectorState.LANDING -> {
                if (hipSignal > config.landingHipLift) {
                    state = DetectorState.FALLING
                } else if (t - landingStarted >= config.landingHoldMs) {
                    if (t - cycleStarted >= config.minimumCycleMs &&
                        (lastJump == null || t - lastJump!! >= config.minimumCycleMs)) {
                        sequence++; lastJump = t
                        event = JumpEvent("$sessionId:$sequence", sessionId, t, sequence, sample.quality, VERSION)
                    }
                    state = DetectorState.GROUND
                }
            }
            else -> Unit
        }
        snapshot = DetectorSnapshot(state, sample.quality, b.hip, (b.leftAnkle + b.rightAnkle) / 2,
            b.scale, hipSignal, velocity, ankles, 1.0, event = event)
        return snapshot
    }

    private fun calibrate(t: Long, sample: Sample, reason: String?): DetectorSnapshot {
        state = DetectorState.CALIBRATING
        if (!sample.standing) calibration.clear()
        else {
            calibration.addLast(t to sample)
            val scale = sample.scale
            fun unstable(value: (Sample) -> Double) =
                (calibration.maxOf { value(it.second) } - calibration.minOf { value(it.second) }) / scale > config.stabilityRange
            if (unstable { it.hip } || unstable { it.centerX }) {
                calibration.clear(); calibration.addLast(t to sample)
            }
            if (t - calibration.first().first >= config.calibrationMs) {
                fun average(value: (Sample) -> Double) = calibration.map { value(it.second) }.average()
                baseline = sample.copy(hip = average { it.hip }, leftAnkle = average { it.leftAnkle },
                    rightAnkle = average { it.rightAnkle }, centerX = average { it.centerX }, scale = average { it.scale })
                calibration.clear(); state = DetectorState.GROUND
                hipSignal = 0.0; leftSignal = 0.0; rightSignal = 0.0
            }
        }
        val progress = if (state == DetectorState.GROUND) 1.0 else calibration.firstOrNull()?.let {
            ((t - it.first).toDouble() / config.calibrationMs).coerceIn(0.0, 1.0)
        } ?: 0.0
        snapshot = DetectorSnapshot(state, sample.quality, baseline?.hip,
            baseline?.let { (it.leftAnkle + it.rightAnkle) / 2 }, baseline?.scale,
            calibrationProgress = progress, interruption = reason)
        return snapshot
    }

    private fun sample(frame: PoseFrame): Sample? {
        val joints = listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_HIP, Joint.RIGHT_HIP,
            Joint.LEFT_KNEE, Joint.RIGHT_KNEE, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE)
        val points = joints.map { frame.landmarks[it] ?: return null }
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f ||
                it.visibility?.let { v -> !v.isFinite() || v < config.confidenceThreshold } != false ||
                it.presence?.let { p -> !p.isFinite() || p < config.confidenceThreshold } == true }) return null
        fun avgY(a: Int, b: Int) = (points[a].y.toDouble() + points[b].y) / 2
        val shoulders = avgY(0, 1); val hip = avgY(2, 3)
        val ankle = avgY(6, 7); val scale = ankle - shoulders
        if (scale < 0.15 || hip <= shoulders || ankle <= hip) return null
        fun angle(a: Landmark, b: Landmark, c: Landmark): Double {
            val aspect = frame.width.toDouble() / frame.height.coerceAtLeast(1)
            val ux = (a.x - b.x) * aspect; val uy = (a.y - b.y).toDouble()
            val vx = (c.x - b.x) * aspect; val vy = (c.y - b.y).toDouble()
            val norm = hypot(ux, uy) * hypot(vx, vy)
            return if (norm < 1e-8) 0.0 else Math.toDegrees(acos(((ux * vx + uy * vy) / norm).coerceIn(-1.0, 1.0)))
        }
        val standing = angle(points[2], points[4], points[6]) >= config.standingKneeAngleDegrees &&
            angle(points[3], points[5], points[7]) >= config.standingKneeAngleDegrees
        val quality = points.minOf { min(it.visibility!!.toDouble(), it.presence?.toDouble() ?: it.visibility.toDouble()) }
        return Sample(hip, points[6].y.toDouble(), points[7].y.toDouble(),
            (points[2].x.toDouble() + points[3].x) / 2, scale, standing, quality)
    }
}
