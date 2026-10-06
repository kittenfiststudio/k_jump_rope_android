# AGENTS.md — 학원용 줄넘기 카메라 프로토타입

## 1. 프로젝트 목적과 확정된 방향

이 문서는 새 저장소에서 Codex가 개발을 시작할 수 있는 구현 지침이자 초기 제품 명세다.
사용자와의 설명, UI 문구, 개발 문서는 한국어를 기본으로 하고 코드 식별자는 영어로 작성한다.

- 장기 목표: 학생의 운동을 캐릭터 성장과 학원 공동 게임에 연결하는 학원용 운동 플랫폼.
- 첫 제품: Android 완전 네이티브 앱. Kotlin + Jetpack Compose + CameraX + MediaPipe Tasks Pose Landmarker.
- 첫 프로토타입: 스마트폰 카메라로 한 사람의 점프를 감지하고 횟수·운동 시간·분당 점프 수를 표시하는 실험용 앱.
- 사용자는 현재 Galaxy Watch가 없다. 워치 구매나 연결 없이 핵심 기능을 실행할 수 있어야 한다.
- 추후 iOS도 Swift + SwiftUI 기반 완전 네이티브로 개발한다. 지금 iOS 앱이나 공유 런타임은 만들지 않는다.
- React Native, Flutter, KMP로 제품 방향을 변경하지 않는다.
- MediaPipe를 주 엔진으로 사용한다. ML Kit A/B 구현은 현재 범위에 포함하지 않는다.

## 2. 첫 구현 범위

반드시 구현할 사용자 흐름:

1. 카메라 권한 요청과 거부 시 안내.
2. 전면 카메라 미리보기. 전면 카메라가 없으면 후면 카메라를 사용하고 안내.
3. 실시간 포즈 추정과 Skeleton Overlay.
4. 전신 인식 상태, 발목 누락, 준비·보정 상태 안내.
5. 시작 버튼 → 짧은 준비 카운트다운 → 안정된 서 있는 자세 보정 → 카운팅.
6. 횟수, 유효 운동 시간, 현재 분당 점프 수 표시.
7. 일시정지·재개·종료. 종료 시 총 횟수, 시간, 평균 분당 점프 수 표시.
8. 최근 운동 결과를 로컬 저장하고 조회.
9. 개발 모드에서 상태 머신, 신호 품질, 처리 FPS, 추론 지연을 확인.
10. 선택적으로 포즈 로그를 로컬 저장·내보내기하고 동일 로그를 재생해 판정 로직을 검증.

현재 제외: 로그인, 학생 명부, 서버, 클라우드 전송, 학원 관리, 결제, 광고, 건강 플랫폼 연동,
워치, 다인 동시 카운팅, 캐릭터·XP·퀘스트·레이드, 자세 코칭, 줄 자체 검출, 이단 뛰기 판별.
서버 제품은 후속 설계에서 결정하며 첫 버전에 백엔드 SDK를 넣지 않는다.

## 3. 측정 의미와 한계

- `jumpCount`는 관측된 점프 주기 수다. 실제 줄 통과·회전 수를 보장하지 않는다.
- 이단 뛰기에서도 점프 한 번은 1로 센다. 초기 지원 동작은 양발 기본 뛰기다.
- 제자리 점프와 줄넘기를 완벽하게 구분할 수 있다고 주장하지 않는다.
- 손목 랜드마크만으로 손목 회전이나 교차 기술을 판별한다고 가정하지 않는다.
- 포즈 모델의 world 좌표를 바닥 기준 실제 점프 높이로 해석하지 않는다.
- 내부 품질 점수는 정답 확률이 아니다. 확률로 검증되지 않은 값을 “정확도 95%”로 표시하지 않는다.
- 한 사람만 전신으로 보이고 카메라가 고정된 조건부터 검증한다.
  여러 사람이 있는 학원 환경은 후속 단계다. 단일 포즈 결과만으로 주변 인물 부재를 보장하지 않는다.

## 4. 구현 구조

초기에는 Gradle app 모듈 하나와 패키지 분리로 시작한다. 불필요한 다중 모듈화를 피한다.

```text
repository/
  AGENTS.md
  README.md
  docs/
    DOMAIN_MODEL.md
    DETECTION_SPEC.md
    VALIDATION.md
  app/src/main/
    assets/pose_landmarker_lite.task
    java/<package>/
      camera/       # CameraX, 프레임 변환, 라이프사이클
      pose/         # PoseEstimator, MediaPipe adapter, 좌표 변환
      detection/    # 순수 Kotlin 신호 처리와 JumpDetector
      session/      # 세션 상태, 시간, 지표 계산
      data/         # Room 결과 저장, 로그 읽기/쓰기
      ui/           # Compose 화면, ViewModel, Overlay
      debug/        # 개발 지표와 로그 재생
  app/src/test/      # 판정 엔진과 세션 테스트
```

데이터 흐름: CameraX → PoseEstimator → PoseFrame → JumpDetector → JumpEvent → Session → UI/저장.

- `detection`은 Android, MediaPipe, Bitmap, Compose, 데이터베이스에 의존하지 않는다.
- `PoseEstimator`는 비동기 프레임 제출과 결과 전달, 실패, 해제 계약을 명시한다.
  비동기 추론을 동기식 함수처럼 숨기지 않는다.
- SDK 랜드마크 인덱스는 adapter 안에서 의미 있는 관절 이름으로 변환한다.
- UI는 ViewModel의 StateFlow 상태를 구독하고 카메라나 추론 객체를 직접 소유하지 않는다.
- 의존성 주입은 생성자 주입부터 시작한다. 처음부터 대형 프레임워크를 추가하지 않는다.

## 5. 공통 데이터 계약과 추후 iOS

`DOMAIN_MODEL.md`에 타입, 단위, 누락값, 상태 전이와 로그 schemaVersion을 명시한다.

### PoseFrame

- `timestampMs`: 세션 내 단조 증가하는 프레임 시각, 밀리초.
- `landmarks`: 관절 이름별 x/y와 제공되는 visibility/presence. 누락은 null.
- 분석 좌표: 회전 보정 후 미러링 전 이미지, 원점 좌상단, x는 오른쪽, y는 아래쪽.
- x/y는 이미지 폭·높이 기준 정규화한다. 화면 밖 값이 나오면 품질 검사에서 처리하고 무조건 clamp하지 않는다.
- 화면의 셀피 미러링은 렌더링 변환으로만 처리한다.
- `trackingStatus`, `engineId`, `modelId`, 입력 크기와 변환 정보.
- 최소 관절: 양 어깨, 골반, 무릎, 발목. 손목은 선택 항목.
- SDK가 제공하지 않는 confidence 필드를 임의의 1.0으로 채우지 않는다.

### JumpEvent

- `eventId`, `sessionId`, `timestampMs`, `sequence`, `qualityScore`, `detectorVersion`.
- 착지 완료에서 정확히 한 번 발생한다. 미완료 주기는 세지 않는다.

### WorkoutSession

- `id`, `startedAtUtc`, `endedAtUtc`, `activeDurationMs`, `validTrackingDurationMs`.
- `jumpCount`, `averageJumpsPerMinute`, `peakJumpsPerMinute`.
- `detectionSource=CAMERA`, 엔진·모델·판정·파라미터 버전, 종료 사유.
- 첫 버전은 개인 식별 정보 없이 익명 로컬 세션을 사용한다.

현재 지표는 `jumps/min`이다. 내부 필드는 jumpsPerMinute를 권장하며 RPM으로 표기한다면
줄 회전수가 아니라 분당 점프 수임을 설명한다.
평균은 `jumpCount × 60_000 / validTrackingDurationMs`; 분모가 0이면 null이다.
현재 값은 최근 유효 점프 간격으로 계산하고 멈추면 일정 시간 후 0으로 표시한다.
일시정지·추적 중단을 가로지르는 간격을 연결하지 않는다. 창 길이와 timeout은 명세에 고정한다.

iOS에서는 동일 도메인 의미와 테스트 벡터를 Swift로 구현한다.
카메라는 AVFoundation, 포즈 엔진은 추후 실측으로 선택한다.
MediaPipe iOS 또는 Apple Vision이 후보이며 지금 확정하지 않는다.
랜드마크 개수·이름·좌표 방향·confidence 차이는 adapter로 흡수한다.
같은 PoseFrame fixture에서 양 플랫폼의 이벤트와 지표가 일치하도록 한다.
카메라 해상도나 다른 모델의 출력까지 동일하다고 가정하지 않는다.

## 6. CameraX와 MediaPipe 구현 규칙

- CameraX Preview + ImageAnalysis를 lifecycle에 bind한다.
- `STRATEGY_KEEP_ONLY_LATEST`로 큐 누적을 막고 메인 스레드에서 추론하지 않는다.
- 모든 경로에서 ImageProxy를 닫는다. 비동기 추론 중 사용하는 이미지 메모리의 소유권과
  생존 시간을 보장하고, 해제된 버퍼를 callback에서 사용하지 않는다.
- MediaPipe Tasks의 Pose Landmarker, `LIVE_STREAM`, 결과·오류 listener를 사용한다.
- 초기 `numPoses=1`, segmentation mask 비활성화, Lite 모델과 CPU부터 시작한다.
- 모델은 assets에 포함하고 첫 실행 다운로드를 필수로 하지 않는다.
- 모델 누락 시 명확한 오류를 표시한다. 가짜 포즈로 성공을 가장하지 않는다.
- 모델 원본 URL, checksum, 라이선스/배포 조건과 SDK 버전을 README에 기록한다.
- MediaPipe, CameraX, Kotlin, AGP, Gradle, JDK, Compose의 호환성을 공식 문서로 확인하고
  구체 버전을 고정한다. `latest.release`, `+` 등 동적 버전은 사용하지 않는다.
- minSdk는 선택한 SDK의 공식 요구사항과 학원 기기 범위를 확인해 정하고 근거를 기록한다.
- 프레임 시각은 단조 시계를 사용한다. 촬영·제출 시각의 기준을 일관되게 정하고
  callback 도착 시각을 점프 간격으로 사용하지 않는다.
- callback이 모든 입력 프레임에 대응한다고 가정하지 않는다. 처리 누락과 시각 gap을 관리한다.
- GPU는 CPU 동작 검증 후 추가한다. delegate의 생성·실행·해제 스레드 요구사항을 지킨다.
- 회전, crop, 종횡비, PreviewView 스케일링, 전면 미러링을 포함한 Overlay 변환을 검증한다.
- 카메라 재연결·회전·세션 변경 후 이전 callback이 새 상태에 반영되지 않게 generation token을 둔다.
- 백그라운드 진입 시 운동을 일시정지하고 카메라를 해제한다. 복귀 시 준비 상태를 재확인한다.
- 권한 거부, 앱 복귀, 빠른 시작/종료, 모델 오류, 추적 누락에서 크래시 없이 안내한다.

## 7. JumpDetector 설계

필터와 상태 머신을 구현하되 아래 규칙을 실측으로 조정한다. 수치는 확정된 정답이 아니다.

1. 준비 구간에서 전신 관절 품질과 위치 안정성을 확인한다.
2. 서 있는 상태의 골반·발목 기준선과 몸 크기를 추정한다.
3. 몸 크기로 정규화한 골반 상승량·속도, 발목 상승량, 무릎 관계를 계산한다.
   몸 크기 기준은 보정 구간에서 얻어 완만하게 갱신한다.
   매 프레임 변하는 크기로 나누어 점프 신호를 상쇄하지 않도록 한다.
4. 시간 간격을 반영한 EMA 등 간단한 필터부터 적용한다.
   프레임 간격이 불규칙하므로 속도와 필터를 고정 FPS 기준으로 계산하지 않는다.
5. `GROUND → RISING → FALLING → LANDING → GROUND` 주기를 기본으로 한다.
   peak는 필요하면 별도 상태로 표현한다. 상승·최소 진폭·하강·기준선 복귀가 확인되어야 한다.
6. 상승/착지 임계값을 분리하는 hysteresis, 최소 주기 간격, 최대 주기 시간으로 중복을 방지한다.
7. 골반과 발목을 함께 평가한다. 발목이 보인다는 이유만으로 실제 이륙을 단정하지 않는다.
8. 추적 품질 저하·큰 시각 gap·대상 위치 급변에서는 진행 중 주기를 폐기한다.
   재획득 시 기준선을 확인하고, 누락 시간의 점프를 추정해 추가하지 않는다.
9. 기준선은 안정된 GROUND에서만 완만하게 갱신한다. 공중 움직임을 기준선에 흡수하지 않는다.
10. 리듬은 보조 품질 지표다. 첫 점프나 불규칙한 초보자 점프를 무조건 탈락시키지 않는다.

`DetectorConfig`에 보정 시간, 품질 임계값, 최소 진폭, 필터 시간상수,
주기 간격, 주기 timeout, 추적 gap 한계를 모은다. 단위와 초기값을 문서화한다.
작은 점프와 빠른 점프를 놓치지 않도록 실제 포즈 로그로 조정한다.
임계값을 UI, adapter, detector 곳곳에 흩어두지 않는다.

## 8. 로컬 결과와 개발 로그

- 운동 결과는 Room으로 저장한다. 저장 실패를 표시하고 중복 저장을 방지한다.
- 원본 영상·오디오를 기본 저장하거나 업로드하지 않는다.
- 포즈 로그는 개발 모드에서 사용자가 켠 세션만 앱 내부 저장소에 저장한다.
- JSONL 권장: header(schemaVersion, 기기/모델/판정 버전, config), PoseFrame,
  상태 전이, JumpEvent, pause/resume, tracking interruption, session summary.
- 로그와 결과의 목록·삭제·명시적 공유 기능을 제공한다. 자동 공유나 외부 분석 SDK는 넣지 않는다.
- 포즈 좌표도 민감하게 취급한다. 이름·얼굴 이미지·학생 ID를 로그에 넣지 않는다.
- 로그 크기 상한과 보관 정책을 문서화하고 쓰기로 인해 카메라 처리를 막지 않는다.
- 재생은 저장된 원본 PoseFrame을 새 JumpDetector에 입력한다.
  저장된 카운트나 상태를 그대로 재출력하는 방식은 검증으로 인정하지 않는다.
- 개발 화면: 입력/처리 FPS, 추론 지연, 추적 품질, 기준선, 골반 신호/속도,
  발목 신호, 현재 상태, 마지막 점프 간격, 사용 중인 config.
- 시간 측정에는 단조 시계를, 날짜 표시에는 UTC 저장 후 로컬 변환을 사용한다.

## 9. 단계별 개발 순서

1. 저장소/환경 확인 → Gradle Wrapper와 네이티브 앱 골격 → debug APK 빌드.
2. 카메라 권한과 Preview → 전면 카메라/라이프사이클 확인.
3. MediaPipe adapter → Overlay → 전신·추적 품질 표시.
4. 순수 Kotlin detector + 합성 fixture 테스트 → 실제 카운팅과 개발 지표.
5. 세션 제어·지표 계산 → 로컬 결과 저장과 조회.
6. 선택적 로그 저장·내보내기 → fixture 재생과 실제 수동 카운트 비교.

각 단계에서 실행 가능한 결과를 유지하고 README에 빌드·실행·검증 방법을 기록한다.
처음부터 추후 기능의 빈 화면이나 의미 없는 인터페이스를 대량 생성하지 않는다.

## 10. 검증과 완료 기준

### 자동 검증

- 정상 주기, 아주 작은 진동, 쪼그리기, 누락 관절, 중복/역순 시각,
  긴 gap, 착지 중복, pause/resume, session reset, 불규칙 FPS를 테스트한다.
- 동일 입력·config는 동일 이벤트를 생성해야 한다.
- 합성 로그 통과를 실제 카운팅 정확도 근거로 삼지 않는다.
- 환경에 맞게 `./gradlew testDebugUnitTest lintDebug assembleDebug`를 실행한다.
  실행 불가 항목은 원인과 다음 명령을 명시한다.

### 실제 Android 기기 검증

- 한 사람, 고정 카메라, 충분한 조명, 전신/발이 보이는 양발 기본 뛰기부터 시작한다.
- 초기 목표: 사람이 수동 확인한 100회에서 97~103회. 이는 달성해야 할 목표이며 보장치가 아니다.
- 느림·보통·빠름 조건별로 반복하고 실제 수행 템포를 기록한다.
- 정지, 쪼그리기, 좌우 이동, 화면 이탈/복귀, 일시정지, 조명 저하를 별도로 검증한다.
- 시험별 기기/OS, 카메라, 거리, 조명, 모델/판정/config 버전,
  수동 참값, 앱 카운트, 오차, 처리 FPS, 발목 누락률, 추적 중단을 `VALIDATION.md`에 기록한다.
- 순 오차가 작아도 오검출과 누락이 상쇄될 수 있다. 가능한 시험은 점프 시각을 대조해 둘을 분리한다.
- 실제 데이터 없는 결과를 만들지 않는다. 기기가 없으면 APK·합성 테스트·실기기 체크리스트까지 완료하고
  정확도 검증은 “미실시”로 표시한다.
- 10분 연속 사용에서 발열, 지연 증가, 메모리, 카메라 멈춤을 관찰한다.
  처리 FPS 저하 시 경고하며 목표 프레임률을 모든 기기에서 보장하지 않는다.

프로토타입 완료 조건: 기본 흐름 실행, 세션 종료 결과 저장, 로그 재생 가능,
자동 검증 통과, 한계와 실기기 검증 상태 명시. 실제 정확도 목표 달성 여부는 별도로 판정한다.

## 11. Codex 작업 원칙

- 먼저 기존 코드·지침·빌드 환경을 읽는다. 새 프로젝트라면 필요한 골격부터 만든다.
- 작은 가역적인 구현 선택은 자율 진행하고 선택 이유를 기록한다.
- 범위 확대, 클라우드 전송, 새 외부 계정·유료 서비스는 사용자 지시 없이 추가하지 않는다.
- 비밀키·개인 데이터·대용량 개인 로그를 저장소에 커밋하지 않는다.
- 빌드와 테스트를 실제 수행한 사실만 보고한다. 에뮬레이터를 실제 기기 검증으로 표기하지 않는다.
- 완료 보고에는 구현 기능, 검증 결과, 미검증 조건과 기기 설치 방법을 짧게 제공한다.
- 정확도에 문제가 있으면 게임 기능으로 넘어가기 전에 관절 품질·시간축·신호·판정 원인을 분석한다.

## 12. 공식 참고 자료

구현 시작 시 아래 문서의 최신 요구사항과 선택한 버전의 API를 다시 확인한다.

- MediaPipe Android Pose Landmarker: https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android
- MediaPipe 모델/옵션: https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker
- MediaPipe Android 환경: https://ai.google.dev/edge/mediapipe/solutions/setup_android
- 공식 예제: https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/pose_landmarker/android
- CameraX 분석: https://developer.android.com/media/camera/camerax/analyze
- CameraX Preview: https://developer.android.com/media/camera/camerax/preview

이 프로젝트의 구현 명세는 공식 예제 전체를 복사하는 것보다 우선한다.
MediaPipe 채택은 고급 동작의 자동 판별이나 정확도를 보장하지 않는다.
