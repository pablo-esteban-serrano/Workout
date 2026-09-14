package io.github.pabloestebanserrano.workout

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A DAO ("Data Access Object") is just an interface listing the queries you
 * need — Room generates the actual SQLite-talking implementation for you at
 * build time (that's what the `ksp`/annotation-processing step does).
 */
@Dao
interface WorkoutRecordDao {

    @Insert
    suspend fun insert(record: WorkoutRecord)

    // Returning Flow (rather than a plain List) means Room re-runs this
    // query and pushes a fresh list automatically every time the table
    // changes — insert a new workout anywhere in the app, and anyone
    // collecting this Flow (the History screen) sees it appear with no
    // manual refresh call, same pattern as UserProfileRepository's Flow.
    @Query("SELECT * FROM workout_records ORDER BY startedAtEpochMs DESC")
    fun getAll(): Flow<List<WorkoutRecord>>

    @Delete
    suspend fun delete(record: WorkoutRecord)
}
