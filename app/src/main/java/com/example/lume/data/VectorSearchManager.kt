package com.example.lume.data

import android.content.Context
import android.util.Base64
import com.example.lume.Track
import com.example.lume.util.SongIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class SimilarTrack(
    val track: Track,
    val score: Float,
    val percentage: Int
)

data class VectorStats(
    val totalVectors: Int,
    val dimension: Int = 384,
    val modelName: String = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
)

enum class VectorImportMode {
    REPLACE_EXISTING, // Actualizar existentes e insertar nuevos
    INSERT_NEW_ONLY   // Insertar sólo los que no existen
}

data class VectorImportResult(
    val totalInJson: Int,
    val processedCount: Int,
    val skippedCount: Int,
    val durationMs: Long
)

object VectorSearchManager {

    const val EXPECTED_MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
    const val EXPECTED_DIMENSION = 384

    // Caché en memoria de embeddings normalizados decodificados (FloatArray) para búsquedas instantáneas (<5ms)
    private val vectorCache = ConcurrentHashMap<String, FloatArray>()
    @Volatile
    private var isCacheLoaded = false

    fun decodificarVector(vectorBase64: String, dimension: Int = EXPECTED_DIMENSION): ByteArray {
        val bytes = Base64.decode(vectorBase64, Base64.DEFAULT)
        require(bytes.size == dimension * 4) {
            "Tamaño de vector inválido: ${bytes.size} bytes; se esperaban ${dimension * 4}"
        }
        return bytes
    }

    fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val floats = FloatArray(buffer.remaining())
        buffer.get(floats)
        return floats
    }

    /**
     * Calcula la similitud coseno entre dos vectores ya normalizados (norma 1).
     * El producto punto equivale exactamente a la similitud coseno.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]
        }
        return dot
    }

    /**
     * Importa los vectores directamente desde el Uri mediante streaming (JsonReader)
     * en segundo plano (Dispatchers.IO).
     *
     * Este enfoque NUNCA carga el archivo JSON completo en memoria como String ni crea
     * un árbol JSONObject, permitiendo procesar archivos de decenas de megabytes con un
     * consumo de memoria mínimo (pocos kilobytes). Al finalizar, limpia todas las colecciones
     * temporales y solicita la recolección de basura (System.gc()).
     */
    suspend fun importVectorsFromUri(
        context: Context,
        uri: android.net.Uri,
        mode: VectorImportMode,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): VectorImportResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val db = AppDatabase.get(context)
        val dao = db.embeddingDao()

        val existingIds: Set<String> = if (mode == VectorImportMode.INSERT_NEW_ONLY) {
            dao.getAllSongIds().toHashSet()
        } else {
            emptySet()
        }

        var processedCount = 0
        var skippedCount = 0
        val batch = ArrayList<EmbeddingEntity>(200)

        var version = -1
        var dimension = EXPECTED_DIMENSION
        var dtype = ""
        var byteOrder = ""
        var normalized = false

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            android.util.JsonReader(java.io.InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "version" -> version = reader.nextInt()
                        "modelo" -> reader.nextString() // validar/consumir
                        "dimension" -> dimension = reader.nextInt()
                        "dtype" -> dtype = reader.nextString()
                        "byte_order" -> byteOrder = reader.nextString()
                        "normalizado" -> normalized = reader.nextBoolean()
                        "items" -> {
                            require(version == 1) { "Versión no soportada: $version" }
                            require(dimension == EXPECTED_DIMENSION) { "Dimensión inválida: $dimension" }
                            require(dtype == "float32") { "dtype inválido: $dtype" }
                            require(byteOrder == "little") { "byte_order inválido: $byteOrder" }
                            require(normalized) { "Los vectores deben estar normalizados" }

                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginObject()
                                var id = ""
                                var vectorB64 = ""
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "id" -> id = reader.nextString().trim()
                                        "vector_b64" -> vectorB64 = reader.nextString()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()

                                if (id.isBlank() || vectorB64.isBlank()) {
                                    skippedCount++
                                    continue
                                }

                                if (mode == VectorImportMode.INSERT_NEW_ONLY && existingIds.contains(id)) {
                                    skippedCount++
                                    continue
                                }

                                try {
                                    val bytes = decodificarVector(vectorB64, dimension)
                                    batch.add(EmbeddingEntity(songId = id, embedding = bytes))
                                } catch (_: Exception) {
                                    skippedCount++
                                }

                                if (batch.size >= 200) {
                                    insertBatch(dao, db, batch, mode)
                                    processedCount += batch.size
                                    batch.clear()
                                    withContext(Dispatchers.Main) {
                                        onProgress(processedCount, 0)
                                    }
                                }
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        } ?: throw IllegalStateException("No se pudo abrir el archivo seleccionado")

        if (batch.isNotEmpty()) {
            insertBatch(dao, db, batch, mode)
            processedCount += batch.size
            batch.clear()
            withContext(Dispatchers.Main) {
                onProgress(processedCount, processedCount)
            }
        }

        // Liberación inmediata de memoria y caché
        invalidateCache()
        System.gc()

        val duration = System.currentTimeMillis() - startTime
        VectorImportResult(
            totalInJson = processedCount + skippedCount,
            processedCount = processedCount,
            skippedCount = skippedCount,
            durationMs = duration
        )
    }

    /**
     * Importa los vectores desde el contenido JSON en segundo plano (Dispatchers.IO).
     */
    suspend fun importVectors(
        context: Context,
        jsonContent: String,
        mode: VectorImportMode,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): VectorImportResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val root = JSONObject(jsonContent)

        require(root.optInt("version", -1) == 1) {
            "Versión de JSON no soportada. Se esperaba version = 1."
        }
        val model = root.optString("modelo")
        require(model.contains("MiniLM", ignoreCase = true) || model.isNotBlank()) {
            "Modelo desconocido: $model"
        }
        val dimension = root.optInt("dimension", EXPECTED_DIMENSION)
        require(dimension == EXPECTED_DIMENSION) {
            "Dimensión inválida: $dimension. Se esperaba $EXPECTED_DIMENSION."
        }
        require(root.optString("dtype", "float32") == "float32") {
            "dtype inválido. Se esperaba float32."
        }
        require(root.optString("byte_order", "little") == "little") {
            "byte_order inválido. Se esperaba little."
        }
        require(root.optBoolean("normalizado", false)) {
            "Los vectores deben estar normalizados (normalizado = true)."
        }

        val items = root.getJSONArray("items")
        val total = items.length()
        val db = AppDatabase.get(context)
        val dao = db.embeddingDao()

        // Si es INSERT_NEW_ONLY, obtenemos los IDs existentes para omitirlos de antemano
        val existingIds: Set<String> = if (mode == VectorImportMode.INSERT_NEW_ONLY) {
            dao.getAllSongIds().toHashSet()
        } else {
            emptySet()
        }

        var processedCount = 0
        var skippedCount = 0
        val batch = ArrayList<EmbeddingEntity>(200)

        for (i in 0 until total) {
            val item = items.getJSONObject(i)
            val id = item.optString("id", "").trim()
            val b64 = item.optString("vector_b64", "")

            if (id.isBlank() || b64.isBlank()) {
                skippedCount++
                continue
            }

            if (mode == VectorImportMode.INSERT_NEW_ONLY && existingIds.contains(id)) {
                skippedCount++
                continue
            }

            try {
                val bytes = decodificarVector(b64, dimension)
                batch.add(EmbeddingEntity(songId = id, embedding = bytes))
            } catch (_: Exception) {
                skippedCount++
            }

            if (batch.size >= 200) {
                insertBatch(dao, db, batch, mode)
                processedCount += batch.size
                batch.clear()
                withContext(Dispatchers.Main) {
                    onProgress(processedCount, total)
                }
            }
        }

        if (batch.isNotEmpty()) {
            insertBatch(dao, db, batch, mode)
            processedCount += batch.size
            batch.clear()
            withContext(Dispatchers.Main) {
                onProgress(processedCount, total)
            }
        }

        // Invalidar caché en memoria para recargar con la nueva data
        invalidateCache()
        System.gc()

        val duration = System.currentTimeMillis() - startTime
        VectorImportResult(
            totalInJson = total,
            processedCount = processedCount,
            skippedCount = skippedCount,
            durationMs = duration
        )
    }

    private suspend fun insertBatch(
        dao: EmbeddingDao,
        db: AppDatabase,
        batch: List<EmbeddingEntity>,
        mode: VectorImportMode
    ) {
        if (mode == VectorImportMode.REPLACE_EXISTING) {
            dao.insertOrReplace(batch)
        } else {
            dao.insertOrIgnore(batch)
        }

        // Intentar reflejar en la tabla virtual vec_canciones si la extensión sqlite-vec estuviese activa
        try {
            val sdb = db.openHelper.writableDatabase
            for (entity in batch) {
                try {
                    sdb.execSQL(
                        "INSERT OR REPLACE INTO vec_canciones(cancion_id, embedding) VALUES (?, ?)",
                        arrayOf(entity.songId, entity.embedding)
                    )
                } catch (_: Exception) {
                    // Si vec_canciones no existe, se omite silenciosamente
                    break
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Carga todos los vectores en memoria una sola vez para que las consultas de similitud
     * sean instantáneas (< 5 milisegundos).
     */
    private suspend fun ensureCacheLoaded(context: Context) {
        if (isCacheLoaded && vectorCache.isNotEmpty()) return
        val dao = AppDatabase.get(context).embeddingDao()
        val allEntities = dao.getAll()
        vectorCache.clear()
        for (entity in allEntities) {
            try {
                vectorCache[entity.songId] = bytesToFloatArray(entity.embedding)
            } catch (_: Exception) {}
        }
        isCacheLoaded = true
    }

    fun invalidateCache() {
        vectorCache.clear()
        isCacheLoaded = false
    }

    /**
     * Genera una lista ordenada de IDs candidatos para una canción para garantizar coincidencia
     * resiliente (ej. con o sin número de pista "01 - ", con o sin texto entre paréntesis "(Remastered)",
     * o extraído del nombre de archivo/tags ID3).
     */
    fun getCandidateIdsForTrack(track: Track): List<String> {
        val ids = LinkedHashSet<String>()

        // 0. ID único persistido en BD (máxima prioridad, identifica de forma unívoca a duplicados/versiones con gema)
        if (track.songId.isNotBlank()) {
            ids.add(track.songId)
        }

        // 1. Coincidencia exacta: Artista + Título
        ids.add(SongIdGenerator.generate(track.artist, track.title))

        // 2. Sal por álbum (si es un álbum específico conocido)
        if (track.album.isNotBlank() && track.album != "Unknown Album") {
            ids.add(SongIdGenerator.generateWithSalt(track.artist, track.title, track.album))
        }

        // 3. Título sin texto entre paréntesis o corchetes (ej. "Believer (Official Audio)" -> "Believer")
        val cleanNoParens = track.title.replace(Regex("""\s*[\(\[\{].*?[\)\]\}]"""), "").trim()
        if (cleanNoParens.isNotBlank() && cleanNoParens != track.title) {
            ids.add(SongIdGenerator.generate(track.artist, cleanNoParens))
        }

        // 3. Título sin prefijo numérico de pista (ej. "01 - Believer", "01. Believer", "1 Believer")
        val cleanNoTrackNum = track.title.replace(Regex("^[0-9]+[.\\s-]+"), "").trim()
        if (cleanNoTrackNum.isNotBlank() && cleanNoTrackNum != track.title) {
            ids.add(SongIdGenerator.generate(track.artist, cleanNoTrackNum))
        }

        // 4. Combinado: sin número de pista Y sin paréntesis
        val cleanBoth = cleanNoTrackNum.replace(Regex("""\s*[\(\[\{].*?[\)\]\}]"""), "").trim()
        if (cleanBoth.isNotBlank() && cleanBoth != track.title) {
            ids.add(SongIdGenerator.generate(track.artist, cleanBoth))
        }

        // 5. Artista sin colaboraciones "feat." / "ft." (ej. "Eminem feat. Rihanna" -> "Eminem")
        val cleanArtistNoFeat = track.artist.replace(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+.*"""), "").trim()
        if (cleanArtistNoFeat.isNotBlank() && cleanArtistNoFeat != track.artist) {
            ids.add(SongIdGenerator.generate(cleanArtistNoFeat, track.title))
            if (cleanBoth.isNotBlank()) {
                ids.add(SongIdGenerator.generate(cleanArtistNoFeat, cleanBoth))
            }
        }

        // 6. Si el título contiene " - ", intentar separar como "Artista - Título"
        val sepList = listOf(" - ", " – ", " — ", " _ ")
        for (sep in sepList) {
            if (track.title.contains(sep)) {
                val parts = track.title.split(sep, limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    ids.add(SongIdGenerator.generate(parts[0].trim(), parts[1].trim()))
                    ids.add(SongIdGenerator.generate(parts[1].trim(), parts[0].trim()))
                }
            }
        }

        // 7. Extraer desde el nombre del archivo de la URI en caso de que los tags ID3 hayan variado
        try {
            val fileName = android.net.Uri.parse(track.uri).lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: ""
            if (fileName.isNotBlank()) {
                val fileNoNum = fileName.replace(Regex("^[0-9]+[.\\s-]+"), "").trim()
                for (sep in sepList) {
                    if (fileNoNum.contains(sep)) {
                        val parts = fileNoNum.split(sep, limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            ids.add(SongIdGenerator.generate(parts[0].trim(), parts[1].trim()))
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return ids.toList()
    }

    /**
     * Obtiene el vector de embeddings para una canción buscando a través de todos sus IDs candidatos.
     */
    fun getVectorForTrack(track: Track): FloatArray? {
        val candidateIds = getCandidateIdsForTrack(track)
        for (id in candidateIds) {
            val vec = vectorCache[id]
            if (vec != null) return vec
        }
        return null
    }

    /**
     * Encuentra las canciones más similares a [currentTrack] dentro de [candidateTracks].
     * Retorna hasta [limit] canciones ordenadas por mayor similitud coseno.
     */
    suspend fun findSimilarTracks(
        context: Context,
        currentTrack: Track,
        candidateTracks: List<Track>,
        limit: Int = 15
    ): List<SimilarTrack> = withContext(Dispatchers.Default) {
        ensureCacheLoaded(context)

        val currentVector = getVectorForTrack(currentTrack) ?: return@withContext emptyList()

        val results = ArrayList<SimilarTrack>(candidateTracks.size)

        for (candidate in candidateTracks) {
            // No comparar la canción consigo misma
            if (candidate.uri == currentTrack.uri) continue

            val candidateVector = getVectorForTrack(candidate) ?: continue

            val sim = cosineSimilarity(currentVector, candidateVector)
            // Porcentaje de afinidad visual (0 a 100%)
            val percentage = (sim.coerceIn(0f, 1f) * 100f).roundToInt()

            results.add(SimilarTrack(track = candidate, score = sim, percentage = percentage))
        }

        // Ordenar por puntuación descendente y tomar las mejores 'limit'
        results.sortByDescending { it.score }
        results.take(limit)
    }

    /**
     * Verifica si una canción tiene vector de embeddings disponible.
     */
    suspend fun hasVectorForTrack(context: Context, track: Track): Boolean = withContext(Dispatchers.Default) {
        ensureCacheLoaded(context)
        getVectorForTrack(track) != null
    }

    /**
     * Obtiene estadísticas de vectores en la base de datos.
     */
    suspend fun getStats(context: Context): VectorStats = withContext(Dispatchers.IO) {
        val count = AppDatabase.get(context).embeddingDao().count()
        VectorStats(totalVectors = count, dimension = EXPECTED_DIMENSION, modelName = EXPECTED_MODEL)
    }

    /**
     * Elimina todos los vectores guardados.
     */
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        db.embeddingDao().clear()
        try {
            db.openHelper.writableDatabase.execSQL("DELETE FROM vec_canciones")
        } catch (_: Exception) {}
        invalidateCache()
    }
}
