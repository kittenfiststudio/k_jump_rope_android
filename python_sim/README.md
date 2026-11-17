# Python 가상 측정기

Python 3.10 이상을 사용합니다. 폰 없이 PC 한 대에서 시뮬레이터와 Godot을 함께 실행합니다.
키보드 입력을 운동 입력으로 바꾸는 개발 도구이며 카메라 측정 정확도를 검증하지 않습니다.

## 설치와 실행

저장소 루트에서 실행합니다(macOS/Linux).

```sh
python3 -m venv python_sim/.venv
python_sim/.venv/bin/python -m pip install -r python_sim/requirements.txt
python_sim/.venv/bin/python python_sim/simulator.py
```

Windows PowerShell:

```powershell
py -3 -m venv python_sim/.venv
python_sim/.venv/Scripts/python.exe -m pip install -r python_sim/requirements.txt
python_sim/.venv/Scripts/python.exe python_sim/simulator.py
```

1. Godot에서 `godot/k_jump_godot/project.godot`를 열어 F5로 실행합니다.
   이전 메시지 확인 버전이 실행 중이었다면 F8로 종료한 뒤 F5로 다시 실행해야 합니다.
2. Python 시뮬레이터를 실행합니다. 기본 주소는 같은 PC의 `ws://127.0.0.1:8080`입니다.
3. **시뮬레이터 창을 선택하고 C 또는 연결 버튼** → Godot에 **연결 되었습니다**.
4. **Enter 또는 준비 버튼** → Godot에 **Ready**.
5. **Space** → 횟수가 1씩 늘고 Godot에 **Counting Up**, 플레이어 사각형이 점프합니다.
6. D 또는 연결 해제로 접속을 끊고 Esc나 창 닫기로 종료합니다.

Space를 길게 눌러도 1회입니다. 키를 뗐다 다시 눌러야 다음 점프가 발생합니다.
입력은 시뮬레이터 창이 선택된 동안에만 받습니다. Godot 창을 선택하면 Space는 전달되지 않습니다.
준비 전 입력은 무시합니다. 재접속은 새 운동으로 시작하므로 Enter로 다시 준비합니다.
횟수는 Godot의 ACK를 받은 뒤 표시합니다. 끊긴 동안의 입력은 보관·재전송하지 않습니다.

서버 주소와 플레이어 이름은 실행 인자로 바꿉니다.

```sh
python_sim/.venv/bin/python python_sim/simulator.py --url ws://192.168.0.137:8080 --player-id sim-01
```

## 두 기기 시뮬레이션

터미널을 두 개 열고 각 명령을 실행합니다.

```sh
python_sim/.venv/bin/python python_sim/simulator.py --player-id sim-01
python_sim/.venv/bin/python python_sim/simulator.py --player-id sim-02
```

각 창에서 C → Enter를 누른 뒤 창을 번갈아 선택해 Space를 누르면
Godot에 두 플레이어의 횟수와 점프가 따로 표시됩니다. 키보드 입력은 선택된 창에만 전달됩니다.
폰과 시뮬레이터를 동시에 연결하는 것도 가능합니다.

## 구현과 프로토콜

- `simulator.py`: pygame 창, 키 입력, 상태와 ACK된 횟수 표시.
- `client.py`: UI와 분리된 WebSocket 작업 스레드. 입력 큐 최대 32개, 연결/응답 timeout 3초.
- `requirements.txt`: pygame 2.6.1, websockets 15.0.1 고정.
- `hello` / `jump`는 Android와 같은 v1 규격입니다. 시뮬레이터에는 `player_id`를 추가합니다.
- 준비 시 `{"protocol_version":1,"type":"ready","player_id":"sim-01","workout_session_id":"UUID","message":"Ready"}`를 보냅니다.
- jump의 `timestamp_ms`는 준비 이후 단조 시각입니다. PC와 폰 간 절대 시각 비교에 사용하지 않습니다.
- Android 앱은 기존 hello/jump를 그대로 사용할 수 있습니다. Android의 별도 ready 전송은 이번 범위에 없습니다.
- Godot은 현재 접속의 운동 세션별 sequence가 증가할 때만 횟수/애니메이션을 갱신합니다.
  재접속을 넘는 플레이어 복원·영구 중복 제거·공동 게임 세션 관리는 후속 범위입니다.
- 연결 실패 시 창은 유지되며 C로 다시 연결할 수 있습니다. 자동 연결/자동 재접속은 하지 않습니다.

## 검증

실행 중인 개발 게임과 분리해서 Godot 테스트 서버를 띄웁니다.
`godot` 명령은 설치된 Godot 실행 파일 경로로 바꿀 수 있습니다.

```sh
godot --headless --path godot/k_jump_godot -- --port=8081
```

다른 터미널에서:

```sh
python_sim/.venv/bin/python python_sim/tests/integration.py --url ws://127.0.0.1:8081
```

연결/준비/점프, 2개 클라이언트, 재접속 초기화, 기존 Android 메시지 호환과 잘못된 입력을 검사합니다.
Godot 표시·중복 애니메이션 억제 검증:

```sh
godot --headless --path godot/k_jump_godot --script res://tests/player_flow.gd -- --port=8082
```

pygame 입력·렌더링 검증(화면 창을 열지 않는 SDL dummy 모드):

```sh
python_sim/.venv/bin/python python_sim/tests/window_smoke.py --url ws://127.0.0.1:8081
```

C → Enter → Space 반복 KEYDOWN → 키 해제/재입력을 실제 창 루프에 전달하여
길게 누르기 중복 방지와 렌더링·종료를 검사합니다.

연결 해제 시 Godot은 해당 플레이어 행을 제거하고 마지막 상태·로그에 `연결 해제`를 표시합니다.
마지막 기기가 해제되면 기기 수는 0, 상태는 `새 연결을 기다리고 있습니다`가 됩니다.
서버는 다음 접속을 받기 위해 계속 실행됩니다. 실제 WebSocket 종료 흐름 검증:

```sh
godot --headless --path godot/k_jump_godot --script res://tests/disconnect_flow.gd -- --port=8082
```
