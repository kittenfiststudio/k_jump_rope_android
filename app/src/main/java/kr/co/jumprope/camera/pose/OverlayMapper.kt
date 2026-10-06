package kr.co.jumprope.camera.pose

import kotlin.math.max

/** Same centered FILL_CENTER transform as PreviewView; mirror only at rendering time. */
object OverlayMapper {
    fun map(x: Float, y: Float, imageWidth: Int, imageHeight: Int,
            viewWidth: Float, viewHeight: Float, mirror: Boolean): Pair<Float, Float> {
        val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
        val renderedWidth = imageWidth * scale
        val renderedHeight = imageHeight * scale
        return ((if (mirror) 1f - x else x) * renderedWidth + (viewWidth - renderedWidth) / 2f) to
            (y * renderedHeight + (viewHeight - renderedHeight) / 2f)
    }
}
