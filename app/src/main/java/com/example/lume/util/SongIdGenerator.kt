package com.example.lume.util

import com.example.lume.Track
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Genera un ID único y determinístico para una canción usando SHA-256.
 *
 * Esquema:
 *   1. Normalizar artista y título (minúsculas, sin acentos, trim).
 *   2. Concatenar como "artista|titulo".
 *   3. Aplicar SHA-256.
 *   4. Devolver los primeros 16 caracteres hex (64 bits → colisión astronómicamente improbable).
 *
 * Si existen canciones idénticas en metadatos básicos (remix, en vivo, diferente álbum),
 * se emplea una sal ("gema") para garantizar que cada archivo físico tenga un hash único.
 */
object SongIdGenerator {

    fun generate(artist: String, title: String): String {
        val normalized = normalize(artist) + "|" + normalize(title)
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Genera un hash determinístico salado con una "gema" (álbum o contador de colisión)
     * para canciones que comparten artista y título idénticos pero representan versiones
     * diferentes (remix, en vivo, reedición, etc.).
     */
    fun generateWithSalt(artist: String, title: String, salt: String): String {
        val cleanSalt = normalize(salt).ifBlank { salt.trim().lowercase() }
        val normalized = normalize(artist) + "|" + normalize(title) + "#" + cleanSalt
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Asigna IDs únicos a una lista de canciones resolviendo colisiones entre pistas
     * duplicadas mediante sal (álbum / gema). Si la pista ya cuenta con un songId único
     * y válido, se preserva.
     */
    fun assignUniqueSongIds(
        tracks: List<Track>,
        existingUsedIds: Set<String> = emptySet()
    ): List<Track> {
        val usedIds = HashSet<String>(existingUsedIds)
        val collisionCounts = mutableMapOf<String, Int>()

        return tracks.map { track ->
            if (track.songId.isNotBlank() && !usedIds.contains(track.songId)) {
                usedIds.add(track.songId)
                track
            } else {
                val baseId = generate(track.artist, track.title)
                val finalId = if (usedIds.add(baseId)) {
                    collisionCounts[baseId] = 1
                    baseId
                } else {
                    // Hay colisión con otra canción idéntica. Probar primero con el álbum como sal (gema)
                    var saltedId = ""
                    if (track.album.isNotBlank() && track.album != "Unknown Album") {
                        val candidate = generateWithSalt(track.artist, track.title, track.album)
                        if (usedIds.add(candidate)) {
                            saltedId = candidate
                        }
                    }
                    // Si el álbum también colisiona o no existe, usar gema incremental
                    if (saltedId.isBlank()) {
                        var counter = (collisionCounts[baseId] ?: 1) + 1
                        var candidate = generateWithSalt(track.artist, track.title, "gema_$counter")
                        while (!usedIds.add(candidate)) {
                            counter++
                            candidate = generateWithSalt(track.artist, track.title, "gema_$counter")
                        }
                        collisionCounts[baseId] = counter
                        saltedId = candidate
                    }
                    saltedId
                }
                track.copy(songId = finalId)
            }
        }
    }

    /**
     * Normalización:
     *  - Minúsculas
     *  - Descomposición NFD para separar letras de sus diacríticos
     *  - Eliminar caracteres de categoría "Mark, Nonspacing" (acentos, tildes, etc.)
     *  - Reemplazar caracteres especiales y puntuación (comillas, apóstrofes, backticks, etc.) por espacios
     *  - Colapsar espacios múltiples y trim
     */
    fun normalize(input: String): String {
        if (input.isEmpty()) return ""
        val lower = input.trim().lowercase()
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val withoutDiacritics = decomposed.replace(Regex("\\p{Mn}"), "")
        val cleaned = withoutDiacritics.replace(Regex("[^\\p{L}\\p{N}]"), " ")
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }
}
