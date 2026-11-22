package com.example.lume.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LyricsDao {

        @Query("SELECT * FROM lyrics WHERE songId = :songId LIMIT 1")
    suspend fun getById(songId: String): LyricsEntity?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsEntity)

        @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LyricsEntity>): List<Long>

    /**
     * Inserta letras solo si NO existen ya (isUpdate = false).
     * Si la canción ya tiene letra en la DB, la deja intacta.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(entities: List<LyricsEntity>): List<Long>

        @Query("DELETE FROM lyrics")
    suspend fun deleteAll()

        @Query("SELECT COUNT(*) FROM lyrics")
    suspend fun count(): Int
}
