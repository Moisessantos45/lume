package com.example.lume.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        FolderEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        LyricsEntity::class,
        EmbeddingEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun embeddingDao(): EmbeddingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_embeddings (
                        songId TEXT PRIMARY KEY NOT NULL,
                        embedding BLOB NOT NULL
                    )
                    """.trimIndent()
                )
                try {
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS vec_canciones USING vec0(
                            cancion_id TEXT PRIMARY KEY,
                            embedding float[384] distance_metric=cosine
                        )
                        """.trimIndent()
                    )
                } catch (_: Exception) {
                    // Si sqlite-vec no está cargado en el runtime del sistema, se utiliza almacenamiento BLOB nativo
                }
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lume.db"
                )
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
        }
    }
}
