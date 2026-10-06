package kr.co.jumprope.camera.pose

import android.graphics.Bitmap

data class PoseResult(val frame: PoseFrame, val latencyMs: Long)

/**
 * All calls run on the camera worker. submit transfers bitmap ownership, even when skipped.
 * Results/errors arrive asynchronously on SDK threads and need not match every submitted frame.
 * close stops native inference before releasing owned images. A closed instance cannot be reused.
 */
interface PoseEstimator : AutoCloseable {
    val acceptingFrame: Boolean
    fun submit(bitmap: Bitmap, timestampMs: Long, transform: ImageTransform): Boolean
    override fun close()
}
