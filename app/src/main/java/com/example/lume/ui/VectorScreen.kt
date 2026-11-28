package com.example.lume.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lume.PlayerViewModel
import com.example.lume.R
import com.example.lume.data.VectorImportMode
import com.example.lume.data.VectorSearchManager
import com.example.lume.data.VectorStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF141113)
private val Card = Color(0xFF221A1E)
private val CardElevated = Color(0xFF2C2227)
private val AccentCoral = Color(0xFFE27D60)
private val TextPrimary = Color(0xFFFBF8F5)
private val TextSub = Color(0xCCDDD2C8)

@Composable
fun VectorScreen(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var stats by remember { mutableStateOf<VectorStats?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableIntStateOf(0) }
    var importTotal by remember { mutableIntStateOf(0) }
    var currentImportMode by remember { mutableStateOf(VectorImportMode.REPLACE_EXISTING) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    fun refreshStats() {
        scope.launch {
            stats = VectorSearchManager.getStats(context)
        }
    }

    LaunchedEffect(Unit) {
        refreshStats()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isImporting = true
            importProgress = 0
            importTotal = 0
            try {
                // Importación por streaming directo desde el archivo para no cargar el JSON en memoria
                val result = VectorSearchManager.importVectorsFromUri(
                    context = context,
                    uri = uri,
                    mode = currentImportMode,
                    onProgress = { processed, total ->
                        importProgress = processed
                        importTotal = total
                    }
                )

                refreshStats()
                val modeDesc = if (currentImportMode == VectorImportMode.REPLACE_EXISTING) {
                    "Actualización completa"
                } else {
                    "Nuevos añadidos"
                }
                snackbarHostState.showSnackbar(
                    "✅ $modeDesc: ${result.processedCount} procesados, ${result.skippedCount} omitidos (${result.durationMs}ms)"
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("❌ Error al importar: ${e.localizedMessage ?: e.message}")
            } finally {
                isImporting = false
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
                                    Text("✨", fontSize = 16.sp)
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
                                    text = "SentenceTransformers / sqlite-vec",
                                    fontSize = 12.sp,
                                    color = TextSub
                                )
                            }
                        }
                        Text(
                            text = "Importa embeddings de 384 dimensiones para habilitar recomendaciones semánticas y canciones afines en tiempo real.",
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

            // Barra de progreso si está importando
            AnimatedVisibility(
                visible = isImporting,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = CardElevated,
                    border = BorderStroke(1.dp, AccentCoral.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Importando vectores en segundo plano...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = AccentCoral
                            )
                        }
                        if (importTotal > 0) {
                            LinearProgressIndicator(
                                progress = { importProgress.toFloat() / importTotal },
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
                            text = if (importTotal > 0) "$importProgress / $importTotal elementos procesados" else "$importProgress elementos procesados",
                            fontSize = 11.sp,
                            color = TextSub
                        )
                    }
                }
            }

            Text(
                text = "Opciones de Importación",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            // Opción 1: Actualizar / Reemplazar
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
                            currentImportMode = VectorImportMode.REPLACE_EXISTING
                            filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        enabled = !isImporting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentCoral,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Seleccionar JSON y Actualizar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Opción 2: Insertar sólo los que no existen
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
                                Text("➕", fontSize = 14.sp)
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
                            currentImportMode = VectorImportMode.INSERT_NEW_ONLY
                            filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        enabled = !isImporting,
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

            // Mantenimiento / Borrar
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
                        enabled = !isImporting && totalVec > 0,
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

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("¿Eliminar todos los vectores?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Esta acción borrará los embeddings guardados en la base de datos. Tendrás que importar nuevamente tu archivo JSON para recuperar las recomendaciones.",
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
                            snackbarHostState.showSnackbar("🗑️ Base de datos de vectores limpiada")
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
