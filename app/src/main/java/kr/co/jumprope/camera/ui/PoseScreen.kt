package kr.co.jumprope.camera.ui

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.co.jumprope.camera.detection.DetectorState
import kr.co.jumprope.camera.detection.JumpDetector
import kr.co.jumprope.camera.pose.TrackingStatus
import kr.co.jumprope.camera.session.SessionPhase
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PoseScreen(model: PoseViewModel = viewModel()) {
    val context = LocalContext.current
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val compact = windowHeight < 500.dp
    val owner = LocalLifecycleOwner.current
    val ui by model.state.collectAsStateWithLifecycle()
    val workoutUi by model.workoutState.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val workout = workoutUi.workout
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var permitted by remember { mutableStateOf(hasPermission()) }
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var sizeRevision by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var debug by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(true) }
    var library by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
    val countingReady = workout.phase == SessionPhase.COUNTING && workout.detector.validTracking &&
        permitted && resumed && ui.cameraReady && !ui.stale && ui.error == null
    var showReadySignal by remember { mutableStateOf(false) }
    val readyBlue = Color(0xFF1565C0)
    LaunchedEffect(countingReady) {
        showReadySignal = countingReady
        if (countingReady) {
            delay(2000)
            showReadySignal = false
        }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    LaunchedEffect(model) {
        model.shares.collect { file ->
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = if (file.extension == "jsonl") "application/x-ndjson" else "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("운동 기록", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "선택한 기록 공유"))
            } catch (error: Exception) { model.message("공유 실행 실패: ${error.message}") }
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { permitted = hasPermission(); resumed = true }
            if (event == Lifecycle.Event.ON_PAUSE) { resumed = false; model.stop() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }
    DisposableEffect(permitted, resumed, preview, sizeRevision, retry) {
        val view = preview
        if (permitted && resumed && view != null && view.width > 0 && view.height > 0) model.start(view, owner)
        onDispose { model.stop() }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!compact) Text("줄넘기 포즈 실험", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Metric("점프", "${workout.jumpCount}회")
                Metric("유효 운동", duration(workout.validTrackingDurationMs))
                Metric("분당 점프", "${workout.currentJumpsPerMinute.toInt()}")
            }
            if (!permitted) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text("카메라 권한이 필요합니다. 밝은 곳에서 휴대폰을 고정하고 전신과 발이 보이도록 서 주세요.")
                    Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text("카메라 권한 허용") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri())) }) { Text("앱 설정에서 권한 변경") }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.Black)) {
                    AndroidView(factory = { ctx -> PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                            if (r - l != or - ol || b - t != ob - ot) sizeRevision++
                        }
                        preview = this
                    } }, modifier = Modifier.fillMaxSize())
                    if (overlay) SkeletonOverlay(ui.frame, Modifier.fillMaxSize())
                    if (workout.phase == SessionPhase.COUNTDOWN) Text("${workout.countdownSeconds}",
                        modifier = Modifier.align(Alignment.Center), color = Color.White,
                        style = MaterialTheme.typography.displayLarge)
                    if (countingReady) {
                        Box(Modifier.matchParentSize().border(8.dp, readyBlue))
                        if (showReadySignal) {
                            Column(Modifier.matchParentSize().background(readyBlue),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Canvas(Modifier.size(if (compact) 72.dp else 120.dp)) {
                                    val check = Path().apply {
                                        moveTo(size.width * 0.12f, size.height * 0.50f)
                                        lineTo(size.width * 0.38f, size.height * 0.76f)
                                        lineTo(size.width * 0.88f, size.height * 0.24f)
                                    }
                                    drawPath(check, Color.White, style = Stroke(width = size.width * 0.10f,
                                        cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }
                                Text("준비 완료", color = Color.White,
                                    style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall)
                                Text("이제 뛰세요", color = Color.White, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
            // All controls scroll inside a fixed panel; counters and messages never resize the camera.
            Column(Modifier.height(if (compact) 135.dp else 270.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val poseStatus = when {
                    ui.error != null -> ui.error!!
                    !resumed -> "복귀하면 카메라를 다시 연결합니다."
                    !permitted -> "카메라 권한을 허용해 주세요."
                    !ui.cameraReady -> "카메라와 포즈 모델 준비 중…"
                    ui.stale -> "추적이 중단되었습니다. 전신을 확인하세요."
                    ui.frame == null -> "사람을 찾고 있습니다…"
                    ui.frame?.trackingStatus == TrackingStatus.NO_PERSON -> "사람이 인식되지 않습니다. 전신이 보이도록 서 주세요."
                    ui.frame?.trackingStatus == TrackingStatus.MISSING_ANKLES -> "발목이 보이지 않거나 신호가 약합니다. 두 발까지 화면에 들어오도록 서 주세요."
                    ui.frame?.trackingStatus == TrackingStatus.PARTIAL_BODY -> "일부 관절 신호가 약합니다. 전신과 조명을 확인하세요."
                    else -> "전신과 양쪽 발목이 인식됩니다."
                }
                val sessionStatus = when (workout.phase) {
                    SessionPhase.IDLE -> "휴대폰을 고정하고 시작을 눌러 주세요."
                    SessionPhase.COUNTDOWN -> "${workout.countdownSeconds}초 뒤 준비합니다. 가만히 서 주세요."
                    SessionPhase.CALIBRATING -> "가만히 서 주세요 · 준비 ${(workout.detector.calibrationProgress * 100).toInt()}% · 완료되면 화면이 파란색으로 바뀝니다."
                    SessionPhase.COUNTING -> if (workout.detector.validTracking) "점프를 감지하고 있습니다." else "추적 재확인 중입니다. 가만히 서 주세요."
                    SessionPhase.PAUSED -> "일시정지 · 재개하면 서 있는 자세를 다시 확인합니다."
                    SessionPhase.FINISHED -> "운동 종료 · ${workout.jumpCount}회"
                }
                Text(sessionStatus, color = MaterialTheme.colorScheme.primary)
                Text(poseStatus, style = MaterialTheme.typography.bodySmall,
                    color = if (ui.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (ui.cameraReady && !ui.frontCamera) Text("전면 카메라가 없어 후면 카메라를 사용합니다.")
                if (ui.error != null) Button(onClick = { retry++ }) { Text("카메라 다시 연결") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (workout.phase) {
                        SessionPhase.IDLE, SessionPhase.FINISHED -> Button(onClick = model::beginWorkout,
                            enabled = ui.cameraReady && !workoutUi.saving && !workoutUi.replayBusy &&
                                (workoutUi.summary == null || workoutUi.saved)) {
                            Text(if (workout.phase == SessionPhase.FINISHED) "새 운동 시작" else "시작")
                        }
                        SessionPhase.PAUSED -> Button(onClick = model::resumeWorkout,
                            enabled = ui.cameraReady && !workoutUi.replayBusy) { Text("재개") }
                        else -> Button(onClick = { model.pauseWorkout() }) { Text("일시정지") }
                    }
                    if (workout.phase !in listOf(SessionPhase.IDLE, SessionPhase.FINISHED))
                        OutlinedButton(onClick = model::finishWorkout) { Text("종료") }
                    OutlinedButton(onClick = {
                        model.pauseWorkout("LIBRARY"); model.refreshLogs(); library = true
                    }, enabled = !workoutUi.saving) { Text("기록·로그") }
                }
                workoutUi.summary?.let { s ->
                    Text("총 ${s.jumpCount}회 · 운동 ${duration(s.activeDurationMs)} · 유효 ${duration(s.validTrackingDurationMs)} · 평균 ${number(s.averageJumpsPerMinute)}회/분")
                    if (!workoutUi.saved && !workoutUi.saving) TextButton(onClick = model::retrySave) { Text("저장 다시 시도") }
                }
                if (workoutUi.saving) Text("결과와 로그 저장 중…")
                workoutUi.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                workoutUi.logError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(overlay, onCheckedChange = { overlay = it }); Text("관절 표시")
                    Spacer(Modifier.width(8.dp)); Checkbox(debug, onCheckedChange = { debug = it }); Text("개발 지표")
                }
                if (debug) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(workoutUi.logEnabled, onCheckedChange = model::setLogEnabled,
                            enabled = workout.phase in listOf(SessionPhase.IDLE, SessionPhase.FINISHED) && !workoutUi.saving)
                        Text("다음 운동의 포즈 로그 저장")
                    }
                    Text(if (workoutUi.logActive) "포즈 로그 기록 중 · 영상은 저장하지 않습니다." else "포즈 좌표는 기기에만 저장하며 공유는 직접 선택합니다.",
                        style = MaterialTheme.typography.bodySmall)
                    Text(String.format(Locale.KOREA, "입력 %.1f FPS · 처리 %.1f FPS · 추론 %d ms", ui.inputFps, ui.processingFps, ui.latencyMs))
                    if (ui.cameraReady && ui.processingFps in 0.1f..9.9f) Text("처리 속도가 낮습니다. 조명과 발열을 확인하세요.")
                    val d = workout.detector
                    Text("판정 ${detectorLabel(d.state)} · 신호 품질 ${number(d.qualityScore)} (정확도 아님)")
                    Text("골반 상승 ${number(d.hipLift)} · 속도 ${number(d.hipVelocity)} · 발목 상승 ${number(d.ankleLift)}")
                    Text("기준 골반 ${number(d.baselineHip)} · 발목 ${number(d.baselineAnkle)} · 몸 크기 ${number(d.bodyScale)}")
                    Text("활성 ${duration(workout.activeDurationMs)} · 마지막 간격 ${workout.lastJumpIntervalMs?.let { "${it}ms" } ?: "—"}")
                    ui.frame?.let { Text("입력 ${it.width}×${it.height} · 회전 ${it.transform.rotationDegrees}° · ${if (it.transform.selfieMirrored) "전면 미러" else "후면"}") }
                    Text("MediaPipe 0.10.21 · Lite v1 · CPU · ${JumpDetector.VERSION}")
                    Text("사용 중 config: ${model.config}", style = MaterialTheme.typography.bodySmall)
                }
                Text("관측된 점프 횟수입니다. 줄 회전·통과 여부를 판별하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (library) Dialog(onDismissRequest = { library = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("기록·포즈 로그", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { library = false }) { Text("닫기") }
                }
                Text("최근 운동", style = MaterialTheme.typography.titleMedium)
                if (history.isEmpty()) Text("저장된 운동이 없습니다.")
                history.forEach { result ->
                    Text("${localDate(result.endedAtUtc)} · ${result.jumpCount}회\n유효 ${duration(result.validTrackingDurationMs)} · 평균 ${number(result.averageJumpsPerMinute)}회/분")
                    Row {
                        TextButton(onClick = { model.shareWorkout(result) }) { Text("결과 공유") }
                        TextButton(onClick = { pendingDelete = "result" to result.id }) { Text("삭제") }
                    }
                    HorizontalDivider()
                }
                Text("포즈 로그", style = MaterialTheme.typography.titleMedium)
                Text("최대 10 MiB/개, 20개·7일 보관. 다음 로그 시작 시 오래된 로그를 정리합니다.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::replayDemo, enabled = !workoutUi.replayBusy) { Text("합성 예제 재생 · 3회") }
                if (workoutUi.logs.isEmpty()) Text("개발 지표에서 포즈 로그를 켜고 운동한 뒤 종료하면 여기에 표시됩니다.")
                workoutUi.logs.forEach { log ->
                    Text("${Date(log.modifiedAt)} · ${log.bytes / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { model.replayLog(log.name) }, enabled = !workoutUi.replayBusy) { Text("재생") }
                        TextButton(onClick = { model.shareLog(log.name) }) { Text("로그 공유") }
                        TextButton(onClick = { pendingDelete = "log" to log.name }) { Text("삭제") }
                    }
                }
                if (workoutUi.replayBusy) Text("원본 포즈 프레임을 새 판정기에 입력하는 중…")
                workoutUi.replay?.let { r ->
                    Text("재생 ${r.jumpCount}회 · 기록 ${r.recordedJumpCount?.let { "${it}회" } ?: "없음"}\n${r.frameCount}프레임 · 유효 ${duration(r.validTrackingDurationMs)} · 평균 ${number(r.averageJumpsPerMinute)}회/분")
                    if (!r.complete && workoutUi.replayName != "합성 예제 · 3회") Text("종료 요약이 없는 불완전 로그입니다.")
                    if (r.recordedJumpCount != null && r.recordedJumpCount != r.jumpCount) Text("저장된 횟수와 재판정 횟수가 다릅니다.", color = MaterialTheme.colorScheme.error)
                    Text("점프 시각(ms): ${r.eventTimestamps.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    Text("합성 예제 통과는 실제 카운팅 정확도를 뜻하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                }
                workoutUi.message?.let { Text(it) }
            }
        }
    }
    pendingDelete?.let { item -> AlertDialog(onDismissRequest = { pendingDelete = null },
        title = { Text("선택한 기록을 삭제할까요?") }, text = { Text("기기에서 삭제됩니다.") },
        confirmButton = { TextButton(onClick = {
            if (item.first == "log") model.deleteLog(item.second) else model.deleteWorkout(item.second)
            pendingDelete = null
        }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } }) }
}

@Composable private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
private fun duration(ms: Long): String = String.format(Locale.KOREA, "%02d:%02d", ms / 60_000, ms / 1000 % 60)
private fun number(value: Double?): String = value?.let { String.format(Locale.KOREA, "%.2f", it) } ?: "—"
private fun localDate(utc: String): String = try {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(parser.parse(utc)!!)
} catch (_: Exception) { utc }
private fun detectorLabel(state: DetectorState) = when (state) {
    DetectorState.CALIBRATING -> "보정"; DetectorState.GROUND -> "지면"
    DetectorState.RISING -> "상승"; DetectorState.FALLING -> "하강"
    DetectorState.LANDING -> "착지 확인"; DetectorState.LOST -> "추적 중단"
}
