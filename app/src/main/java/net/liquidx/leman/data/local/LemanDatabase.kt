package net.liquidx.leman.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ThreadEntity::class, TurnEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class LemanDatabase : RoomDatabase() {
    abstract fun threadDao(): ThreadDao
    abstract fun turnDao(): TurnDao

    companion object {
        fun build(context: Context): LemanDatabase =
            Room.databaseBuilder(context, LemanDatabase::class.java, "leman.db")
                // Acceptable only until first release (spec 03); then real
                // migrations + schema-export CI check.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
