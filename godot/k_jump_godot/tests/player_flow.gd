extends SceneTree

func _initialize() -> void:
	call_deferred("_run")
	create_timer(8.0).timeout.connect(func(): quit(1))

func _run() -> void:
	var view = load("res://main.tscn").instantiate()
	root.add_child(view)
	# Let the initial font/layout frame finish before timing a short animation.
	await create_timer(0.2).timeout
	await process_frame
	view._on_message(1, {"type": "hello", "player_id": "sim-01", "message": "hello"})
	assert("연결 되었습니다" in view.latest_label.text)
	view._on_message(1, {"type": "ready", "workout_session_id": "a", "message": "Ready"})
	assert("Ready" in view.latest_label.text)
	var jump := {"type": "jump", "workout_session_id": "a", "sequence": 1, "message": "Counting Up"}
	view._on_message(1, jump)
	assert(view.players[1].sequence == 1)
	assert("Counting Up" in view.latest_label.text)
	await create_timer(0.06).timeout
	assert(view.players[1].marker.position.y < 28.0, "Jump must move the marker")
	var tween = view.players[1].tween
	view._on_message(1, jump)
	assert(view.players[1].tween == tween, "Duplicate must not animate twice")
	await create_timer(0.35).timeout
	assert(is_equal_approx(view.players[1].marker.position.y, 28.0), "Marker must land")
	view._on_message(2, {"type": "hello", "player_id": "sim-02", "message": "hello"})
	assert(view.players[2].sequence == 0)
	assert(view.players[1].sequence == 1)
	view._on_message(1, {"type": "ready", "workout_session_id": "b", "message": "Ready"})
	assert(view.players[1].sequence == 0)
	view._on_disconnect(1)
	assert(not view.players.has(1))
	assert("sim-01 · 연결 해제" in view.latest_label.text)
	assert(view.players.has(2), "Other player must remain connected")
	view._on_disconnect(2)
	assert("새 연결을 기다리고 있습니다" in view.latest_label.text)
	assert("연결 해제" in view.log_view.text)
	view.queue_free()
	await process_frame
	print("PASS: connected / Ready / Counting Up, duplicate, two players, reset, disconnect")
	quit()
