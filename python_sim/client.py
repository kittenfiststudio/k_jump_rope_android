"""Godot protocol v1 client. Network I/O runs off the simulator window thread."""
import json
import queue
import threading
import time
import uuid
from dataclasses import dataclass

from websockets.sync.client import connect


@dataclass(frozen=True)
class State:
    status: str = "연결 안 됨"
    connected: bool = False
    ready: bool = False
    count: int = 0


class SimulatorClient:
    def __init__(self, url, player_id):
        self.url = url
        self.player_id = player_id
        self.commands = queue.Queue(maxsize=32)
        self.updates = queue.Queue(maxsize=128)
        self.stopping = threading.Event()
        self.state = State()
        self.socket = None
        self.session_id = ""
        self.origin = 0.0
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def submit(self, command):
        try:
            self.commands.put_nowait(command)
            return True
        except queue.Full:
            return False

    def _publish(self, state):
        self.state = state
        if self.updates.full():
            self.updates.get_nowait()
        self.updates.put_nowait(state)

    def _exchange(self, kind, **fields):
        message = {"protocol_version": 1, "type": kind,
                   "player_id": self.player_id, **fields}
        self.socket.send(json.dumps(message, ensure_ascii=False))
        reply = json.loads(self.socket.recv(timeout=3))
        if reply.get("type") != "ack" or reply.get("received_type") != kind:
            raise ValueError(f"Godot 응답 오류: {reply}")

    def _disconnect(self, status="연결 안 됨"):
        if self.socket is not None:
            self.socket.close()
            self.socket = None
        self._publish(State(status=status, count=self.state.count))

    def _run(self):
        try:
            while not self.stopping.is_set():
                try:
                    command = self.commands.get(timeout=0.05)
                except queue.Empty:
                    if self.socket is not None:
                        try:
                            self.socket.recv(timeout=0)
                        except TimeoutError:
                            pass
                        except Exception as error:
                            self._disconnect(f"연결 끊김: {error}")
                    continue
                try:
                    if command == "connect":
                        self._disconnect("연결 중…")
                        self.socket = connect(self.url, proxy=None, open_timeout=3,
                                              close_timeout=1, max_size=8192)
                        self.session_id = str(uuid.uuid4())
                        self._exchange("hello", message="연결 되었습니다", source="python_sim")
                        self._publish(State(status="연결 되었습니다", connected=True))
                    elif command == "ready" and self.state.connected and not self.state.ready:
                        self.origin = time.monotonic()
                        self._exchange("ready", workout_session_id=self.session_id, message="Ready")
                        self._publish(State("Ready", True, True))
                    elif command == "jump" and self.state.ready:
                        sequence = self.state.count + 1
                        self._exchange("jump", workout_session_id=self.session_id,
                                       event_id=f"{self.session_id}:{sequence}", sequence=sequence,
                                       timestamp_ms=int((time.monotonic() - self.origin) * 1000),
                                       message="Counting Up")
                        self._publish(State("Counting Up", True, True, sequence))
                    elif command == "disconnect":
                        self._disconnect()
                except Exception as error:
                    # A timed-out send may already have arrived. Never replay it automatically.
                    self._disconnect(f"통신 실패: {error} · C로 다시 연결")
                    while True:
                        try:
                            self.commands.get_nowait()
                        except queue.Empty:
                            break
        finally:
            self._disconnect()

    def close(self):
        self.stopping.set()
        self.thread.join(timeout=5)
