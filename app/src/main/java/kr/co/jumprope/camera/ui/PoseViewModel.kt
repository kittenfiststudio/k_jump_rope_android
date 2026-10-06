package kr.co.jumprope.camera.ui

import android.app.Application
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.co.jumprope.camera.camera.CameraController
import kr.co.jumprope.camera.pose.PoseFrame
import kr.co.jumprope.camera.pose.PoseResult

data class PoseUiState(
    val frame: PoseFrame? = null,
    val cameraReady: Boolean = false,
    val frontCamera: Boolean = true,
    val error: String? = null,
    val inputFps: Float = 0f,
    val processingFps: Float = 0f,
    val latencyMs: Long = 0,
    val stale: Boolean = false
)

class PoseViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(PoseUiState())
    val state = mutableState.asStateFlow()
    private var lastResultAt = 0L
    private var startedAt = 0L
    private var inputWindow = 0L
    private var outputWindow = 0L
    private var inputs = 0
    private var outputs = 0
    private val controller = CameraController(application,
        onResult = ::receive,
        onCameraReady = { front -> mutableState.update { it.copy(cameraReady = true, frontCamera = front) } },
        onError = { error -> mutableState.update { it.copy(error = error, cameraReady = false, frame = null) } },
        onInputFrame = { timestamp ->
            inputs++
            if (timestamp - inputWindow >= 1000) {
                val fps = inputs * 1000f / (timestamp - inputWindow)
                inputs = 0; inputWindow = timestamp
                mutableState.update { it.copy(inputFps = fps) }
            }
        })

    init {
        viewModelScope.launch {
            while (true) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                val ui = mutableState.value
                if (ui.cameraReady && now - maxOf(lastResultAt, startedAt) > 1000) {
                    mutableState.update { it.copy(frame = null, stale = true, processingFps = 0f) }
                    if (now - maxOf(lastResultAt, startedAt) > 5000) {
                        controller.stop()
                        mutableState.update { it.copy(cameraReady = false,
                            error = "포즈 결과가 5초 이상 들어오지 않았습니다. 카메라를 다시 연결해 주세요.") }
                    }
                }
            }
        }
    }

    fun start(view: PreviewView, owner: LifecycleOwner) {
        startedAt = SystemClock.elapsedRealtime(); lastResultAt = 0
        inputs = 0; outputs = 0; inputWindow = 0; outputWindow = 0
        mutableState.value = PoseUiState()
        controller.start(view, owner)
    }

    fun stop() {
        controller.stop()
        mutableState.update { it.copy(frame = null, cameraReady = false, inputFps = 0f, processingFps = 0f) }
    }

    private fun receive(result: PoseResult) {
        lastResultAt = SystemClock.elapsedRealtime()
        outputs++
        var fps = mutableState.value.processingFps
        if (result.frame.timestampMs - outputWindow >= 1000) {
            fps = outputs * 1000f / (result.frame.timestampMs - outputWindow)
            outputs = 0; outputWindow = result.frame.timestampMs
        }
        mutableState.update { it.copy(frame = result.frame, latencyMs = result.latencyMs,
            processingFps = fps, stale = false) }
    }

    override fun onCleared() { controller.close() }
}
