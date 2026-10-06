package kr.co.jumprope.camera.pose

import org.junit.Assert.*
import org.junit.Test

class PoseQualityTest {
    private val good = Landmark(0.5f, 0.5f, 0.9f, 0.9f)
    private fun body() = PoseQuality.requiredJoints.associateWith { good as Landmark? }

    @Test fun fullBodyRequiresAllEightJoints() {
        assertEquals(TrackingStatus.FULL_BODY, PoseQuality.status(body()))
        assertEquals(TrackingStatus.NO_PERSON, PoseQuality.status(emptyMap()))
        assertEquals(TrackingStatus.PARTIAL_BODY, PoseQuality.status(body() + (Joint.LEFT_HIP to null)))
    }
    @Test fun missingOrWeakAnkleIsReported() {
        assertEquals(TrackingStatus.MISSING_ANKLES, PoseQuality.status(body() + (Joint.LEFT_ANKLE to null)))
        assertEquals(TrackingStatus.MISSING_ANKLES, PoseQuality.status(body() +
            (Joint.RIGHT_ANKLE to good.copy(visibility = 0.1f))))
    }
    @Test fun outOfImageAndInvalidCoordinatesAreRejectedWithoutClamping() {
        assertFalse(PoseQuality.usable(good.copy(x = 1.2f)))
        assertFalse(PoseQuality.usable(good.copy(y = Float.NaN)))
        assertFalse(PoseQuality.usable(good.copy(visibility = Float.NaN)))
        assertFalse(PoseQuality.usable(good.copy(presence = 0.1f)))
    }
    @Test fun missingConfidenceIsNotInvented() {
        assertFalse(PoseQuality.usable(good.copy(visibility = null)))
        assertTrue(PoseQuality.usable(good.copy(presence = null)))
    }
}
