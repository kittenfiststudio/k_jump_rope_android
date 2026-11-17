extends Node3D
## Presentation only. One accepted jump = one animation; tricks never add counts.
const REST_POSITION := Vector3(0, 1.12, 0)
const REST_ROTATION := Vector3(0, PI / 4.0, 0)
const SPECIALS := ["spin", "flip", "diagonal", "twist"]
const MOTION_NAMES := {"jump": "기본 점프", "spin": "360° 스핀", "flip": "공중 앞구르기",
	"diagonal": "대각선 왕복 점프", "twist": "더블 트위스트"}
var body := Node3D.new()
var motion_tween: Tween
var last_motion := "jump"
var motion_history: Array[String] = []
var bag: Array[String] = []
var last_special := ""
var last_input_ms := -1
var random := RandomNumberGenerator.new()

func _ready() -> void:
	random.randomize()
	add_child(body)
	body.position = REST_POSITION
	body.rotation = REST_ROTATION
	var cube := MeshInstance3D.new()
	var box := BoxMesh.new()
	box.size = Vector3.ONE * 1.65
	cube.mesh = box
	var material := StandardMaterial3D.new()
	material.albedo_color = Color("52e69c")
	material.metallic = 0.22
	material.roughness = 0.3
	cube.material_override = material
	body.add_child(cube)
	# Thin edges keep the three-dimensional silhouette legible from a distance.
	var edge_material := StandardMaterial3D.new()
	edge_material.albedo_color = Color("c4ffdc")
	edge_material.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	for axis in range(3):
		for a in [-1.0, 1.0]:
			for b in [-1.0, 1.0]:
				var edge := MeshInstance3D.new()
				var beam := BoxMesh.new()
				var dimensions := Vector3.ONE * 0.012
				dimensions[axis] = 1.66
				beam.size = dimensions
				edge.mesh = beam
				var point := Vector3.ZERO
				point[(axis + 1) % 3] = a * 0.825
				point[(axis + 2) % 3] = b * 0.825
				edge.position = point
				edge.material_override = edge_material
				body.add_child(edge)

func play_jump(sequence: int) -> Tween:
	stop_motion()
	last_motion = "jump" if sequence % 2 == 1 else _next_special()
	motion_history.append(last_motion)
	if motion_history.size() > 12:
		motion_history.pop_front()
	var now := Time.get_ticks_msec()
	var duration := 0.42 if last_input_ms < 0 else clampf((now - last_input_ms) / 1000.0 * 0.88, 0.18, 0.44)
	last_input_ms = now
	var direction := -1.0 if random.randf() < 0.5 else 1.0
	motion_tween = create_tween()
	motion_tween.tween_method(_animate.bind(last_motion, direction), 0.0, 1.0, duration)
	motion_tween.tween_callback(_rest)
	return motion_tween

func _next_special() -> String:
	if bag.is_empty():
		bag.assign(SPECIALS)
		# Use the local RNG so tests can seed it without affecting the application.
		for index in range(bag.size() - 1, 0, -1):
			var other := random.randi_range(0, index)
			var saved := bag[index]
			bag[index] = bag[other]
			bag[other] = saved
		if bag.back() == last_special:
			var saved := bag[0]
			bag[0] = bag[bag.size() - 1]
			bag[bag.size() - 1] = saved
	last_special = bag.pop_back()
	return last_special

func _animate(progress: float, motion: String, direction: float) -> void:
	var arc := sin(PI * progress)
	var turn := smoothstep(0.0, 1.0, progress)
	var height := 1.2 if motion == "jump" else 1.65
	body.position = REST_POSITION + Vector3(0, arc * height, 0)
	body.rotation = REST_ROTATION
	match motion:
		"spin": body.rotation.y += direction * TAU * turn
		"flip": body.rotation.x -= TAU * turn
		"diagonal":
			body.position += Vector3(direction * 1.55 * arc, 0, -0.65 * arc)
			body.rotation.z = -direction * 0.45 * arc
		"twist":
			body.rotation.x += TAU * turn
			body.rotation.y += direction * TAU * turn
	var squash := 0.0
	if progress < 0.14:
		squash = sin(PI * progress / 0.14)
	elif progress > 0.82:
		squash = sin(PI * (progress - 0.82) / 0.18)
	body.scale = Vector3(1.0 + 0.13 * squash, 1.0 - 0.2 * squash, 1.0 + 0.13 * squash)

func _rest() -> void:
	body.position = REST_POSITION
	body.rotation = REST_ROTATION
	body.scale = Vector3.ONE

func stop_motion() -> void:
	if motion_tween != null:
		motion_tween.kill()
	_rest()

func reset_demo() -> void:
	stop_motion()
	bag.clear()
	motion_history.clear()
	last_special = ""
	last_input_ms = -1
