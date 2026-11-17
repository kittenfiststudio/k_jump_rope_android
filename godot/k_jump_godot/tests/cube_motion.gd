extends SceneTree

func _initialize() -> void:
	call_deferred("_run")
	create_timer(10.0).timeout.connect(func(): quit(1))

func _run() -> void:
	var cube = load("res://demo_cube.gd").new()
	root.add_child(cube)
	cube.random.seed = 42
	await create_timer(0.2).timeout
	var specials: Array[String] = []
	for sequence in range(1, 9):
		cube.play_jump(sequence)
		if sequence % 2 == 0:
			specials.append(cube.last_motion)
		else:
			assert(cube.last_motion == "jump")
		cube.stop_motion()
	assert(specials.size() == 4)
	for motion in cube.SPECIALS:
		assert(motion in specials, "All four tricks must occur in the first eight jumps")
	for motion in ["jump", "spin", "flip", "diagonal", "twist"]:
		cube._animate(0.5, motion, 1.0)
		assert(cube.body.position.y > cube.REST_POSITION.y)
		if motion == "diagonal":
			assert(cube.body.position.x > 1.0)
		cube._animate(1.0, motion, 1.0)
		cube._rest()
		assert(cube.body.position.is_equal_approx(cube.REST_POSITION))
		assert(cube.body.rotation.is_equal_approx(cube.REST_ROTATION))
		assert(cube.body.scale.is_equal_approx(Vector3.ONE))
	cube.play_jump(10)
	await create_timer(0.06).timeout
	assert(cube.body.position.y > cube.REST_POSITION.y)
	cube.stop_motion()
	assert(cube.body.position.is_equal_approx(cube.REST_POSITION))
	assert(cube.body.rotation.is_equal_approx(cube.REST_ROTATION))
	for sequence in range(11, 111):
		cube.play_jump(sequence)
	cube.stop_motion()
	assert(cube.motion_history.size() <= 12)
	assert(cube.body.position.is_equal_approx(cube.REST_POSITION))
	cube.reset_demo()
	assert(cube.bag.is_empty())
	assert(cube.motion_history.is_empty())
	cube.queue_free()
	await process_frame
	print("PASS: first eight jumps cover all tricks, arc/return, timed motion, interrupt, rapid inputs, reset")
	quit()
