package com.example.lume.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lume.PlayerViewModel
import com.example.lume.Track
import com.example.lume.data.LyricsEntry
import com.example.lume.data.MusicRepository
import com.example.lume.util.SongIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val AccentCoral = Color(0xFFE27D60)
private val DarkCard = Color(0xFF221A1E)
private val DarkCardBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
private val TextPrimary = Color(0xFFFBF8F5)
private val TextSub = Color(0xCCDDD2C8)

data class ArtistStat(
    val artist: String,
    val totalTimeMs: Long,
    val sampleTrack: Track? = null,
    val songCount: Int = 0,
    val playCount: Int = 0
)

data class StatsData(
    val totalTimeMs: Long = 0L,
    val favoriteGenre: String = "N/A",
    val favoriteArtist: String = "N/A",
    val peakHour: Int = -1,
    val hourTimes: LongArray = LongArray(24),
    val topSongs: List<Pair<Track, Long>> = emptyList(),
    val topArtists: List<ArtistStat> = emptyList(),
    val playCounts: Map<String, Int> = emptyMap(),
    val totalTracks: Int = 0,
    val totalFavorites: Int = 0,
    val uniqueListened: Int = 0,
    val topSongsWeekly: List<Pair<Track, Long>> = emptyList(),
    val topSongsMonthly: List<Pair<Track, Long>> = emptyList()
)

fun formatListeningTime(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / (1000 * 60)) % 60
    val hrs = ms / (1000 * 60 * 60)
    return when {
        hrs > 0 -> "${hrs}h ${min}m"
        min > 0 -> "${min}m ${sec}s"
        else -> "${sec}s"
    }
}

fun formatHourRange(hour: Int): String {
    val start = if (hour == 0) "12 AM" else if (hour < 12) "$hour AM" else if (hour == 12) "12 PM" else "${hour - 12} PM"
    val nextHour = (hour + 1) % 24
    val end = if (nextHour == 0) "12 AM" else if (nextHour < 12) "$nextHour AM" else if (nextHour == 12) "12 PM" else "${nextHour - 12} PM"
    return "$start - $end"
}
// Metadatos reales leídos del archivo bajo demanda (las pistas se cargan inicialmente solo con título/artista)
private fun readRawMetadata(context: Context, uri: String): Map<String, String?> {
    val r = android.media.MediaMetadataRetriever()
    return try {
        r.setDataSource(context, android.net.Uri.parse(uri))
        mapOf(
            "album"    to r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM),
            "genre"    to r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE),
            "duration" to r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION),
            "year"     to r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR),
            "date"     to r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE),
            "composer" to r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_COMPOSER)
        )
    } catch (e: Exception) {
        emptyMap()
    } finally {
        try { r.release() } catch (_: Exception) {}
    }
}
private fun logMetadataDiagnostics(context: Context, tracks: List<Track>, sample: Int = 30) {
    val picked = tracks.take(sample)
    var withDuration = 0; var withGenre = 0; var withYear = 0; var withAlbum = 0
    picked.forEach { t ->
        val m = readRawMetadata(context, t.uri)
        if (!m["duration"].isNullOrBlank()) withDuration++
        if (!m["genre"].isNullOrBlank()) withGenre++
        if (!(m["year"] ?: m["date"]).isNullOrBlank()) withYear++
        if (!m["album"].isNullOrBlank()) withAlbum++
        android.util.Log.d("LumeMeta", "${t.artist} - ${t.title} -> $m")
    }
    android.util.Log.d(
        "LumeMeta",
        "RESUMEN de ${picked.size}: duracion=$withDuration genero=$withGenre año=$withYear album=$withAlbum"
    )
}
private fun getTrackExtension(context: Context, uri: String): String {
    val parsed = android.net.Uri.parse(uri)
    var name: String? = null
    if (parsed.scheme == "content") {
        try {
            context.contentResolver.query(
                parsed, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
        } catch (_: Exception) {}
    }
    if (name.isNullOrBlank()) name = android.net.Uri.decode(parsed.lastPathSegment ?: uri)
    val ext = name!!.substringAfterLast('.', "").lowercase()
    if (ext.isNotBlank() && ext.length <= 5) return ext
    val mime = try { context.contentResolver.getType(parsed) } catch (_: Exception) { null }
    return mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: ""
}

private fun buildLibraryJson(
    context: Context,
    tracks: List<Track>,
    playCounts: Map<String, Int>,
    onProgress: (Int) -> Unit = {}
): String {
    val array = JSONArray()
    tracks.forEachIndexed { index, track ->
        if (index % 10 == 0 || index == tracks.lastIndex) onProgress(index + 1)
        val needsMeta = track.durationMs <= 0L || track.genre.isNullOrBlank() ||
            track.year == null || track.album.isBlank() || track.album == "Unknown Album"
        val raw = if (needsMeta) readRawMetadata(context, track.uri) else emptyMap()
        val duration = if (track.durationMs > 0L) track.durationMs else raw["duration"]?.toLongOrNull() ?: 0L
        val genre = track.genre?.takeIf { it.isNotBlank() } ?: raw["genre"] ?: ""
        val year = track.year
            ?: raw["year"]?.toIntOrNull()
            ?: raw["date"]?.take(4)?.toIntOrNull()
            ?: 0
        val album = track.album.takeIf { it.isNotBlank() && it != "Unknown Album" }
            ?: raw["album"] ?: track.album
        val composer = track.composer?.takeIf { it.isNotBlank() } ?: raw["composer"] ?: ""

        val obj = JSONObject()
        val songId = track.songId.ifBlank {
            SongIdGenerator.generate(track.artist, track.title)
        }
        obj.put("id",         songId)
        obj.put("titulo",     track.title)
        obj.put("artista",    track.artist)
        obj.put("album",      album)
        obj.put("genero",     genre)
        obj.put("duracion_ms", duration)
        obj.put("año",        year)
        obj.put("compositor", composer)
        obj.put("formato",    getTrackExtension(context, track.uri))
        obj.put("reproducciones", playCounts[track.uri] ?: 0)
        array.put(obj)
    }
    return array.toString(2) // pretty-print con indentación de 2 espacios
}

@Composable
fun StatsScreen(viewModel: PlayerViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var statsData by remember { mutableStateOf<StatsData?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var songFilter by remember { mutableStateOf("Todo") }
    var pendingJsonContent by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0) }
    var exportTotal by remember { mutableStateOf(0) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val content = pendingJsonContent ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    scope.launch { snackbarHostState.showSnackbar("✅ Biblioteca exportada correctamente") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    scope.launch { snackbarHostState.showSnackbar("❌ Error al guardar: ${e.localizedMessage}") }
                }
            } finally {
                pendingJsonContent = null
            }
        }
    }
    var lyricsImportMode by remember { mutableStateOf(false) } // true = Reemplazar
    var showImportDialog by remember { mutableStateOf(false) }  // controla el diálogo

    val openLyricsFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val replaceMode = lyricsImportMode
        scope.launch(Dispatchers.IO) {
            try {
                val raw = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.readText() ?: return@launch
                val jsonArray = JSONArray(raw)
                val entries = mutableListOf<LyricsEntry>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val title = obj.optString("trackName").ifBlank {
                        obj.optString("titulo").ifBlank { obj.optString("title") }
                    }
                    val artist = obj.optString("artistName").ifBlank {
                        obj.optString("artista").ifBlank { obj.optString("artist") }
                    }
                    val songId = obj.optString("id").takeIf { it.isNotBlank() }
                        ?: if (artist.isNotBlank() && title.isNotBlank()) SongIdGenerator.generate(artist, title) else null

                    if (songId == null) continue

                    val plainLyrics = obj.optString("plainLyrics").ifBlank {
                        obj.optString("plain", "")
                    }

                    entries.add(
                        LyricsEntry(
                            songId       = songId,
                            plainLyrics  = plainLyrics,
                            syncedLyrics = obj.optString("syncedLyrics", ""),
                            instrumental = obj.optBoolean("instrumental", false),
                            isUpdate     = obj.optBoolean("isUpdate", false)
                        )
                    )
                }
                val repo = MusicRepository.get(context)
                val result = repo.importLyrics(entries, replaceMode)
                withContext(Dispatchers.Main) {
                    val msg = if (replaceMode) {
                        "✅ Reemplazado: ${result.insertedOrUpdated} letras importadas"
                    } else {
                        if (result.ignored > 0) {
                            "✅ Fusionado: ${result.insertedOrUpdated} añadidas/actualizadas (${result.ignored} ya existían sin duplicar)"
                        } else {
                            "✅ Fusionado: ${result.insertedOrUpdated} letras importadas"
                        }
                    }
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    scope.launch { snackbarHostState.showSnackbar("❌ Error al importar: ${e.localizedMessage}") }
                }
            }
        }
    }
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Importar Letras", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Text(
                    "¿Cómo quieres importar el archivo?\n\n" +
                    "• Reemplazar — borra todas las letras actuales e importa las nuevas.\n" +
                    "• Fusionar — añade/actualiza las letras del archivo sin borrar las existentes.",
                    color = TextSub
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showImportDialog = false
                        lyricsImportMode = true // Reemplazar
                        openLyricsFileLauncher.launch(arrayOf("application/json", "*/*"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Text("Reemplazar", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showImportDialog = false
                        lyricsImportMode = false // Fusionar
                        openLyricsFileLauncher.launch(arrayOf("application/json", "*/*"))
                    },
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Text("Fusionar")
                }
            },
            containerColor = Color(0xFF1E1719),
            shape = RoundedCornerShape(20.dp)
        )
    }

    LaunchedEffect(viewModel.currentTrackIndex, refreshTrigger) {
        withContext(Dispatchers.IO) {
            val statsPrefs = context.getSharedPreferences("lume_stats_prefs", Context.MODE_PRIVATE)
            val totalTimeMs = statsPrefs.getLong("total_listening_time_ms", 0L)
            val rawSongTimes = statsPrefs.getString("song_listening_times", null)
            val songTimes = mutableMapOf<String, Long>()
            if (rawSongTimes != null) {
                try {
                    val json = org.json.JSONObject(rawSongTimes)
                    json.keys().forEach { key ->
                        songTimes[key] = json.optLong(key, 0L)
                    }
                } catch (e: Exception) {}
            }

            val rawHourTimes = statsPrefs.getString("hour_listening_times", null)
            val hourTimes = LongArray(24) { 0L }
            if (rawHourTimes != null) {
                try {
                    val json = org.json.JSONObject(rawHourTimes)
                    for (i in 0..23) {
                        hourTimes[i] = json.optLong(i.toString(), 0L)
                    }
                } catch (e: Exception) {}
            }

            var peakHour = -1
            var maxVal = 0L
            for (i in 0..23) {
                if (hourTimes[i] > maxVal) {
                    maxVal = hourTimes[i]
                    peakHour = i
                }
            }

            val playlistSnapshot = viewModel.playlist.toList()

            val topSongs = songTimes.entries
                .filter { entry -> playlistSnapshot.any { it.uri == entry.key } }
                .sortedByDescending { it.value }
                .take(10)
                .mapNotNull { entry ->
                    val track = playlistSnapshot.find { it.uri == entry.key }
                    if (track != null) Pair(track, entry.value) else null
                }
            val cal = java.util.Calendar.getInstance()
            val weekKey = "${cal.get(java.util.Calendar.YEAR)}-W${cal.get(java.util.Calendar.WEEK_OF_YEAR).toString().padStart(2, '0')}"
            val monthKey = "${cal.get(java.util.Calendar.YEAR)}-${(cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')}"

            val storedWeekKey = statsPrefs.getString("current_week_key", null)
            val weeklySongTimes = mutableMapOf<String, Long>()
            if (storedWeekKey == weekKey) {
                val rawWeekly = statsPrefs.getString("song_listening_times_weekly", null)
                if (rawWeekly != null) {
                    try {
                        val json = org.json.JSONObject(rawWeekly)
                        json.keys().forEach { key -> weeklySongTimes[key] = json.optLong(key, 0L) }
                    } catch (e: Exception) {}
                }
            }
            val topSongsWeekly = weeklySongTimes.entries
                .filter { entry -> playlistSnapshot.any { it.uri == entry.key } }
                .sortedByDescending { it.value }
                .take(10)
                .mapNotNull { entry ->
                    val track = playlistSnapshot.find { it.uri == entry.key }
                    if (track != null) Pair(track, entry.value) else null
                }
            val storedMonthKey = statsPrefs.getString("current_month_key", null)
            val monthlySongTimes = mutableMapOf<String, Long>()
            if (storedMonthKey == monthKey) {
                val rawMonthly = statsPrefs.getString("song_listening_times_monthly", null)
                if (rawMonthly != null) {
                    try {
                        val json = org.json.JSONObject(rawMonthly)
                        json.keys().forEach { key -> monthlySongTimes[key] = json.optLong(key, 0L) }
                    } catch (e: Exception) {}
                }
            }
            val topSongsMonthly = monthlySongTimes.entries
                .filter { entry -> playlistSnapshot.any { it.uri == entry.key } }
                .sortedByDescending { it.value }
                .take(10)
                .mapNotNull { entry ->
                    val track = playlistSnapshot.find { it.uri == entry.key }
                    if (track != null) Pair(track, entry.value) else null
                }

            val rawPlayCounts = statsPrefs.getString("song_play_counts", null)
            val playCounts = mutableMapOf<String, Int>()
            if (rawPlayCounts != null) {
                try {
                    val json = org.json.JSONObject(rawPlayCounts)
                    json.keys().forEach { key ->
                        playCounts[key] = json.optInt(key, 0)
                    }
                } catch (e: Exception) {}
            }

            val genreMap = mutableMapOf<String, Long>()
            songTimes.forEach { (uri, time) ->
                val track = playlistSnapshot.find { it.uri == uri }
                val genre = track?.genre?.trim()
                if (!genre.isNullOrEmpty() && genre != "Unknown Genre") {
                    genreMap[genre] = (genreMap[genre] ?: 0L) + time
                }
            }
            val favoriteGenre = genreMap.entries.maxByOrNull { it.value }?.key ?: "N/A"

            val tracksByArtist = playlistSnapshot.groupBy { it.artist.trim() }

            val topArtists = tracksByArtist.mapNotNull { (artistName, tracks) ->
                if (artistName.isEmpty() || artistName == "Unknown Artist") return@mapNotNull null
                val totalArtistTime = tracks.sumOf { track -> songTimes[track.uri] ?: 0L }
                val totalArtistPlays = tracks.sumOf { track -> playCounts[track.uri] ?: 0 }
                if (totalArtistTime > 0L) {
                    ArtistStat(
                        artist = artistName,
                        totalTimeMs = totalArtistTime,
                        sampleTrack = tracks.firstOrNull(),
                        songCount = tracks.size,
                        playCount = totalArtistPlays
                    )
                } else null
            }
                .sortedByDescending { it.totalTimeMs }
                .take(10)

            val favoriteArtist = topArtists.firstOrNull()?.artist ?: "N/A"

            val totalTracks = playlistSnapshot.size
            val totalFavorites = playlistSnapshot.count { it.isFavorite }
            val uniqueListened = songTimes.filter { it.value > 0 }.size

            val data = StatsData(
                totalTimeMs = totalTimeMs,
                favoriteGenre = favoriteGenre,
                favoriteArtist = favoriteArtist,
                peakHour = peakHour,
                hourTimes = hourTimes,
                topSongs = topSongs,
                topSongsWeekly = topSongsWeekly,
                topSongsMonthly = topSongsMonthly,
                topArtists = topArtists,
                playCounts = playCounts,
                totalTracks = totalTracks,
                totalFavorites = totalFavorites,
                uniqueListened = uniqueListened
            )
            withContext(Dispatchers.Main) {
                statsData = data
            }
        }
    }

    val data = statsData

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { innerPadding ->
        if (data == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentCoral)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = AccentCoral)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Tiempo de Reproducción Total",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = formatListeningTime(data.totalTimeMs),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Sigue disfrutando de tu música",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkCard),
                            border = DarkCardBorder
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "Género Favorito",
                                    fontSize = 12.sp,
                                    color = TextSub,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = data.favoriteGenre,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkCard),
                            border = DarkCardBorder
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "Artista Favorito",
                                    fontSize = 12.sp,
                                    color = TextSub,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = data.favoriteArtist,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                item {
                    HourActivityChart(hourTimes = data.hourTimes, peakHour = data.peakHour)
                }

                item {
                    Text(
                        text = "Top 10 Canciones",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Semanal", "Mensual", "Todo").forEach { filter ->
                            val selected = songFilter == filter
                            Surface(
                                onClick = { songFilter = filter },
                                shape = RoundedCornerShape(20.dp),
                                color = if (selected) AccentCoral else DarkCard,
                                border = if (selected) null else DarkCardBorder
                            ) {
                                Text(
                                    text = filter,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) Color.White else TextSub
                                )
                            }
                        }
                    }
                }

                val filteredTopSongs = when (songFilter) {
                    "Semanal" -> data.topSongsWeekly
                    "Mensual" -> data.topSongsMonthly
                    else -> data.topSongs
                }

                if (filteredTopSongs.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkCard),
                            border = DarkCardBorder
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when (songFilter) {
                                        "Semanal" -> "Sin reproducciones esta semana"
                                        "Mensual" -> "Sin reproducciones este mes"
                                        else -> "Sin historial de reproducción"
                                    },
                                    fontSize = 14.sp,
                                    color = TextSub
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(filteredTopSongs) { index, (track, time) ->
                        val count = data.playCounts[track.uri] ?: 1
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkCard, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(AccentCoral, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))

                            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))) {
                                TrackCover(track = track, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    fontSize = 11.sp,
                                    color = TextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatListeningTime(time),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AccentCoral
                                )
                                Text(
                                    text = "$count repr.",
                                    fontSize = 10.sp,
                                    color = TextSub
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Top 10 Artistas",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (data.topArtists.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkCard),
                            border = DarkCardBorder
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Sin historial de artistas",
                                    fontSize = 14.sp,
                                    color = TextSub
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(data.topArtists) { index, artistStat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkCard, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(AccentCoral, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))

                            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))) {
                                if (artistStat.sampleTrack != null) {
                                    TrackCover(track = artistStat.sampleTrack, modifier = Modifier.fillMaxSize())
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFF282024)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = artistStat.artist.take(1).uppercase(),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentCoral
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = artistStat.artist,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${artistStat.songCount} ${if (artistStat.songCount == 1) "canción" else "canciones"}",
                                    fontSize = 11.sp,
                                    color = TextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatListeningTime(artistStat.totalTimeMs),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AccentCoral
                                )
                                if (artistStat.playCount > 0) {
                                    Text(
                                        text = "${artistStat.playCount} repr.",
                                        fontSize = 10.sp,
                                        color = TextSub
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Resumen de Biblioteca",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatRow(label = "Total de canciones", value = "${data.totalTracks}")
                        StatRow(label = "Canciones favoritas", value = "${data.totalFavorites}")
                        StatRow(label = "Canciones escuchadas", value = "${data.uniqueListened}")
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (isExporting) return@Button
                            isExporting = true
                            exportProgress = 0
                            scope.launch(Dispatchers.IO) {
                                val exportCounts = mutableMapOf<String, Int>()
                                try {
                                    val raw = context.getSharedPreferences("lume_stats_prefs", Context.MODE_PRIVATE)
                                        .getString("song_play_counts", null)
                                    if (raw != null) {
                                        val counts = JSONObject(raw)
                                        counts.keys().forEach { k -> exportCounts[k] = counts.optInt(k, 0) }
                                    }
                                } catch (e: Exception) {}
                                val rawSnapshot = viewModel.playlist.toList()
                                val snapshot = com.example.lume.util.SongIdGenerator.assignUniqueSongIds(rawSnapshot)
                                if (rawSnapshot.any { it.songId.isBlank() }) {
                                    withContext(Dispatchers.Main) {
                                        viewModel.playlist.clear()
                                        viewModel.playlist.addAll(snapshot)
                                    }
                                }
                                try {
                                    withContext(Dispatchers.Main) {
                                        exportTotal = snapshot.size
                                        exportProgress = 0
                                    }
                                    logMetadataDiagnostics(context, snapshot)
                                    val json = buildLibraryJson(context, snapshot, exportCounts) { done ->
                                        exportProgress = done
                                    }
                                    withContext(Dispatchers.Main) {
                                        pendingJsonContent = json
                                        isExporting = false
                                        val timestamp = java.text.SimpleDateFormat(
                                            "yyyyMMdd_HHmmss",
                                            java.util.Locale.getDefault()
                                        ).format(java.util.Date())
                                        createDocumentLauncher.launch("lume_library_$timestamp.json")
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isExporting = false
                                        snackbarHostState.showSnackbar("❌ Error al exportar: ${e.localizedMessage}")
                                    }
                                }
                            }
                        },
                        enabled = !isExporting,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentCoral,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isExporting) "Exportando… $exportProgress / $exportTotal"
                                   else "Exportar Biblioteca como JSON",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (isExporting) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (exportTotal > 0) exportProgress.toFloat() / exportTotal else 0f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = AccentCoral,
                            trackColor = Color.White.copy(alpha = 0.12f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5C6BC0),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Importar Letras JSON",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.resetStats(context)
                            refreshTrigger++
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE57373)),
                        border = BorderStroke(1.dp, Color(0xFFE57373).copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = "Restablecer Estadísticas", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun HourActivityChart(hourTimes: LongArray, peakHour: Int) {
    val maxListeningVal = hourTimes.maxOrNull() ?: 0L
    val displayMax = if (maxListeningVal == 0L) 1L else maxListeningVal

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = DarkCardBorder
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Actividad por Hora",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (peakHour != -1 && maxListeningVal > 0L) {
                    "Hora pico: ${formatHourRange(peakHour)}"
                } else {
                    "Sin actividad registrada"
                },
                fontSize = 12.sp,
                color = TextSub
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                for (hour in 0..23) {
                    val time = hourTimes[hour]
                    val heightRatio = time.toFloat() / displayMax.toFloat()
                    val isPeak = hour == peakHour && time > 0L
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(heightRatio.coerceAtLeast(0.05f))
                                .background(
                                    if (isPeak) AccentCoral else AccentCoral.copy(alpha = 0.45f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("12 AM", fontSize = 10.sp, color = TextSub)
                Text("6 AM", fontSize = 10.sp, color = TextSub)
                Text("12 PM", fontSize = 10.sp, color = TextSub)
                Text("6 PM", fontSize = 10.sp, color = TextSub)
                Text("11 PM", fontSize = 10.sp, color = TextSub)
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkCard, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSub
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
    }
}
