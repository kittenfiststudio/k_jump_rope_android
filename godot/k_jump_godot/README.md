# Android ↔ Godot 메시지 확인

첫 목표는 **점프 없이 Android 앱에서 보낸 인사가 PC 화면에 나타나는 것**입니다.
Android 테스트 버튼은 추가하지 않습니다. Python 시뮬레이터와 Ready/Counting Up 표시는
후속으로 구현했으며 [실행 방법](../../python_sim/README.md)을 참고합니다.

## 실행

1. Godot 4에서 이 폴더의 `project.godot`를 열고 F5로 실행합니다.
2. 화면에 `서버 실행 중 · 포트 8080`이 표시되는지 확인합니다.
3. Android와 PC를 같은 Wi-Fi에 연결합니다.
4. Android debug APK를 설치하고 앱의 **개발 지표**를 켭니다.
5. 아래로 스크롤해 **PC Godot 연결**에 `ws://PC의LAN주소:8080`을 입력하고 **연결**합니다.
6. PC 화면에 `Android 줄넘기 앱이 연결되었습니다.`가 표시되고,
   Android에 `PC 응답: ... Godot 수신 완료`가 표시되면 왕복 통신 성공입니다.
   카메라 권한이나 운동 시작·점프는 이 확인에 필요하지 않습니다.
7. 운동을 시작하면 PC에 `준비 중`, 실제 보정이 완료되면 `Ready`가 표시됩니다.
   이후 실제 점프마다 `Counting Up`과 횟수가 표시되며 해당 사각형이 점프합니다.

PC 화면의 주소 후보 중 실제 Wi-Fi/LAN 주소를 선택합니다. VPN·가상 어댑터 주소도 표시될 수 있습니다.
실제 휴대폰에서 `127.0.0.1`은 휴대폰 자신입니다. Android 기본 에뮬레이터는
호스트 PC에 `ws://10.0.2.2:8080`으로 연결합니다.
방화벽에서 Godot의 TCP 8080 수신을 허용하고, 공유기의 게스트 Wi-Fi/AP 격리를 확인합니다.
USB 연결로 네트워크를 우회하려면 `adb reverse tcp:8080 tcp:8080` 후 앱에서
`ws://127.0.0.1:8080`을 사용합니다. 이는 Wi-Fi 검증과 별도로 기록합니다.

포트는 `message_server.gd`의 `port`에서 변경할 수 있습니다. 변경 시 Android 주소도 맞춥니다.
현재 UI의 주소 후보 표시는 기본 포트 8080 기준입니다.

## 구조와 범위

- `message_server.gd`: 표준 WebSocket 서버, JSON 검사와 ACK. `TCPServer` + `WebSocketPeer` 사용.
- `main.gd` / `main.tscn`: 서버 신호를 받아 최근 메시지와 기기 수 표시.
- 화면 로그는 최대 100줄, 클라이언트 최대 16개, 메시지 최대 8 KiB입니다.
- 연결 설정은 Android에 저장됩니다. 재실행 시 자동 접속하지 않고 사용자가 연결합니다.
- 네트워크 오류는 측정·로컬 저장을 중단시키지 않습니다.
- 재연결은 수동입니다. 끊긴 동안의 점프를 저장하거나 재전송하지 않습니다.
- 여기서는 수신 로그만 표시합니다. 현재 접속/운동 세션 내 sequence 중복은 표시·애니메이션에 반영하지 않습니다.
  Android는 재접속 시 현재 상태·누적 횟수를 복원합니다. 지난 점프를 다시 애니메이션으로 재생하지 않습니다.
  공동 게임 세션 복구·게임 공격은 후속 단계입니다.
- LAN 연결은 debug 빌드 전용입니다. debug Manifest에서만 INTERNET과 평문 통신을 허용합니다.
  release는 기존 네트워크 권한 제거 정책을 유지합니다.
- 영상·포즈 좌표·학생 개인정보는 전송하지 않습니다. 수신 로그는 디스크에 저장하지 않습니다.

## 메시지 v1

WebSocket 텍스트 프레임에 JSON 객체를 전달합니다.

```json
{"protocol_version":1,"type":"hello","message":"Android 줄넘기 앱이 연결되었습니다."}
```

연결 성공 직후 자동 전송됩니다. 연결 재시도에도 새 인사를 전송합니다.

```json
{"protocol_version":1,"type":"jump","event_id":"UUID:1","workout_session_id":"UUID","sequence":1,"timestamp_ms":1234,"message":"점프 1회 완료"}
```

`workout_session_id`는 Android 운동 UUID이며 공동 게임 세션이 아닙니다.
`event_id`와 `sequence`는 기존 판정 결과를 그대로 사용합니다. 일시정지에도 sequence는 유지됩니다.
`timestamp_ms`는 운동 시작 이후 단조 시각이며 PC 시계와 빼서 네트워크 지연을 구하면 안 됩니다.

```json
{"protocol_version":1,"type":"ack","received_type":"hello","message":"Godot 수신 완료"}
```

Godot이 유효한 메시지를 화면에 전달한 뒤 ACK를 보냅니다. ACK는 전송 내용의 수신 확인이며
운동 정확도나 게임 동작 성공을 뜻하지 않습니다.

공식 API 참고: https://docs.godotengine.org/en/stable/tutorials/networking/websocket.html

## Python 준비 이벤트

`ready` 메시지의 운동 세션 ID를 받아 Ready를 표시합니다. 이후 jump에서 Counting Up과
횟수·사각형 점프를 표시합니다. 기존 Android는 ready 없이 hello/jump를 사용할 수 있습니다.

## Android 실측 운동 상태

- 연결 시 저장된 익명 `android-…` ID와 hello를 보냅니다. 이름·학생 ID·영상·포즈는 전송하지 않습니다.
- 시작/재개 → 준비 중, 실제 보정 완료 → Ready, 확정 점프 → Counting Up과 사각형 점프.
- 일시정지/백그라운드 → 일시정지, 추적 품질 저하 → 추적 재확인 중, 종료 → 운동 종료.
- 상태는 전환 시에만 보냅니다. 재접속하면 최신 상태와 `total_count`를 보내지만 지난 jump는 재전송하지 않습니다.
- 추가 메시지 예: `{"protocol_version":1,"type":"workout_state","player_id":"android-…","workout_session_id":"UUID","state":"paused","total_count":3,"message":"일시정지"}`.
- `ready`에도 `total_count`를 포함할 수 있습니다. 현재 횟수 복원은 애니메이션을 발생시키지 않습니다.
- 지원 state: preparing / paused / tracking_lost / finished. Ready는 기존 ready 메시지를 사용합니다.
