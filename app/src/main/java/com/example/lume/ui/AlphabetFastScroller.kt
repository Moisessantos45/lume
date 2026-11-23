package com.example.lume.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lume.Track
import com.example.lume.util.Debouncer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AlphabetFastScroller(
    alphabet: List<String>,
    currentLetter: String,
    letterToIndexMap: Map<String, Int>,
    onLetterSelected: (Int, String) -> Unit,
    accent: Color,
    textSub: Color,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var activeLetter by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(22.dp)
                .pointerInput(letterToIndexMap) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val idx = ((offset.y / size.height) * alphabet.size).toInt().coerceIn(0, alphabet.size - 1)
                            val letter = alphabet[idx]
                            activeLetter = letter
                            letterToIndexMap[letter]?.let { targetIdx ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onLetterSelected(targetIdx, letter)
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            activeLetter = null
                        },
                        onDragCancel = {
                            isDragging = false
                            activeLetter = null
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val idx = ((change.position.y / size.height) * alphabet.size).toInt().coerceIn(0, alphabet.size - 1)
                            val letter = alphabet[idx]
                            if (activeLetter != letter) {
                                activeLetter = letter
                                letterToIndexMap[letter]?.let { targetIdx ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onLetterSelected(targetIdx, letter)
                                }
                            }
                        }
                    )
                }
                .pointerInput(letterToIndexMap) {
                    detectTapGestures { offset ->
                        val idx = ((offset.y / size.height) * alphabet.size).toInt().coerceIn(0, alphabet.size - 1)
                        val letter = alphabet[idx]
                        activeLetter = letter
                        letterToIndexMap[letter]?.let { targetIdx ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onLetterSelected(targetIdx, letter)
                        }
                        scope.launch {
                            delay(400)
                            activeLetter = null
                        }
                    }
                },
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            alphabet.forEach { letter ->
                val isSelected = (activeLetter ?: currentLetter) == letter
                val hasItems = letterToIndexMap.containsKey(letter)
                Text(
                    text = letter,
                    fontSize = 9.sp,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                    color = when {
                        isSelected -> accent
                        hasItems -> textSub
                        else -> textSub.copy(alpha = 0.22f)
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = activeLetter != null,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-36).dp)
        ) {
            Surface(
                shape = CircleShape,
                color = accent,
                shadowElevation = 8.dp,
                modifier = Modifier.size(50.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = activeLetter ?: "",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

private fun extractLetter(text: String): String {
    val clean = text.trim()
    val firstChar = clean.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '#'
    return if (firstChar in 'A'..'Z') firstChar.toString() else "#"
}

@Composable
fun TrackListWithFastScroll(
    tracks: List<Track>,
    listState: LazyListState,
    activeTrackUri: String?,
    accent: Color,
    textPrimary: Color,
    textSub: Color,
    debouncer: Debouncer,
    onTrackSelect: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onDeleteClick: ((Track) -> Unit)? = null,
    emptyMessage: String = "Sin canciones",
    keySelector: (Track) -> String = {
        if (it.artist.isNotBlank() && !it.artist.equals("Unknown Artist", ignoreCase = true)) {
            it.artist
        } else {
            it.title
        }
    },
    modifier: Modifier = Modifier
) {
    if (tracks.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emptyMessage, fontSize = 14.sp, color = textSub)
        }
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val alphabet = remember { ('A'..'Z').map { it.toString() } + "#" }

    val letterToIndexMap = remember(tracks, keySelector) {
        val map = mutableMapOf<String, Int>()
        tracks.forEachIndexed { index, track ->
            val key = extractLetter(keySelector(track))
            if (!map.containsKey(key)) {
                map[key] = index
            }
        }
        map
    }

    val currentVisibleLetter = remember(listState.firstVisibleItemIndex, tracks, keySelector) {
        val track = tracks.getOrNull(listState.firstVisibleItemIndex)
        if (track != null) extractLetter(keySelector(track)) else "#"
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 24.dp),
            contentPadding = PaddingValues(bottom = 80.dp, top = 4.dp)
        ) {
            itemsIndexed(tracks) { index, track ->
                val isPlaying = track.uri == activeTrackUri
                TrackRow(
                    track = track,
                    isPlaying = isPlaying,
                    accent = accent,
                    textPrimary = textPrimary,
                    textSub = textSub,
                    onFavoriteToggle = { onFavoriteToggle(track) },
                    onDeleteClick = onDeleteClick?.let { { it(track) } }
                ) {
                    debouncer.guard("selectSong-${track.uri}") {
                        onTrackSelect(track)
                    }
                }
            }
        }

        AlphabetFastScroller(
            alphabet = alphabet,
            currentLetter = currentVisibleLetter,
            letterToIndexMap = letterToIndexMap,
            onLetterSelected = { targetIdx, _ ->
                coroutineScope.launch { listState.scrollToItem(targetIdx) }
            },
            accent = accent,
            textSub = textSub,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(top = 4.dp, bottom = 84.dp)
        )
    }
}
