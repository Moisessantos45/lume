package com.example.lume.data

import android.content.Context
import com.example.lume.Track
import com.example.lume.Playlist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


data class LyricsEntry(
    val songId: String,
    val plainLyrics: String,
    val syncedLyrics: String,
    val instrumental: Boolean,
        val isUpdate: Boolean = false
)

class MusicRepository(private val dao: TrackDao, private val lyricsDao: LyricsDao) {


    fun observeTracks(): Flow<List<Track>> =
        dao.observeAll().map { list -> list.map { it.toTrack() } }

    fun observeFolders(): Flow<List<String>> =
        dao.observeFolders().map { list -> list.map { it.uri } }

    suspend fun loadTracks(): List<Track> = dao.getAll().map { it.toTrack() }

    suspend fun loadFolders(): List<String> = dao.getAllFolders().map { it.uri }

    suspend fun getTrackByUri(uri: String): Track? = dao.getByUri(uri)?.toTrack()

    suspend fun addTracks(tracks: List<Track>) {
        dao.insertAll(tracks.map { it.toEntity() })
    }

    suspend fun clearTracks() {
        dao.deleteAll()
    }

    suspend fun getAllTrackUris(): List<String> = dao.getAllUris()

    suspend fun removeTrack(uri: String) {
        dao.deleteByUri(uri)
    }

    suspend fun updateFavorite(uri: String, favorite: Boolean) {
        dao.updateFavorite(uri, favorite)
    }

    suspend fun addFolder(uri: String) {
        dao.addFolder(FolderEntity(uri))
    }

    suspend fun removeFolder(uri: String) {
        dao.deleteFolder(uri)
    }

    suspend fun clearFolders() {
        dao.clearFolders()
    }

    private fun TrackEntity.toTrack(): Track = Track(
        uri = uri,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        durationMs = durationMs,
        year = year,
        composer = null, // TrackEntity no persiste composer; se obtiene al escanear metadatos
        isFavorite = isFavorite
    )

    private fun Track.toEntity(): TrackEntity = TrackEntity(
        uri = uri,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        durationMs = durationMs,
        year = year,
        isFavorite = isFavorite
    )
    fun observePlaylists(): Flow<List<Playlist>> =
        dao.observePlaylists().map { list -> list.map { it.toPlaylist() } }

    suspend fun getPlaylists(): List<Playlist> = dao.getAllPlaylists().map { it.toPlaylist() }

    suspend fun createPlaylist(name: String): Long =
        dao.insertPlaylist(PlaylistEntity(name = name))

    suspend fun updatePlaylistName(id: Long, name: String) =
        dao.updatePlaylistName(id, name)

    suspend fun deletePlaylist(id: Long) =
        dao.deletePlaylist(id)
    suspend fun addTracksToPlaylist(playlistId: Long, trackUris: List<String>) {
        dao.insertPlaylistTracks(trackUris.map { PlaylistTrackEntity(playlistId, it) })
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackUri: String) {
        dao.deletePlaylistTrack(playlistId, trackUri)
    }

    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        dao.observePlaylistTracks(playlistId).map { list -> list.map { it.toTrack() } }

    suspend fun getPlaylistTracks(playlistId: Long): List<Track> =
        dao.getPlaylistTracks(playlistId).map { it.toTrack() }

    suspend fun getTrackCountForPlaylist(playlistId: Long): Int =
        dao.getTrackCountForPlaylist(playlistId)

    private fun PlaylistEntity.toPlaylist(): Playlist = Playlist(
        id = id,
        name = name
    )


data class LyricsImportResult(
    val insertedOrUpdated: Int,
    val ignored: Int,
    val total: Int
)

    
    suspend fun getLyrics(songId: String): LyricsEntity? = lyricsDao.getById(songId)

    /**
 * Importa letras en JSON: replace = true sobrescribe toda la tabla;
 * replace = false realiza un merge según isUpdate de cada entrada.
 */
    suspend fun importLyrics(entries: List<LyricsEntry>, replace: Boolean): LyricsImportResult {
        val distinctEntries = entries.distinctBy { it.songId }
        val entities = distinctEntries.map {
            LyricsEntity(
                songId       = it.songId,
                plainLyrics  = it.plainLyrics,
                syncedLyrics = it.syncedLyrics,
                instrumental = it.instrumental
            )
        }

        if (distinctEntries.isEmpty()) {
            return LyricsImportResult(0, 0, 0)
        }

        if (replace) {
            lyricsDao.deleteAll()
            lyricsDao.upsertAll(entities)
            return LyricsImportResult(
                insertedOrUpdated = entities.size,
                ignored = 0,
                total = entities.size
            )
        } else {
            val toUpdate = entities.filterIndexed { i, _ -> distinctEntries[i].isUpdate }
            val toInsert = entities.filterIndexed { i, _ -> !distinctEntries[i].isUpdate }

            var insertedOrUpdated = 0
            if (toUpdate.isNotEmpty()) {
                lyricsDao.upsertAll(toUpdate)
                insertedOrUpdated += toUpdate.size
            }
            if (toInsert.isNotEmpty()) {
                val results = lyricsDao.insertIgnoreAll(toInsert)
                val newInserts = results.count { it != -1L }
                insertedOrUpdated += newInserts
            }

            val ignored = entities.size - insertedOrUpdated
            return LyricsImportResult(
                insertedOrUpdated = insertedOrUpdated,
                ignored = ignored,
                total = entities.size
            )
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: MusicRepository? = null

        fun get(context: Context): MusicRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MusicRepository(
                    AppDatabase.get(context).trackDao(),
                    AppDatabase.get(context).lyricsDao()
                ).also { INSTANCE = it }
            }
        }
    }
}
