package com.example.lume.util

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Fingerprint(val hash: Long, val offsetMs: Long)

/**
 * Generador de huellas tipo Shazam. EL MISMO algoritmo se usa para archivos y micrófono:
 *  1. Mezcla a mono y reduce a 11 025 Hz.
 *  2. FFT de 1024 puntos (ventana Hann, salto de 512 muestras ≈ 46 ms).
 *  3. Por cada banda de frecuencia (< 5 kHz) toma el pico más fuerte; por bloque de
 *     [BLOCK] frames conserva solo los [PEAKS_PER_BLOCK] más fuertes (constelación).
 *  4. Empareja cada pico (ancla) con hasta [FANOUT] picos posteriores:
 *     hash = f1(9 bits) | f2(9 bits) | delta_frames(10 bits), offset = tiempo del ancla.
 */
class AudioFingerprinter(private val srcRate: Int, private val channels: Int = 1) {

    companion object {
        const val TARGET_RATE = 11025
        const val FRAME = 1024
        const val HOP = 512
        const val MAX_BIN = 464                 // ≈ 5 kHz
        const val BLOCK = 32
        const val PEAKS_PER_BLOCK = 30
        const val FANOUT = 3
        const val MIN_DT = 2                    // Target zone: salto temporal mínimo (~93 ms)
        const val MAX_DT = 64
        const val MIN_MAGNITUDE = 1.0f          // Umbral mínimo de energía: descarta silencio y ruido ambiente
        // Bandas logarítmicas desde 200 Hz (bin 19) hasta 5000 Hz (bin 464).
        // Ignora frecuencias < 200 Hz (elimina zumbido 50/60 Hz, viento y roces del micrófono)
        private val BANDS = intArrayOf(19, 36, 64, 116, 212, 330, MAX_BIN + 1)

        private val WINDOW = FloatArray(FRAME) { (0.5 - 0.5 * cos(2.0 * PI * it / (FRAME - 1))).toFloat() }
        private val COS = FloatArray(FRAME / 2) { cos(2.0 * PI * it / FRAME).toFloat() }
        private val SIN = FloatArray(FRAME / 2) { (-sin(2.0 * PI * it / FRAME)).toFloat() }
    }

    private class Peak(val frame: Int, val bin: Int, val mag: Float)

    private val ratio = srcRate.toDouble() / TARGET_RATE
    private var acc = 0.0
    private var accN = 0
    private var phase = 0.0

    private val buf = FloatArray(FRAME)
    private var bufCount = 0
    private var frameIdx = 0

    private val re = FloatArray(FRAME)
    private val im = FloatArray(FRAME)
    private val block = ArrayList<Peak>()
    private val peaks = ArrayList<Peak>()

    
    val processedMs: Long get() = frameIdx.toLong() * HOP * 1000 / TARGET_RATE

    
    fun feed(data: ShortArray, len: Int) {
        var i = 0
        while (i + channels <= len) {
            var s = 0
            for (c in 0 until channels) s += data[i + c]
            i += channels
            acc += s.toDouble() / channels
            accN++
            phase += 1.0
            if (phase >= ratio) {
                phase -= ratio
                pushSample((acc / accN / 32768.0).toFloat())
                acc = 0.0; accN = 0
            }
        }
    }

    private fun pushSample(v: Float) {
        buf[bufCount++] = v
        if (bufCount == FRAME) {
            processFrame()
            System.arraycopy(buf, HOP, buf, 0, FRAME - HOP)
            bufCount = FRAME - HOP
        }
    }

    private fun processFrame() {
        for (i in 0 until FRAME) { re[i] = buf[i] * WINDOW[i]; im[i] = 0f }
        fft()
        for (b in 0 until BANDS.size - 1) {
            var best = -1; var bestMag = 0f
            for (k in BANDS[b] until BANDS[b + 1]) {
                val m = re[k] * re[k] + im[k] * im[k]
                if (m > bestMag) { bestMag = m; best = k }
            }
            if (best >= 0 && bestMag > MIN_MAGNITUDE) block.add(Peak(frameIdx, best, bestMag))
        }
        frameIdx++
        if (frameIdx % BLOCK == 0) flushBlock()
    }

    private fun flushBlock() {
        if (block.isEmpty()) return
        block.sortByDescending { it.mag }
        for (i in 0 until minOf(PEAKS_PER_BLOCK, block.size)) peaks.add(block[i])
        block.clear()
    }

    private fun fft() {
        val n = FRAME
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in i until i + half) {
                    val wr = COS[k]; val wi = SIN[k]
                    val xr = re[m + half] * wr - im[m + half] * wi
                    val xi = re[m + half] * wi + im[m + half] * wr
                    re[m + half] = re[m] - xr; im[m + half] = im[m] - xi
                    re[m] += xr; im[m] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    
    fun finish(): List<Fingerprint> {
        flushBlock()
        peaks.sortWith(compareBy<Peak> { it.frame }.thenBy { it.bin })
        val out = ArrayList<Fingerprint>(peaks.size * FANOUT)
        for (i in peaks.indices) {
            val a = peaks[i]
            var fan = 0
            var j = i + 1
            while (j < peaks.size && fan < FANOUT) {
                val p = peaks[j]
                val dt = p.frame - a.frame
                if (dt > MAX_DT) break
                if (dt >= MIN_DT) {
                    val hash = (a.bin.toLong() shl 19) or (p.bin.toLong() shl 10) or dt.toLong()
                    out.add(Fingerprint(hash, a.frame.toLong() * HOP * 1000 / TARGET_RATE))
                    fan++
                }
                j++
            }
        }
        return out
    }
}

object AudioDecoder {
    /**
     * Decodifica un archivo a PCM con MediaExtractor/MediaCodec (sin reproducirlo)
     * y lo alimenta al [AudioFingerprinter]. Devuelve las huellas.
     */
    fun fingerprint(context: Context, uri: String, isActive: () -> Boolean = { true }): List<Fingerprint> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i; format = f; break
                }
            }
            if (trackIdx < 0 || format == null) return emptyList()
            extractor.selectTrack(trackIdx)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var fp = AudioFingerprinter(
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            )
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcm = ShortArray(0)
            while (!outputDone && isActive()) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(idx)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        val enc = if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            nf.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        if (enc == AudioFormat.ENCODING_PCM_16BIT) {
                            fp = AudioFingerprinter(
                                nf.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            )
                        }
                    }
                    out >= 0 -> {
                        val ob = codec.getOutputBuffer(out)!!
                        val n = info.size / 2
                        if (n > 0) {
                            if (pcm.size < n) pcm = ShortArray(n)
                            ob.position(info.offset)
                            ob.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().get(pcm, 0, n)
                            fp.feed(pcm, n)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return fp.finish()
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
