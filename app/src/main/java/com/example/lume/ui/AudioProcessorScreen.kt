package com.example.lume.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.JsonWriter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.lume.PlayerViewModel
import com.example.lume.services.FingerprintExport
import com.example.lume.util.AudioFingerprinter
import com.example.lume.util.Fingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private val AccentCoral = Color(0xFFE27D60)
private val DarkCard = Color(0xFF221A1E)
private val DarkCardBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
private val TextPrimary = Color(0xFFFBF8F5)
private val TextSub = Color(0xCCDDD2C8)
private const val MIC_SECONDS = 10

private fun JsonWriter.writeFingerprints(fps: List<Fingerprint>) {
    name("huellas").beginArray()
    for (f in fps) {
        beginObject().name("hash").value(f.hash).name("offset_ms").value(f.offsetMs).endObject()
    }
    endArray()
}

private fun timestamp(): String =
    java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())

@Composable
fun AudioProcessorScreen(viewModel: PlayerViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val exp by FingerprintExport.state.collectAsState()
    val libRunning = exp.running
    val libDone = exp.done
    val libTotal = exp.total
    val libCurrent = exp.current
    LaunchedEffect(exp.message) {
        exp.message?.takeIf { it.isNotEmpty() }?.let { snackbar.showSnackbar(it) }
    }
    var micJob by remember { mutableStateOf<Job?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var micProgress by remember { mutableFloatStateOf(0f) }
    var micElapsed by remember { mutableIntStateOf(0) }
    var micRecordedDurationMs by remember { mutableLongStateOf(0L) }
    var micStatus by remember { mutableStateOf("") }
    var micResult by remember { mutableStateOf<List<Fingerprint>?>(null) }

    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }
    val libLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        FingerprintExport.start(context, viewModel.playlist.toList(), uri)
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val fps = micResult
        if (uri == null || fps == null) return@rememberLauncherForActivityResult
        val actualDurationMs = if (micRecordedDurationMs > 0) micRecordedDurationMs else (MIC_SECONDS * 1000L)
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { bw ->
                    val w = JsonWriter(bw)
                    w.beginObject()
                    w.name("metadata").beginObject()
                        .name("titulo").value("grabacion_mic")
                        .name("artista").value("")
                        .name("duracion_ms").value(actualDurationMs)
                        .name("sample_rate").value(AudioFingerprinter.TARGET_RATE.toLong())
                        .endObject()
                    w.writeFingerprints(fps)
                    w.endObject()
                    w.flush()
                }
                toast("✅ Archivo JSON de consulta exportado correctamente")
            } catch (e: Exception) {
                toast("❌ Error al guardar: ${e.localizedMessage}")
            }
        }
    }

    fun startRecording() {
        micResult = null
        micElapsed = 0
        micProgress = 0f
        micRecordedDurationMs = 0L
        micStatus = "Iniciando grabación..."
        stopRequested = false
        isRecording = true

        micJob = scope.launch(Dispatchers.IO) {
            val rate = 44100
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            var rec: AudioRecord? = null
            try {
                @Suppress("MissingPermission")
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate)
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("No se pudo inicializar el micrófono")
                val fp = AudioFingerprinter(rate, 1)
                val buf = ShortArray(4096)
                var total = 0L
                val maxSamples = rate.toLong() * MIC_SECONDS

                rec.startRecording()
                withContext(Dispatchers.Main) {
                    micStatus = "Grabando audio..."
                }

                while (coroutineContext.isActive && !stopRequested && total < maxSamples) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    fp.feed(buf, n)
                    total += n
                    val currentProgress = (total.toFloat() / maxSamples).coerceIn(0f, 1f)
                    val currentSec = (total / rate).toInt()
                    withContext(Dispatchers.Main) {
                        micProgress = currentProgress
                        micElapsed = currentSec
                    }
                }

                try { rec.stop() } catch (_: Exception) {}
                val durationMs = (total * 1000L) / rate

                if (total >= rate) { // Al menos 1 segundo registrado
                    val fps = fp.finish()
                    withContext(Dispatchers.Main) {
                        micResult = fps
                        micRecordedDurationMs = durationMs
                        micProgress = 1f
                        micStatus = "✅ Listo: ${fps.size} huellas generadas"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        micStatus = "⚠️ Grabación muy corta (menos de 1s)"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    micStatus = "❌ ${e.localizedMessage}"
                }
            } finally {
                try { rec?.release() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    isRecording = false
                    stopRequested = false
                }
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else toast("Se necesita permiso de micrófono")
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Procesar audio", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(
                "Genera huellas (hash + offset_ms) con FFT a ${AudioFingerprinter.TARGET_RATE} Hz, " +
                    "el mismo algoritmo para archivos y micrófono.",
                fontSize = 12.sp, color = TextSub
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = DarkCardBorder
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Biblioteca (${viewModel.playlist.size} canciones)", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Button(
                        onClick = {
                            if (libRunning) FingerprintExport.cancel(context)
                            else libLauncher.launch("lume_huellas_${timestamp()}.json")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCoral, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(if (libRunning) "Cancelar proceso" else "Exportar huellas de la biblioteca (JSON)", color = Color.White) }
                    if (libRunning || libDone > 0) {
                        LinearProgressIndicator(
                            progress = { if (libTotal > 0) libDone.toFloat() / libTotal else 0f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = AccentCoral,
                            trackColor = Color.White.copy(alpha = 0.12f)
                        )
                        Text("$libDone / $libTotal  $libCurrent", fontSize = 12.sp, maxLines = 1, color = TextSub)
                    }
                }
            }
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = DarkCardBorder
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Micrófono ($MIC_SECONDS s)", fontWeight = FontWeight.SemiBold, color = TextPrimary)

                    Button(
                        onClick = {
                            if (isRecording) {
                                stopRequested = true
                            } else {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED) {
                                    startRecording()
                                } else {
                                    permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFE53935) else AccentCoral,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isRecording) "Detener grabación"
                                   else if (micResult != null) "Grabar de nuevo"
                                   else "Grabar con micrófono",
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }

                    if (isRecording) {
                        LinearProgressIndicator(
                            progress = { micProgress },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFFE53935),
                            trackColor = Color.White.copy(alpha = 0.12f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Escuchando audio...", fontSize = 12.sp, color = TextSub)
                            Text("${micElapsed}s / ${MIC_SECONDS}s", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentCoral)
                        }
                    }

                    if (micStatus.isNotEmpty()) {
                        Text(
                            text = micStatus,
                            fontSize = 13.sp,
                            fontWeight = if (micResult != null && !isRecording) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (micResult != null && !isRecording) Color(0xFF81C784) else TextPrimary
                        )
                    }
                    if (micResult != null && !isRecording) {
                        Button(
                            onClick = { micLauncher.launch("lume_consulta_${timestamp()}.json") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF388E3C),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "Guardar y Exportar JSON (${micResult?.size ?: 0} huellas)",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
}
