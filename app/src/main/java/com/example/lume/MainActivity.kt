package com.example.lume

import android.content.Context
import com.example.lume.ui.MainScreen
import com.example.lume.ui.PlayerScreen
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.*
import java.util.Calendar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.lume.data.MusicRepository
import com.example.lume.services.SmartShuffleService
import com.example.lume.services.PlaybackService
import com.example.lume.util.Debouncer
import com.example.lume.util.TextSanitizer
import com.example.lume.util.rememberDebouncer
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.BorderStroke

data class Track(
    val uri: String,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val genre: String? = null,
    val durationMs: Long = 0L,
    val year: Int? = null,
    val composer: String? = null,
    val isFavorite: Boolean = false
)

data class Playlist(
    val id: Long,
    val name: String
)

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

class PlayerViewModel : ViewModel() {
    var repeatMode by mutableStateOf(RepeatMode.OFF)
    var playbackSpeed by mutableStateOf(1f)
    private val exoPlayer: ExoPlayer?
        get() = PlaybackService.playerInstance
    val playlist = mutableStateListOf<Track>()
    val recentTracks = mutableStateListOf<Track>()
    val playbackQueue = mutableStateListOf<Track>()
    val folders = mutableStateListOf<String>()
    var currentTrackIndex by mutableIntStateOf(-1)
    var isPlaying by mutableStateOf(false)
    var currentPosition by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var isShuffleEnabled by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    var loadingProgress by mutableIntStateOf(0)
    var isSyncing by mutableStateOf(false)
    private val playedUris = mutableSetOf<String>()
    private var appContext: Context? = null
    private var lastCountedTrackUri: String? = null
    private var lastCountedPosition: Long = -1L
    private var repository: MusicRepository? = null
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val debouncer = Debouncer()

    val customPlaylists = mutableStateListOf<Playlist>()
    val selectedPlaylistTracks = mutableStateListOf<Track>()
    val playlistTrackCounts = androidx.compose.runtime.mutableStateMapOf<Long, Int>()
    private var playlistTracksJob: kotlinx.coroutines.Job? = null

    fun observePlaylists(context: Context) {
        scope.launch {
            val repo = repository ?: MusicRepository.get(context)
            repo.observePlaylists().collect { list ->
                withContext(Dispatchers.Main) {
                    customPlaylists.clear()
                    customPlaylists.addAll(list)
                }
                val counts = list.associate { it.id to repo.getTrackCountForPlaylist(it.id) }
                withContext(Dispatchers.Main) {
                    playlistTrackCounts.putAll(counts)
                }
            }
        }
    }

    fun createPlaylist(context: Context, name: String) {
        scope.launch {
            val repo = repository ?: MusicRepository.get(context)
            repo.createPlaylist(name)
        }
    }

    fun updatePlaylistName(context: Context, id: Long, name: String) {
        scope.launch {
            val repo = repository ?: MusicRepository.get(context)
            repo.updatePlaylistName(id, name)
        }
    }

    fun deletePlaylist(context: Context, id: Long) {
        scope.launch {
            val repo = repository ?: MusicRepository.get(context)
            repo.deletePlaylist(id)
        }
    }

    fun addTracksToPlaylist(context: Context, playlistId: Long, trackUris: List<String>) {
        scope.launch {
            val repo = repository ?: MusicRepository.get(context)
            repo.addTracksToPlaylist(playlistId, trackUris)
            val count = repo.getTrackCountForPlaylist(playlistId)
            withContext(Dispatchers.Main) { playlistTrackCounts[playlistId] = count }
        }
    }

    fun removeTrackFromPlaylist(context: Context, playlistId: Long, trackUri: String) {
        scope.launch {
            val repo = repository ?: MusicRepository.get(context)
            repo.removeTrackFromPlaylist(playlistId, trackUri)
            val count = repo.getTrackCountForPlaylist(playlistId)
            withContext(Dispatchers.Main) { playlistTrackCounts[playlistId] = count }
        }
    }

    fun loadPlaylistTracks(context: Context, playlistId: Long) {
        playlistTracksJob?.cancel()
        playlistTracksJob = scope.launch {
            val repo = repository ?: MusicRepository.get(context)
            repo.observePlaylistTracks(playlistId).collect { list ->
                withContext(Dispatchers.Main) {
                    selectedPlaylistTracks.clear()
                    selectedPlaylistTracks.addAll(list)
                }
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            this@PlayerViewModel.isPlaying = playing
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                val d = exoPlayer?.duration ?: 0L
                this@PlayerViewModel.duration = if (d < 0) 0L else d
            } else if (state == Player.STATE_ENDED) {
                this@PlayerViewModel.next(isAutoEnd = true)
            }
        }
    }

    suspend fun initPlayer(context: Context) {
        appContext = context.applicationContext
        repository = MusicRepository.get(context)
        observePlaylists(context)
        
        val player = PlaybackService.playerInstance ?: run {
            withContext(Dispatchers.Main) {
                PlaybackService.playerViewModelInstance = this@PlayerViewModel
                context.startService(Intent(context, PlaybackService::class.java))
            }
            waitForPlayerInstance(context)
        }
        
        withContext(Dispatchers.Main) {
            player.removeListener(playerListener)
            player.addListener(playerListener)
            player.repeatMode = when (repeatMode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_OFF
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
            this@PlayerViewModel.isPlaying = player.isPlaying
            val d = player.duration
            this@PlayerViewModel.duration = if (d < 0) 0L else d
            this@PlayerViewModel.currentPosition = player.currentPosition
        }
        if (playlist.isEmpty()) {
            loadSavedState(context)
        } else if (playbackQueue.isEmpty()) {
            playbackQueue.addAll(playlist)
        }
        SmartShuffleService.instance.warmUp(playlist, context)
    }

    private suspend fun waitForPlayerInstance(context: Context, timeoutMs: Long = 5000): ExoPlayer {
        val startTime = System.currentTimeMillis()
        while (PlaybackService.playerInstance == null) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return withContext(Dispatchers.Main) {
                    PlaybackService.createLumePlayer(context).also { PlaybackService.playerInstance = it }
                }
            }
            kotlinx.coroutines.delay(50)
        }
        return PlaybackService.playerInstance!!
    }

    private fun loadSavedState(context: Context) {
        scope.launch {
            val repo = repository ?: return@launch
            val loadedTracks = repo.loadTracks()
            val loadedFolders = repo.loadFolders()
            val loadedRecent = repo.loadTracksByInsertion()
            withContext(Dispatchers.Main) {
                playlist.clear()
                playlist.addAll(loadedTracks)
                recentTracks.clear()
                recentTracks.addAll(loadedRecent)
                folders.clear()
                folders.addAll(loadedFolders)
                playbackQueue.clear()
                playbackQueue.addAll(loadedTracks)

                val prefs = context.getSharedPreferences("lume_playback_prefs", Context.MODE_PRIVATE)
                val lastUri = prefs.getString("last_track_uri", null)
                val lastPos = prefs.getLong("last_track_position", 0L)
                if (lastUri != null) {
                    val idx = playbackQueue.indexOfFirst { it.uri == lastUri }
                    if (idx != -1) {
                        currentTrackIndex = idx
                        val track = playbackQueue[idx]
                        val metadata = androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .build()
                        val mediaItem = MediaItem.Builder()
                            .setUri(Uri.parse(track.uri))
                            .setMediaMetadata(metadata)
                            .build()
                        
                        val currentMediaItem = exoPlayer?.currentMediaItem
                        val currentUriStr = currentMediaItem?.localConfiguration?.uri?.toString()
                        if (currentUriStr != null && currentUriStr == track.uri) {
                            currentPosition = exoPlayer?.currentPosition ?: 0L
                            duration = exoPlayer?.duration ?: 0L
                            isPlaying = exoPlayer?.isPlaying ?: false
                        } else {
                            exoPlayer?.apply {
                                setMediaItem(mediaItem)
                                seekTo(lastPos)
                                prepare()
                            }
                            currentPosition = lastPos
                            duration = if (track.durationMs < 0) 0L else track.durationMs
                        }
                    }
                }
            }
            syncAllFolders(context)
        }
    }

    private suspend fun syncAllFolders(context: Context) {
        val repo = repository ?: return
        val foldersSnapshot = folders.toList()
        if (foldersSnapshot.isEmpty()) return

        withContext(Dispatchers.Main) { isSyncing = true }
        try {
            val toAdd = mutableListOf<Track>()
            val toRemove = mutableListOf<String>()
            val foldersDropped = mutableListOf<String>()

            for (folderStr in foldersSnapshot) {
                val treeUri = try {
                    Uri.parse(folderStr)
                } catch (e: Exception) {
                    foldersDropped.add(folderStr)
                    continue
                }
                val folderPrefix = treeUri.toString() + "/document/"
                try {
                    val walked = walkFolderUris(context, treeUri)
                    val walkedUris = walked.map { it.first }.toSet()
                    val dbUrisInFolder = repo.getAllTrackUris().filter { it.startsWith(folderPrefix) }.toSet()
                    val newUris = walkedUris - dbUrisInFolder
                    val removedUris = dbUrisInFolder - walkedUris
                    for ((uri, name) in walked) {
                        if (uri in newUris) {
                            toAdd.add(createBasicTrack(uri, name))
                        }
                    }
                    toRemove.addAll(removedUris)
                } catch (e: SecurityException) {
                    e.printStackTrace()
                    foldersDropped.add(folderStr)
                } catch (e: java.io.FileNotFoundException) {
                    e.printStackTrace()
                    foldersDropped.add(folderStr)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            for (folderStr in foldersDropped) {
                val prefix = Uri.parse(folderStr).toString() + "/document/"
                val stale = repo.getAllTrackUris().filter { it.startsWith(prefix) }
                toRemove.addAll(stale)
            }
            val removeDistinct = toRemove.distinct()

            if (toAdd.isNotEmpty()) repo.addTracks(toAdd)
            for (uri in removeDistinct) repo.removeTrack(uri)
            for (folderStr in foldersDropped) repo.removeFolder(folderStr)

            if (toAdd.isNotEmpty() || removeDistinct.isNotEmpty() || foldersDropped.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    if (removeDistinct.isNotEmpty()) {
                        val removeSet = removeDistinct.toSet()
                        val removedIndexes = playlist.indices
                            .filter { playlist[it].uri in removeSet }
                            .sortedDescending()
                        for (idx in removedIndexes) {
                            playlist.removeAt(idx)
                        }

                        val currentUri = playbackQueue.getOrNull(currentTrackIndex)?.uri
                        val qRemovedIndexes = playbackQueue.indices
                            .filter { playbackQueue[it].uri in removeSet }
                            .sortedDescending()
                        for (idx in qRemovedIndexes) {
                            if (idx == currentTrackIndex) {
                                exoPlayer?.stop()
                                currentTrackIndex = -1
                                isPlaying = false
                            } else if (currentTrackIndex in (idx + 1)..<playbackQueue.size) {
                                currentTrackIndex -= 1
                            }
                            playbackQueue.removeAt(idx)
                        }
                        if (currentUri != null && currentTrackIndex != -1) {
                            val newIdx = playbackQueue.indexOfFirst { it.uri == currentUri }
                            if (newIdx == -1) {
                                currentTrackIndex = -1
                            } else if (newIdx != currentTrackIndex) {
                                currentTrackIndex = newIdx
                            }
                        }
                    }
                    if (toAdd.isNotEmpty()) {
                        playlist.addAll(toAdd)
                        playlist.sortBy { "${it.artist.lowercase()}|${it.title.lowercase()}" }
                    }
                    if (foldersDropped.isNotEmpty()) {
                        folders.removeAll(foldersDropped.toSet())
                    }
                    SmartShuffleService.instance.warmUp(playlist, context)
                    refreshRecentTracks()
                }
            }
        } finally {
            withContext(Dispatchers.Main) { isSyncing = false }
        }
    }

    fun addFolder(context: Context, uri: Uri) {
        if (!debouncer.tryAcquire("addFolder")) return
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val path = uri.toString()
            if (!folders.contains(path)) {
                folders.add(path)
                isLoading = true
                loadingProgress = 0
                scope.launch {
                    val newTracks = scanFolderWithProgress(context, uri) { progress ->
                        loadingProgress = progress
                    }
                    repository?.addTracks(newTracks)
                    repository?.addFolder(path)
                    withContext(Dispatchers.Main) {
                        playlist.addAll(newTracks)
                        playlist.sortBy { "${it.artist.lowercase()}|${it.title.lowercase()}" }
                        if (playbackQueue.isEmpty()) {
                            playbackQueue.addAll(playlist)
                        }
                        SmartShuffleService.instance.warmUp(playlist, context)
                        isLoading = false
                        refreshRecentTracks()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isLoading = false
        }
    }

    fun addSongs(context: Context, uris: List<Uri>) {
        if (!debouncer.tryAcquire("addSongs")) return
        val newTracks = mutableListOf<Track>()
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val name = getFileName(context, uri)
                val track = getTrackWithMetadata(context, uri, name)
                if (!playlist.any { it.uri == track.uri }) {
                    newTracks.add(track)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (newTracks.isNotEmpty()) {
            scope.launch {
                repository?.addTracks(newTracks)
                withContext(Dispatchers.Main) {
                    playlist.addAll(newTracks)
                    playlist.sortBy { "${it.artist.lowercase()}|${it.title.lowercase()}" }
                    if (playbackQueue.isEmpty()) {
                        playbackQueue.addAll(playlist)
                    }
                    SmartShuffleService.instance.warmUp(playlist, context)
                    refreshRecentTracks()
                }
            }
        }
    }

    fun playTrack(index: Int) {
        if (!debouncer.tryAcquire("playTrack")) return
        if (index in playbackQueue.indices) {
            currentTrackIndex = index
            var track = playbackQueue[index]
            appContext?.let { ctx ->
                val fullTrack = loadFullMetadata(ctx, track)
                if (fullTrack.title != track.title || fullTrack.artist != track.artist || fullTrack.album != track.album ||
                    fullTrack.genre != track.genre || fullTrack.durationMs != track.durationMs) {
                    track = fullTrack
                    playbackQueue[index] = track
                    val pIdx = playlist.indexOfFirst { it.uri == track.uri }
                    if (pIdx != -1) {
                        playlist[pIdx] = track
                    }
                }
            }
            val metadata = androidx.media3.common.MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(track.uri))
                .setMediaMetadata(metadata)
                .build()
            exoPlayer?.apply {
                setMediaItem(mediaItem)
                repeatMode = when (this@PlayerViewModel.repeatMode) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ALL -> Player.REPEAT_MODE_OFF
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                }
                seekTo(0, 0L)
                prepare()
                play()
            }
            appContext?.let {
                SmartShuffleService.instance.recordPlay(track, it)
                val prefs = it.getSharedPreferences("lume_playback_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("last_track_uri", track.uri)
                    .putLong("last_track_position", 0L)
                    .apply()
            }
            playedUris.add(track.uri)
        }
    }

    fun playTrack(track: Track, newQueue: List<Track>) {
        playbackQueue.clear()
        playbackQueue.addAll(newQueue)
        val index = playbackQueue.indexOfFirst { it.uri == track.uri }
        if (index != -1) {
            playTrack(index)
        }
    }

    fun togglePlay() {
        if (!debouncer.tryAcquire("togglePlay")) return
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                if (currentTrackIndex in playbackQueue.indices) {
                    if (player.currentMediaItem == null) {
                        val track = playbackQueue[currentTrackIndex]
                        val metadata = androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .build()
                        val mediaItem = MediaItem.Builder()
                            .setUri(Uri.parse(track.uri))
                            .setMediaMetadata(metadata)
                            .build()
                        player.setMediaItem(mediaItem)
                        player.prepare()
                        player.seekTo(currentPosition)
                    } else if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    } else if (player.playbackState == Player.STATE_ENDED) {
                        player.seekTo(0L)
                    }
                    player.play()
                } else {
                    if (playbackQueue.isNotEmpty()) {
                        playTrack(0)
                    } else if (playlist.isNotEmpty()) {
                        playbackQueue.clear()
                        playbackQueue.addAll(playlist)
                        playTrack(0)
                    }
                }
            }
        }
    }

    fun next(isAutoEnd: Boolean = false) {
        if (!debouncer.tryAcquire("next")) return
        if (playbackQueue.isEmpty()) return

        if (isAutoEnd) {
            if (repeatMode == RepeatMode.ONE) {
                playTrack(currentTrackIndex)
                return
            }
            if (repeatMode == RepeatMode.OFF) {
                if (isShuffleEnabled) {
                    if (playedUris.size >= playbackQueue.size) {
                        isPlaying = false
                        currentPosition = 0L
                        exoPlayer?.seekTo(0L)
                        exoPlayer?.pause()
                        return
                    }
                } else {
                    if (currentTrackIndex == playbackQueue.size - 1) {
                        isPlaying = false
                        currentPosition = 0L
                        exoPlayer?.seekTo(0L)
                        exoPlayer?.pause()
                        return
                    }
                }
            }
        }

        if (isShuffleEnabled) {
            val currentUri = playbackQueue.getOrNull(currentTrackIndex)?.uri ?: ""
            val candidates = playbackQueue.map { it.uri }
            appContext?.let { ctx ->
                val nextUri = SmartShuffleService.instance.nextSmartSong(currentUri, candidates, playedUris, ctx)
                if (nextUri != null) {
                    val idx = playbackQueue.indexOfFirst { it.uri == nextUri }
                    if (idx != -1) {
                        playTrack(idx)
                        return
                    }
                }
            }
            val nextIndex = (currentTrackIndex + 1) % playbackQueue.size
            playTrack(nextIndex)
        } else {
            val nextIndex = (currentTrackIndex + 1) % playbackQueue.size
            playTrack(nextIndex)
        }
    }

    fun toggleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        exoPlayer?.repeatMode = when (repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

        fun changePlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        exoPlayer?.setPlaybackParameters(
            androidx.media3.common.PlaybackParameters(speed)
        )
    }

    fun previous() {
        if (!debouncer.tryAcquire("previous")) return
        if (playbackQueue.isEmpty()) return
        val prevIndex = if (currentTrackIndex - 1 < 0) playbackQueue.size - 1 else currentTrackIndex - 1
        playTrack(prevIndex)
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
        currentPosition = position
        if (currentTrackIndex in playbackQueue.indices) {
            val track = playbackQueue[currentTrackIndex]
            appContext?.let { ctx ->
                val prefs = ctx.getSharedPreferences("lume_playback_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("last_track_uri", track.uri)
                    .putLong("last_track_position", position)
                    .apply()
            }
        }
    }

    fun updateProgress() {
        exoPlayer?.let {
            currentPosition = it.currentPosition
            val d = it.duration
            duration = if (d < 0) 0L else d

            if (currentTrackIndex in playbackQueue.indices) {
                val track = playbackQueue[currentTrackIndex]
                appContext?.let { ctx ->
                    val prefs = ctx.getSharedPreferences("lume_playback_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("last_track_uri", track.uri)
                        .putLong("last_track_position", currentPosition)
                        .apply()

                    val statsPrefs = ctx.getSharedPreferences("lume_stats_prefs", Context.MODE_PRIVATE)
                    val totalTime = statsPrefs.getLong("total_listening_time_ms", 0L) + 500L
                    val rawSongTimes = statsPrefs.getString("song_listening_times", null)
                    val songTimesJson = if (rawSongTimes != null) org.json.JSONObject(rawSongTimes) else org.json.JSONObject()
                    val currentSongTime = songTimesJson.optLong(track.uri, 0L) + 500L
                    songTimesJson.put(track.uri, currentSongTime)
                    val cal = java.util.Calendar.getInstance()
                    val weekKey = "${cal.get(java.util.Calendar.YEAR)}-W${cal.get(java.util.Calendar.WEEK_OF_YEAR).toString().padStart(2, '0')}"
                    val monthKey = "${cal.get(java.util.Calendar.YEAR)}-${(cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')}"

                    val storedWeekKey = statsPrefs.getString("current_week_key", null)
                    val rawWeeklySongTimes = if (storedWeekKey == weekKey) statsPrefs.getString("song_listening_times_weekly", null) else null
                    val weeklySongTimesJson = if (rawWeeklySongTimes != null) org.json.JSONObject(rawWeeklySongTimes) else org.json.JSONObject()
                    weeklySongTimesJson.put(track.uri, weeklySongTimesJson.optLong(track.uri, 0L) + 500L)
                    val storedMonthKey = statsPrefs.getString("current_month_key", null)
                    val rawMonthlySongTimes = if (storedMonthKey == monthKey) statsPrefs.getString("song_listening_times_monthly", null) else null
                    val monthlySongTimesJson = if (rawMonthlySongTimes != null) org.json.JSONObject(rawMonthlySongTimes) else org.json.JSONObject()
                    monthlySongTimesJson.put(track.uri, monthlySongTimesJson.optLong(track.uri, 0L) + 500L)

                    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    val rawHourTimes = statsPrefs.getString("hour_listening_times", null)
                    val hourTimesJson = if (rawHourTimes != null) org.json.JSONObject(rawHourTimes) else org.json.JSONObject()
                    val currentHourTime = hourTimesJson.optLong(hour.toString(), 0L) + 500L
                    hourTimesJson.put(hour.toString(), currentHourTime)

                    val isNewOrRepeated = track.uri != lastCountedTrackUri || (track.uri == lastCountedTrackUri && currentPosition < lastCountedPosition - 1000L && lastCountedPosition > 2000L)
                    if (isNewOrRepeated) {
                        lastCountedTrackUri = track.uri
                        val playCountsStr = statsPrefs.getString("song_play_counts", null)
                        val playCountsJson = if (playCountsStr != null) org.json.JSONObject(playCountsStr) else org.json.JSONObject()
                        val currentPlayCount = playCountsJson.optInt(track.uri, 0) + 1
                        playCountsJson.put(track.uri, currentPlayCount)
                        statsPrefs.edit().putString("song_play_counts", playCountsJson.toString()).apply()
                    }
                    lastCountedPosition = currentPosition

                    statsPrefs.edit()
                        .putLong("total_listening_time_ms", totalTime)
                        .putString("song_listening_times", songTimesJson.toString())
                        .putString("song_listening_times_weekly", weeklySongTimesJson.toString())
                        .putString("current_week_key", weekKey)
                        .putString("song_listening_times_monthly", monthlySongTimesJson.toString())
                        .putString("current_month_key", monthKey)
                        .putString("hour_listening_times", hourTimesJson.toString())
                        .apply()
                }
            }
        }
    }

    fun resetStats(context: Context) {
        val statsPrefs = context.getSharedPreferences("lume_stats_prefs", Context.MODE_PRIVATE)
        statsPrefs.edit().clear().apply()
        lastCountedTrackUri = null
        lastCountedPosition = -1L
    }

    fun clearAll(context: Context) {
        if (!debouncer.tryAcquire("clearAll")) return
        playlist.clear()
        playbackQueue.clear()
        folders.clear()
        currentTrackIndex = -1
        isPlaying = false
        currentPosition = 0L
        duration = 0L
        recentTracks.clear()
        playedUris.clear()
        exoPlayer?.stop()
        val prefs = context.getSharedPreferences("lume_playback_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        scope.launch {
            repository?.clearTracks()
            repository?.clearFolders()
        }
    }

    fun toggleFavorite(track: Track) {
        if (!debouncer.tryAcquire("toggleFavorite")) return
        val index = playlist.indexOfFirst { it.uri == track.uri }
        if (index != -1) {
            val updated = playlist[index].copy(isFavorite = !playlist[index].isFavorite)
            playlist[index] = updated
            scope.launch {
                repository?.updateFavorite(track.uri, updated.isFavorite)
            }
        }
        val qIndex = playbackQueue.indexOfFirst { it.uri == track.uri }
        if (qIndex != -1) {
            playbackQueue[qIndex] = playbackQueue[qIndex].copy(isFavorite = !playbackQueue[qIndex].isFavorite)
        }
        val rIndex = recentTracks.indexOfFirst { it.uri == track.uri }
        if (rIndex != -1) {
            recentTracks[rIndex] = recentTracks[rIndex].copy(isFavorite = !recentTracks[rIndex].isFavorite)
        }
    }

    fun refreshRecentTracks() {
        scope.launch {
            val repo = repository ?: return@launch
            val list = repo.loadTracksByInsertion()
            withContext(Dispatchers.Main) {
                recentTracks.clear()
                recentTracks.addAll(list)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        PlaybackService.playerViewModelInstance = null
        PlaybackService.playerInstance?.removeListener(playerListener)
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "Unknown Track"
    }

    private fun getTrackWithMetadata(context: Context, uri: Uri, defaultTitle: String): Track {
        val retriever = MediaMetadataRetriever()
        var title = defaultTitle
        var artist = "Unknown Artist"
        var album = "Unknown Album"
        var genre: String? = null
        var durationMs = 0L
        var year: Int? = null
        var composer: String? = null
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { artist = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { album = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { genre = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { durationMs = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()?.let { year = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)?.let { composer = it }
        } catch (e: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }
        if (artist == "Unknown Artist" || artist.isBlank()) {
            val fileName = getFileName(context, uri)
            val nameWithoutExt = if (fileName.isNotBlank() && fileName != "Unknown Track") {
                fileName.substringBeforeLast(".")
            } else {
                defaultTitle.substringBeforeLast(".")
            }
            val parsed = parseFileName(nameWithoutExt)
            if (parsed.first.isNotEmpty()) {
                artist = parsed.first
                if (title == defaultTitle || title == "Unknown Track" || title.isBlank() || title == fileName) {
                    title = parsed.second
                }
            }
        }
        return Track(
            uri = uri.toString(),
            title = TextSanitizer.sanitize(title),
            artist = TextSanitizer.sanitize(artist),
            album = TextSanitizer.sanitize(album),
            genre = genre?.let { TextSanitizer.sanitize(it) },
            durationMs = durationMs,
            year = year,
            composer = composer?.let { TextSanitizer.sanitize(it) }
        )
    }

    private fun scanFolder(context: Context, treeUri: Uri): List<Track> {
        return walkFolderUris(context, treeUri).map { (uri, name) -> createBasicTrack(uri, name) }
    }

    private fun scanFolderWithProgress(context: Context, treeUri: Uri, onProgress: (Int) -> Unit): List<Track> {
        val entries = walkFolderUris(context, treeUri, onProgress)
        return entries.map { (uri, name) -> createBasicTrack(uri, name) }
    }

    private fun walkFolderUris(
        context: Context,
        treeUri: Uri,
        onCount: (Int) -> Unit = {}
    ): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        val docIdsToScan = mutableListOf<String>()
        var totalFound = 0
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            docIdsToScan.add(rootDocId)
            while (docIdsToScan.isNotEmpty()) {
                val currentDocId = docIdsToScan.removeAt(0)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (idCol != -1 && nameCol != -1 && mimeCol != -1) {
                        while (cursor.moveToNext()) {
                            val docId = cursor.getString(idCol)
                            val name = cursor.getString(nameCol)
                            val mimeType = cursor.getString(mimeCol)
                            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                docIdsToScan.add(docId)
                            } else if (mimeType.startsWith("audio/") || name.endsWith(".mp3", true) || name.endsWith(".flac", true)) {
                                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                entries.add(Pair(fileUri.toString(), name))
                                totalFound++
                                if (totalFound % 100 == 0) {
                                    onCount(totalFound)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        onCount(totalFound)
        return entries
    }

    private fun createBasicTrack(uri: String, fileName: String): Track {
        val nameWithoutExt = fileName.substringBeforeLast(".")
        val parsed = parseFileName(nameWithoutExt)
        val rawArtist = parsed.first.ifEmpty { "Unknown Artist" }
        val rawTitle = parsed.second.ifEmpty { nameWithoutExt }
        return Track(
            uri = uri,
            title = TextSanitizer.sanitize(rawTitle),
            artist = TextSanitizer.sanitize(rawArtist)
        )
    }

    private fun parseFileName(name: String): Pair<String, String> {
        val separators = listOf(" - ", " – ", " — ", " _ ", "-", "_")
        for (sep in separators) {
            val parts = name.split(sep, limit = 2)
            if (parts.size == 2) {
                val first = parts[0].trim()
                val second = parts[1].trim()
                if (first.isNotEmpty() && second.isNotEmpty()) {
                    return Pair(first, second)
                }
            }
        }
        return Pair("", name)
    }

    private fun loadFullMetadata(context: Context, track: Track): Track {
        return try {
            val retrieved = getTrackWithMetadata(context, Uri.parse(track.uri), track.title)
            val finalArtist = if (retrieved.artist.isBlank() || retrieved.artist.equals("Unknown Artist", ignoreCase = true)) {
                if (track.artist.isNotBlank() && !track.artist.equals("Unknown Artist", ignoreCase = true)) {
                    track.artist
                } else {
                    retrieved.artist
                }
            } else {
                retrieved.artist
            }
            val finalTitle = if (retrieved.title.isBlank() || retrieved.title.equals("Unknown Track", ignoreCase = true)) {
                if (track.title.isNotBlank() && !track.title.equals("Unknown Track", ignoreCase = true)) {
                    track.title
                } else {
                    retrieved.title
                }
            } else {
                retrieved.title
            }
            val finalAlbum = if (retrieved.album.isBlank() || retrieved.album.equals("Unknown Album", ignoreCase = true)) {
                if (track.album.isNotBlank() && !track.album.equals("Unknown Album", ignoreCase = true)) {
                    track.album
                } else {
                    retrieved.album
                }
            } else {
                retrieved.album
            }
            retrieved.copy(
                artist = finalArtist,
                title = finalTitle,
                album = finalAlbum,
                isFavorite = track.isFavorite
            )
        } catch (e: Exception) {
            track
        }
    }
}

class MainActivity : ComponentActivity() {
    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch(Dispatchers.IO) {
            playerViewModel.initPlayer(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {}
            LaunchedEffect(Unit) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            LaunchedEffect(playerViewModel.isPlaying) {
                while (playerViewModel.isPlaying) {
                    playerViewModel.updateProgress()
                    delay(500)
                }
            }
            val navigator = remember { CustomNavigator() }
            val LumeBg = Color(0xFFF5F0E8)
            val LumeAccent = Color(0xFFC9A227)
            val LumeCard = Color(0xFFEDE8DC)
            val LumeText = Color(0xFF1A1A1A)

            MaterialTheme(
                colorScheme = lightColorScheme(
                    background = LumeBg,
                    surface = LumeCard,
                    primary = LumeAccent,
                    onBackground = LumeText,
                    onSurface = LumeText
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = LumeBg) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainScreen(viewModel = playerViewModel, navigator = navigator)
                        AnimatedVisibility(
                            visible = navigator.currentScreen == Screen.Player,
                            enter = slideInVertically(animationSpec = tween(350)) { it } + fadeIn(animationSpec = tween(350)),
                            exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(animationSpec = tween(300))
                        ) {
                            PlayerScreen(viewModel = playerViewModel, navigator = navigator)
                        }
                    }
                }
            }
        }
    }
}

fun Modifier.simpleVerticalScrollbar(
    state: androidx.compose.foundation.lazy.LazyListState,
    width: androidx.compose.ui.unit.Dp = 4.dp
): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        
        val layoutInfo = state.layoutInfo
        val totalItems = layoutInfo.totalItemsCount
        val visibleItems = layoutInfo.visibleItemsInfo
        
        if (totalItems > 0 && visibleItems.isNotEmpty()) {
            val firstVisible = visibleItems.first()
            val lastVisible = visibleItems.last()
            val visibleCount = visibleItems.size
            
            if (visibleCount < totalItems) {
                val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                val totalHeight = (viewportHeight.toFloat() / visibleCount) * totalItems
                
                val firstItemOffset = firstVisible.offset
                val averageItemHeight = (lastVisible.offset - firstVisible.offset).toFloat() / (visibleCount - 1).coerceAtLeast(1)
                val scrolledBytes = (firstVisible.index * averageItemHeight) - firstItemOffset
                
                val scrollPercent = (scrolledBytes / (totalHeight - viewportHeight)).coerceIn(0f, 1f)
                
                val scrollbarHeight = (viewportHeight.toFloat() / totalHeight) * viewportHeight
                val maxScrollbarOffset = viewportHeight - scrollbarHeight
                val scrollbarOffset = scrollPercent * maxScrollbarOffset
                
                drawRoundRect(
                    color = Color(0xFFC9A227),
                    topLeft = androidx.compose.ui.geometry.Offset(this.size.width - width.toPx() - 4.dp.toPx(), scrollbarOffset),
                    size = androidx.compose.ui.geometry.Size(width.toPx(), scrollbarHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width.toPx() / 2),
                    alpha = 0.6f
                )
            }
        }
    }
)