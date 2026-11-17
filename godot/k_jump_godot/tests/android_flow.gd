extends SceneTree

func _initialize() -> void:
	call_deferred("_run")
	create_timer(8.0).timeout.connect(func(): quit(1))

func _run() -> void:
	var view = load("res://main.tscn").instantiate()
	root.add_child(view)
	await create_timer(0.2).timeout
	view._on_message(1, {"type": "hello", "player_id": "android-test", "message": "연결 되었습니다"})
	view._on_message(1, {"type": "ready", "workout_session_id": "a", "total_count": 5, "message": "Ready"})
	assert(view.players[1].sequence == 5)
	assert(view.players[1].tween == null, "Restored counts must not animate")
	view._on_message(1, {"type": "jump", "workout_session_id": "a", "sequence": 5, "message": "Counting Up"})
	assert(view.players[1].tween == null)
	view._on_message(1, {"type": "jump", "workout_session_id": "a", "sequence": 6, "message": "Counting Up"})
	assert(view.players[1].sequence == 6)
	assert(view.players[1].tween != null)
	view._on_message(1, {"type": "workout_state", "workout_session_id": "a", "total_count": 6, "state": "paused", "message": "일시정지"})
	assert("일시정지" in view.latest_label.text)
	assert(is_equal_approx(view.players[1].marker.position.y, 1.12))
	view._on_message(1, {"type": "ready", "workout_session_id": "a", "total_count": 6, "message": "Ready"})
	assert(view.players[1].sequence == 6, "Recalibration must preserve counts")
	view._on_message(1, {"type": "workout_state", "workout_session_id": "a", "total_count": 6, "state": "finished", "message": "운동 종료"})
	assert("운동 종료" in view.latest_label.text)
	view._on_message(1, {"type": "workout_state", "workout_session_id": "b", "total_count": 0, "state": "preparing", "message": "준비 중"})
	assert(view.players[1].sequence == 0)
	assert("준비 중" in view.latest_label.text)
	view.queue_free()
	await process_frame
	print("PASS: Android ready/count restoration, new jump, pause, recalibration, finish, new workout")
	quit()
