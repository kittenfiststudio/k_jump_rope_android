package kr.co.jumprope.camera.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kr.co.jumprope.camera.session.WorkoutSession

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey val id: String, val startedAtUtc: String, val endedAtUtc: String,
    val activeDurationMs: Long, val validTrackingDurationMs: Long, val jumpCount: Int,
    val averageJumpsPerMinute: Double?, val peakJumpsPerMinute: Double,
    val detectionSource: String, val engineId: String, val modelId: String,
    val detectorVersion: String, val parameterVersion: String, val endReason: String
) {
    companion object {
        fun from(s: WorkoutSession) = WorkoutEntity(s.id, s.startedAtUtc, s.endedAtUtc,
            s.activeDurationMs, s.validTrackingDurationMs, s.jumpCount, s.averageJumpsPerMinute,
            s.peakJumpsPerMinute, s.detectionSource, s.engineId, s.modelId, s.detectorVersion,
            s.parameterVersion, s.endReason)
    }
}
@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY endedAtUtc DESC")
    fun observe(): Flow<List<WorkoutEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(workout: WorkoutEntity): Long
    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun delete(id: String)
}
@Database(entities = [WorkoutEntity::class], version = 1, exportSchema = true)
abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao
    companion object {
        @Volatile private var instance: WorkoutDatabase? = null
        fun get(context: Context): WorkoutDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WorkoutDatabase::class.java,
                "workouts.db").build().also { instance = it }
        }
    }
}
