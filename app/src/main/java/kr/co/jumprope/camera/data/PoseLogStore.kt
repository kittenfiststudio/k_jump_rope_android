package kr.co.jumprope.camera.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kr.co.jumprope.camera.debug.LogHeader
import kr.co.jumprope.camera.debug.PoseLogCodec
import java.io.File

data class LogFile(val name: String, val bytes: Long, val modifiedAt: Long)

/** Bounded asynchronous writer. A truncated/failed log remains identifiable as incomplete. */
class PoseLogStore(context: Context) {
    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        const val KEEP_FILES = 20
        const val KEEP_DAYS = 7
    }
    private val directory = File(context.filesDir, "pose_logs")
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var channel: Channel<Pair<String, Any>>? = null
    private var job: Job? = null
    var activeName: String? = null; private set

    fun start(header: LogHeader, onError: (String) -> Unit) {
        check(channel == null)
        val name = "${header.sessionId}.jsonl"
        val queue = Channel<Pair<String, Any>>(256)
        channel = queue; activeName = name
        job = writerScope.launch {
            try {
                directory.mkdirs()
                prune(name)
                var bytes = 0L
                File(directory, name).bufferedWriter().use { writer ->
                    fun write(type: String, value: Any) {
                        val line = PoseLogCodec.line(type, value)
                        bytes += line.toByteArray(Charsets.UTF_8).size + 1
                        check(bytes <= MAX_BYTES) { "로그 10 MiB 상한에 도달했습니다." }
                        writer.write(line); writer.newLine()
                    }
                    write("header", header)
                    for ((type, value) in queue) write(type, value)
                }
            } catch (error: Exception) {
                queue.close()
                onError("로그 저장 중단: ${error.message}")
            }
        }
    }
    fun append(type: String, value: Any): Boolean = channel?.trySend(type to value)?.isSuccess ?: false
    suspend fun finish() {
        val old = channel; val oldJob = job
        channel = null; job = null; activeName = null
        old?.close(); oldJob?.join()
    }
    suspend fun files(): List<LogFile> = withContext(Dispatchers.IO) {
        directory.listFiles()?.filter { it.isFile && it.extension == "jsonl" && it.name != activeName }
            ?.sortedByDescending { it.lastModified() }?.map { LogFile(it.name, it.length(), it.lastModified()) } ?: emptyList()
    }
    fun file(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9-]+\\.jsonl"))) { "잘못된 파일 이름" }
        return File(directory, name)
    }
    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        require(name != activeName)
        check(file(name).delete()) { "로그 삭제 실패" }
    }
    private fun prune(exclude: String) {
        val files = directory.listFiles()?.filter { it.name != exclude && it.extension == "jsonl" }
            ?.sortedByDescending { it.lastModified() } ?: return
        val expiry = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        files.forEachIndexed { index, file -> if (index >= KEEP_FILES - 1 || file.lastModified() < expiry) file.delete() }
    }
}
