#!/usr/bin/env python3
"""
Lume Audio Fingerprinter (Python)
---------------------------------
Genera huellas acústicas (tipo Shazam) de canciones a partir de una carpeta de audio
usando EXACTAMENTE el mismo algoritmo optimizado de Lume en Kotlin:
  1. Decodificación y remuestreo a 11,025 Hz mono (vía FFmpeg).
  2. FFT de 1024 puntos, ventana Hann, salto de 512 muestras (~46.4 ms).
  3. Filtro High-Pass: ignora frecuencias < 200 Hz (elimina zumbido eléctrico 50/60 Hz,
     viento, roces de micrófono y ruidos de baja frecuencia).
  4. Umbral de silencio/volumen (Threshold): ignora picos con energía < MIN_MAGNITUDE (evita ruido estático).
  5. Agrupamiento en bloques de 32 frames conservando los 30 picos más potentes.
  6. Emparejamiento por constelación (Anchor Peak + Target Zone, fanout = 3, delta 2..64 frames):
     hash = (f1 << 19) | (f2 << 10) | dt
     offset_ms = a_frame * 512 * 1000 // 11025
  7. Identificador determinístico SHA-256 (16 hex chars) para artista|título.
"""

import os
import sys
import json
import re
import argparse
import hashlib
import unicodedata
import subprocess
from pathlib import Path
from concurrent.futures import ProcessPoolExecutor, as_completed
import numpy as np

try:
    import mutagen
except ImportError:
    mutagen = None

# ─────────────────────────────────────────────────────────────────────────────
# CONSTANTES DEL ALGORITMO (IDÉNTICAS A AudioFingerprinter.kt)
# ─────────────────────────────────────────────────────────────────────────────
TARGET_RATE = 11025
FRAME = 1024
HOP = 512
MAX_BIN = 464               # ~5000 Hz (464 * 11025 / 1024 = 4995 Hz)
BLOCK = 32
PEAKS_PER_BLOCK = 30
FANOUT = 3
MIN_DT = 2                  # Target zone: delta temporal mínimo (~93 ms)
MAX_DT = 64
MIN_MAGNITUDE = 1.0         # Umbral mínimo de energía: descarta silencio y ruido ambiente

# Bandas logarítmicas de 200 Hz (bin 19) a 5000 Hz (bin 464).
# Ignora frecuencias < 200 Hz (elimina ruido de 50/60 Hz, viento y roces de mic)
BANDS = [19, 36, 64, 116, 212, 330, MAX_BIN + 1]

# Ventana Hann precalculada
WINDOW = (0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(FRAME) / (FRAME - 1))).astype(np.float32)

AUDIO_EXTENSIONS = {
    ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".wma", ".aiff", ".alac"
}


# ─────────────────────────────────────────────────────────────────────────────
# NORMALIZACIÓN Y GENERACIÓN DE ID (IDÉNTICO A SongIdGenerator.kt)
# ─────────────────────────────────────────────────────────────────────────────
def normalize_string(input_str: str) -> str:
    """Normaliza texto: minúsculas, descompone diacríticos y elimina puntuación."""
    if not input_str:
        return ""
    lower = input_str.strip().lower()
    decomposed = unicodedata.normalize("NFD", lower)
    without_diacritics = "".join(c for c in decomposed if unicodedata.category(c) != "Mn")
    cleaned = "".join(c if c.isalnum() else " " for c in without_diacritics)
    return re.sub(r"\s+", " ", cleaned).strip()


def generate_song_id(artist: str, title: str) -> str:
    """Genera un hash SHA-256 de 16 caracteres hexadecimales para artista|titulo."""
    normalized = f"{normalize_string(artist)}|{normalize_string(title)}"
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
    return digest[:16]


# ─────────────────────────────────────────────────────────────────────────────
# EXTRACCIÓN DE METADATOS
# ─────────────────────────────────────────────────────────────────────────────
def get_tag_value(tag):
    if tag is None:
        return None
    if isinstance(tag, (list, tuple)):
        return str(tag[0]) if len(tag) > 0 else None
    return str(tag)


def extract_metadata(file_path: Path) -> dict:
    """Extrae artista, título, álbum y duración (ms) con mutagen o ffprobe."""
    meta = {
        "titulo": file_path.stem,
        "artista": "Unknown Artist",
        "album": "Unknown Album",
        "duracion_ms": 0
    }

    if mutagen is not None:
        try:
            audio = mutagen.File(str(file_path))
            if audio is not None:
                if audio.info and hasattr(audio.info, "length") and audio.info.length:
                    meta["duracion_ms"] = int(audio.info.length * 1000)

                tags = audio.tags
                if tags:
                    artist = (
                        get_tag_value(tags.get("artist")) or
                        get_tag_value(tags.get("ARTIST")) or
                        get_tag_value(tags.get("TPE1")) or
                        get_tag_value(tags.get("\xa9ART"))
                    )
                    if artist:
                        meta["artista"] = artist.strip()

                    title = (
                        get_tag_value(tags.get("title")) or
                        get_tag_value(tags.get("TITLE")) or
                        get_tag_value(tags.get("TIT2")) or
                        get_tag_value(tags.get("\xa9nam"))
                    )
                    if title:
                        meta["titulo"] = title.strip()

                    album = (
                        get_tag_value(tags.get("album")) or
                        get_tag_value(tags.get("ALBUM")) or
                        get_tag_value(tags.get("TALB")) or
                        get_tag_value(tags.get("\xa9alb"))
                    )
                    if album:
                        meta["album"] = album.strip()
                return meta
        except Exception:
            pass

    try:
        cmd = [
            "ffprobe",
            "-v", "quiet",
            "-print_format", "json",
            "-show_format",
            str(file_path)
        ]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode == 0:
            data = json.loads(res.stdout).get("format", {})
            tags = data.get("tags", {})
            tag_lookup = {k.lower(): v for k, v in tags.items()}
            if "artist" in tag_lookup:
                meta["artista"] = tag_lookup["artist"]
            if "title" in tag_lookup:
                meta["titulo"] = tag_lookup["title"]
            if "album" in tag_lookup:
                meta["album"] = tag_lookup["album"]
            dur = data.get("duration")
            if dur:
                meta["duracion_ms"] = int(float(dur) * 1000)
    except Exception:
        pass

    return meta


# ─────────────────────────────────────────────────────────────────────────────
# DECODIFICACIÓN DE AUDIO
# ─────────────────────────────────────────────────────────────────────────────
def decode_audio(file_path: Path, target_rate: int = TARGET_RATE) -> np.ndarray:
    """Decodifica cualquier formato soportado por FFmpeg a PCM mono float32 a 11,025 Hz."""
    cmd = [
        "ffmpeg",
        "-nostdin",
        "-v", "error",
        "-i", str(file_path),
        "-f", "f32le",
        "-ac", "1",
        "-ar", str(target_rate),
        "-"
    ]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        err_msg = proc.stderr.decode("utf-8", errors="ignore").strip()
        raise RuntimeError(f"FFmpeg falló al decodificar: {err_msg}")
    
    samples = np.frombuffer(proc.stdout, dtype=np.float32)
    return samples


# ─────────────────────────────────────────────────────────────────────────────
# CÁLCULO DE HUELLAS (IDÉNTICO A AudioFingerprinter.kt)
# ─────────────────────────────────────────────────────────────────────────────
def calculate_fingerprints(audio: np.ndarray, target_rate: int = TARGET_RATE) -> list:
    """
    Calcula la lista de huellas [{'hash': int, 'offset_ms': int}, ...]
    con filtro de 200 Hz y umbral mínimo de energía.
    """
    total_samples = len(audio)
    if total_samples < FRAME:
        return []

    num_frames = (total_samples - FRAME) // HOP + 1
    frames = np.lib.stride_tricks.sliding_window_view(
        audio[:num_frames * HOP + FRAME - HOP], FRAME
    )[::HOP]

    windowed = frames * WINDOW
    rfft = np.fft.rfft(windowed, n=FRAME)
    mags = (np.real(rfft)**2 + np.imag(rfft)**2).astype(np.float32)

    peaks = []
    block = []

    for frame_idx in range(num_frames):
        mag_frame = mags[frame_idx]
        for b in range(len(BANDS) - 1):
            b_start = BANDS[b]
            b_end = BANDS[b + 1]
            band_slice = mag_frame[b_start:b_end]
            max_idx = int(np.argmax(band_slice))
            best_mag = float(band_slice[max_idx])
            # Umbral de energía: ignora silencio y ruido estático
            if best_mag > MIN_MAGNITUDE:
                best_bin = b_start + max_idx
                block.append((frame_idx, best_bin, best_mag))

        if (frame_idx + 1) % BLOCK == 0:
            if block:
                block.sort(key=lambda x: x[2], reverse=True)
                peaks.extend(block[:PEAKS_PER_BLOCK])
                block.clear()

    if block:
        block.sort(key=lambda x: x[2], reverse=True)
        peaks.extend(block[:PEAKS_PER_BLOCK])
        block.clear()

    if not peaks:
        return []

    # Ordenar picos por frame y luego por bin
    peaks.sort(key=lambda x: (x[0], x[1]))

    # Emparejamiento por constelación (Anchor Peak + Target Zone)
    fingerprints = []
    n_peaks = len(peaks)
    for i in range(n_peaks):
        a_frame, a_bin, _ = peaks[i]
        fan = 0
        j = i + 1
        while j < n_peaks and fan < FANOUT:
            p_frame, p_bin, _ = peaks[j]
            dt = p_frame - a_frame
            if dt > MAX_DT:
                break
            if dt >= MIN_DT:
                h = (a_bin << 19) | (p_bin << 10) | dt
                offset_ms = int(a_frame * HOP * 1000 // target_rate)
                fingerprints.append({"hash": h, "offset_ms": offset_ms})
                fan += 1
            j += 1

    return fingerprints


# ─────────────────────────────────────────────────────────────────────────────
# PROCESAMIENTO DE ARCHIVO INDIVIDUAL
# ─────────────────────────────────────────────────────────────────────────────
def process_single_file(file_path_str: str) -> dict:
    path = Path(file_path_str)
    meta = extract_metadata(path)
    song_id = generate_song_id(meta["artista"], meta["titulo"])

    try:
        audio = decode_audio(path, TARGET_RATE)
        if meta["duracion_ms"] <= 0:
            meta["duracion_ms"] = int(len(audio) * 1000 // TARGET_RATE)
        huellas = calculate_fingerprints(audio, TARGET_RATE)
        return {
            "success": True,
            "path": str(path),
            "data": {
                "metadata": {
                    "id": song_id,
                    "titulo": meta["titulo"],
                    "artista": meta["artista"],
                    "album": meta["album"],
                    "duracion_ms": meta["duracion_ms"]
                },
                "huellas": huellas
            }
        }
    except Exception as e:
        return {
            "success": False,
            "path": str(path),
            "error": str(e),
            "data": {
                "metadata": {
                    "id": song_id,
                    "titulo": meta["titulo"],
                    "artista": meta["artista"],
                    "album": meta["album"],
                    "duracion_ms": meta["duracion_ms"],
                    "error": True
                },
                "huellas": []
            }
        }


def find_audio_files(input_dir: Path, recursive: bool = True) -> list:
    files = []
    pattern = "**/*" if recursive else "*"
    for p in input_dir.glob(pattern):
        if p.is_file() and p.suffix.lower() in AUDIO_EXTENSIONS:
            files.append(p)
    return sorted(files)


def main():
    parser = argparse.ArgumentParser(
        description="Genera el JSON de huellas acústicas de una carpeta de canciones (idéntico a Lume Kotlin)."
    )
    parser.add_argument("input_dir", type=str, help="Ruta a la carpeta con archivos de audio.")
    parser.add_argument("-o", "--output", type=str, default="lume_huellas.json", help="Ruta del JSON de salida.")
    parser.add_argument("-w", "--workers", type=int, default=None, help="Número de procesos en paralelo.")
    parser.add_argument("--split", action="store_true", help="Guarda un JSON individual por canción.")
    parser.add_argument("--no-recursive", action="store_true", help="No busca en subcarpetas.")

    args = parser.parse_args()

    input_path = Path(args.input_dir).resolve()
    if not input_path.exists() or not input_path.is_dir():
        print(f"❌ Error: La carpeta '{input_path}' no existe o no es un directorio.", file=sys.stderr)
        sys.exit(1)

    print(f"🔍 Escaneando archivos de audio en: {input_path}")
    audio_files = find_audio_files(input_path, recursive=not args.no_recursive)
    total_files = len(audio_files)

    if total_files == 0:
        print(f"⚠️ No se encontraron archivos de audio con extensiones: {', '.join(sorted(AUDIO_EXTENSIONS))}")
        sys.exit(0)

    workers = args.workers or max(1, os.cpu_count() or 2)
    print(f"🎵 Se encontraron {total_files} canciones.")
    print(f"⚡ Procesando con {workers} hilos/procesos en paralelo...\n")

    out_path = Path(args.output).resolve()
    if args.split:
        if out_path.suffix.lower() == ".json":
            out_dir = out_path.parent / (out_path.stem + "_split")
        else:
            out_dir = out_path
        out_dir.mkdir(parents=True, exist_ok=True)
        print(f"📁 Modo SPLIT: los JSONs individuales se guardarán en: {out_dir}")

    success_count = 0
    fail_count = 0
    total_huellas = 0

    if not args.split:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_file = open(out_path, "w", encoding="utf-8")
        out_file.write('{\n  "version": 1,\n  "sample_rate": 11025,\n  "canciones": [\n')
        first_song = True

    try:
        with ProcessPoolExecutor(max_workers=workers) as executor:
            futures = {
                executor.submit(process_single_file, str(f)): f for f in audio_files
            }

            done_count = 0
            for future in as_completed(futures):
                orig_file = futures[future]
                done_count += 1
                try:
                    res = future.result()
                    data = res["data"]
                    song_name = data["metadata"]["titulo"]
                    artist_name = data["metadata"]["artista"]
                    huellas_count = len(data["huellas"])
                    total_huellas += huellas_count

                    status_sym = "✅" if res["success"] else "⚠️"
                    if res["success"]:
                        success_count += 1
                    else:
                        fail_count += 1

                    pct = (done_count / total_files) * 100
                    print(
                        f"[{done_count}/{total_files} - {pct:5.1f}%] {status_sym} "
                        f"{artist_name} - {song_name} ({huellas_count} huellas)"
                    )

                    if args.split:
                        song_id = data["metadata"]["id"]
                        split_file = out_dir / f"{song_id}.json"
                        with open(split_file, "w", encoding="utf-8") as sf:
                            json.dump(data, sf, indent=2, ensure_ascii=False)
                    else:
                        song_json = json.dumps(data, indent=4, ensure_ascii=False)
                        indented = "\n".join("    " + line for line in song_json.split("\n"))
                        if not first_song:
                            out_file.write(",\n")
                        else:
                            first_song = False
                        out_file.write(indented)
                        out_file.flush()

                except Exception as exc:
                    fail_count += 1
                    print(f"[{done_count}/{total_files}] ❌ Error procesando {orig_file.name}: {exc}")

    finally:
        if not args.split:
            out_file.write("\n  ]\n}\n")
            out_file.close()

    print("\n" + "═" * 60)
    print("✨ PROCESO COMPLETADO")
    print(f"📊 Canciones procesadas exitosamente: {success_count}/{total_files}")
    if fail_count > 0:
        print(f"⚠️ Canciones con error: {fail_count}")
    print(f"🏷️  Total de huellas generadas: {total_huellas:,}")
    if args.split:
        print(f"💾 Archivos guardados en: {out_dir}")
    else:
        file_size_mb = out_path.stat().st_size / (1024 * 1024)
        print(f"💾 Archivo JSON generado: {out_path} ({file_size_mb:.2f} MB)")
    print("═" * 60)


if __name__ == "__main__":
    main()
