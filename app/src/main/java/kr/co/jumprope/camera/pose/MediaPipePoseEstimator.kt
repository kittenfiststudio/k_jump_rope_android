package kr.co.jumprope.camera.pose

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.concurrent.atomic.AtomicReference

class MediaPipePoseEstimator(
    context: Context,
    private val onResult: (PoseResult) -> Unit,
    private val onError: (String) -> Unit
) : PoseEstimator {
    private data class Pending(val bitmap: Bitmap, val image: MPImage,
                               val timestamp: Long, val submittedAt: Long, val transform: ImageTransform) {
        fun release() { image.close(); bitmap.recycle() }
    }
    private val pending = AtomicReference<Pending?>(null)
    @Volatile private var closed = false
    private var lastTimestamp = -1L
    override val acceptingFrame: Boolean get() = !closed && pending.get() == null
    // SDK indices never escape this adapter.
    private val indices = mapOf(Joint.NOSE to 0, Joint.LEFT_SHOULDER to 11, Joint.RIGHT_SHOULDER to 12,
        Joint.LEFT_ELBOW to 13, Joint.RIGHT_ELBOW to 14, Joint.LEFT_WRIST to 15, Joint.RIGHT_WRIST to 16,
        Joint.LEFT_HIP to 23, Joint.RIGHT_HIP to 24, Joint.LEFT_KNEE to 25, Joint.RIGHT_KNEE to 26,
        Joint.LEFT_ANKLE to 27, Joint.RIGHT_ANKLE to 28, Joint.LEFT_HEEL to 29, Joint.RIGHT_HEEL to 30,
        Joint.LEFT_FOOT_INDEX to 31, Joint.RIGHT_FOOT_INDEX to 32)
    private val landmarker = PoseLandmarker.createFromOptions(context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task")
                .setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.LIVE_STREAM).setNumPoses(1)
            .setOutputSegmentationMasks(false)
            .setMinPoseDetectionConfidence(0.5f).setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ ->
                val request = pending.get() ?: return@setResultListener
                if (result.timestampMs() != request.timestamp) return@setResultListener
                try {
                    if (!closed) {
                        val pose = result.landmarks().firstOrNull()
                        val points = indices.mapValues { (_, index) -> pose?.getOrNull(index)?.let {
                            Landmark(it.x(), it.y(), it.visibility().orElse(null), it.presence().orElse(null))
                        } }
                        onResult(PoseResult(PoseFrame(request.timestamp, points, request.bitmap.width,
                            request.bitmap.height, request.transform, PoseQuality.status(points)),
                            SystemClock.elapsedRealtime() - request.submittedAt))
                    }
                } finally {
                    // Retain pixels until the native result listener has consumed the image.
                    if (pending.compareAndSet(request, null)) request.release()
                }
            }
            .setErrorListener { error ->
                // An asynchronous error stops this instance; release native input during close.
                if (!closed) onError("포즈 추론 오류: ${error.message ?: "알 수 없는 오류"}")
            }.build())

    override fun submit(bitmap: Bitmap, timestampMs: Long, transform: ImageTransform): Boolean {
        if (closed || timestampMs <= lastTimestamp || pending.get() != null) {
            bitmap.recycle()
            return false
        }
        val request = Pending(bitmap, BitmapImageBuilder(bitmap).build(), timestampMs,
            SystemClock.elapsedRealtime(), transform)
        pending.set(request)
        lastTimestamp = timestampMs
        try { landmarker.detectAsync(request.image, timestampMs) }
        catch (error: Exception) {
            if (pending.compareAndSet(request, null)) request.release()
            onError("포즈 제출 오류: ${error.message}")
            return false
        }
        return true
    }

    override fun close() {
        closed = true
        try { landmarker.close() } finally { pending.getAndSet(null)?.release() }
    }
}
