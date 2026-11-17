extends SceneTree
## Real WebSocket close handshake must update the monitor, not just remove a row.

func _initialize() -> void:
	call_deferred("_run")
	create_timer(10.0).timeout.connect(func(): quit(1))

func _wait_open(socket: WebSocketPeer) -> void:
	while socket.get_ready_state() != WebSocketPeer.STATE_OPEN:
		socket.poll()
		await process_frame

func _hello(socket: WebSocketPeer, name: String) -> void:
	socket.send_text(JSON.stringify({"protocol_version": 1, "type": "hello",
		"player_id": name, "message": "연결 되었습니다"}))
	while socket.get_available_packet_count() == 0:
		socket.poll()
		await process_frame
	assert(JSON.parse_string(socket.get_packet().get_string_from_utf8()).type == "ack")

func _close(socket: WebSocketPeer, view: Control, remaining: int) -> void:
	socket.close(1000, "Simulator disconnected")
	while socket.get_ready_state() != WebSocketPeer.STATE_CLOSED or view.players.size() != remaining:
		socket.poll()
		await process_frame
	await process_frame

func _run() -> void:
	var view = load("res://main.tscn").instantiate()
	root.add_child(view)
	await create_timer(0.2).timeout
	var a := WebSocketPeer.new()
	var b := WebSocketPeer.new()
	var url := "ws://127.0.0.1:%d" % view.server.port
	assert(a.connect_to_url(url) == OK)
	await _wait_open(a)
	await _hello(a, "sim-01")
	assert(b.connect_to_url(url) == OK)
	await _wait_open(b)
	await _hello(b, "sim-02")
	assert(view.clients_label.text == "연결된 기기: 2")
	await _close(a, view, 1)
	assert(view.clients_label.text == "연결된 기기: 1")
	assert("sim-01 · 연결 해제" in view.latest_label.text)
	assert(view.players.values()[0].name == "sim-02")
	await _close(b, view, 0)
	assert(view.clients_label.text == "연결된 기기: 0")
	assert("sim-02 · 연결 해제" in view.latest_label.text)
	assert("새 연결을 기다리고 있습니다" in view.latest_label.text)
	view.queue_free()
	await process_frame
	print("PASS: real WebSocket disconnect updates count, latest status, remaining player, waiting state")
	quit()
