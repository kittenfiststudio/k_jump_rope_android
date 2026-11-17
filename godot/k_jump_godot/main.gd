extends Control

var server: Node
var status_label: Label
var clients_label: Label
var latest_label: Label
var log_view: RichTextLabel
var lines: Array[String] = []

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
	title.text = "K-Rope · Android 연결 확인"
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
	log_view = RichTextLabel.new()
	log_view.size_flags_vertical = Control.SIZE_EXPAND_FILL
	log_view.scroll_following = true
	column.add_child(log_view)
	server = load("res://message_server.gd").new()
	server.status_changed.connect(func(text: String): status_label.text = text)
	server.clients_changed.connect(func(count: int): clients_label.text = "연결된 기기: %d" % count)
	server.message_received.connect(_on_message)
	add_child(server)

func _on_message(id: int, message: Dictionary) -> void:
	latest_label.text = "기기 #%d · %s" % [id, message.message.left(200)]
	var line := "%s · #%d · %s" % [Time.get_time_string_from_system(), id, JSON.stringify(message)]
	print(line)
	lines.append(line)
	if lines.size() > 100:
		lines.pop_front()
	log_view.text = "\n".join(lines)
