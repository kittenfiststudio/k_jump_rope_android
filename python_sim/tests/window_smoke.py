"""Headless pygame input/render smoke test against the Godot test server."""
import argparse
import os
from pathlib import Path
import sys
import time
from unittest.mock import patch

os.environ["SDL_VIDEODRIVER"] = "dummy"
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pygame
import simulator
from client import SimulatorClient


def run(url, screenshot=None):
    clients = []
    phase = 0
    first_jump_at = 0.0
    deadline = time.monotonic() + 12

    def factory(*args):
        client = SimulatorClient(*args)
        clients.append(client)
        return client

    def events():
        nonlocal phase, first_jump_at
        if time.monotonic() > deadline:
            raise AssertionError(f"Window flow timed out: {clients[0].state}")
        state = clients[0].state
        if phase == 0:
            phase = 1
            return [pygame.event.Event(pygame.KEYDOWN, key=pygame.K_c)]
        if phase == 1 and state.connected:
            phase = 2
            return [pygame.event.Event(pygame.KEYDOWN, key=pygame.K_RETURN)]
        if phase == 2 and state.ready:
            phase = 3
            first_jump_at = time.monotonic()
            return [pygame.event.Event(pygame.KEYDOWN, key=pygame.K_SPACE)] * 3
        if phase == 3 and state.count == 1 and time.monotonic() - first_jump_at > 0.2:
            phase = 4
            return [pygame.event.Event(pygame.KEYUP, key=pygame.K_SPACE),
                    pygame.event.Event(pygame.KEYDOWN, key=pygame.K_SPACE)]
        if phase == 4 and state.count == 2:
            phase = 5
            return []  # Let the window consume and render the second ACK.
        if phase == 5:
            if screenshot:
                pygame.image.save(pygame.display.get_surface(), screenshot)
            phase = 6
            return [pygame.event.Event(pygame.QUIT)]
        if phase == 3:
            assert state.count <= 1, "Held Space generated extra jumps"
        return []

    with patch.object(simulator, "SimulatorClient", factory), patch.object(pygame.event, "get", events), \
            patch.object(sys, "argv", ["simulator.py", "--url", url, "--player-id", "window-test"]):
        simulator.main()
    assert phase == 6
    assert clients[0].state.count == 2
    assert not clients[0].thread.is_alive()
    print("PASS: pygame C / Enter / held Space / release + Space / render / exit")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="ws://127.0.0.1:8081")
    parser.add_argument("--screenshot")
    args = parser.parse_args()
    run(args.url, args.screenshot)
