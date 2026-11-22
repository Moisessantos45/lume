package com.example.lume.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.JsonWriter
import androidx.core.app.NotificationCompat
import com.example.lume.Track
import com.example.lume.util.AudioDecoder
import com.example.lume.util.AudioFingerprinter
import com.example.lume.util.Fingerprint
import com.example.lume.util.SongIdGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger


object FingerprintExport {
    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
        val failed: Int = 0,
        val message: String? = null
    )

    val state = MutableStateFlow(State())
    @Volatile internal var tracks: List<Track> = emptyList()
    @Volatile internal var target: Uri? = null

    fun start(context: Context, tracks: List<Track>, target: Uri) {
        if (state.value.running) return
        this.tracks = tracks
        this.target = target
        state.value = State(running = true, total = tracks.size)
        val i = Intent(context, FingerprintExportService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(context, i)
    }

    fun cancel(context: Context) {
        context.startService(Intent(context, FingerprintExportService::class.java).setAction(FingerprintExportService.ACTION_CANCEL))
    }
}

/**
 * Servicio en primer plano (dataSync): Android no lo mata mientras procesa.
 *  - Pool acotado de hilos en prioridad de fondo (máx. 3, 1 si ahorro de batería).
 *  - Como máximo [workers]*2 canciones en vuelo → memoria acotada.
 *  - Un único escritor, en orden original, a un archivo temporal; solo si TODO termina bien
 *    se copia al destino. Si se cancela o falla, el destino no queda corrupto.
 *  - Pausa mientras el teléfono esté en estado térmico moderado o peor.
 */
class FingerprintExportService : Service() {

    companion object {
        const val ACTION_CANCEL = "com.example.lume.CANCEL_FP_EXPORT"
        private const val CHANNEL_ID = "fp_export"
        private const val NOTIF_ID = 4107
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        startInForeground(0, FingerprintExport.state.value.total, "")
        job = scope.launch { runExport() }
        return START_NOT_STICKY
    }

    private fun startInForeground(done: Int, total: Int, current: String) {
        val n = buildNotification(done, total, current)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIF_ID, n)
    }

    private fun buildNotification(done: Int, total: Int, current: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Exportación de huellas", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val cancel = PendingIntent.getService(
            this, 0,
            Intent(this, FingerprintExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Procesando audio ($done/$total)")
            .setContentText(current)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancelar", cancel)
            .build()
    }

    private fun thermalThrottled(pm: PowerManager): Boolean =
        Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

    private suspend fun runExport() {
        val ctx = applicationContext
        val tracks = FingerprintExport.tracks
        val target = FingerprintExport.target
        val pm = getSystemService(PowerManager::class.java)
        val tmp = File(cacheDir, "fp_export_${System.currentTimeMillis()}.json.tmp")
        val failed = AtomicInteger(0)
        var message = ""
        var executor: java.util.concurrent.ExecutorService? = null

        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lume:fp_export").apply {
            acquire(6 * 60 * 60 * 1000L)
        }
        try {
            if (target == null) throw IllegalStateException("Sin destino")
            val cores = Runtime.getRuntime().availableProcessors()
            val workers = if (pm.isPowerSaveMode) 1 else (cores / 2).coerceIn(1, 3)
            executor = Executors.newFixedThreadPool(workers) { r ->
                Thread {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    r.run()
                }.apply { name = "fp-worker" }
            }
            val dispatcher = executor.asCoroutineDispatcher()
            val permits = Semaphore(workers * 2)           // límite de memoria
            val results = Channel<Pair<Int, List<Fingerprint>?>>(Channel.UNLIMITED)

            tmp.bufferedWriter(Charsets.UTF_8).use { bw ->
                val w = JsonWriter(bw)
                w.beginObject()
                w.name("version").value(1)
                w.name("sample_rate").value(AudioFingerprinter.TARGET_RATE.toLong())
                w.name("canciones").beginArray()

                coroutineScope {
                    launch {
                        for ((i, t) in tracks.withIndex()) {
                            permits.acquire()
                            while (thermalThrottled(pm) && isActive) delay(3000)
                            launch(dispatcher) {
                                val fps = try {
                                    AudioDecoder.fingerprint(ctx, t.uri) { isActive }
                                } catch (e: Exception) { null }
                                results.send(i to fps)
                            }
                        }
                    }
                    val pending = HashMap<Int, List<Fingerprint>?>()
                    var next = 0
                    var written = 0
                    while (written < tracks.size) {
                        val (i, fps) = results.receive()
                        pending[i] = fps
                        while (pending.containsKey(next)) {
                            val f = pending.remove(next)
                            val t = tracks[next]
                            if (f == null) failed.incrementAndGet()
                            w.beginObject()
                            w.name("metadata").beginObject()
                            w.name("id").value(SongIdGenerator.generate(t.artist, t.title))
                            w.name("titulo").value(t.title)
                            w.name("artista").value(t.artist)
                            w.name("album").value(t.album)
                            w.name("duracion_ms").value(t.durationMs)
                            if (f == null) w.name("error").value(true)
                            w.endObject()
                            w.name("huellas").beginArray()
                            f?.forEach { fp ->
                                w.beginObject().name("hash").value(fp.hash)
                                    .name("offset_ms").value(fp.offsetMs).endObject()
                            }
                            w.endArray()
                            w.endObject()
                            next++; written++
                            permits.release()
                            val st = FingerprintExport.state.value
                            FingerprintExport.state.value =
                                st.copy(done = written, current = t.title, failed = failed.get())
                            if (written % 5 == 0 || written == tracks.size) {
                                getSystemService(NotificationManager::class.java)
                                    .notify(NOTIF_ID, buildNotification(written, tracks.size, t.title))
                            }
                        }
                    }
                }
                w.endArray().endObject()
                w.flush()
            }
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(target, "wt")?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                    out.flush()
                } ?: throw IllegalStateException("No se pudo abrir el destino")
            }
            message = "✅ Huellas exportadas" + if (failed.get() > 0) " (${failed.get()} con error)" else ""
        } catch (e: CancellationException) {
            try { target?.let { DocumentsContract.deleteDocument(ctx.contentResolver, it) } } catch (_: Exception) {}
            message = "⏹ Cancelado, no se guardó nada"
        } catch (e: Exception) {
            try { target?.let { DocumentsContract.deleteDocument(ctx.contentResolver, it) } } catch (_: Exception) {}
            message = "❌ Error: ${e.localizedMessage}"
        } finally {
            executor?.shutdown()
            tmp.delete()
            try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
            FingerprintExport.tracks = emptyList()
            FingerprintExport.state.value =
                FingerprintExport.state.value.copy(running = false, message = message)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
