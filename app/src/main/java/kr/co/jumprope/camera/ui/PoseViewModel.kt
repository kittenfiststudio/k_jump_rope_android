package kr.co.jumprope.camera.ui

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kr.co.jumprope.camera.camera.CameraController
import kr.co.jumprope.camera.data.*
import kr.co.jumprope.camera.debug.*
import kr.co.jumprope.camera.detection.*
import kr.co.jumprope.camera.pose.PoseFrame
import kr.co.jumprope.camera.pose.PoseResult
import kr.co.jumprope.camera.session.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class PoseUiState(
    val frame: PoseFrame? = null, val cameraReady: Boolean = false,
    val frontCamera: Boolean = true, val error: String? = null,
    val inputFps: Float = 0f, val processingFps: Float = 0f,
    val latencyMs: Long = 0, val stale: Boolean = false
)
data class WorkoutUiState(
    val workout: WorkoutState = WorkoutState(), val summary: WorkoutSession? = null,
    val saving: Boolean = false, val saved: Boolean = false, val message: String? = null,
    val logEnabled: Boolean = false, val logActive: Boolean = false, val logError: String? = null,
    val logs: List<LogFile> = emptyList(), val replay: ReplayReport? = null,
    val replayBusy: Boolean = false, val replayName: String? = null
)

class PoseViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(PoseUiState())
    val state = mutableState.asStateFlow()
    private val mutableWorkout = MutableStateFlow(WorkoutUiState())
    val workoutState = mutableWorkout.asStateFlow()
    private val database = WorkoutDatabase.get(application)
    val history = database.workouts().observe().catch { error ->
        mutableWorkout.update { it.copy(message = "운동 기록 조회 실패: ${error.message}") }
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val logs = PoseLogStore(application)
    private val shareChannel = Channel<File>(Channel.BUFFERED)
    val shares = shareChannel.receiveAsFlow()
    val config = DetectorConfig()
    private var engine: WorkoutEngine? = null
    private var workoutOrigin = 0L
    private var lastResultAt = 0L
    private var startedAt = 0L
    private var inputWindow = 0L
    private var outputWindow = 0L
    private var inputs = 0
    private var outputs = 0
    private val controller = CameraController(application,
        onResult = ::receive,
        onCameraReady = { front -> mutableState.update { it.copy(cameraReady = true, frontCamera = front) } },
        onError = { error ->
            pauseWorkout("CAMERA_ERROR")
            mutableState.update { it.copy(error = error, cameraReady = false, frame = null) }
        },
        onInputFrame = { timestamp ->
            inputs++
            if (timestamp - inputWindow >= 1000) {
                val fps = inputs * 1000f / (timestamp - inputWindow)
                inputs = 0; inputWindow = timestamp
                mutableState.update { it.copy(inputFps = fps) }
            }
        })

    init {
        refreshLogs()
        viewModelScope.launch {
            while (true) {
                delay(100)
                val now = SystemClock.elapsedRealtime()
                val e = engine
                if (e != null && e.state.phase !in listOf(SessionPhase.FINISHED, SessionPhase.IDLE)) {
                    val t = now - workoutOrigin
                    record("control", ControlRecord(t, "tick"))
                    e.tick(t); publishWorkout()
                }
                val ui = mutableState.value
                if (ui.cameraReady && now - maxOf(lastResultAt, startedAt) > 1000) {
                    mutableState.update { it.copy(frame = null, stale = true, processingFps = 0f) }
                    if (now - maxOf(lastResultAt, startedAt) > 5000) {
                        stop()
                        mutableState.update { it.copy(error = "포즈 결과가 5초 이상 들어오지 않았습니다. 카메라를 다시 연결해 주세요.") }
                    }
                }
            }
        }
    }

    fun start(view: PreviewView, owner: LifecycleOwner) {
        pauseWorkout("CAMERA_RECONNECT")
        startedAt = SystemClock.elapsedRealtime(); lastResultAt = 0
        inputs = 0; outputs = 0; inputWindow = startedAt; outputWindow = startedAt
        mutableState.value = PoseUiState()
        controller.start(view, owner)
    }
    fun stop() {
        pauseWorkout("BACKGROUND_OR_CAMERA_CHANGE")
        controller.stop()
        mutableState.update { it.copy(frame = null, cameraReady = false, inputFps = 0f, processingFps = 0f) }
    }
    fun setLogEnabled(enabled: Boolean) {
        if (canBegin()) mutableWorkout.update { it.copy(logEnabled = enabled) }
    }
    private fun canBegin() = !mutableWorkout.value.saving && !mutableWorkout.value.replayBusy &&
        (mutableWorkout.value.summary == null || mutableWorkout.value.saved) &&
        (engine == null || engine?.state?.phase == SessionPhase.FINISHED)
    fun beginWorkout() {
        if (!canBegin() || !mutableState.value.cameraReady) return
        val enabled = mutableWorkout.value.logEnabled
        val id = UUID.randomUUID().toString()
        workoutOrigin = SystemClock.elapsedRealtime()
        val utc = utcNow()
        engine = WorkoutEngine(id, utc, config)
        mutableWorkout.value = WorkoutUiState(workout = engine!!.state, logEnabled = enabled,
            logActive = enabled, logs = mutableWorkout.value.logs)
        if (enabled) logs.start(LogHeader(sessionId = id, startedAtUtc = utc,
            device = "${Build.MANUFACTURER} ${Build.MODEL}", osVersion = Build.VERSION.RELEASE,
            engineId = "mediapipe-tasks-vision-0.10.21", modelId = "pose_landmarker_lite-float16-v1",
            detectorVersion = JumpDetector.VERSION, config = config)) { message ->
            viewModelScope.launch { mutableWorkout.update { it.copy(logError = message, logActive = false) } }
        }
    }
    fun pauseWorkout(reason: String = "USER") {
        val e = engine ?: return
        if (e.state.phase in listOf(SessionPhase.PAUSED, SessionPhase.FINISHED)) return
        val t = SystemClock.elapsedRealtime() - workoutOrigin
        record("control", ControlRecord(t, "pause", reason))
        e.pause(t); publishWorkout()
    }
    fun resumeWorkout() {
        if (!mutableState.value.cameraReady) return
        val e = engine ?: return
        val t = SystemClock.elapsedRealtime() - workoutOrigin
        if (e.state.phase != SessionPhase.PAUSED) return
        record("control", ControlRecord(t, "resume")); e.resume(t); publishWorkout()
    }
    fun finishWorkout() {
        val e = engine ?: return
        if (e.state.phase == SessionPhase.FINISHED || mutableWorkout.value.saving) return
        val t = SystemClock.elapsedRealtime() - workoutOrigin
        record("control", ControlRecord(t, "finish"))
        val summary = e.finish(t, utcNow())
        record("summary", summary)
        mutableWorkout.update { it.copy(workout = e.state, summary = summary, saving = true, message = null) }
        viewModelScope.launch {
            logs.finish()
            mutableWorkout.update { it.copy(logActive = false) }
            saveSummary(summary)
            refreshLogs()
        }
    }
    fun retrySave() {
        val summary = mutableWorkout.value.summary ?: return
        if (mutableWorkout.value.saving) return
        mutableWorkout.update { it.copy(saving = true) }
        viewModelScope.launch { saveSummary(summary) }
    }
    private suspend fun saveSummary(summary: WorkoutSession) {
        try {
            database.workouts().insert(WorkoutEntity.from(summary))
            mutableWorkout.update { it.copy(saving = false, saved = true, message = "운동 결과를 기기에 저장했습니다.") }
        } catch (error: Exception) {
            mutableWorkout.update { it.copy(saving = false, saved = false, message = "결과 저장 실패: ${error.message}") }
        }
    }
    private fun record(type: String, value: Any) {
        if (!mutableWorkout.value.logActive) return
        if (!logs.append(type, value)) {
            mutableWorkout.update { it.copy(logActive = false, logError = "로그 쓰기 큐가 가득 찼거나 중단됐습니다. 불완전 로그로 남습니다.") }
        }
    }
    private fun publishWorkout() { engine?.let { e -> mutableWorkout.update { it.copy(workout = e.state) } } }
    private fun receive(result: PoseResult) {
        lastResultAt = SystemClock.elapsedRealtime()
        outputs++
        var fps = mutableState.value.processingFps
        if (result.frame.timestampMs - outputWindow >= 1000) {
            fps = outputs * 1000f / (result.frame.timestampMs - outputWindow)
            outputs = 0; outputWindow = result.frame.timestampMs
        }
        mutableState.update { it.copy(frame = result.frame, latencyMs = result.latencyMs, processingFps = fps, stale = false) }
        val e = engine ?: return
        if (e.state.phase in listOf(SessionPhase.FINISHED, SessionPhase.PAUSED)) return
        val frame = PoseLogCodec.clean(result.frame.copy(timestampMs = result.frame.timestampMs - workoutOrigin))
        if (frame.timestampMs < 0) return
        record("frame", frame)
        val before = e.state.detector.state
        e.frame(frame)?.let { detected ->
            if (detected.state != before) record("transition", mapOf("timestampMs" to frame.timestampMs,
                "from" to before.name, "to" to detected.state.name))
            detected.interruption?.let { reason ->
                // The invalid frame already reproduces this reset; this record is observational only.
                record("transition", mapOf("timestampMs" to frame.timestampMs, "interruption" to reason))
            }
            detected.event?.let { record("event", it) }
        }
        publishWorkout()
    }
    fun refreshLogs() {
        viewModelScope.launch {
            try { val files = logs.files(); mutableWorkout.update { it.copy(logs = files) } }
            catch (error: Exception) { message("로그 목록 오류: ${error.message}") }
        }
    }
    fun deleteLog(name: String) {
        viewModelScope.launch {
            try { logs.delete(name); refreshLogs() } catch (error: Exception) { message("로그 삭제 실패: ${error.message}") }
        }
    }
    fun deleteWorkout(id: String) {
        viewModelScope.launch {
            try { database.workouts().delete(id) } catch (error: Exception) { message("기록 삭제 실패: ${error.message}") }
        }
    }
    fun replayLog(name: String) {
        if (mutableWorkout.value.replayBusy || mutableWorkout.value.saving) return
        pauseWorkout("REPLAY")
        mutableWorkout.update { it.copy(replayBusy = true, replay = null, replayName = name) }
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.Default) {
                    val file = logs.file(name)
                    check(file.length() <= PoseLogStore.MAX_BYTES)
                    file.bufferedReader().use { PoseLogCodec.replay(it.lineSequence()) }
                }
                mutableWorkout.update { it.copy(replay = report, replayBusy = false) }
            } catch (error: Exception) {
                mutableWorkout.update { it.copy(replayBusy = false, message = "로그 재생 실패: ${error.message}") }
            }
        }
    }
    fun replayDemo() {
        if (mutableWorkout.value.replayBusy) return
        pauseWorkout("REPLAY")
        mutableWorkout.update { it.copy(replayBusy = true, replay = null, replayName = "합성 예제 · 3회") }
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.Default) {
                    getApplication<Application>().assets.open("fixtures/basic-jumps.jsonl").bufferedReader().use {
                        PoseLogCodec.replay(it.lineSequence())
                    }
                }
                mutableWorkout.update { it.copy(replay = report, replayBusy = false) }
            } catch (error: Exception) {
                mutableWorkout.update { it.copy(replayBusy = false, message = "예제 재생 실패: ${error.message}") }
            }
        }
    }
    fun shareLog(name: String) {
        viewModelScope.launch {
            try { shareChannel.send(logs.file(name)) } catch (error: Exception) { message("공유 준비 실패: ${error.message}") }
        }
    }
    fun shareWorkout(workout: WorkoutEntity) {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, "workout-${workout.id}.json").apply { writeText(PoseLogCodec.gson.toJson(workout)) }
                }
                shareChannel.send(file)
            } catch (error: Exception) { message("결과 공유 준비 실패: ${error.message}") }
        }
    }
    fun message(text: String) { mutableWorkout.update { it.copy(message = text) } }
    private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
    override fun onCleared() {
        controller.close()
        // Writer owns its IO scope, so flushing is not cancelled with the ViewModel.
        CoroutineScope(Dispatchers.IO).launch { logs.finish() }
    }
}
