package com.example.lume.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val durationMs: Long,
    val year: Int?,
    val isFavorite: Boolean,
    val songId: String = ""
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val uri: String
)
