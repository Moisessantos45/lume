package com.example.lume.util

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
 * El mismo par (artista, título) siempre produce el mismo ID en cualquier dispositivo.
 */
object SongIdGenerator {

        fun generate(artist: String, title: String): String {
        val normalized = normalize(artist) + "|" + normalize(title)
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
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
