좋아! 그럼 너가 제안한 python 가상 측정기를 여기서 제작 들어가자. python 과 관련된 .gitignore 를 추가하는 작업을 시작으로
다음과 같은 기본 설정을 가지고 간다. simulator 에 연결된 측정은

키보드 space 바 입력을 점프로 간주.

연결 - 안드로이드 폰 서버 연결 - Godot '연결 되었습니다'

케릭터 동작 연결 - 운동 인식 연결 - Godot 'Ready'

시뮬레이션 space 바 입력 - 점프 카운팅 - 'Counting Up'


## 구현 — 2026-11-17

- 구현은 `python_sim/`에서 관리합니다. 설치·실행·두 기기 시연 방법은
  [시뮬레이터 README](../python_sim/README.md)를 참고합니다.
- C/연결 버튼 → `hello` → Godot **연결 되었습니다**.
- Enter/준비 버튼 → `ready` → Godot **Ready**.
- 준비 후 Space → `jump` → Godot **Counting Up**, 횟수 증가와 사각형 점프.
- Python 창의 입력으로 운동 준비를 대신합니다. 실제 포즈 추론이나 보정은 실행하지 않습니다.
- Android는 기존 hello/jump 메시지 호환을 유지합니다. 이번에는 앱 코드를 변경하지 않습니다.
