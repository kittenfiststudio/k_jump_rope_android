# 3단계 포즈 데이터 계약

이번 범위에는 PoseFrame만 구현합니다. JumpEvent, WorkoutSession, 판정 상태 머신과 로그 schema는 4~6단계에서 추가합니다.

- `timestampMs`: 현재 카메라 연결 시작 이후 분석기 수신 시각, 단조 시계 기반 밀리초. 재연결 시 리셋합니다.
  같은 시각은 SDK에 중복 제출하지 않으며 결과는 입력 시각을 유지합니다.
  추후 세션 연결에서는 연결 리셋과 세션 시간축을 구분해야 합니다.
- `landmarks`: Joint별 Landmark 또는 null. SDK 인덱스는 adapter 내부에만 있습니다.
- `x/y`: crop 및 회전 보정한 미러링 전 분석 이미지의 정규화 좌표. 좌상단 원점, 오른쪽 x 증가, 아래쪽 y 증가.
  범위 밖 값과 NaN은 표시·품질 검사에서 제외하며 좌표를 clamp하지 않습니다.
- `visibility/presence`: SDK 제공값 또는 null. 임의 기본 confidence를 채우지 않습니다.
  표시 가능 관절은 visibility ≥ 0.5, presence가 제공되면 presence ≥ 0.5, x/y 유효 범위입니다.
  visibility가 누락되면 품질을 확인할 수 없어 제외합니다. 내부 판정은 정확도 확률이 아닙니다.
- `width/height`: crop·회전 보정 후 입력 픽셀 크기.
- `transform`: 원본 폭/높이, 원본에서 crop 영역, 시계 방향 회전 각도(0/90/180/270), 화면 전면 미러링 여부.
- `engineId/modelId`: 버전을 포함한 엔진·모델 식별자.
- `trackingStatus`: 사람이 없으면 NO_PERSON, 발목 둘 중 하나가 기준 미달이면 MISSING_ANKLES,
  나머지 필수 관절이 기준 미달이면 PARTIAL_BODY, 8개 모두 통과하면 FULL_BODY.

필수 관절: 좌우 어깨·골반·무릎·발목. 팔꿈치·손목·뒤꿈치·발끝·코는 추가 표시용입니다.
단일 모델 결과에서 한 사람만 처리하지만 다른 사람이 없음을 보장하지 않습니다.
world 좌표를 사용하지 않으며 실제 점프 높이나 줄 회전 수를 추정하지 않습니다.

최신 결과가 1초 이상 없으면 화면의 pose를 제거합니다. 결과가 5초 이상 없으면 카메라를 해제하고 재시도를 안내합니다.
앱 백그라운드 진입, 크기/회전 변경, 재연결 때 pose를 초기화하고 이전 generation 결과를 차단합니다.
