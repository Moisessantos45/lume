package com.example.lume.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.lume.BuildConfig
import com.example.lume.PlayerViewModel
import com.example.lume.data.VectorImportMode
import com.example.lume.data.VectorSearchManager
import com.example.lume.data.VectorStats
import com.example.lume.util.SongIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF141113)
private val Card = Color(0xFF221A1E)
private val CardElevated = Color(0xFF2C2227)
private val AccentCoral = Color(0xFFE27D60)
private val TextPrimary = Color(0xFFFBF8F5)
private val TextSub = Color(0xCCDDD2C8)

private fun formatDurationMs(ms: Long): String {
    return if (ms < 1000) {
        "${ms} ms"
    } else {
        val seconds = ms / 1000.0
        "%.2f s".format(java.util.Locale.US, seconds)
    }
}

enum class ApiSendScope {
    ALL_SONGS,    // Enviar todas las canciones de la biblioteca (recomendado 1ª vez o BD vacía)
    MISSING_ONLY  // Enviar sólo canciones que no tienen vector en BD
}

@Composable
fun VectorScreen(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var stats by remember { mutableStateOf<VectorStats?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var processTitle by remember { mutableStateOf("") }
    var processMessage by remember { mutableStateOf("") }
    var processProgress by remember { mutableFloatStateOf(0f) }

    var isApiOnline by remember { mutableStateOf<Boolean?>(null) }
    var sendScopeMode by remember { mutableStateOf(ApiSendScope.ALL_SONGS) }
    var apiImportMode by remember { mutableStateOf(VectorImportMode.REPLACE_EXISTING) }
    var lastApiDurationMs by remember { mutableStateOf<Long?>(null) }
    var lastApiItemCount by remember { mutableIntStateOf(0) }

    var currentFileImportMode by remember { mutableStateOf(VectorImportMode.REPLACE_EXISTING) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    fun refreshStats() {
        scope.launch {
            stats = VectorSearchManager.getStats(context)
        }
    }

    LaunchedEffect(Unit) {
        refreshStats()
        isApiOnline = VectorSearchManager.checkApiHealth()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isProcessing = true
            processTitle = "Importando archivo JSON local"
            processMessage = "Leyendo archivo y decodificando vectores..."
            processProgress = 0f
            try {
                val result = VectorSearchManager.importVectorsFromUri(
                    context = context,
                    uri = uri,
                    mode = currentFileImportMode,
                    onProgress = { processed, total ->
                        processProgress = if (total > 0) processed.toFloat() / total else 0f
                        processMessage = "Importados $processed de $total vectores..."
                    }
                )

                refreshStats()
                val modeDesc = if (currentFileImportMode == VectorImportMode.REPLACE_EXISTING) {
                    "Actualización completa"
                } else {
                    "Nuevos añadidos"
                }
                snackbarHostState.showSnackbar(
                    "$modeDesc: ${result.processedCount} procesados, ${result.skippedCount} omitidos (${result.durationMs} ms)"
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Error al importar: ${e.localizedMessage ?: e.message}")
            } finally {
                isProcessing = false
            }
        }
    }

    fun startApiVectorization() {
        if (isProcessing) return
        scope.launch {
            val allTracks = viewModel.playlist.toList()
            if (allTracks.isEmpty()) {
                snackbarHostState.showSnackbar("Tu biblioteca no tiene canciones cargadas.")
                return@launch
            }

            isProcessing = true
            processTitle = "Conectando con la API..."
            processMessage = "Verificando canciones y vectores existentes..."
            processProgress = 0f

            try {
                val tracksToSend = if (sendScopeMode == ApiSendScope.MISSING_ONLY) {
                    val existingIds = VectorSearchManager.getExistingVectorSongIds(context)
                    allTracks.filter { track ->
                        val id = track.songId.ifBlank { SongIdGenerator.generate(track.artist, track.title) }
                        id !in existingIds
                    }
                } else {
                    allTracks
                }

                if (tracksToSend.isEmpty()) {
                    snackbarHostState.showSnackbar("Todas las canciones ya tienen vectores generados en la base de datos.")
                    isProcessing = false
                    return@launch
                }

                processTitle = "Vectorizando en la Nube con IA"
                val effectiveImportMode = if (sendScopeMode == ApiSendScope.MISSING_ONLY) {
                    VectorImportMode.INSERT_NEW_ONLY
                } else {
                    apiImportMode
                }

                val result = VectorSearchManager.vectoriseViaApi(
                    context = context,
                    tracksToVectorise = tracksToSend,
                    mode = effectiveImportMode,
                    onProgress = { processed, total, batch, totalBatches, msg ->
                        processProgress = if (total > 0) processed.toFloat() / total else 0f
                        processMessage = msg
                    }
                )

                refreshStats()
                isApiOnline = true
                lastApiDurationMs = result.durationMs
                lastApiItemCount = result.processedCount
                val formattedTime = formatDurationMs(result.durationMs)
                val modeDesc = if (effectiveImportMode == VectorImportMode.REPLACE_EXISTING) "Actualización completa" else "Solo faltantes"
                snackbarHostState.showSnackbar(
                    "$modeDesc: ${result.processedCount} vectores procesados en $formattedTime (${result.durationMs} ms)"
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Error al vectorizar: ${e.localizedMessage ?: e.message}")
            } finally {
                isProcessing = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Card con Gradiente
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Card,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(AccentCoral.copy(alpha = 0.20f), Color.Transparent)
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = AccentCoral,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Vectores de Búsqueda IA",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "SentenceTransformers / MiniLM-L12-v2",
                                    fontSize = 12.sp,
                                    color = TextSub
                                )
                            }
                        }

                        // API Status Pill
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when (isApiOnline) {
                                true -> Color(0xFF2E7D32).copy(alpha = 0.25f)
                                false -> Color(0xFFC62828).copy(alpha = 0.25f)
                                null -> Color.White.copy(alpha = 0.08f)
                            },
                            border = BorderStroke(
                                1.dp,
                                when (isApiOnline) {
                                    true -> Color(0xFF81C784).copy(alpha = 0.6f)
                                    false -> Color(0xFFEF5350).copy(alpha = 0.6f)
                                    null -> Color.White.copy(alpha = 0.15f)
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (isApiOnline) {
                                                true -> Color(0xFF4CAF50)
                                                false -> Color(0xFFE53935)
                                                null -> Color.Gray
                                            }
                                        )
                                )
                                Text(
                                    text = when (isApiOnline) {
                                        true -> "API en línea (${BuildConfig.VECTOR_API_URL.removePrefix("https://").removePrefix("http://")})"
                                        false -> "API no accesible (${BuildConfig.VECTOR_API_URL})"
                                        null -> "Comprobando conexión con la API..."
                                    },
                                    fontSize = 11.5.sp,
                                    color = when (isApiOnline) {
                                        true -> Color(0xFFA5D6A7)
                                        false -> Color(0xFFFFCDD2)
                                        null -> TextSub
                                    },
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Text(
                            text = "Genera embeddings semánticos para habilitar recomendaciones automáticas y canciones afines en tiempo real.",
                            fontSize = 12.5.sp,
                            color = TextSub,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // Estadísticas rápidas
            val totalVec = stats?.totalVectors ?: 0
            val totalSongs = viewModel.playlist.size
            val coverage = if (totalSongs > 0) ((totalVec.toFloat() / totalSongs) * 100f).coerceAtMost(100f) else 0f

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Card 1: Total Vectores
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Card,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Vectores en BD", fontSize = 11.sp, color = TextSub)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "$totalVec",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }

                // Card 2: Cobertura
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Card,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Cobertura", fontSize = 11.sp, color = TextSub)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${"%.1f".format(coverage)}%",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (coverage > 80f) Color(0xFF81C784) else AccentCoral
                        )
                    }
                }
            }

            // ==========================================
            // SECCIÓN 1: Vectorización en la Nube (API)
            // ==========================================
            Text(
                text = "Vectorización en la Nube (API)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Card,
                border = BorderStroke(1.dp, AccentCoral.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = AccentCoral.copy(alpha = 0.20f),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = AccentCoral,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Generar Directo desde la API",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Procesa las canciones en la API y descarga los vectores automáticamente",
                                fontSize = 11.5.sp,
                                color = TextSub
                            )
                        }
                    }

                    // Selector de canciones a enviar
                    Text(
                        text = "¿Qué canciones deseas enviar?",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )

                    // Opción A: Todas las canciones
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (sendScopeMode == ApiSendScope.ALL_SONGS) CardElevated else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (sendScopeMode == ApiSendScope.ALL_SONGS) AccentCoral else Color.White.copy(alpha = 0.10f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isProcessing) { sendScopeMode = ApiSendScope.ALL_SONGS }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = sendScopeMode == ApiSendScope.ALL_SONGS,
                                onClick = { sendScopeMode = ApiSendScope.ALL_SONGS },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentCoral),
                                enabled = !isProcessing
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Todas las canciones ($totalSongs)",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = AccentCoral.copy(alpha = 0.20f)
                                    ) {
                                        Text(
                                            text = "1ª vez o vacía",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AccentCoral,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "Recomendado si aún no tienes vectores o acabas de limpiarla.",
                                    fontSize = 11.sp,
                                    color = TextSub,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    // Opción B: Sólo canciones sin vectores
                    val missingEstimated = (totalSongs - totalVec).coerceAtLeast(0)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (sendScopeMode == ApiSendScope.MISSING_ONLY) CardElevated else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (sendScopeMode == ApiSendScope.MISSING_ONLY) AccentCoral else Color.White.copy(alpha = 0.10f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isProcessing) { sendScopeMode = ApiSendScope.MISSING_ONLY }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = sendScopeMode == ApiSendScope.MISSING_ONLY,
                                onClick = { sendScopeMode = ApiSendScope.MISSING_ONLY },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentCoral),
                                enabled = !isProcessing
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Solo canciones sin vectores ($missingEstimated)",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF81C784).copy(alpha = 0.20f)
                                    ) {
                                        Text(
                                            text = "Ahorro de datos",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFA5D6A7),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "Ideal tras añadir nuevas canciones; omite las que ya están en BD.",
                                    fontSize = 11.sp,
                                    color = TextSub,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    // Modo de inserción al recibir la data: sólo aplica si se envían todas las canciones
                    AnimatedVisibility(
                        visible = sendScopeMode == ApiSendScope.ALL_SONGS,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Modo de guardado al recibir la data:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = apiImportMode == VectorImportMode.REPLACE_EXISTING,
                                    onClick = { apiImportMode = VectorImportMode.REPLACE_EXISTING },
                                    label = { Text("Actualizar todo", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentCoral,
                                        selectedLabelColor = Color.White
                                    ),
                                    enabled = !isProcessing,
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = apiImportMode == VectorImportMode.INSERT_NEW_ONLY,
                                    onClick = { apiImportMode = VectorImportMode.INSERT_NEW_ONLY },
                                    label = { Text("Solo los nuevos", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentCoral,
                                        selectedLabelColor = Color.White
                                    ),
                                    enabled = !isProcessing,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Botón principal
                    Button(
                        onClick = { startApiVectorization() },
                        enabled = !isProcessing && totalSongs > 0,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentCoral,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Iniciar Vectorización con la API",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Indicador de tiempo de respuesta de la API
                    if (lastApiDurationMs != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = CardElevated,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = AccentCoral,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text("Tiempo de respuesta API:", fontSize = 11.5.sp, color = TextSub)
                                }
                                Text(
                                    text = "${formatDurationMs(lastApiDurationMs!!)} (${lastApiDurationMs} ms)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF81C784)
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SECCIÓN 2: Importar desde Archivo Local
            // ==========================================
            Text(
                text = "Importar desde Archivo Local (JSON)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            // Opción Archivo 1: Actualizar / Reemplazar
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Card,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = AccentCoral.copy(alpha = 0.18f),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = AccentCoral,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Actualizar Vectores Existentes",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Sobrescribe la data de los que ya existen e inserta los nuevos",
                                fontSize = 11.5.sp,
                                color = TextSub
                            )
                        }
                    }

                    Button(
                        onClick = {
                            currentFileImportMode = VectorImportMode.REPLACE_EXISTING
                            filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CardElevated,
                            contentColor = TextPrimary
                        ),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Seleccionar JSON y Actualizar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Opción Archivo 2: Insertar sólo los que no existen
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Card,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = TextPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Insertar sólo Nuevos",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Si la canción ya tiene vector se omite y no se modifica",
                                fontSize = 11.5.sp,
                                color = TextSub
                            )
                        }
                    }

                    Button(
                        onClick = {
                            currentFileImportMode = VectorImportMode.INSERT_NEW_ONLY
                            filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CardElevated,
                            contentColor = TextPrimary
                        ),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Seleccionar JSON e Insertar Faltantes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ==========================================
            // SECCIÓN 3: Mantenimiento
            // ==========================================
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Card,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Limpiar Base de Datos", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Elimina todos los vectores guardados", fontSize = 11.5.sp, color = TextSub)
                    }
                    TextButton(
                        onClick = { showDeleteConfirmDialog = true },
                        enabled = !isProcessing && totalVec > 0,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Borrar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
        )
    }

    // ==========================================
    // OVERLAY / MODAL DE BLOQUEO DE UI
    // ==========================================
    if (isProcessing) {
        Dialog(
            onDismissRequest = {}, // Bloqueado, no se puede descartar tocando afuera ni con botón Atrás
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = CardElevated,
                border = BorderStroke(1.5.dp, AccentCoral.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        color = AccentCoral,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp)
                    )

                    Text(
                        text = processTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )

                    if (processProgress > 0f) {
                        LinearProgressIndicator(
                            progress = { processProgress },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = AccentCoral,
                            trackColor = Color.White.copy(alpha = 0.12f)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = AccentCoral,
                            trackColor = Color.White.copy(alpha = 0.12f)
                        )
                    }

                    Text(
                        text = processMessage,
                        fontSize = 12.5.sp,
                        color = TextSub,
                        textAlign = TextAlign.Center,
                        lineHeight = 17.sp
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White.copy(alpha = 0.05f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = AccentCoral, modifier = Modifier.size(14.dp))
                            Text(
                                text = "La interfaz permanece bloqueada para evitar inconsistencias en la base de datos.",
                                fontSize = 10.5.sp,
                                color = TextSub
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("¿Eliminar todos los vectores?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Esta acción borrará los embeddings guardados en la base de datos. Tendrás que importar nuevamente tu archivo JSON o usar la API para recuperar las recomendaciones.",
                    color = TextSub,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        scope.launch {
                            VectorSearchManager.clearAll(context)
                            refreshStats()
                            snackbarHostState.showSnackbar("Base de datos de vectores limpiada")
                        }
                    }
                ) {
                    Text("Eliminar", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancelar", color = TextSub)
                }
            },
            containerColor = CardElevated
        )
    }
}
