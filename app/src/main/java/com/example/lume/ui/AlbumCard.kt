package com.example.lume.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lume.Track

@Composable
fun AlbumCard(album: String, songCount: Int, sampleTrack: Track, card: Color, textPrimary: Color, textSub: Color, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = card, modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                contentAlignment = Alignment.Center
            ) {
                TrackCover(track = sampleTrack, modifier = Modifier.fillMaxSize())
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(album, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$songCount songs", fontSize = 12.sp, color = textSub)
            }
        }
    }
}
