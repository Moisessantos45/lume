package com.example.lume.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "track_embeddings")
data class EmbeddingEntity(
    @PrimaryKey val songId: String,
    val embedding: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddingEntity
        if (songId != other.songId) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = songId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
