"""Run against a fresh Godot server: python python_sim/tests/integration.py --url ws://127.0.0.1:8081"""
import argparse
import json
from pathlib import Path
import sys
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from client import SimulatorClient
from websockets.sync.client import connect

URL = "ws://127.0.0.1:8081"


def wait_for(client, predicate):
    deadline = time.monotonic() + 6
    while time.monotonic() < deadline:
        if predicate(client.state):
            return client.state
        time.sleep(0.02)
    raise AssertionError(f"Timed out: {client.state}")


class IntegrationTest(unittest.TestCase):
    def setUp(self):
        self.clients = []

    def tearDown(self):
        for client in self.clients:
            client.close()
            self.assertFalse(client.thread.is_alive())

    def client(self, player="sim-01"):
        client = SimulatorClient(URL, player)
        self.clients.append(client)
        client.submit("connect")
        wait_for(client, lambda s: s.connected)
        return client

    def test_connect_ready_and_jump(self):
        client = self.client()
        self.assertEqual(client.state.status, "연결 되었습니다")
        client.submit("jump")
        client.submit("ready")  # Queue order checks pre-ready jump is ignored.
        wait_for(client, lambda s: s.ready)
        self.assertEqual(client.state.count, 0)
        client.submit("jump")
        client.submit("jump")
        state = wait_for(client, lambda s: s.count == 2)
        self.assertEqual(state.status, "Counting Up")

    def test_two_clients_are_independent(self):
        a, b = self.client("sim-01"), self.client("sim-02")
        for client in [a, b]:
            client.submit("ready")
            wait_for(client, lambda s: s.ready)
        a.submit("jump")
        b.submit("jump")
        b.submit("jump")
        wait_for(a, lambda s: s.count == 1)
        wait_for(b, lambda s: s.count == 2)
        self.assertNotEqual(a.session_id, b.session_id)

    def test_reconnect_resets_session_without_replaying(self):
        client = self.client()
        client.submit("ready")
        wait_for(client, lambda s: s.ready)
        client.submit("jump")
        wait_for(client, lambda s: s.count == 1)
        old_session = client.session_id
        client.submit("disconnect")
        wait_for(client, lambda s: not s.connected)
        client.submit("connect")
        wait_for(client, lambda s: s.connected)
        self.assertFalse(client.state.ready)
        self.assertEqual(client.state.count, 0)
        self.assertNotEqual(old_session, client.session_id)

    def test_legacy_android_and_invalid_messages(self):
        with connect(URL, proxy=None) as socket:
            for message in [
                {"protocol_version": 1, "type": "hello", "message": "Android"},
                {"protocol_version": 1, "type": "jump", "event_id": "android:1",
                 "workout_session_id": "android", "sequence": 1, "timestamp_ms": 1000,
                 "message": "점프 1회 완료"},
            ]:
                socket.send(json.dumps(message))
                self.assertEqual(json.loads(socket.recv(timeout=3))["type"], "ack")
            for message in [[], {"protocol_version": 99, "type": "hello", "message": "bad"},
                            {"protocol_version": 1, "type": "ready", "message": "no session"}]:
                socket.send(json.dumps(message))
                self.assertEqual(json.loads(socket.recv(timeout=3))["type"], "error")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=URL)
    args = parser.parse_args()
    URL = args.url
    unittest.main(argv=[sys.argv[0]], verbosity=2)
