package com.example.lume.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Almacena las letras de una canción identificada por su hash SHA-256 (songId).
 *
 * Campos:
 *  - songId       : hash generado por SongIdGenerator (16 hex chars). Clave primaria.
 *  - plainLyrics  : letra en texto plano, sin timestamps. Para mostrar estática.
 *  - syncedLyrics : letra en formato LRC "[mm:ss.cc] línea". Para highlight sincronizado.
 *  - instrumental : si true, la canción es instrumental (no tiene letra vocal).
 */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: String,
    val plainLyrics: String = "",
    val syncedLyrics: String = "",
    val instrumental: Boolean = false
)
