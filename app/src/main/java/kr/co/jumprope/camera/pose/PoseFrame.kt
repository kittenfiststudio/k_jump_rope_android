package kr.co.jumprope.camera.pose

enum class Joint {
    NOSE, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW,
    LEFT_WRIST, RIGHT_WRIST, LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE,
    LEFT_ANKLE, RIGHT_ANKLE, LEFT_HEEL, RIGHT_HEEL, LEFT_FOOT_INDEX, RIGHT_FOOT_INDEX
}

data class Landmark(val x: Float, val y: Float, val visibility: Float?, val presence: Float?)
enum class TrackingStatus { NO_PERSON, PARTIAL_BODY, MISSING_ANKLES, FULL_BODY }
data class ImageTransform(
    val sourceWidth: Int, val sourceHeight: Int,
    val cropLeft: Int, val cropTop: Int, val cropWidth: Int, val cropHeight: Int,
    val rotationDegrees: Int, val selfieMirrored: Boolean
)

/** Cropped, rotation-corrected, UNMIRRORED normalized image coordinates. */
data class PoseFrame(
    val timestampMs: Long,
    val landmarks: Map<Joint, Landmark?>,
    val width: Int, val height: Int,
    val transform: ImageTransform,
    val trackingStatus: TrackingStatus,
    val engineId: String = "mediapipe-tasks-vision-0.10.21",
    val modelId: String = "pose_landmarker_lite-float16-v1"
)

object PoseQuality {
    const val MIN_CONFIDENCE = 0.5f
    val requiredJoints = listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER,
        Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_KNEE, Joint.RIGHT_KNEE,
        Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE)

    fun usable(point: Landmark?): Boolean = point != null &&
        point.x.isFinite() && point.y.isFinite() && point.x in 0f..1f && point.y in 0f..1f &&
        point.visibility?.let { it.isFinite() && it >= MIN_CONFIDENCE } == true &&
        (point.presence == null || (point.presence.isFinite() && point.presence >= MIN_CONFIDENCE))

    fun status(points: Map<Joint, Landmark?>): TrackingStatus = when {
        points.values.none { it != null } -> TrackingStatus.NO_PERSON
        !usable(points[Joint.LEFT_ANKLE]) || !usable(points[Joint.RIGHT_ANKLE]) -> TrackingStatus.MISSING_ANKLES
        requiredJoints.any { !usable(points[it]) } -> TrackingStatus.PARTIAL_BODY
        else -> TrackingStatus.FULL_BODY
    }
}
