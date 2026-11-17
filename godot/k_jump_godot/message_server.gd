extends Node
## Standard WebSocket transport; the UI receives only validated messages.
signal status_changed(text: String)
signal message_received(peer_id: int, message: Dictionary)
signal clients_changed(count: int)
signal client_disconnected(peer_id: int)

@export var port: int = 8080
var tcp := TCPServer.new()
var peers: Dictionary = {}
var deadlines: Dictionary = {}
var next_id: int = 0
const MAX_CLIENTS := 16
const MAX_PACKETS_PER_FRAME := 32

func _ready() -> void:
	for argument in OS.get_cmdline_user_args():
		if argument.begins_with("--port="):
			port = argument.trim_prefix("--port=").to_int()
	if port < 1 or port > 65535:
		status_changed.emit("서버 포트는 1~65535여야 합니다.")
		set_process(false)
		return
	var error := tcp.listen(port, "0.0.0.0")
	if error != OK:
		status_changed.emit("서버 시작 실패: %s · 포트 %d 확인" % [error_string(error), port])
		set_process(false)
		return
	status_changed.emit("서버 실행 중 · 포트 %d" % port)

func _process(_delta: float) -> void:
	while tcp.is_connection_available():
		var stream := tcp.take_connection()
		if peers.size() >= MAX_CLIENTS:
			stream.disconnect_from_host()
			continue
		var peer := WebSocketPeer.new()
		peer.inbound_buffer_size = 16384
		peer.outbound_buffer_size = 16384
		if peer.accept_stream(stream) == OK:
			next_id += 1
			peers[next_id] = peer
			deadlines[next_id] = Time.get_ticks_msec() + 5000
	var opened := 0
	for id in peers.keys():
		var peer: WebSocketPeer = peers[id]
		peer.poll()
		var state := peer.get_ready_state()
		if state == WebSocketPeer.STATE_OPEN:
			opened += 1
			for _packet in range(MAX_PACKETS_PER_FRAME):
				if peer.get_available_packet_count() == 0:
					break
				var bytes := peer.get_packet()
				if not peer.was_string_packet() or bytes.size() > 8192:
					peer.close(1008, "Expected small JSON text")
					break
				_receive(id, peer, bytes.get_string_from_utf8())
		elif state == WebSocketPeer.STATE_CLOSED or (
			state == WebSocketPeer.STATE_CONNECTING and Time.get_ticks_msec() > deadlines[id]
		):
			client_disconnected.emit(id)
			peers.erase(id)
			deadlines.erase(id)
	clients_changed.emit(opened)

func _receive(id: int, peer: WebSocketPeer, text: String) -> void:
	var parsed: Variant = JSON.parse_string(text)
	if not parsed is Dictionary:
		peer.send_text(JSON.stringify({"type": "error", "message": "JSON object required"}))
		return
	var message: Dictionary = parsed
	if message.get("protocol_version") != 1 or message.get("type") not in ["hello", "ready", "jump", "workout_state"]:
		peer.send_text(JSON.stringify({"type": "error", "message": "Unsupported message"}))
		return
	if not message.get("message") is String:
		return
	if message.type in ["ready", "workout_state"] and not message.get("workout_session_id") is String:
		peer.send_text(JSON.stringify({"type": "error", "message": "Session required"}))
		return
	if message.type == "workout_state" and message.get("state") not in ["preparing", "paused", "finished", "tracking_lost"]:
		peer.send_text(JSON.stringify({"type": "error", "message": "Unsupported workout state"}))
		return
	if message.has("total_count"):
		var count_value: Variant = message.total_count
		if not (count_value is float or count_value is int):
			return
		if not is_finite(float(count_value)) or count_value < 0 or count_value != floor(count_value):
			return
	if message.has("player_id") and not message.player_id is String:
		return
	if message.type == "jump":
		if not message.get("event_id") is String or not message.get("workout_session_id") is String:
			return
		if not (message.get("sequence") is float or message.get("sequence") is int):
			return
		if message.sequence < 1 or message.sequence != floor(message.sequence):
			return
	message_received.emit(id, message)
	peer.send_text(JSON.stringify({"protocol_version": 1, "type": "ack",
		"received_type": message.type, "message": "Godot 수신 완료"}))

func _exit_tree() -> void:
	for peer: WebSocketPeer in peers.values():
		peer.close(1001, "Server stopped")
	tcp.stop()
