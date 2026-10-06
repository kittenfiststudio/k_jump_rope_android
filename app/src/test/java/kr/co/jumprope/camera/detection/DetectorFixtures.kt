package kr.co.jumprope.camera.detection

import kr.co.jumprope.camera.pose.*
import kotlin.math.PI
import kotlin.math.sin

object DetectorFixtures {
    fun frame(t: Long, lift: Float = 0f, ankleLift: Float = lift, squat: Float = 0f,
              offsetX: Float = 0f): PoseFrame {
        val points = mapOf(
            Joint.LEFT_SHOULDER to (0.42f to 0.20f), Joint.RIGHT_SHOULDER to (0.58f to 0.20f),
            Joint.LEFT_HIP to (0.45f to (0.48f + squat)), Joint.RIGHT_HIP to (0.55f to (0.48f + squat)),
            Joint.LEFT_KNEE to (0.45f to (0.66f + squat / 2)), Joint.RIGHT_KNEE to (0.55f to (0.66f + squat / 2)),
            Joint.LEFT_ANKLE to (0.45f to 0.86f), Joint.RIGHT_ANKLE to (0.55f to 0.86f)
        ).mapValues { (joint, xy) -> Landmark(xy.first + offsetX,
            xy.second - if (joint in listOf(Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE)) ankleLift else lift, 0.95f, 0.95f) }
        return PoseFrame(t, points, 480, 640, ImageTransform(640, 480, 0, 0, 640, 480, 90, true), TrackingStatus.FULL_BODY)
    }
    fun standing(from: Long = 0, duration: Long = 1500, step: Long = 33) =
        generateSequence(from) { it + step }.takeWhile { it <= from + duration }.map { frame(it) }.toList()
    fun cycles(from: Long, count: Int, amplitude: Float = 0.055f, step: Long = 33,
               period: Long = 600, airborne: Long = 300): List<PoseFrame> =
        generateSequence(from) { it + step }.takeWhile { it <= from + count * period + 400 }.map { t ->
            val phase = (t - from) % period
            val lift = if (t < from + count * period && phase <= airborne)
                (sin(PI * phase / airborne) * amplitude).toFloat() else 0f
            frame(t, lift)
        }.toList()
}
