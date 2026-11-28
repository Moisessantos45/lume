package com.example.lume.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lume.*
import com.example.lume.R
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import com.example.lume.util.TextSanitizer
import com.example.lume.util.rememberDebouncer
import java.util.Calendar

data class ScreenState(
    val chip: String,
    val artist: String?,
    val album: String?,
    val playlistId: Long?
)

@Composable
fun MainScreen(viewModel: PlayerViewModel, navigator: CustomNavigator) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val Bg = Color(0xFF141113)
    val Accent = Color(0xFFE27D60)
    val Card = Color(0xFF221A1E)
    val TextPrimary = Color(0xFFFBF8F5)
    val TextSub = Color(0xCCDDD2C8)

    val greeting = remember {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when { h < 12 -> "Lume"; h < 18 -> "Lume"; else -> "Lume" }
    }

    val chips = listOf("All Songs", "Recent", "Favorites", "Artists", "Albums", "Playlists", "Vectors", "Stats", "Audio")
    var selectedChip by remember { mutableStateOf("All Songs") }
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playlistToEditTitle by remember { mutableStateOf<Playlist?>(null) }
    var showAddSongsDialog by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val debouncer = rememberDebouncer()

    val activeTrackUri = viewModel.playbackQueue.getOrNull(viewModel.currentTrackIndex)?.uri

    LaunchedEffect(selectedPlaylistId) {
        if (selectedPlaylistId != null) {
            viewModel.loadPlaylistTracks(context, selectedPlaylistId!!)
        }
    }

    LaunchedEffect(selectedChip) {
        if (selectedChip == "Recent" && viewModel.recentTracks.isEmpty()) {
            viewModel.refreshRecentTracks()
        }
    }

    BackHandler(enabled = selectedArtist != null) { selectedArtist = null }
    BackHandler(enabled = selectedAlbum != null) { selectedAlbum = null }
    BackHandler(enabled = selectedPlaylistId != null) { selectedPlaylistId = null }
    BackHandler(enabled = isSearchActive) { isSearchActive = false; searchQuery = "" }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.addFolder(context, it) }
    }
    val songLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addSongs(context, uris)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSearchActive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = {
                        isSearchActive = false
                        searchQuery = ""
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = "Cancel search",
                            tint = TextPrimary
                        )
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        placeholder = { Text("Search songs, artists...", color = TextSub) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Card,
                            unfocusedContainerColor = Card,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Accent,
                            focusedPlaceholderColor = TextSub,
                            unfocusedPlaceholderColor = TextSub
                        ),
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear search",
                                        tint = TextSub
                                    )
                                }
                            }
                        }
                    )
                }
            } else if (selectedArtist != null || selectedAlbum != null || selectedPlaylistId != null) {
                val label = if (selectedPlaylistId != null) {
                    viewModel.customPlaylists.find { it.id == selectedPlaylistId }?.name ?: "Playlist"
                } else {
                    selectedArtist ?: selectedAlbum ?: ""
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(shape = RoundedCornerShape(20.dp), color = Card, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable {
                            selectedArtist = null; selectedAlbum = null; selectedPlaylistId = null
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_back),
                                contentDescription = null,
                                tint = TextPrimary
                            )
                        }
                    }
                    Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (selectedPlaylistId != null) {
                        val currentPlaylist = viewModel.customPlaylists.find { it.id == selectedPlaylistId }
                        val tracks = viewModel.selectedPlaylistTracks
                        if (currentPlaylist != null) {
                            IconButton(onClick = { showAddSongsDialog = true }, modifier = Modifier.size(40.dp)) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "Agregar", tint = Accent, modifier = Modifier.size(22.dp))
                            }
                            if (tracks.isNotEmpty()) {
                                Surface(shape = RoundedCornerShape(20.dp), color = Accent, modifier = Modifier.size(40.dp)) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable {
                                        viewModel.playTrack(tracks.first(), tracks)
                                        navigator.pushFade(Screen.Player)
                                    }) {
                                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                            IconButton(onClick = { playlistToEditTitle = currentPlaylist }, modifier = Modifier.size(40.dp)) {
                                Icon(imageVector = Icons.Default.Edit, contentDescription = "Editar", tint = TextPrimary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                viewModel.deletePlaylist(context, selectedPlaylistId!!)
                                selectedPlaylistId = null
                            }, modifier = Modifier.size(40.dp)) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Eliminar", tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            } else {
                Text(greeting, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { isSearchActive = true }) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = TextPrimary)
                    }
                    IconButton(onClick = { debouncer.guard("addSong") { songLauncher.launch(arrayOf("audio/*")) } }) {
                        Icon(painter = painterResource(id = R.drawable.ic_add_music), contentDescription = null, tint = TextPrimary)
                    }
                    IconButton(onClick = { debouncer.guard("addFolder") { folderLauncher.launch(null) } }) {
                        Icon(painter = painterResource(id = R.drawable.ic_folder), contentDescription = null, tint = TextPrimary)
                    }
                }
            }
        }

        val statusMessage = when {
            viewModel.isLoading -> "Loading ${viewModel.loadingProgress} songs..."
            viewModel.isSyncing -> "Syncing library..."
            else -> null
        }
        if (statusMessage != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                Text(statusMessage, fontSize = 14.sp, color = TextSub)
            }
        }

        if (selectedArtist == null && selectedAlbum == null && selectedPlaylistId == null && !isSearchActive) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chips) { label ->
                    val isSelected = label == selectedChip
                    val chipBgColor by animateColorAsState(
                        targetValue = if (isSelected) Accent else Card,
                        animationSpec = tween(durationMillis = 200),
                        label = "chip_bg"
                    )
                    val chipTextColor by animateColorAsState(
                        targetValue = if (isSelected) Color.White else TextSub,
                        animationSpec = tween(durationMillis = 200),
                        label = "chip_text"
                    )
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = chipBgColor,
                        border = if (!isSelected) BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)) else null,
                        modifier = Modifier.clickable { selectedChip = label }
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = chipTextColor
                        )
                    }
                }
            }
        }

        val screenState = ScreenState(
            chip = if (isSearchActive) "Search" else selectedChip,
            artist = selectedArtist,
            album = selectedAlbum,
            playlistId = selectedPlaylistId
        )

        AnimatedContent(
            targetState = screenState,
            transitionSpec = {
                fadeIn(animationSpec = tween(220)).togetherWith(
                    fadeOut(animationSpec = tween(180))
                )
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "library_content_transition"
        ) { state ->
            when {
                state.playlistId != null -> {
                    val tracks = viewModel.selectedPlaylistTracks
                    val stateList = rememberLazyListState()
                    TrackListWithFastScroll(
                        tracks = tracks,
                        listState = stateList,
                        activeTrackUri = activeTrackUri,
                        accent = Accent,
                        textPrimary = TextPrimary,
                        textSub = TextSub,
                        debouncer = debouncer,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, tracks)
                            navigator.pushFade(Screen.Player)
                        },
                        onFavoriteToggle = { viewModel.toggleFavorite(it) },
                        onDeleteClick = { track ->
                            viewModel.removeTrackFromPlaylist(context, state.playlistId, track.uri)
                        },
                        emptyMessage = "Sin canciones en la lista"
                    )
                }
                state.artist != null -> {
                    val filtered = viewModel.playlist.filter { it.artist == state.artist }
                    val stateList = rememberLazyListState()
                    TrackListWithFastScroll(
                        tracks = filtered,
                        listState = stateList,
                        activeTrackUri = activeTrackUri,
                        accent = Accent,
                        textPrimary = TextPrimary,
                        textSub = TextSub,
                        debouncer = debouncer,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, filtered)
                            navigator.pushFade(Screen.Player)
                        },
                        onFavoriteToggle = { viewModel.toggleFavorite(it) },
                        keySelector = { it.title }
                    )
                }
                state.album != null -> {
                    val filtered = viewModel.playlist.filter { it.album == state.album }
                    val stateList = rememberLazyListState()
                    TrackListWithFastScroll(
                        tracks = filtered,
                        listState = stateList,
                        activeTrackUri = activeTrackUri,
                        accent = Accent,
                        textPrimary = TextPrimary,
                        textSub = TextSub,
                        debouncer = debouncer,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, filtered)
                            navigator.pushFade(Screen.Player)
                        },
                        onFavoriteToggle = { viewModel.toggleFavorite(it) },
                        keySelector = { it.title }
                    )
                }
                state.chip == "Artists" -> {
                    val artists = viewModel.playlist.groupBy { it.artist }.entries.sortedBy { it.key }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp)
                    ) {
                        gridItems(artists, key = { it.key }) { (artistKey, tracks) ->
                            ArtistCard(artistKey, tracks.size, tracks.first(), Card, TextPrimary, TextSub) { selectedArtist = artistKey }
                        }
                    }
                }
                state.chip == "Albums" -> {
                    val albums = viewModel.playlist.groupBy { it.album }.entries.sortedBy { it.key }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp)
                    ) {
                        gridItems(albums, key = { it.key }) { (albumKey, tracks) ->
                            AlbumCard(albumKey, tracks.size, tracks.first(), Card, TextPrimary, TextSub) { selectedAlbum = albumKey }
                        }
                    }
                }
                state.chip == "Recent" -> {
                    val stateList = rememberLazyListState()
                    TrackListWithFastScroll(
                        tracks = viewModel.recentTracks,
                        listState = stateList,
                        activeTrackUri = activeTrackUri,
                        accent = Accent,
                        textPrimary = TextPrimary,
                        textSub = TextSub,
                        debouncer = debouncer,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, viewModel.recentTracks)
                            navigator.pushFade(Screen.Player)
                        },
                        onFavoriteToggle = { viewModel.toggleFavorite(it) },
                        emptyMessage = "No hay canciones agregadas recientemente",
                        showFastScroll = false
                    )
                }
                state.chip == "Favorites" -> {
                    val filtered = viewModel.playlist.filter { it.isFavorite }
                    val stateList = rememberLazyListState()
                    TrackListWithFastScroll(
                        tracks = filtered,
                        listState = stateList,
                        activeTrackUri = activeTrackUri,
                        accent = Accent,
                        textPrimary = TextPrimary,
                        textSub = TextSub,
                        debouncer = debouncer,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, filtered)
                            navigator.pushFade(Screen.Player)
                        },
                        onFavoriteToggle = { viewModel.toggleFavorite(it) },
                        emptyMessage = "No favorites yet"
                    )
                }
                state.chip == "Playlists" -> {
                    val playlists = viewModel.customPlaylists
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clickable { showCreatePlaylistDialog = true },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Card)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            tint = Accent,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Crear Lista",
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                        gridItems(playlists, key = { it.id }) { playlist ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clickable { selectedPlaylistId = playlist.id },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Card)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Text(
                                            text = playlist.name,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            fontSize = 16.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.align(Alignment.BottomStart),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_play),
                                            contentDescription = null,
                                            tint = Accent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        val songCount = viewModel.playlistTrackCounts[playlist.id] ?: 0
                                        Text(
                                            text = "$songCount cancion${if (songCount == 1) "" else "es"}",
                                            fontSize = 12.sp,
                                            color = TextSub
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                state.chip == "Search" -> {
                    val query = TextSanitizer.sanitize(searchQuery).lowercase()
                    val filtered = if (query.isEmpty()) {
                        viewModel.playlist
                    } else {
                        viewModel.playlist.filter { track ->
                            track.title.lowercase().contains(query) ||
                                    track.artist.lowercase().contains(query) ||
                                    track.album.lowercase().contains(query)
                        }
                    }
                    val stateList = rememberLazyListState()
                    TrackListWithFastScroll(
                        tracks = filtered,
                        listState = stateList,
                        activeTrackUri = activeTrackUri,
                        accent = Accent,
                        textPrimary = TextPrimary,
                        textSub = TextSub,
                        debouncer = debouncer,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, filtered)
                            navigator.pushFade(Screen.Player)
                        },
                        onFavoriteToggle = { viewModel.toggleFavorite(it) },
                        emptyMessage = "No se encontraron canciones"
                    )
                }
                state.chip == "Vectors" -> {
                    VectorScreen(viewModel = viewModel)
                }
                state.chip == "Stats" -> {
                    StatsScreen(viewModel = viewModel)
                }
                state.chip == "Audio" -> {
                    AudioProcessorScreen(viewModel = viewModel)
                }
                else -> {
                    TrackListWithFastScroll(
                        tracks = viewModel.playlist,
                        listState = listState,
                        activeTrackUri = activeTrackUri,
                        accent = Accent,
                        textPrimary = TextPrimary,
                        textSub = TextSub,
                        debouncer = debouncer,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, viewModel.playlist)
                            navigator.pushFade(Screen.Player)
                        },
                        onFavoriteToggle = { viewModel.toggleFavorite(it) },
                        emptyMessage = "No tracks in library"
                    )
                }
            }
        }

        if (viewModel.currentTrackIndex in viewModel.playbackQueue.indices) {
            val track = viewModel.playbackQueue[viewModel.currentTrackIndex]
            val coverRotation = remember { Animatable(0f) }
            LaunchedEffect(viewModel.isPlaying) {
                if (viewModel.isPlaying) {
                    while (true) {
                        coverRotation.animateTo(
                            targetValue = coverRotation.value + 360f,
                            animationSpec = tween(durationMillis = 8000, easing = LinearEasing)
                        )
                    }
                }
            }
            val progress = if (viewModel.duration > 0) {
                (viewModel.currentPosition.toFloat() / viewModel.duration.toFloat()).coerceIn(0f, 1f)
            } else 0f
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(bottom = 12.dp)
                    .align(Alignment.CenterHorizontally)
                    .clickable { debouncer.guard("openPlayer") { navigator.pushFade(Screen.Player) } },
                shape = RoundedCornerShape(20.dp),
                color = Card,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(50.dp)) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(12.dp))
                                .graphicsLayer { rotationZ = coverRotation.value }
                        ) {
                            TrackCover(track = track, modifier = Modifier.fillMaxSize())
                        }
                        Canvas(modifier = Modifier.size(50.dp)) {
                            val strokeWidthPx = 3.dp.toPx()
                            val radiusPx = (size.minDimension / 2) - strokeWidthPx / 2
                            val topLeft = Offset(
                                (size.width - radiusPx * 2) / 2,
                                (size.height - radiusPx * 2) / 2
                            )
                            val arcSize = Size(radiusPx * 2, radiusPx * 2)
                            drawArc(
                                color = Accent,
                                startAngle = -90f,
                                sweepAngle = 360f * progress,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, fontSize = 12.sp, color = TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { viewModel.previous() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_skip_previous),
                            contentDescription = null,
                            tint = TextPrimary
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = Color.White,
                        modifier = Modifier.size(42.dp),
                        shadowElevation = 4.dp
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable { viewModel.togglePlay() }) {
                            Icon(
                                painter = painterResource(
                                    id = if (viewModel.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                                ),
                                contentDescription = null,
                                tint = Color(0xFF1E1719),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.next() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_skip_next),
                            contentDescription = null,
                            tint = TextPrimary
                        )
                    }
                }
            }
        }

        if (showCreatePlaylistDialog) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreatePlaylistDialog = false },
                properties = DialogProperties(decorFitsSystemWindows = false),
                title = { Text("Nueva Lista de Reproducción", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Nombre de la lista", color = TextSub) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Card,
                            focusedContainerColor = Card,
                            unfocusedContainerColor = Card,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Accent,
                            focusedPlaceholderColor = TextSub,
                            unfocusedPlaceholderColor = TextSub
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (name.trim().isNotEmpty()) {
                                viewModel.createPlaylist(context, name.trim())
                                showCreatePlaylistDialog = false
                            }
                        }
                    ) {
                        Text("Crear", color = Accent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreatePlaylistDialog = false }) {
                        Text("Cancelar", color = TextSub)
                    }
                },
                containerColor = Bg
            )
        }

        if (playlistToEditTitle != null) {
            var name by remember { mutableStateOf(playlistToEditTitle!!.name) }
            AlertDialog(
                onDismissRequest = { playlistToEditTitle = null },
                properties = DialogProperties(decorFitsSystemWindows = false),
                title = { Text("Editar Nombre", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Nombre de la lista", color = TextSub) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Card,
                            focusedContainerColor = Card,
                            unfocusedContainerColor = Card,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Accent,
                            focusedPlaceholderColor = TextSub,
                            unfocusedPlaceholderColor = TextSub
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (name.trim().isNotEmpty()) {
                                viewModel.updatePlaylistName(context, playlistToEditTitle!!.id, name.trim())
                                playlistToEditTitle = null
                            }
                        }
                    ) {
                        Text("Guardar", color = Accent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { playlistToEditTitle = null }) {
                        Text("Cancelar", color = TextSub)
                    }
                },
                containerColor = Bg
            )
        }

        if (showAddSongsDialog && selectedPlaylistId != null) {
            var songQuery by remember { mutableStateOf("") }
            val availableSongs = remember(viewModel.playlist, viewModel.selectedPlaylistTracks) {
                viewModel.playlist.filter { song ->
                    viewModel.selectedPlaylistTracks.none { it.uri == song.uri }
                }
            }
            val filteredAvailable = remember(availableSongs, songQuery) {
                val q = songQuery.lowercase().trim()
                if (q.isEmpty()) availableSongs
                else availableSongs.filter {
                    it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
                }
            }
            val selectedSongs = remember { mutableStateListOf<String>() }

            AlertDialog(
                onDismissRequest = { showAddSongsDialog = false },
                properties = DialogProperties(decorFitsSystemWindows = false),
                title = { Text("Agregar Canciones", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = songQuery,
                            onValueChange = { songQuery = it },
                            placeholder = { Text("Buscar canción...", color = TextSub) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = Card,
                                focusedContainerColor = Card,
                                unfocusedContainerColor = Card,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = Accent,
                                focusedPlaceholderColor = TextSub,
                                unfocusedPlaceholderColor = TextSub
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (filteredAvailable.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No se encontraron canciones", color = TextSub, fontSize = 14.sp)
                            }
                        } else {
                            val scrollState = rememberLazyListState()
                            LazyColumn(
                                state = scrollState,
                                modifier = Modifier.fillMaxWidth().weight(1f).simpleVerticalScrollbar(scrollState),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(filteredAvailable) { track ->
                                    val isSelected = selectedSongs.contains(track.uri)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (isSelected) selectedSongs.remove(track.uri)
                                                else selectedSongs.add(track.uri)
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                if (checked == true) selectedSongs.add(track.uri)
                                                else selectedSongs.remove(track.uri)
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = Accent)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(track.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(track.artist, fontSize = 11.sp, color = TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (selectedSongs.isNotEmpty()) {
                                viewModel.addTracksToPlaylist(context, selectedPlaylistId!!, selectedSongs.toList())
                            }
                            showAddSongsDialog = false
                        }
                    ) {
                        Text("Agregar", color = Accent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddSongsDialog = false }) {
                        Text("Cancelar", color = TextSub)
                    }
                },
                containerColor = Bg
            )
        }
    }
}
