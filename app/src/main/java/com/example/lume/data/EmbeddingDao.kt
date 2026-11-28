package com.example.lume.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmbeddingDao {
    @Query("SELECT * FROM track_embeddings")
    suspend fun getAll(): List<EmbeddingEntity>

    @Query("SELECT * FROM track_embeddings WHERE songId = :songId LIMIT 1")
    suspend fun getBySongId(songId: String): EmbeddingEntity?

    @Query("SELECT * FROM track_embeddings WHERE songId IN (:songIds)")
    suspend fun getBySongIds(songIds: List<String>): List<EmbeddingEntity>

    @Query("SELECT songId FROM track_embeddings")
    suspend fun getAllSongIds(): List<String>

    @Query("SELECT COUNT(*) FROM track_embeddings")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(items: List<EmbeddingEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(items: List<EmbeddingEntity>): List<Long>

    @Query("DELETE FROM track_embeddings")
    suspend fun clear()

    @Query("DELETE FROM track_embeddings WHERE songId = :songId")
    suspend fun deleteBySongId(songId: String)
}
