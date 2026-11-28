package com.example.lume.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY artist COLLATE NOCASE, title COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY artist COLLATE NOCASE, title COLLATE NOCASE")
    suspend fun getAll(): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY rowid DESC")
    suspend fun getAllByInsertion(): List<TrackEntity>

    @Query("SELECT uri FROM tracks")
    suspend fun getAllUris(): List<String>

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<TrackEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackEntity)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()

    @Query("DELETE FROM tracks WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("UPDATE tracks SET isFavorite = :favorite WHERE uri = :uri")
    suspend fun updateFavorite(uri: String, favorite: Boolean)

    @Query("SELECT * FROM folders")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun getAllFolders(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE uri = :uri")
    suspend fun deleteFolder(uri: String)

    @Query("DELETE FROM folders")
    suspend fun clearFolders()
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun updatePlaylistName(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackUri = :trackUri")
    suspend fun deletePlaylistTrack(playlistId: Long, trackUri: String)

    @Query("SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON t.uri = pt.trackUri WHERE pt.playlistId = :playlistId ORDER BY t.artist COLLATE NOCASE, t.title COLLATE NOCASE")
    fun observePlaylistTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON t.uri = pt.trackUri WHERE pt.playlistId = :playlistId ORDER BY t.artist COLLATE NOCASE, t.title COLLATE NOCASE")
    suspend fun getPlaylistTracks(playlistId: Long): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getTrackCountForPlaylist(playlistId: Long): Int
}
