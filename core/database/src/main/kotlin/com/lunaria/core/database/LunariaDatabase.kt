package com.lunaria.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lunaria.core.database.dao.PlaylistDao
import com.lunaria.core.database.dao.TrackDao
import com.lunaria.core.database.entity.PlaylistEntity
import com.lunaria.core.database.entity.PlaylistTrackCrossRef
import com.lunaria.core.database.entity.TrackEntity

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackCrossRef::class],
    version = 3,
    exportSchema = false,
)
abstract class LunariaDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val NAME = "lunaria.db"

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN replayGainTrackDb REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN replayGainAlbumDb REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN replayGainTrackPeak REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN replayGainAlbumPeak REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN clipStartMs INTEGER")
                db.execSQL("ALTER TABLE tracks ADD COLUMN clipEndMs INTEGER")
                db.execSQL("ALTER TABLE tracks ADD COLUMN cueSourcePath TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN lastPlayedAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN totalPlayDurationMs INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
