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
    version = 5,
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

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN songId TEXT NOT NULL DEFAULT ''")
                try {
                    val cursor = db.query("SELECT uri, title, artist, album FROM tracks")
                    val rows = mutableListOf<Array<String>>()
                    while (cursor.moveToNext()) {
                        rows.add(
                            arrayOf(
                                cursor.getString(0) ?: "",
                                cursor.getString(1) ?: "",
                                cursor.getString(2) ?: "",
                                cursor.getString(3) ?: ""
                            )
                        )
                    }
                    cursor.close()

                    val usedIds = HashSet<String>()
                    val collisionCounts = mutableMapOf<String, Int>()

                    for (row in rows) {
                        val uri = row[0]
                        val title = row[1]
                        val artist = row[2]
                        val album = row[3]

                        val baseId = com.example.lume.util.SongIdGenerator.generate(artist, title)
                        val finalId = if (usedIds.add(baseId)) {
                            collisionCounts[baseId] = 1
                            baseId
                        } else {
                            var saltedId = ""
                            if (album.isNotBlank() && album != "Unknown Album") {
                                val candidate = com.example.lume.util.SongIdGenerator.generateWithSalt(artist, title, album)
                                if (usedIds.add(candidate)) {
                                    saltedId = candidate
                                }
                            }
                            if (saltedId.isBlank()) {
                                var counter = (collisionCounts[baseId] ?: 1) + 1
                                var candidate = com.example.lume.util.SongIdGenerator.generateWithSalt(artist, title, "gema_$counter")
                                while (!usedIds.add(candidate)) {
                                    counter++
                                    candidate = com.example.lume.util.SongIdGenerator.generateWithSalt(artist, title, "gema_$counter")
                                }
                                collisionCounts[baseId] = counter
                                saltedId = candidate
                            }
                            saltedId
                        }
                        db.execSQL("UPDATE tracks SET songId = ? WHERE uri = ?", arrayOf(finalId, uri))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
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
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
        }
    }
}
