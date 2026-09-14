package io.github.pabloestebanserrano.workout

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * One row = one finished workout. This is what actually lives in the SQLite
 * database Room manages for us — a plain data class with a table name and a
 * primary key, nothing BLE- or Compose-specific in here.
 */
@Entity(tableName = "workout_records")
data class WorkoutRecord(
    // autoGenerate + defaulting to 0 is the standard Room pattern: you never
    // set this yourself when inserting a *new* record, Room assigns the
    // next free id and returns/stores the real value.
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutType: WorkoutType,
    val startedAtEpochMs: Long,
    val durationSeconds: Long,
    val calories: Double,
    // Nullable because a workout ended before any HR reading ever arrived
    // (e.g. the strap never connected) legitimately has no HR data — that's
    // a different, distinguishable case from "average was zero."
    val averageHeartRate: Int?,
    val peakHeartRate: Int?,
)

/**
 * Room can only store primitive-ish column types (Int, Long, String, etc.)
 * directly — it doesn't know what to do with our WorkoutType enum on its
 * own. A `@TypeConverter` pair is how you teach it: one function to turn
 * your type into something storable, one to turn it back on the way out.
 * Registered on the database below via `@TypeConverters(Converters::class)`.
 */
class Converters {
    @TypeConverter
    fun fromWorkoutType(type: WorkoutType): String = type.name

    @TypeConverter
    fun toWorkoutType(name: String): WorkoutType = WorkoutType.valueOf(name)
}
