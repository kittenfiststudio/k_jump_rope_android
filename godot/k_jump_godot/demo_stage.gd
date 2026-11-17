extends Node3D

var camera := Camera3D.new()
var preview: Node3D
var actors: Dictionary = {}

func _ready() -> void:
	var environment_node := WorldEnvironment.new()
	var environment := Environment.new()
	environment.background_mode = Environment.BG_COLOR
	environment.background_color = Color("101923")
	environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	environment.ambient_light_color = Color("a2d5d6")
	environment.ambient_light_energy = 0.3
	environment.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	environment_node.environment = environment
	add_child(environment_node)
	camera.projection = Camera3D.PROJECTION_ORTHOGONAL
	camera.size = 6.9
	camera.position = Vector3(0, 5.5, 11)
	add_child(camera)
	camera.look_at(Vector3(0, 1.3, 0))
	camera.current = true
	var key := DirectionalLight3D.new()
	key.rotation_degrees = Vector3(-45, -35, 0)
	key.light_energy = 1.2
	key.shadow_enabled = true
	add_child(key)
	var rim := DirectionalLight3D.new()
	rim.rotation_degrees = Vector3(-25, 135, 0)
	rim.light_color = Color("77c5ff")
	rim.light_energy = 0.35
	add_child(rim)
	var floor_mesh := PlaneMesh.new()
	floor_mesh.size = Vector2(200, 200)
	_add_mesh(floor_mesh, Vector3.ZERO, Color("080f16"))
	var platform := CylinderMesh.new()
	platform.top_radius = 2.7
	platform.bottom_radius = 2.85
	platform.height = 0.2
	platform.radial_segments = 96
	_add_mesh(platform, Vector3(0, 0.1, 0), Color("111e24"))
	var ring := TorusMesh.new()
	ring.inner_radius = 2.72
	ring.outer_radius = 2.76
	_add_mesh(ring, Vector3(0, 0.19, 0), Color("4cbd9b"), true)
	preview = load("res://demo_cube.gd").new()
	add_child(preview)

func _add_mesh(mesh: Mesh, point: Vector3, color: Color, unshaded := false) -> void:
	var instance := MeshInstance3D.new()
	instance.mesh = mesh
	instance.position = point
	var material := StandardMaterial3D.new()
	material.albedo_color = color
	material.roughness = 0.8
	if unshaded:
		material.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	instance.material_override = material
	add_child(instance)

func add_player(id: int) -> Node3D:
	var actor: Node3D
	if actors.is_empty():
		actor = preview
		actor.reset_demo()
	else:
		actor = load("res://demo_cube.gd").new()
		add_child(actor)
	actors[id] = actor
	_layout_players()
	return actor

func remove_player(id: int) -> void:
	if not actors.has(id):
		return
	var actor: Node3D = actors[id]
	actor.stop_motion()
	actors.erase(id)
	if actor != preview:
		actor.queue_free()
	preview.visible = actors.is_empty() or preview in actors.values()
	_layout_players()

func _layout_players() -> void:
	var count := actors.size()
	preview.visible = count == 0 or preview in actors.values()
	var index := 0
	for actor: Node3D in actors.values():
		actor.position = Vector3((index - (count - 1) / 2.0) * 3.7, 0, 0)
		index += 1
	if count == 0:
		preview.position = Vector3.ZERO
	camera.size = maxf(6.9, count * 3.7 * 0.75)
