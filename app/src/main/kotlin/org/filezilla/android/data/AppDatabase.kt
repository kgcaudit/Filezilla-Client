package org.filezilla.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TransferEntity::class, SiteEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transfers(): TransferDao

    abstract fun sites(): SiteDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "filezilla.db",
            )
                // No fallbackToDestructiveMigration: dropping this database
                // throws away the offsets that make a resume safe, which
                // would turn a schema change into silently re-downloading
                // everything in the queue. A future version migrates.
                .build()
    }
}
