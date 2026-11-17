extends Control

var server: Node
var status_label: Label
var clients_label: Label
var latest_label: Label
var count_label: Label
var motion_label: Label
var log_view: RichTextLabel
var lines: Array[String] = []
var player_list: VBoxContainer
var players: Dictionary = {}
var cube_stage

func _ready() -> void:
	cube_stage = load("res://demo_stage.gd").new()
	add_child(cube_stage)
	var margin := MarginContainer.new()
	margin.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	for side in ["left", "right", "top", "bottom"]:
		margin.add_theme_constant_override("margin_" + side, 24)
	add_child(margin)
	var column := VBoxContainer.new()
	column.add_theme_constant_override("separation", 6)
	margin.add_child(column)
	var header := HBoxContainer.new()
	column.add_child(header)
	var title := Label.new()
	title.text = "K-ROPE   /   JUMP STAGE"
	title.add_theme_font_size_override("font_size", 20)
	title.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	header.add_child(title)
	clients_label = Label.new()
	clients_label.text = "연결된 기기: 0"
	clients_label.add_theme_font_size_override("font_size", 13)
	header.add_child(clients_label)
	status_label = Label.new()
	status_label.add_theme_font_size_override("font_size", 11)
	status_label.modulate = Color("9bb5c1")
	column.add_child(status_label)
	var addresses: Array[String] = []
	for address in IP.get_local_addresses():
		if ":" not in address and not address.begins_with("127."):
			addresses.append("ws://%s:8080" % address)
	var help_label := Label.new()
	help_label.text = "폰 / 시뮬레이터 연결 → Ready → 점프  ·  " + ", ".join(addresses)
	help_label.add_theme_font_size_override("font_size", 11)
	help_label.modulate = Color("9bb5c1")
	help_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	column.add_child(help_label)
	count_label = Label.new()
	count_label.text = "0"
	count_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	count_label.add_theme_font_size_override("font_size", 68)
	count_label.add_theme_color_override("font_color", Color("9affc6"))
	column.add_child(count_label)
	var space := Control.new()
	space.size_flags_vertical = Control.SIZE_EXPAND_FILL
	space.mouse_filter = Control.MOUSE_FILTER_IGNORE
	column.add_child(space)
	motion_label = Label.new()
	motion_label.text = "READY TO JUMP"
	motion_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	motion_label.add_theme_font_size_override("font_size", 22)
	column.add_child(motion_label)
	latest_label = Label.new()
	latest_label.text = "기기를 연결하고 운동을 시작하세요."
	latest_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	latest_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	latest_label.add_theme_font_size_override("font_size", 13)
	column.add_child(latest_label)
	var scroll := ScrollContainer.new()
	scroll.custom_minimum_size.y = 34
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	column.add_child(scroll)
	player_list = VBoxContainer.new()
	player_list.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	scroll.add_child(player_list)
	var log_header := HBoxContainer.new()
	column.add_child(log_header)
	var hint := Label.new()
	hint.text = "2회마다 랜덤 스페셜 · 스핀 / 플립 / 대각선 / 트위스트"
	hint.add_theme_font_size_override("font_size", 11)
	hint.modulate = Color("9bb5c1")
	hint.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	log_header.add_child(hint)
	var log_toggle := CheckButton.new()
	log_toggle.text = "수신 로그"
	log_toggle.button_pressed = true
	log_toggle.add_theme_font_size_override("font_size", 11)
	log_header.add_child(log_toggle)
	log_view = RichTextLabel.new()
	log_view.custom_minimum_size.y = 64
	log_view.add_theme_font_size_override("normal_font_size", 10)
	log_view.modulate = Color("8098a7")
	log_view.scroll_following = true
	column.add_child(log_view)
	log_toggle.toggled.connect(func(visible_logs: bool): log_view.visible = visible_logs)
	server = load("res://message_server.gd").new()
	server.status_changed.connect(func(text: String): status_label.text = text)
	server.clients_changed.connect(func(count: int): clients_label.text = "연결된 기기: %d" % count)
	server.message_received.connect(_on_message)
	server.client_disconnected.connect(_on_disconnect)
	add_child(server)

func _on_message(id: int, message: Dictionary) -> void:
	if not players.has(id):
		var row := HBoxContainer.new()
		var label := Label.new()
		label.add_theme_font_size_override("font_size", 12)
		label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		row.add_child(label)
		player_list.add_child(row)
		var actor = cube_stage.add_player(id)
		players[id] = {"row": row, "label": label, "actor": actor, "marker": actor.body,
			"session": "", "sequence": 0, "tween": null,
			"name": str(message.get("player_id", "기기 #%d" % id)).left(40)}
	var player: Dictionary = players[id]
	var status := "연결 되었습니다"
	if message.type in ["ready", "workout_state", "jump"] and player.session != message.workout_session_id:
		player.session = message.workout_session_id
		player.sequence = 0
		player.actor.reset_demo()
		player.tween = null
	if message.type in ["ready", "workout_state"]:
		if message.has("total_count"):
			player.sequence = max(player.sequence, int(message.total_count))
		status = "Ready" if message.type == "ready" else str(message.message).left(80)
		player.actor.stop_motion()
		player.tween = null
		motion_label.text = "READY" if message.type == "ready" else status
	elif message.type == "jump":
		if message.sequence <= player.sequence:
			return
		player.sequence = int(message.sequence)
		status = "Counting Up"
		player.tween = player.actor.play_jump(player.sequence)
		motion_label.text = player.actor.MOTION_NAMES[player.actor.last_motion]
	player.label.text = "%s · %s · %d회" % [player.name, status, player.sequence]
	latest_label.text = player.label.text
	count_label.text = str(player.sequence)
	_append_log("#%d · %s" % [id, JSON.stringify(message)])

func _append_log(text: String) -> void:
	var line := "%s · %s" % [Time.get_time_string_from_system(), text]
	print(line)
	lines.append(line)
	if lines.size() > 100:
		lines.pop_front()
	log_view.text = "\n".join(lines)

func _on_disconnect(id: int) -> void:
	if players.has(id):
		var player_name: String = players[id].name
		cube_stage.remove_player(id)
		players[id].row.queue_free()
		players.erase(id)
		latest_label.text = "%s · 연결 해제" % player_name
		if players.is_empty():
			latest_label.text += " · 새 연결을 기다리고 있습니다."
			count_label.text = "0"
			motion_label.text = "READY TO JUMP"
		else:
			count_label.text = str(players.values()[0].sequence)
			motion_label.text = "연결된 플레이어 대기"
		_append_log("#%d · %s · 연결 해제" % [id, player_name])
