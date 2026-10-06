package kr.co.jumprope.camera.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.co.jumprope.camera.pose.PoseQuality
import kr.co.jumprope.camera.pose.TrackingStatus
import java.util.Locale

@Composable
fun PoseScreen(model: PoseViewModel = viewModel()) {
    val context = LocalContext.current
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val panelHeight = if (windowHeight < 500.dp) 140.dp else 240.dp
    val owner = LocalLifecycleOwner.current
    val ui by model.state.collectAsStateWithLifecycle()
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var permitted by remember { mutableStateOf(hasPermission()) }
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var sizeRevision by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var debug by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(true) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
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
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("줄넘기 포즈 실험", style = MaterialTheme.typography.headlineSmall)
            Text("3단계 · 한 사람의 전신과 발목 인식 확인", style = MaterialTheme.typography.bodyMedium)
            if (!permitted) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text("포즈를 인식하려면 카메라 권한이 필요합니다. 밝은 곳에서 휴대폰을 고정하고 전신과 발이 보이도록 서 주세요.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text("카메라 권한 허용") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri())) }) { Text("앱 설정에서 권한 변경") }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.Black)) {
                    AndroidView(factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) sizeRevision++
                            }
                            preview = this
                        }
                    }, modifier = Modifier.fillMaxSize())
                    if (overlay) SkeletonOverlay(ui.frame, Modifier.fillMaxSize())
                }
                // Fixed panel height prevents status text changes from triggering camera rebinds.
                Column(Modifier.height(panelHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val status = when {
                        ui.error != null -> ui.error!!
                        !resumed -> "복귀하면 카메라를 다시 연결합니다."
                        !ui.cameraReady -> "카메라와 포즈 모델을 준비하고 있습니다…"
                        ui.stale -> "추적이 중단되었습니다. 전신이 화면에 보이는지 확인하세요."
                        ui.frame == null -> "사람을 찾고 있습니다…"
                        ui.frame?.trackingStatus == TrackingStatus.NO_PERSON -> "사람이 인식되지 않습니다. 전신이 보이도록 서 주세요."
                        ui.frame?.trackingStatus == TrackingStatus.MISSING_ANKLES -> "발목이 보이지 않거나 신호가 약합니다. 두 발까지 화면에 들어오도록 뒤로 이동하세요."
                        ui.frame?.trackingStatus == TrackingStatus.PARTIAL_BODY -> "일부 관절의 신호가 약합니다. 전신이 보이도록 위치와 조명을 조정하세요."
                        else -> "전신과 양쪽 발목이 인식됩니다. 천천히 움직이며 관절 표시를 확인하세요."
                    }
                    Text(status, color = if (ui.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    if (ui.cameraReady && !ui.frontCamera) Text("전면 카메라가 없어 후면 카메라를 사용합니다.")
                    if (ui.error != null) Button(onClick = { retry++ }) { Text("다시 연결") }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(overlay, onCheckedChange = { overlay = it }); Text("관절 표시")
                        Spacer(Modifier.width(12.dp))
                        Checkbox(debug, onCheckedChange = { debug = it }); Text("개발 지표")
                    }
                    if (debug) {
                        Text(String.format(Locale.KOREA, "입력 %.1f FPS · 처리 %.1f FPS · 추론 %d ms",
                            ui.inputFps, ui.processingFps, ui.latencyMs))
                        if (ui.cameraReady && ui.processingFps in 0.1f..9.9f) Text("처리 속도가 낮습니다. 조명과 기기 발열을 확인하세요.")
                        val frame = ui.frame
                        val count = frame?.let { PoseQuality.requiredJoints.count { joint -> PoseQuality.usable(it.landmarks[joint]) } } ?: 0
                        Text("필수 관절 $count/8 · ${frame?.trackingStatus ?: "결과 대기"}")
                        frame?.let {
                            Text("입력 ${it.width}×${it.height} · 회전 ${it.transform.rotationDegrees}° · ${if (it.transform.selfieMirrored) "전면 미러 표시" else "후면 표시"}")
                        }
                        Text("MediaPipe 0.10.21 · Lite v1 · CPU · 최대 1명")
                    }
                    Text("현재는 포즈 인식 단계입니다. 점프 횟수와 운동 기록은 다음 단계에서 구현합니다.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
