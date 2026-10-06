package kr.co.jumprope.camera.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kr.co.jumprope.camera.debug.*
import kr.co.jumprope.camera.session.WorkoutSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LocalStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun result() = WorkoutEntity.from(WorkoutSession(UUID.randomUUID().toString(),
        "2026-10-06T00:00:00.000Z", "2026-10-06T00:01:00.000Z", 60_000, 50_000, 100,
        120.0, 130.0, engineId = "SYNTHETIC", modelId = "fixture", parameterVersion = "test", endReason = "TEST"))

    @Test fun roomPersistsAcrossReopenRejectsDuplicateAndDeletes() = runBlocking {
        val name = "verification-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, WorkoutDatabase::class.java, name).build()
        try {
            val sample = result()
            assertTrue(db.workouts().insert(sample) != -1L)
            assertEquals(-1L, db.workouts().insert(sample))
            db.close()
            db = Room.databaseBuilder(context, WorkoutDatabase::class.java, name).build()
            assertEquals(listOf(sample), db.workouts().observe().first())
            db.workouts().delete(sample.id)
            assertTrue(db.workouts().observe().first().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private suspend fun withStore(block: suspend (PoseLogStore, File) -> Unit) {
        val root = File(context.cacheDir, "log-test-${UUID.randomUUID()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir(): File = root }
        val store = PoseLogStore(wrapped)
        try { block(store, root) } finally { store.finish(); root.deleteRecursively() }
    }
    private fun demoLines() = context.assets.open("fixtures/basic-jumps.jsonl").bufferedReader().use { it.readLines() }
    private fun header() = PoseLogCodec.gson.fromJson(JsonParser.parseString(demoLines().first()).asJsonObject["data"], LogHeader::class.java)

    @Test fun asynchronousLogWritesRawFramesAndCanBeReplayed() = runBlocking {
        withStore { store, _ ->
            val error = AtomicReference<String?>(null)
            store.start(header()) { error.set(it) }
            demoLines().drop(1).forEach {
                val record = JsonParser.parseString(it).asJsonObject
                assertTrue(store.append(record["type"].asString, record["data"]))
            }
            store.finish()
            assertNull(error.get())
            assertEquals(1, store.files().size)
            store.file(store.files().single().name).bufferedReader().use {
                assertEquals(3, PoseLogCodec.replay(it.lineSequence()).jumpCount)
            }
            store.delete(store.files().single().name)
            assertTrue(store.files().isEmpty())
        }
    }
    @Test fun logByteLimitStopsWriterAndReportsError() = runBlocking {
        withStore { store, _ ->
            val error = AtomicReference<String?>(null)
            store.start(header()) { error.set(it) }
            assertTrue(store.append("transition", "x".repeat(PoseLogStore.MAX_BYTES.toInt())))
            store.finish()
            assertNotNull(error.get())
            assertTrue(store.files().single().bytes <= PoseLogStore.MAX_BYTES)
        }
    }
    @Test fun retentionRemovesExpiredAndExcessLogsAtNextStart() = runBlocking {
        withStore { store, root ->
            val dir = File(root, "pose_logs").apply { mkdirs() }
            repeat(25) { index ->
                File(dir, "old-$index.jsonl").apply {
                    writeText("test")
                    setLastModified(System.currentTimeMillis() - (if (index == 0) 8 * 86_400_000L else index.toLong() * 1000))
                }
            }
            store.start(header()) { fail(it) }; store.finish()
            assertEquals(20, store.files().size)
            assertFalse(File(dir, "old-0.jsonl").exists())
        }
    }
}
