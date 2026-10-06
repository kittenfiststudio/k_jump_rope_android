# 공통 데이터 계약 — v1

코드의 도메인 데이터는 순수 Kotlin입니다. 추후 iOS에서는 같은 의미·단위·fixture를 Swift로 구현합니다.

## PoseFrame

- `timestampMs`: 세션 시작 이후 단조 증가하는 밀리초. 분석기 수신 시각 기준이며 callback 도착 시각이 아닙니다.
  카메라 adapter는 플랫폼 단조 시각을 전달하고 ViewModel에서 세션 시작 시각을 빼 판정기와 로그에 입력합니다.
  일시정지·카메라 재연결 시에도 세션 시간축은 유지합니다. 새 세션은 0부터 시작합니다.
- `landmarks`: Joint별 Landmark 또는 null. SDK 인덱스는 adapter 안에만 있습니다.
- `x/y`: crop 및 회전 보정한 미러링 전 이미지 정규화 좌표. 좌상단 원점, x 오른쪽, y 아래쪽.
  화면 밖 좌표를 clamp하지 않습니다. NaN/Infinity 좌표는 판정에서 제외하고 JSON 로그에서는 해당 관절을 null로 기록합니다.
- `visibility/presence`: SDK 제공값 또는 null. 임의 confidence를 채우지 않습니다.
  JSON에 기록할 수 없는 confidence 값은 null로 변환하며 판정에서도 품질 미달로 제외합니다.
- `width/height`: crop·회전 보정 후 입력 이미지의 픽셀 크기.
- `transform`: 원본 픽셀 크기, 원본 기준 crop 영역, 시계 방향 회전(0/90/180/270), 화면 셀피 미러링 여부.
- `trackingStatus`: NO_PERSON / MISSING_ANKLES / PARTIAL_BODY / FULL_BODY.
  화면 품질 표시는 visibility ≥ 0.5, 제공되면 presence ≥ 0.5 및 유효한 이미지 좌표를 요구합니다.
- `engineId/modelId`: 버전을 포함한 엔진·모델 식별자.

필수 관절은 좌우 어깨·골반·무릎·발목입니다. 팔꿈치·손목·뒤꿈치·발끝·코는 추가 표시용입니다.
단일 포즈 결과만으로 다른 사람이 없음을 보장하지 않습니다. world 좌표는 사용하지 않습니다.

## JumpEvent

`eventId`, `sessionId`, `timestampMs`, `sequence`, `qualityScore`, `detectorVersion`.
착지 완료에서 정확히 한 번 발생합니다. sequence는 1부터 시작하며 일시정지/추적 중단에도 유지됩니다.
eventId는 `sessionId:sequence`입니다. 신호 품질은 정답 확률이 아닙니다.

## WorkoutSession / Room WorkoutEntity

- id: 익명 UUID. 개인 식별 정보는 사용하지 않습니다.
- startedAtUtc/endedAtUtc: `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, UTC 저장 후 화면에서 로컬 시각으로 표시.
- activeDurationMs/validTrackingDurationMs: 밀리초. 정확한 분모 의미는 DETECTION_SPEC.md 참고.
- jumpCount: 관측된 완료 점프 주기 수. 이단 뛰기도 점프 한 번은 1회.
- averageJumpsPerMinute: `jumpCount × 60000 / validTrackingDurationMs`, 분모 0이면 null.
- peakJumpsPerMinute: 현재 분당 점프의 세션 최댓값. 관측값이 없으면 0.
- detectionSource=CAMERA, engineId, modelId, detectorVersion, parameterVersion, endReason=USER.

Room schemaVersion=1. id 기본키와 INSERT IGNORE로 같은 세션의 중복 저장을 방지합니다.
저장 실패 시 종료 결과를 ViewModel에 유지해 재시도할 수 있습니다. 원본 DB를 공유하지 않고 선택한 결과만 JSON으로 내보냅니다.

## 세션 상태

`IDLE → COUNTDOWN(3초) → CALIBRATING → COUNTING → FINISHED`

진행 중 일시정지·백그라운드·카메라 재연결·모델 오류는 PAUSED로 전이합니다.
재개는 CALIBRATING으로 전이하며 새 기준선을 확인합니다. 새 운동은 새로운 UUID/판정기를 만듭니다.
앱 프로세스가 강제 종료되면 진행 중 세션을 자동 복구하지 않습니다. 종료 버튼을 누른 세션 결과만 저장합니다.

## JSONL 로그 schemaVersion=1

각 줄은 `{"type": "...", "data": ...}`입니다.

| type | data |
|---|---|
| header | schemaVersion, sessionId, startedAtUtc, device/OS, engine/model/detector 버전, DetectorConfig 전체, countdownMs |
| frame | 원본 PoseFrame (필터 적용 전) |
| transition | 시각, 이전/다음 상태 또는 추적 중단 사유 |
| event | JumpEvent |
| control | timestampMs, action(tick/pause/resume/interruption/finish), reason |
| summary | WorkoutSession |

live 주기마다 전달한 tick도 기록하므로 지연된 callback, 추적 timeout과 일시정지 시간축을 동일하게 재생합니다.
재생은 header의 config로 **새 WorkoutEngine과 JumpDetector**를 만들고 frame/control을 순서대로 적용합니다.
저장된 event/transition/summary의 횟수를 카운트에 사용하지 않습니다. summary 횟수는 재판정 결과 비교에만 사용합니다.
summary가 없으면 불완전 로그로 표시합니다. 불완전 로그도 포함된 원본 프레임까지 재판정할 수 있습니다.
지원하지 않는 schema와 손상된 JSON은 오류로 안내합니다. 합성 예제는 SYNTHETIC으로 표시되며 운동 기록에 저장하지 않습니다.

포즈 로그는 개발 지표에서 사용자가 켠 다음 세션에만 기록합니다. 영상/오디오/이름/학생 ID를 저장하지 않습니다.
쓰기 큐는 256개로 제한하고 IO 스레드에서 직렬화합니다. 큐 포화/쓰기 실패/10 MiB 상한은 로그 중단과 불완전 상태로 안내합니다.
최근 20개·7일 보관을 다음 로그 시작 때 적용합니다. 사용자가 개별 삭제·명시적 공유를 선택할 수 있습니다.
공유는 FileProvider content URI와 임시 읽기 권한을 사용합니다. 네트워크 권한과 자동 업로드는 없습니다.

### 판정 v2 호환

현재 엔진은 `jump-detector-2.0.0`, 기본 config는 `hip-sensitive-2`입니다.
발목 상승은 참고 지표이며 카운트 조건이 아닙니다. JSONL schemaVersion은 1을 유지합니다.
v1 config의 `minimumAnkleLift`/`landingAnkleLift`는 무시합니다. 재생은 현재 판정기와 저장된
골반 config를 사용하므로 과거 엔진 버전의 이벤트를 그대로 재현한다고 보장하지 않습니다.
