package com.example.lume.ui

import android.net.Uri
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.lume.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TrackCover(
    modifier: Modifier = Modifier,
    track: Track
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(track.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(track.uri) {
        withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(track.uri))
                val artBytes = retriever.embeddedPicture
                if (artBytes != null) {
                    bitmap = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                }
                retriever.release()
            } catch (e: Exception) {
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.background(Color(0xFF282024)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = track.title.take(1).uppercase(),
                color = Color(0xFFE27D60),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

fun blendColors(color: Color, background: Color, ratio: Float): Color {
    val r = color.red * ratio + background.red * (1f - ratio)
    val g = color.green * ratio + background.green * (1f - ratio)
    val b = color.blue * ratio + background.blue * (1f - ratio)
    return Color(r, g, b)
}

data class ExtractedThemeColors(
    val primary: Color,
    val secondary: Color,
    val accent: Color
)

fun extractThemeColors(bitmap: android.graphics.Bitmap): ExtractedThemeColors {
    val sampleSize = 16
    val smallBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, false)
    val totalPixels = sampleSize * sampleSize
    val pixels = IntArray(totalPixels)
    smallBitmap.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)

    val hsv = FloatArray(3)
    var maxSat = -1f
    var mostVibrant = Color(0xFFC9A227)
    var rSum = 0f
    var gSum = 0f
    var bSum = 0f
    var validCount = 0

    val scoredColors = mutableListOf<Pair<Color, Float>>()

    for (pixel in pixels) {
        val r = android.graphics.Color.red(pixel)
        val g = android.graphics.Color.green(pixel)
        val b = android.graphics.Color.blue(pixel)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)

        val sat = hsv[1]
        val value = hsv[2]

        // Excluir casi negros o blancos extremos para el color primario
        if (value > 0.08f && value < 0.95f) {
            rSum += r / 255f
            gSum += g / 255f
            bSum += b / 255f
            validCount++
            scoredColors.add(Color(r / 255f, g / 255f, b / 255f) to (sat * 0.7f + value * 0.3f))
        }
        if (sat > maxSat && value > 0.25f) {
            maxSat = sat
            mostVibrant = Color(r / 255f, g / 255f, b / 255f)
        }
    }

    val primaryColor = if (validCount > 0) {
        Color(rSum / validCount, gSum / validCount, bSum / validCount)
    } else {
        Color(0xFF332228)
    }
    var maxDist = -1f
    var secondaryColor = primaryColor
    for ((color, _) in scoredColors) {
        val dr = color.red - primaryColor.red
        val dg = color.green - primaryColor.green
        val db = color.blue - primaryColor.blue
        val dist = dr * dr + dg * dg + db * db
        if (dist > maxDist) {
            maxDist = dist
            secondaryColor = color
        }
    }

    smallBitmap.recycle()

    // Si el acento encontrado es muy opaco o gris, iluminarlo ligeramente
    val accentFinal = if (maxSat < 0.2f) {
        blendColors(primaryColor, Color.White, 0.4f)
    } else {
        mostVibrant
    }

    return ExtractedThemeColors(
        primary = primaryColor,
        secondary = secondaryColor,
        accent = accentFinal
    )
}

fun extractColors(bitmap: android.graphics.Bitmap): Pair<Color, Color> {
    val theme = extractThemeColors(bitmap)
    return Pair(theme.primary, theme.secondary)
}
