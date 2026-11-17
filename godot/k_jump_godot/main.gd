extends Control

var server: Node
var status_label: Label
var clients_label: Label
var latest_label: Label
var log_view: RichTextLabel
var lines: Array[String] = []
var player_list: VBoxContainer
var players: Dictionary = {}

func _ready() -> void:
	var margin := MarginContainer.new()
	margin.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	for side in ["left", "right", "top", "bottom"]:
		margin.add_theme_constant_override("margin_" + side, 24)
	add_child(margin)
	var column := VBoxContainer.new()
	column.add_theme_constant_override("separation", 14)
	margin.add_child(column)
	var title := Label.new()
	title.text = "K-Rope · 기기 / 시뮬레이터 연결"
	title.add_theme_font_size_override("font_size", 30)
	column.add_child(title)
	status_label = Label.new()
	column.add_child(status_label)
	var addresses: Array[String] = []
	for address in IP.get_local_addresses():
		if ":" not in address and not address.begins_with("127."):
			addresses.append("ws://%s:8080" % address)
	var help_label := Label.new()
	help_label.text = "Android 개발 지표 → PC Godot 연결 → 주소 입력 → 연결\nPC 주소 후보: " + ", ".join(addresses)
	help_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	column.add_child(help_label)
	clients_label = Label.new()
	clients_label.text = "연결된 기기: 0"
	column.add_child(clients_label)
	latest_label = Label.new()
	latest_label.text = "Android의 인사 메시지를 기다리고 있습니다. 점프할 필요 없습니다."
	latest_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	latest_label.add_theme_font_size_override("font_size", 24)
	column.add_child(latest_label)
	player_list = VBoxContainer.new()
	column.add_child(player_list)
	log_view = RichTextLabel.new()
	log_view.size_flags_vertical = Control.SIZE_EXPAND_FILL
	log_view.scroll_following = true
	column.add_child(log_view)
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
		label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		row.add_child(label)
		var stage := Control.new()
		stage.custom_minimum_size = Vector2(64, 64)
		row.add_child(stage)
		var marker := ColorRect.new()
		marker.size = Vector2(32, 32)
		marker.position = Vector2(16, 28)
		marker.color = Color(0.3, 0.8, 0.65)
		stage.add_child(marker)
		player_list.add_child(row)
		players[id] = {"row": row, "label": label, "marker": marker,
			"session": "", "sequence": 0, "tween": null,
			"name": str(message.get("player_id", "기기 #%d" % id)).left(40)}
	var player: Dictionary = players[id]
	var status := "연결 되었습니다"
	if message.type in ["ready", "workout_state"]:
		if player.session != message.workout_session_id:
			player.session = message.workout_session_id
			player.sequence = 0
		if message.has("total_count"):
			player.sequence = max(player.sequence, int(message.total_count))
		status = "Ready" if message.type == "ready" else str(message.message).left(80)
		if message.type == "workout_state" and player.tween != null:
			player.tween.kill()
			player.marker.position.y = 28
	elif message.type == "jump":
		if player.session != message.workout_session_id:
			player.session = message.workout_session_id
			player.sequence = 0
		if message.sequence <= player.sequence:
			return
		player.sequence = int(message.sequence)
		status = "Counting Up"
		if player.tween != null:
			player.tween.kill()
		player.marker.position.y = 28
		var tween := create_tween()
		player.tween = tween
		tween.tween_property(player.marker, "position:y", 0.0, 0.12)
		tween.tween_property(player.marker, "position:y", 28.0, 0.18)
	player.label.text = "%s · %s · %d회" % [player.name, status, player.sequence]
	latest_label.text = player.label.text
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
		if players[id].tween != null:
			players[id].tween.kill()
		players[id].row.queue_free()
		players.erase(id)
		latest_label.text = "%s · 연결 해제" % player_name
		if players.is_empty():
			latest_label.text += " · 새 연결을 기다리고 있습니다."
		_append_log("#%d · %s · 연결 해제" % [id, player_name])
