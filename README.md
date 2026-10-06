# 줄넘기 카메라 — 점프 카운팅 프로토타입

Kotlin + Jetpack Compose + CameraX + MediaPipe Tasks로 만든 Android 네이티브 앱입니다.
AGENTS.md의 1~6단계를 구현합니다. AGENTS.md 자체는 수정하지 않았습니다.

## 현재 기능

- 카메라 권한 요청, 거부 안내, 앱 설정 이동 및 복귀 시 권한 재확인
- 전면 카메라 미리보기와 셀피 미러링, 전면 미지원 기기의 후면 카메라 대체 안내
- MediaPipe Lite/CPU/LIVE_STREAM 실시간 단일 포즈 추론과 관절 오버레이
- 양 어깨·골반·무릎·발목의 신호 검사, 전신/발목 누락/일부 관절 누락 안내
- 관절 표시와 개발 지표 토글: 입력/처리 FPS, 추론 지연, 판정 신호 품질·기준선·상승/속도, 회전·입력 크기
- 백그라운드 카메라 해제와 복귀 시 재연결, 회전·재연결 이후 이전 결과 차단
- 모델·카메라 오류 안내 및 재시도, 1초 결과 누락 시 오버레이 제거, 5초 누락 시 재연결 안내

추가 기능: 3초 준비 → 안정된 서 있는 자세 보정 → 착지 기반 점프 카운팅, 일시정지·재개·종료,
횟수·유효 운동 시간·현재/평균 분당 점프 표시, Room 결과 저장·조회·삭제·결과 JSON 공유,
개발 모드의 선택적 포즈 JSONL 로그 저장·삭제·공유와 원본 프레임 재판정, 합성 예제 재생.
영상·오디오는 저장하지 않으며 INTERNET 권한과 자동 업로드가 없습니다.
전신 인식 표시는 관절 신호 상태이며 점프 카운팅 정확도나 주변 인물 부재를 뜻하지 않습니다.

## 빌드 환경

| 항목 | 고정 버전 |
|---|---|
| JDK | 17 |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin | 8.9.2 |
| Kotlin / Compose compiler plugin | 2.1.20 / 2.1.20 |
| Compose BOM | 2025.04.01 |
| CameraX | 1.4.2 |
| MediaPipe Tasks Vision | 0.10.21 |
| Activity / Lifecycle | 1.10.1 / 2.8.7 |
| Room / Gson | 2.7.2 / 2.11.0 |
| Room compiler 처리 | Kotlin kapt 2.1.20 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 24 |

minSdk 24(Android 7.0)는 [MediaPipe Android 공식 최소 요구사항](https://developers.google.com/edge/mediapipe/solutions/setup_android)에 따릅니다.
실제 학원 기기 지원 범위는 실기기 시험 후 확인합니다.
[AGP 8.9 호환성](https://developer.android.com/build/releases/agp-8-9-0-release-notes)은 Gradle 8.11.1/JDK 17/API 35를 명시합니다.
Kotlin 2.x의 [Compose compiler plugin](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)을 Kotlin과 같은 버전으로 사용합니다.
[CameraX 릴리스](https://developer.android.com/jetpack/androidx/releases/camera)와
[MediaPipe Android API](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android),
[Room 공식 문서/릴리스](https://developer.android.com/jetpack/androidx/releases/room),
[FileProvider 공유](https://developer.android.com/training/secure-file-sharing/share-file)를 참고했습니다.

Android Studio에서 저장소 루트를 열고 Gradle JDK를 17로 지정합니다.
SDK Manager에서 Android SDK Platform 35와 Build Tools 35.0.0을 설치합니다.
`local.properties`의 `sdk.dir`은 본인 SDK 경로이며 Git에서 제외됩니다.

macOS에서 Android Studio의 JDK를 이용하는 예:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest lintDebug assembleDebug
```

기본 터미널 Java가 11이면 위 설정이 필요합니다. 첫 빌드에는 Gradle/의존성 다운로드용 네트워크가 필요하며,
앱 실행 시에는 모델 다운로드가 필요 없습니다. debug 서명은 Android 도구의 기본 개발용 키를 사용합니다.

APK: `app/build/outputs/apk/debug/app-debug.apk`

## 기기 설치 및 확인

Android 7.0 이상 기기의 개발자 옵션과 USB 디버깅을 켜고 Android Studio의 Run을 사용합니다.
또는 SDK의 platform-tools가 PATH에 있을 때:

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

앱 이름은 **줄넘기 포즈 실험**입니다. 카메라 권한을 허용하고 밝은 곳에서 휴대폰을 고정한 뒤,
한 사람의 머리부터 두 발까지 여유 있게 보이도록 섭니다.
전신 인식 안내가 뜨면 **시작**을 누릅니다. 3초 준비와 보정이 끝나 "점프를 감지하고 있습니다"가 뜰 때부터 뜁니다.
보정 완료 시 카메라 영역이 **2초간 파란색 화면과 큰 체크 표시**로 바뀝니다. 이때부터 뛰면 됩니다. 이후 파란 테두리가 카운팅 가능한 상태를 나타내며, 추적 중단·일시정지 때 사라집니다. 재보정 완료 시 다시 파란 화면을 표시합니다.

발목 점은 노란색입니다. **종료**하면 결과가 저장됩니다. **기록·로그**에서 조회·삭제·공유할 수 있습니다.

실제 판정 개선을 위한 로그는 **개발 지표 → 다음 운동의 포즈 로그 저장**을 운동 시작 전에 켭니다.
종료 후 기록·로그에서 **재생**을 누르면 같은 원본 포즈로 횟수를 다시 계산합니다.
로그를 공유하면 다른 앱에 포즈 좌표가 전달되므로 직접 공유할 로그만 선택하세요.
**합성 예제 재생 · 3회**는 카메라와 무관하게 재생 경로를 확인하는 기능이며 실제 정확도 근거가 아닙니다.
로그는 최대 10 MiB/개, 최근 20개·7일 보관이며 다음 로그 시작 시 오래된 파일을 정리합니다.
자세한 실기기 체크리스트는 `docs/VALIDATION.md`에 있습니다.

## 모델 및 배포 조건

- 파일: `app/src/main/assets/pose_landmarker_lite.task` (앱에 포함)
- 공식 버전 URL: https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task
- SHA-256: `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`
- 모델 정보: [Pose Landmarker 모델](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker)
- [BlazePose GHUM 3D 모델 카드](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf)는 Apache License 2.0을 명시합니다.
- [MediaPipe 라이선스](https://github.com/google-ai-edge/mediapipe/blob/master/LICENSE): Apache License 2.0.
  재배포 시 라이선스와 적용되는 저작권·NOTICE를 유지해야 합니다. SDK/모델을 수정했다고 표시하지 않습니다.
- 라이선스 원문은 `app/src/main/assets/licenses/mediapipe-apache-2.0.txt`에 포함합니다.

## 구조와 처리 계약

`camera/`는 CameraX와 RGBA 프레임 복사·crop·회전을 담당하고 `pose/`는 비동기 추론과 의미 있는 관절 이름 변환을 담당합니다.
`detection/`는 순수 Kotlin 신호 처리와 판정을, `session/`은 시간·지표를, `data/`는 Room과 비동기 로그 쓰기를,
`debug/`는 원본 로그 재생을 담당합니다. `ui/`의 ViewModel이 카메라/추론 객체를 소유하고 화면은 StateFlow를 구독합니다.
후속 단계의 빈 패키지나 화면은 만들지 않았습니다.

CameraX Preview와 ImageAnalysis에 같은 ViewPort를 적용합니다. RGBA 버퍼는 CameraX의 `ImageProxy.toBitmap()`으로 패딩과 채널 배치를 처리해 복사하고
crop 후 회전 보정한 Bitmap을 전달하며 ImageProxy는 모든 경로에서 닫습니다.
추론 입력은 callback 또는 task 종료까지 유지합니다. 한 번에 한 입력만 제출하고 바쁜 동안의 프레임은 버립니다.
분석은 미러링하지 않고 화면 좌표 변환에서만 전면 미러링을 적용합니다.
Overlay는 PreviewView의 FILL_CENTER 확대·중앙 crop과 같은 변환을 사용합니다.
기존 3단계는 사용자 기기 동작 확인을 받았습니다. 다른 기종의 crop·회전·미러링과 새 운동 화면은 실기기 확인이 필요합니다.

프레임 시각은 CameraX 분석기가 프레임을 받은 `elapsedRealtime` 기준으로 세션 시작 시각을 빼 사용합니다.
callback 도착 시각을 동작 시각으로 쓰지 않습니다. 같은 밀리초의 입력은 건너뜁니다.
지연은 모델 제출부터 결과 callback까지이며 프레임 변환 시간과 디스플레이 지연은 포함하지 않습니다.
각 연결은 generation token으로 구분하며 카메라/권한/생명주기 변경 때 진행 중 결과를 폐기합니다.
좌표와 품질 계약은 `docs/DOMAIN_MODEL.md`를 참고하세요.

## 검증 상태

자동 검증 및 실기기 상태는 `docs/VALIDATION.md`에 기록합니다.
3단계 포즈 인식은 사용자 실기기 동작 확인을 받았습니다. **실제 수동 참값과의 점프 횟수 정확도 검증은 미실시**입니다.
초기 목표인 100회에서 97~103회는 아직 달성 여부를 확인하지 않았습니다.
현재 판정은 골반 기반 v2이며 상승량·속도 임계값을 v1보다 80% 낮췄습니다. 발목 상승량은 카운트 조건에서 제외하고 전신 인식은 유지합니다.

판정 초기값과 시간·평균 의미는 `docs/DETECTION_SPEC.md`, 로그 계약은 `docs/DOMAIN_MODEL.md`를 참고하세요.
