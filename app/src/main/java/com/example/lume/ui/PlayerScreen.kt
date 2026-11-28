package com.example.lume.ui

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lume.*
import com.example.lume.R
import com.example.lume.data.LyricsEntity
import com.example.lume.data.MusicRepository
import com.example.lume.util.SongIdGenerator
import com.example.lume.util.rememberDebouncer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatTime(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / (1000 * 60)) % 60
    return String.format("%02d:%02d", min, sec)
}

@Composable
fun PlayingEqualizerBars(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "eq_bars")
    val h1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "eq1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.30f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "eq2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "eq3"
    )

    Row(
        modifier = modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val h1Val = if (isPlaying) h1 else 0.4f
        val h2Val = if (isPlaying) h2 else 0.7f
        val h3Val = if (isPlaying) h3 else 0.5f

        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight(h1Val)
                .clip(RoundedCornerShape(1.5.dp))
                .background(color)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight(h2Val)
                .clip(RoundedCornerShape(1.5.dp))
                .background(color)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight(h3Val)
                .clip(RoundedCornerShape(1.5.dp))
                .background(color)
        )
    }
}

private fun ensureVisibleAccent(color: Color, minLuminance: Float = 0.52f): Color {
    val lum = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (lum < minLuminance) {
        val factor = (minLuminance - lum) / (1f - lum).coerceAtLeast(0.01f)
        Color(
            red = (color.red + (1f - color.red) * factor).coerceIn(0f, 1f),
            green = (color.green + (1f - color.green) * factor).coerceIn(0f, 1f),
            blue = (color.blue + (1f - color.blue) * factor).coerceIn(0f, 1f),
            alpha = 1f
        )
    } else {
        color
    }
}

@Composable
private fun ModernCapsuleProgressBar(
    currentPosition: Long,
    duration: Long,
    accentColor: Color,
    trackColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragRatio by remember { mutableFloatStateOf(0f) }

    val actualRatio = if (duration > 0) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val currentRatio = if (isDragging) dragRatio else actualRatio
    val activeColor = remember(accentColor) { ensureVisibleAccent(accentColor) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (duration > 0) {
                        val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek((ratio * duration).toLong())
                    }
                }
            }
            .pointerInput(duration) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragRatio = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        if (duration > 0) {
                            onSeek((dragRatio * duration).toLong())
                        }
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragRatio = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val totalWidth = maxWidth
        val capsuleHeight = 13.dp
        val cornerShape = RoundedCornerShape(capsuleHeight / 2)

        // Pista de fondo estilo Instagram / Spotify con borde blanco de alto contraste
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(capsuleHeight)
                .clip(cornerShape)
                .background(Color.White.copy(alpha = 0.28f))
                .border(1.dp, Color.White.copy(alpha = 0.40f), cornerShape)
        )

        // Barra de progreso activa en BLANCO puro de alto contraste (estilo Instagram)
        val filledWidth = totalWidth * currentRatio
        if (filledWidth > 0.dp) {
            Box(
                modifier = Modifier
                    .width(filledWidth)
                    .height(capsuleHeight)
                    .clip(cornerShape)
                    .background(Color.White)
            )
        }

        // Indicador / Thumb blanco con sombra de contraste
        val thumbWidth = if (isDragging) 6.5.dp else 5.dp
        val thumbHeight = if (isDragging) 30.dp else 26.dp
        val maxOffset = if (totalWidth > thumbWidth) totalWidth - thumbWidth else 0.dp
        val thumbOffset = maxOffset * currentRatio
        val thumbShape = RoundedCornerShape(3.dp)

        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(thumbWidth)
                .height(thumbHeight)
                .clip(thumbShape)
                .background(Color.White)
                .border(1.dp, Color.Black.copy(alpha = 0.35f), thumbShape)
        )
    }
}
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)

@Composable
fun SpeedSelector(
    currentSpeed: Float,
    accent: Color,
    textPrimary: Color,
    onSpeedSelected: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SPEED_OPTIONS.forEach { speed ->
            val isActive = currentSpeed == speed
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isActive) accent else Color.White.copy(alpha = 0.12f),
                modifier = Modifier
                    .clickable { onSpeedSelected(speed) }
                    .height(28.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 10.dp)
                ) {
                    Text(
                        text = if (speed == speed.toLong().toFloat()) "${speed.toLong()}×"
                               else "${speed}×",
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        color = if (isActive) Color.White else textPrimary.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}
private data class LrcLine(val startMs: Long, val text: String)

private fun parseSyncedLyrics(lrc: String): List<LrcLine> {
    if (lrc.isBlank()) return emptyList()
    val regex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\](.*)""")
    return lrc.lines()
        .mapNotNull { rawLine ->
            val trimmed = rawLine.trim()
            val match = regex.find(trimmed) ?: return@mapNotNull null
            val (min, sec, cs, text) = match.destructured
            val csVal = when (cs.length) {
                1 -> cs.toLong() * 100
                2 -> cs.toLong() * 10
                3 -> cs.toLong()
                else -> 0L
            }
            val ms = min.toLong() * 60_000L + sec.toLong() * 1_000L + csVal
            val cleanText = text.trim()
            if (cleanText.isEmpty()) null else LrcLine(ms, cleanText)
        }
        .sortedBy { it.startMs }
}
@Composable
fun LyricsView(
    lyrics: LyricsEntity?,
    currentPositionMs: Long,
    accent: Color,
    textPrimary: Color,
    textSub: Color,
    onSeek: ((Long) -> Unit)? = null
) {
    if (lyrics == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "🎵 Sin letra disponible\npara esta canción",
                fontSize = 16.sp,
                color = textSub,
                textAlign = TextAlign.Center,
                lineHeight = 26.sp
            )
        }
        return
    }

    if (lyrics.instrumental) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "🎶 Canción instrumental",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = textSub,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val syncedLines = remember(lyrics.syncedLyrics) {
        parseSyncedLyrics(lyrics.syncedLyrics)
    }

    if (syncedLines.isNotEmpty()) {
        val currentLineIndex = remember(currentPositionMs, syncedLines) {
            if (currentPositionMs < syncedLines.first().startMs) {
                -1
            } else {
                syncedLines.indexOfLast { it.startMs <= currentPositionMs }
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(currentLineIndex) {
            if (currentLineIndex >= 0) {
                listState.animateScrollToItem(
                    index = (currentLineIndex - 2).coerceAtLeast(0)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(
                items = syncedLines,
                key = { idx, line -> "${line.startMs}_$idx" }
            ) { idx, line ->
                val isActive = idx == currentLineIndex
                Text(
                    text = line.text,
                    fontSize = if (isActive) 23.sp else 17.sp,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.50f),
                    textAlign = TextAlign.Center,
                    lineHeight = 32.sp,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.70f),
                            offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                            blurRadius = 6f
                        )
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isActive) {
                                Modifier
                                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                            } else Modifier
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSeek?.invoke(line.startMs) }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .graphicsLayer {
                            scaleX = if (isActive) 1.05f else 1f
                            scaleY = if (isActive) 1.05f else 1f
                        }
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 24.dp, horizontal = 16.dp)
        ) {
            item {
                Text(
                    text = lyrics.plainLyrics,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    lineHeight = 32.sp,
                    textAlign = TextAlign.Center,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.70f),
                            offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                            blurRadius = 6f
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
private fun loadTrackInfo(context: Context, track: Track): List<Pair<String, String>> {
    val r = android.media.MediaMetadataRetriever()
    var album: String? = track.album.takeIf { it.isNotBlank() && it != "Unknown Album" }
    var genre: String? = track.genre?.takeIf { it.isNotBlank() }
    var duration: Long = track.durationMs
    var year: String? = track.year?.toString()
    var composer: String? = track.composer?.takeIf { it.isNotBlank() }
    var bitrate: String? = null
    try {
        r.setDataSource(context, Uri.parse(track.uri))
        if (album == null) album = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
        if (genre == null) genre = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)
        if (duration <= 0L) duration = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (year == null) {
            year = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)
        }
        if (composer == null) composer = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_COMPOSER)
        bitrate = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
            ?.toLongOrNull()?.let { "${it / 1000} kbps" }
    } catch (_: Exception) {
    } finally {
        try { r.release() } catch (_: Exception) {}
    }
    val plays = try {
        context.getSharedPreferences("lume_stats_prefs", Context.MODE_PRIVATE)
            .getString("song_play_counts", null)?.let { org.json.JSONObject(it).optInt(track.uri, 0) } ?: 0
    } catch (_: Exception) { 0 }
    val dash = "—"
    return listOf(
        "Álbum" to (album ?: dash),
        "Género" to (genre ?: dash),
        "Año" to (year ?: dash),
        "Duración" to if (duration > 0) formatTime(duration) else dash,
        "Compositor" to (composer ?: dash),
        "Bitrate" to (bitrate ?: dash),
        "Reproducciones" to plays.toString()
    )
}
@Composable
private fun FlipCover(
    track: Track,
    shape: androidx.compose.ui.graphics.Shape,
    accent: Color,
    textPrimary: Color,
    textSub: Color,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = 20.dp
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var flipped by remember(track.uri) { mutableStateOf(false) }
    var info by remember(track.uri) { mutableStateOf<List<Pair<String, String>>?>(null) }

    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(durationMillis = 700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "cover_flip"
    )

    LaunchedEffect(flipped, track.uri) {
        if (flipped && info == null) {
            info = withContext(Dispatchers.IO) { loadTrackInfo(context, track) }
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 16f * density
                // Pequeño "levantamiento" a mitad del giro para dar profundidad
                val lift = 1f - 0.05f * kotlin.math.sin(Math.toRadians(rotation.toDouble())).toFloat()
                scaleX = lift
                scaleY = lift
            }
            .clip(shape)
            .pointerInput(track.uri) {
                detectTapGestures(
                    onLongPress = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        flipped = !flipped
                    },
                    onTap = { if (flipped) flipped = false }
                )
            }
    ) {
        if (rotation <= 90f) {
            TrackCover(track = track, modifier = Modifier.fillMaxSize())
        } else {
            // Cara trasera: se contra-rota para que el texto no se vea espejado
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color(0xFF1E1E24), Color(0xFF121216))
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.15f), shape),
                contentAlignment = Alignment.Center
            ) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        track.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                    )
                    Text(
                        track.artist, fontSize = 12.sp, color = accent, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                    )
                    Box(Modifier.fillMaxWidth(0.40f).height(1.5.dp).clip(CircleShape).background(accent.copy(alpha = 0.7f)))
                    val rows = info
                    if (rows == null) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = accent)
                    } else {
                        rows.forEach { (label, value) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.60f), fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    value, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Toca para volver",
                        fontSize = 9.5.sp,
                        color = Color.White.copy(alpha = 0.40f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(viewModel: PlayerViewModel, navigator: CustomNavigator) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("lume_ui_prefs", Context.MODE_PRIVATE) }
    var useAltLayout by remember { mutableStateOf(prefs.getBoolean("player_alt_layout", false)) }
    var showLyrics by remember { mutableStateOf(false) }
    BackHandler(enabled = navigator.currentScreen == Screen.Player) { navigator.pop() }
    if (viewModel.currentTrackIndex !in viewModel.playbackQueue.indices) {
        navigator.pushReplacementFade(Screen.Library); return
    }
    val track = viewModel.playbackQueue[viewModel.currentTrackIndex]
    var lyricsData by remember(track.uri) { mutableStateOf<LyricsEntity?>(null) }
    var lyricsLoaded by remember(track.uri) { mutableStateOf(false) }

    var themeColors by remember(track.uri) { mutableStateOf<ExtractedThemeColors?>(null) }

    LaunchedEffect(track.uri) {
        withContext(Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(track.uri))
                val artBytes = retriever.embeddedPicture
                if (artBytes != null) {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                    if (bmp != null) {
                        val colors = extractThemeColors(bmp)
                        withContext(Dispatchers.Main) {
                            themeColors = colors
                        }
                    }
                }
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    val defaultBg = Color(0xFF1E1719)
    val defaultPrimary = Color(0xFF382329)
    val defaultSecondary = Color(0xFF24181B)
    val defaultAccent = Color(0xFFE27D60)

    val animatedBg by animateColorAsState(
        targetValue = themeColors?.let { blendColors(it.primary, Color(0xFF0F0C0E), 0.45f) } ?: defaultBg,
        animationSpec = tween(durationMillis = 800),
        label = "bg_color_anim"
    )
    val animatedPrimary by animateColorAsState(
        targetValue = themeColors?.primary ?: defaultPrimary,
        animationSpec = tween(durationMillis = 800),
        label = "primary_color_anim"
    )
    val animatedSecondary by animateColorAsState(
        targetValue = themeColors?.secondary ?: defaultSecondary,
        animationSpec = tween(durationMillis = 800),
        label = "secondary_color_anim"
    )
    val animatedAccent by animateColorAsState(
        targetValue = themeColors?.accent ?: defaultAccent,
        animationSpec = tween(durationMillis = 800),
        label = "accent_color_anim"
    )

    val Bg = animatedBg
    val Accent = animatedAccent
    val TextPrimary = Color(0xFFFBF8F5)
    val TextSub = Color(0xCCDDD2C8)

    var dragAmountX by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }
    val debouncer = rememberDebouncer()
    LaunchedEffect(track.uri, track.artist, track.title) {
        lyricsLoaded = false
        lyricsData = null
        withContext(Dispatchers.IO) {
            val repo = MusicRepository.get(context)
            val primaryId = SongIdGenerator.generate(track.artist, track.title)
            var result = repo.getLyrics(primaryId)

            // 2. Si no encuentra, intentar quitando texto entre paréntesis/corchetes (ej. "Careless Whisper (Remastered)" -> "Careless Whisper")
            if (result == null) {
                val cleanTitleNoParens = track.title.replace(Regex("""\s*[\(\[\{].*?[\)\]\}]"""), "").trim()
                if (cleanTitleNoParens.isNotBlank() && cleanTitleNoParens != track.title) {
                    val altId = SongIdGenerator.generate(track.artist, cleanTitleNoParens)
                    result = repo.getLyrics(altId)
                }
            }

            // 3. Si no encuentra, intentar quitando prefijo numérico de pista (ej. "01 - Unity", "01. Unity")
            if (result == null) {
                val cleanTitle = track.title.replace(Regex("^[0-9]+[.\\s-]+"), "").trim()
                if (cleanTitle.isNotBlank() && cleanTitle != track.title) {
                    val altId = SongIdGenerator.generate(track.artist, cleanTitle)
                    result = repo.getLyrics(altId)
                }
            }

            // 4. Si el artista es desconocido o aún no encuentra, intentar extraer "Artista - Canción" del título o del nombre del archivo
            if (result == null) {
                val candidatesToParse = mutableListOf<String>()
                if (track.artist.isBlank() || track.artist.equals("Unknown Artist", ignoreCase = true)) {
                    candidatesToParse.add(track.title)
                }
                try {
                    val fileName = Uri.parse(track.uri).lastPathSegment?.substringAfterLast("/") ?: ""
                    if (fileName.isNotBlank()) {
                        candidatesToParse.add(fileName.substringBeforeLast("."))
                    }
                } catch (_: Exception) {}

                val separators = listOf(" - ", " – ", " — ", " _ ", "-", "_")
                for (cand in candidatesToParse) {
                    if (result != null) break
                    for (sep in separators) {
                        val parts = cand.split(sep, limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            val altId = SongIdGenerator.generate(parts[0].trim(), parts[1].trim())
                            result = repo.getLyrics(altId)
                            if (result != null) break
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                lyricsData = result
                lyricsLoaded = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        val isHorizontal = kotlin.math.abs(dragAmountX) > kotlin.math.abs(dragOffsetY.value)
                        if (isHorizontal) {
                            if (dragAmountX > 100f) {
                                viewModel.previous()
                            } else if (dragAmountX < -100f) {
                                viewModel.next()
                            }
                            coroutineScope.launch { dragOffsetY.animateTo(0f) }
                        } else {
                            if (dragOffsetY.value > 300f) {
                                navigator.pop()
                            } else {
                                coroutineScope.launch { dragOffsetY.animateTo(0f) }
                            }
                        }
                        dragAmountX = 0f
                    },
                    onDragCancel = {
                        coroutineScope.launch { dragOffsetY.animateTo(0f) }
                        dragAmountX = 0f
                    },
                    onDrag = { change, dragAmountDelta ->
                        change.consume()
                        dragAmountX += dragAmountDelta.x
                        if (dragAmountDelta.y > 0f || dragOffsetY.value > 0f) {
                            coroutineScope.launch {
                                dragOffsetY.snapTo(maxOf(0f, dragOffsetY.value + dragAmountDelta.y))
                            }
                        }
                    }
                )
            }
    ) {
        val blendedColor1 = blendColors(animatedPrimary, animatedBg, 0.45f)
        val blendedColor2 = blendColors(animatedSecondary, animatedBg, 0.35f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = (1f - (dragOffsetY.value / 350f)).coerceIn(0f, 1f)
                }
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            blendedColor1,
                            blendedColor2,
                            animatedBg
                        )
                    )
                )
        )
        val currentLyricLine = remember(lyricsData, viewModel.currentPosition) {
            lyricsData?.syncedLyrics?.let { lrc ->
                val lines = parseSyncedLyrics(lrc)
                lines.lastOrNull { it.startMs <= viewModel.currentPosition }?.text
            }
        }
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = showLyrics,
                        label = "landscape_cover_lyrics"
                    ) { isLyrics ->
                        if (isLyrics) {
                            LyricsView(
                                lyrics = if (lyricsLoaded) lyricsData else null,
                                currentPositionMs = viewModel.currentPosition,
                                accent = Accent,
                                textPrimary = TextPrimary,
                                textSub = TextSub,
                                onSeek = { viewModel.seekTo(it) }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight(0.88f)
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color.Black.copy(alpha = 0.25f))
                            ) {
                                FlipCover(
                                    track = track,
                                    shape = RoundedCornerShape(22.dp),
                                    accent = Accent,
                                    textPrimary = TextPrimary,
                                    textSub = TextSub,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (!currentLyricLine.isNullOrBlank()) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.65f),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 10.dp, start = 8.dp, end = 8.dp)
                                    ) {
                                        Text(
                                            text = currentLyricLine,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable { debouncer.guard("pop") { navigator.pop() } }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_chevron_down),
                                    contentDescription = null,
                                    tint = TextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "REPRODUCIENDO",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSub.copy(alpha = 0.7f),
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "${viewModel.currentTrackIndex + 1} de ${viewModel.playbackQueue.size}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = CircleShape,
                                color = if (showLyrics) Accent else Color.White.copy(alpha = 0.12f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable {
                                    debouncer.guard("lyricsToggle") { showLyrics = !showLyrics }
                                }) {
                                    Text(text = "🎵", fontSize = 14.sp)
                                }
                            }
                            Surface(
                                shape = CircleShape,
                                color = if (useAltLayout) Accent else Color.White.copy(alpha = 0.12f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable {
                                    debouncer.guard("layoutSwitch") {
                                        useAltLayout = !useAltLayout
                                        prefs.edit().putBoolean("player_alt_layout", useAltLayout).apply()
                                    }
                                }) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_layout_switch),
                                        contentDescription = null,
                                        tint = if (useAltLayout) Color.White else TextPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = track.title,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, velocity = 40.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${track.artist}  •  ${track.album.ifBlank { "Video" }}",
                                fontSize = 12.sp,
                                color = TextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PlayingEqualizerBars(
                                isPlaying = viewModel.isPlaying,
                                color = Accent,
                                modifier = Modifier.padding(end = 4.dp)
                            )

                            IconButton(
                                onClick = { debouncer.guard("favorite-${track.uri}") { viewModel.toggleFavorite(track) } }
                            ) {
                                Icon(
                                    imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (track.isFavorite) Color(0xFFFF4081) else TextSub.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ModernCapsuleProgressBar(
                            currentPosition = viewModel.currentPosition,
                            duration = viewModel.duration,
                            accentColor = Accent,
                            trackColor = Color.White.copy(alpha = 0.16f),
                            onSeek = { viewModel.seekTo(it) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                formatTime(viewModel.currentPosition),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSub
                            )
                            Text(
                                formatTime(viewModel.duration),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSub
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { debouncer.guard("shuffle") { viewModel.isShuffleEnabled = !viewModel.isShuffleEnabled } }
                        ) {
                            val isShuffleActive = viewModel.isShuffleEnabled
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .then(
                                        if (isShuffleActive) {
                                            Modifier
                                                .background(Color.White.copy(alpha = 0.18f), CircleShape)
                                                .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                                        } else Modifier
                                    )
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_shuffle),
                                    contentDescription = null,
                                    tint = if (isShuffleActive) Color.White else Color.White.copy(alpha = 0.38f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.previous() },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_skip_previous),
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = Color.White,
                            modifier = Modifier.size(56.dp),
                            shadowElevation = 6.dp
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.clickable { viewModel.togglePlay() }
                            ) {
                                Icon(
                                    painter = painterResource(id = if (viewModel.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                                    contentDescription = null,
                                    tint = Color(0xFF1E1719),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.next() },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_skip_next),
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.toggleRepeatMode() }
                        ) {
                            val isRepeatActive = viewModel.repeatMode != RepeatMode.OFF
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .then(
                                        if (isRepeatActive) {
                                            Modifier
                                                .background(Color.White.copy(alpha = 0.18f), CircleShape)
                                                .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                                        } else Modifier
                                    )
                            ) {
                                Icon(
                                    painter = painterResource(
                                        id = if (viewModel.repeatMode == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
                                    ),
                                    contentDescription = null,
                                    tint = if (isRepeatActive) Color.White else Color.White.copy(alpha = 0.38f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .offset { IntOffset(0, dragOffsetY.value.toInt()) }
                    .graphicsLayer {
                        val scaleVal = (1f - (dragOffsetY.value / 3000f)).coerceIn(0.85f, 1f)
                        this.scaleX = scaleVal
                        this.scaleY = scaleVal
                        this.alpha = (1f - (dragOffsetY.value / 600f)).coerceIn(0f, 1f)
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable { debouncer.guard("pop") { navigator.pop() } }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_chevron_down),
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "REPRODUCIENDO",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSub.copy(alpha = 0.7f),
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = "${viewModel.currentTrackIndex + 1} de ${viewModel.playbackQueue.size}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = CircleShape,
                            color = if (showLyrics) Accent else Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable {
                                debouncer.guard("lyricsToggle") { showLyrics = !showLyrics }
                            }) {
                                Text(
                                    text = "🎵",
                                    fontSize = 15.sp
                                )
                            }
                        }
                        Surface(
                            shape = CircleShape,
                            color = if (useAltLayout) Accent else Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable {
                                debouncer.guard("layoutSwitch") {
                                    useAltLayout = !useAltLayout
                                    prefs.edit().putBoolean("player_alt_layout", useAltLayout).apply()
                                }
                            }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_layout_switch),
                                    contentDescription = null,
                                    tint = if (useAltLayout) Color.White else TextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    label = "lyrics_toggle_view",
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) { isLyrics ->
                    if (isLyrics) {
                        LyricsView(
                            lyrics = if (lyricsLoaded) lyricsData else null,
                            currentPositionMs = viewModel.currentPosition,
                            accent = Accent,
                            textPrimary = TextPrimary,
                            textSub = TextSub,
                            onSeek = { viewModel.seekTo(it) }
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!useAltLayout) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(26.dp))
                                        .background(Color.Black.copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    FlipCover(
                                        track = track,
                                        shape = RoundedCornerShape(26.dp),
                                        accent = Accent,
                                        textPrimary = TextPrimary,
                                        textSub = TextSub,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (!currentLyricLine.isNullOrBlank()) {
                                        Surface(
                                            color = Color.Black.copy(alpha = 0.65f),
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(bottom = 12.dp, start = 14.dp, end = 14.dp)
                                        ) {
                                            Text(
                                                text = currentLyricLine,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            } else {
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .fillMaxWidth(0.88f)
                                        .aspectRatio(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val boxSize = maxWidth
                                    val blobColor = animatedPrimary.copy(alpha = 0.40f)
                                    Box(modifier = Modifier.size(boxSize), contentAlignment = Alignment.Center) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            val cx = size.width / 2f
                                            val cy = size.height / 2f
                                            val r = size.minDimension / 2f
                                            val path = androidx.compose.ui.graphics.Path().apply {
                                                moveTo(cx, cy - r)
                                                cubicTo(cx + r * 0.9f, cy - r * 0.6f, cx + r * 1.1f, cy + r * 0.3f, cx + r * 0.4f, cy + r * 0.9f)
                                                cubicTo(cx, cy + r * 1.1f, cx - r * 0.8f, cy + r * 0.8f, cx - r * 0.9f, cy + r * 0.1f)
                                                cubicTo(cx - r * 1.1f, cy - r * 0.5f, cx - r * 0.4f, cy - r * 1.0f, cx, cy - r)
                                                close()
                                            }
                                            drawPath(path, color = blobColor)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(boxSize * 0.90f)
                                                .clip(CircleShape)
                                        ) {
                                            FlipCover(
                                                track = track,
                                                shape = CircleShape,
                                                accent = Accent,
                                                textPrimary = TextPrimary,
                                                textSub = TextSub,
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = 36.dp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = track.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, velocity = 40.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${track.artist}  •  ${track.album.ifBlank { "Video" }}",
                            fontSize = 13.sp,
                            color = TextSub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PlayingEqualizerBars(
                            isPlaying = viewModel.isPlaying,
                            color = Accent,
                            modifier = Modifier.padding(end = 4.dp)
                        )

                        IconButton(
                            onClick = { debouncer.guard("favorite-${track.uri}") { viewModel.toggleFavorite(track) } }
                        ) {
                            Icon(
                                imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                tint = if (track.isFavorite) Color(0xFFFF4081) else TextSub.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                ModernCapsuleProgressBar(
                    currentPosition = viewModel.currentPosition,
                    duration = viewModel.duration,
                    accentColor = Accent,
                    trackColor = Color.White.copy(alpha = 0.16f),
                    onSeek = { viewModel.seekTo(it) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatTime(viewModel.currentPosition),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSub
                    )
                    Text(
                        formatTime(viewModel.duration),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSub
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { debouncer.guard("shuffle") { viewModel.isShuffleEnabled = !viewModel.isShuffleEnabled } }
                    ) {
                        val isShuffleActive = viewModel.isShuffleEnabled
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .then(
                                    if (isShuffleActive) {
                                        Modifier
                                            .background(Color.White.copy(alpha = 0.18f), CircleShape)
                                            .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                                    } else Modifier
                                )
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_shuffle),
                                contentDescription = null,
                                tint = if (isShuffleActive) Color.White else Color.White.copy(alpha = 0.38f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.previous() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_skip_previous),
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = Color.White,
                        modifier = Modifier.size(66.dp),
                        shadowElevation = 8.dp
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.clickable { viewModel.togglePlay() }
                        ) {
                            Icon(
                                painter = painterResource(id = if (viewModel.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                                contentDescription = null,
                                tint = Color(0xFF1E1719),
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.next() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_skip_next),
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.toggleRepeatMode() }
                    ) {
                        val isRepeatActive = viewModel.repeatMode != RepeatMode.OFF
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .then(
                                    if (isRepeatActive) {
                                        Modifier
                                            .background(Color.White.copy(alpha = 0.18f), CircleShape)
                                            .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                                    } else Modifier
                                )
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (viewModel.repeatMode == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
                                ),
                                contentDescription = null,
                                tint = if (isRepeatActive) Color.White else Color.White.copy(alpha = 0.38f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(26.dp))

                SpeedSelector(
                    currentSpeed = viewModel.playbackSpeed,
                    accent = Accent,
                    textPrimary = TextPrimary,
                    onSpeedSelected = { viewModel.changePlaybackSpeed(it) }
                )

                Spacer(modifier = Modifier.height(10.dp))
            }
        }

    }
}
