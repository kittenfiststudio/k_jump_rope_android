package kr.co.jumprope.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kr.co.jumprope.camera.pose.Joint
import kr.co.jumprope.camera.pose.OverlayMapper
import kr.co.jumprope.camera.pose.PoseFrame
import kr.co.jumprope.camera.pose.PoseQuality

private val bones = listOf(
    Joint.LEFT_SHOULDER to Joint.RIGHT_SHOULDER, Joint.LEFT_SHOULDER to Joint.LEFT_ELBOW,
    Joint.LEFT_ELBOW to Joint.LEFT_WRIST, Joint.RIGHT_SHOULDER to Joint.RIGHT_ELBOW,
    Joint.RIGHT_ELBOW to Joint.RIGHT_WRIST, Joint.LEFT_SHOULDER to Joint.LEFT_HIP,
    Joint.RIGHT_SHOULDER to Joint.RIGHT_HIP, Joint.LEFT_HIP to Joint.RIGHT_HIP,
    Joint.LEFT_HIP to Joint.LEFT_KNEE, Joint.LEFT_KNEE to Joint.LEFT_ANKLE,
    Joint.RIGHT_HIP to Joint.RIGHT_KNEE, Joint.RIGHT_KNEE to Joint.RIGHT_ANKLE,
    Joint.LEFT_ANKLE to Joint.LEFT_HEEL, Joint.LEFT_HEEL to Joint.LEFT_FOOT_INDEX,
    Joint.LEFT_ANKLE to Joint.LEFT_FOOT_INDEX, Joint.RIGHT_ANKLE to Joint.RIGHT_HEEL,
    Joint.RIGHT_HEEL to Joint.RIGHT_FOOT_INDEX, Joint.RIGHT_ANKLE to Joint.RIGHT_FOOT_INDEX
)

@Composable
fun SkeletonOverlay(frame: PoseFrame?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (frame == null) return@Canvas
        fun point(joint: Joint): Offset? {
            val landmark = frame.landmarks[joint]
            if (!PoseQuality.usable(landmark)) return null
            val (x, y) = OverlayMapper.map(landmark!!.x, landmark.y, frame.width, frame.height,
                size.width, size.height, frame.transform.selfieMirrored)
            return Offset(x, y)
        }
        bones.forEach { (first, second) ->
            val a = point(first); val b = point(second)
            if (a != null && b != null) drawLine(Color(0xFF58E8C6), a, b, 3.dp.toPx())
        }
        frame.landmarks.keys.forEach { joint -> point(joint)?.let { position ->
            val ankle = joint == Joint.LEFT_ANKLE || joint == Joint.RIGHT_ANKLE
            drawCircle(if (ankle) Color(0xFFFFCB6B) else Color.White, 5.dp.toPx(), position)
        } }
    }
}
