package com.example.lume.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lume.Track

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

@Composable
fun TrackRow(
    track: Track,
    isPlaying: Boolean,
    accent: Color,
    textPrimary: Color,
    textSub: Color,
    onFavoriteToggle: () -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        color = if (isPlaying) accent.copy(alpha = 0.12f) else Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.20f))
            ) {
                TrackCover(track = track, modifier = Modifier.fillMaxSize())
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.40f)),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayingEqualizerBars(
                            isPlaying = true,
                            color = accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontSize = 15.sp,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isPlaying) accent else textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                val subtitleText = remember(track.artist, track.album) {
                    val artistClean = if (track.artist.isBlank() || track.artist.equals("Unknown Artist", ignoreCase = true)) {
                        ""
                    } else {
                        track.artist.trim()
                    }
                    val albumClean = if (track.album.isBlank() || track.album.equals("Unknown Album", ignoreCase = true)) {
                        ""
                    } else {
                        track.album.trim()
                    }
                    when {
                        artistClean.isNotEmpty() && albumClean.isNotEmpty() -> "$artistClean  •  $albumClean"
                        artistClean.isNotEmpty() -> artistClean
                        albumClean.isNotEmpty() -> albumClean
                        else -> "Artista desconocido"
                    }
                }
                Text(
                    text = subtitleText,
                    fontSize = 12.sp,
                    color = textSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (track.durationMs > 0L) {
                Text(
                    text = formatDuration(track.durationMs),
                    fontSize = 12.sp,
                    color = textSub,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }

            if (onDeleteClick != null) {
                IconButton(
                    onClick = { onDeleteClick() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove from playlist",
                        tint = textSub,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = { onFavoriteToggle() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (track.isFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (track.isFavorite) Color(0xFFFF4081) else textSub.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
