package kr.co.jumprope.camera.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayMapperTest {
    private fun check(expectedX: Float, expectedY: Float, result: Pair<Float, Float>) {
        assertEquals(expectedX, result.first, 0.001f)
        assertEquals(expectedY, result.second, 0.001f)
    }
    @Test fun centeredPortraitPreview() {
        check(50f, 100f, OverlayMapper.map(0.5f, 0.5f, 100, 200, 100f, 200f, false))
    }
    @Test fun selfieMirrorChangesOnlyHorizontalRendering() {
        check(20f, 60f, OverlayMapper.map(0.2f, 0.3f, 100, 200, 100f, 200f, false))
        check(80f, 60f, OverlayMapper.map(0.2f, 0.3f, 100, 200, 100f, 200f, true))
    }
    @Test fun landscapeImageIsCroppedAtTheCenterInPortraitViewport() {
        check(-150f, 0f, OverlayMapper.map(0f, 0f, 200, 100, 100f, 200f, false))
        check(50f, 100f, OverlayMapper.map(0.5f, 0.5f, 200, 100, 100f, 200f, false))
    }
    @Test fun portraitImageIsCroppedAtTheCenterInLandscapeViewport() {
        check(0f, -150f, OverlayMapper.map(0f, 0f, 100, 200, 200f, 100f, false))
        check(100f, 50f, OverlayMapper.map(0.5f, 0.5f, 100, 200, 200f, 100f, false))
    }
}
