"""Run from the repository root: python python_sim/simulator.py"""
import argparse
import queue
import pygame

from client import SimulatorClient, State


def main():
    parser = argparse.ArgumentParser(description="Space 입력을 Godot 점프 이벤트로 전송")
    parser.add_argument("--url", default="ws://127.0.0.1:8080")
    parser.add_argument("--player-id", default="sim-01")
    args = parser.parse_args()
    if not args.player_id.strip() or len(args.player_id) > 40:
        parser.error("player-id는 1~40자여야 합니다.")
    pygame.display.init()
    pygame.font.init()
    screen = pygame.display.set_mode((760, 470))
    pygame.display.set_caption(f"K-Rope Simulator · {args.player_id}")
    pygame.key.set_repeat()  # A held Space key must produce one jump.
    font = pygame.font.SysFont("applegothic,malgungothic,nanumgothic", 24)
    large = pygame.font.SysFont("applegothic,malgungothic,nanumgothic", 56)
    clock = pygame.time.Clock()
    client = SimulatorClient(args.url, args.player_id)
    state = State()
    pressed = set()
    running = True
    notice = "C 또는 연결 버튼으로 시작하세요."
    buttons = [(pygame.Rect(30, 345, 210, 50), "연결 (C)", "connect"),
               (pygame.Rect(265, 345, 210, 50), "준비 (Enter)", "ready"),
               (pygame.Rect(500, 345, 230, 50), "연결 해제 (D)", "disconnect")]

    def submit(command):
        nonlocal notice
        if command == "jump" and not state.ready:
            notice = "먼저 연결하고 Enter를 눌러 Ready 상태로 만드세요."
            return
        if not client.submit(command):
            notice = "입력이 너무 빠릅니다. 잠시 후 다시 눌러 주세요."
        else:
            notice = "시뮬레이터 창에서 Space를 한 번 누르면 점프 1회입니다."

    try:
        while running:
            while True:
                try:
                    state = client.updates.get_nowait()
                except queue.Empty:
                    break
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    running = False
                elif event.type == pygame.WINDOWFOCUSLOST:
                    pressed.clear()
                elif event.type == pygame.KEYUP:
                    pressed.discard(event.key)
                elif event.type == pygame.KEYDOWN and event.key not in pressed:
                    pressed.add(event.key)
                    if event.key == pygame.K_ESCAPE:
                        running = False
                    command = {pygame.K_c: "connect", pygame.K_RETURN: "ready",
                               pygame.K_SPACE: "jump", pygame.K_d: "disconnect"}.get(event.key)
                    if command:
                        submit(command)
                elif event.type == pygame.MOUSEBUTTONDOWN and event.button == 1:
                    for rect, _label, command in buttons:
                        if rect.collidepoint(event.pos):
                            submit(command)
            screen.fill((19, 25, 38))
            texts = [("K-Rope · 가상 측정기", 30, 25),
                     (f"플레이어: {args.player_id}  |  {args.url}", 30, 80),
                     (state.status[:60], 30, 125),
                     ("Space = 점프 1회  ·  Enter = Ready", 30, 270),
                     (notice, 30, 415)]
            for text, x, y in texts:
                screen.blit(font.render(text, True, (225, 232, 245)), (x, y))
            screen.blit(large.render(f"{state.count} 회", True, (93, 217, 177)), (30, 180))
            for rect, label, _command in buttons:
                pygame.draw.rect(screen, (44, 67, 102), rect, border_radius=8)
                screen.blit(font.render(label, True, (255, 255, 255)), (rect.x + 15, rect.y + 13))
            pygame.display.flip()
            clock.tick(30)
    finally:
        client.close()
        pygame.quit()


if __name__ == "__main__":
    main()
