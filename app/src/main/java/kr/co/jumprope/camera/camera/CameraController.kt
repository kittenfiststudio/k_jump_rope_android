package kr.co.jumprope.camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import kr.co.jumprope.camera.pose.ImageTransform
import kr.co.jumprope.camera.pose.MediaPipePoseEstimator
import kr.co.jumprope.camera.pose.PoseEstimator
import kr.co.jumprope.camera.pose.PoseResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Owned by the ViewModel. Binding/unbinding happens on main; inference work is serialized. */
class CameraController(
    private val context: Context,
    private val onResult: (PoseResult) -> Unit,
    private val onCameraReady: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onInputFrame: (Long) -> Unit
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var cameraState: LiveData<CameraState>? = null
    private var cameraObserver: Observer<CameraState>? = null
    // Accessed only by worker.
    private var estimator: PoseEstimator? = null
    private var closed = false

    fun start(view: PreviewView, owner: LifecycleOwner) {
        if (closed || view.width == 0 || view.height == 0) return
        stop()
        val token = generation.incrementAndGet()
        worker.execute {
            if (generation.get() != token) return@execute
            try {
                estimator = MediaPipePoseEstimator(context,
                    onResult = { result -> publish(token) { onResult(result) } },
                    onError = { message -> publish(token) { fail(message) } })
                main.post {
                    if (generation.get() != token) return@post
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener({
                        if (generation.get() != token) return@addListener
                        try {
                            val cameraProvider = future.get()
                            provider = cameraProvider
                            val front = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                            if (!cameraProvider.hasCamera(selector)) error("사용 가능한 카메라가 없습니다.")
                            val rotation = view.display.rotation
                            val cameraPreview = Preview.Builder().setTargetRotation(rotation).build()
                            cameraPreview.setSurfaceProvider(view.surfaceProvider)
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setTargetRotation(rotation)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            imageAnalysis.setAnalyzer(worker) { image ->
                                try {
                                    if (generation.get() == token) {
                                        val timestamp = SystemClock.elapsedRealtime()
                                        publish(token) { onInputFrame(timestamp) }
                                        val crop = image.cropRect
                                        val transform = ImageTransform(image.width, image.height,
                                            crop.left, crop.top, crop.width(), crop.height(),
                                            image.imageInfo.rotationDegrees, front)
                                        estimator?.let { engine ->
                                            if (engine.acceptingFrame) engine.submit(toBitmap(image), timestamp, transform)
                                        }
                                    }
                                } catch (error: Exception) {
                                    publish(token) { fail("프레임 처리 오류: ${error.message}") }
                                } finally { image.close() }
                            }
                            preview = cameraPreview
                            analysis = imageAnalysis
                            val viewport = view.viewPort ?: error("카메라 화면 준비에 실패했습니다.")
                            // Shared sensor crop keeps preview and analysis fields of view aligned.
                            val group = UseCaseGroup.Builder().setViewPort(viewport)
                                .addUseCase(cameraPreview).addUseCase(imageAnalysis).build()
                            val camera = cameraProvider.bindToLifecycle(owner, selector, group)
                            val observer = Observer<CameraState> { state ->
                                if (generation.get() == token && state.error != null) {
                                    fail("카메라를 사용할 수 없습니다. 다른 카메라 앱을 닫고 다시 시도하세요. (코드 ${state.error?.code})")
                                }
                            }
                            cameraState = camera.cameraInfo.cameraState
                            cameraObserver = observer
                            cameraState?.observe(owner, observer)
                            if (generation.get() != token) return@addListener
                            onCameraReady(front)
                        } catch (error: Exception) { fail("카메라 연결 실패: ${error.message}") }
                    }, ContextCompat.getMainExecutor(context))
                }
            } catch (error: Exception) {
                publish(token) { fail("포즈 모델 초기화 실패: ${error.message}. 앱을 다시 설치하거나 재시도하세요.") }
            }
        }
    }

    private fun publish(token: Long, block: () -> Unit) {
        main.post { if (generation.get() == token) block() }
    }

    private fun fail(message: String) { stop(); onError(message) }

    fun stop() {
        generation.incrementAndGet()
        cameraObserver?.let { cameraState?.removeObserver(it) }
        cameraState = null
        cameraObserver = null
        analysis?.clearAnalyzer()
        val ownedCases = listOfNotNull(preview, analysis)
        if (ownedCases.isNotEmpty()) provider?.unbind(*ownedCases.toTypedArray())
        preview = null
        analysis = null
        if (!closed) worker.execute {
            try { estimator?.close() } catch (_: Exception) { /* Already stopping native task. */ }
            estimator = null
        }
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        worker.shutdown()
    }

    private fun toBitmap(image: ImageProxy): Bitmap {
        // CameraX's converter handles RGBA channel layout and row/pixel padding.
        val original = image.toBitmap()
        try {
            val crop = image.cropRect
            val rotation = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(original, crop.left, crop.top, crop.width(), crop.height(), rotation, true)
            return if (rotated === original) original.copy(Bitmap.Config.ARGB_8888, false) else rotated
        } finally { original.recycle() }
    }
}
