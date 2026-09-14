package io.github.pabloestebanserrano.workout

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [WorkoutRecord::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class WorkoutDatabase : RoomDatabase() {

    abstract fun workoutRecordDao(): WorkoutRecordDao

    companion object {
        // @Volatile ensures a write to this field from one thread is
        // immediately visible to every other thread — without it, two
        // threads calling getInstance() at nearly the same moment could
        // each see a stale "null" and both proceed to build a database,
        // which is exactly the kind of bug that only shows up rarely and
        // is miserable to track down later.
        @Volatile private var INSTANCE: WorkoutDatabase? = null

        fun getInstance(context: Context): WorkoutDatabase =
            INSTANCE ?: synchronized(this) {
                // Re-check inside the lock: if two threads both got past the
                // first null-check before either acquired the lock, only the
                // first one through should actually build the database —
                // the second must see the now-non-null INSTANCE instead of
                // building a second, competing one.
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WorkoutDatabase::class.java,
                    "workout_history.db",
                ).build().also { INSTANCE = it }
            }
    }
}
