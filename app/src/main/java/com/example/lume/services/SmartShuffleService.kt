package com.example.lume.services

import android.content.Context
import com.example.lume.Track
import org.json.JSONObject
import java.util.Random
import kotlin.math.abs
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min

class SmartShuffleService private constructor() {
    companion object {
        val instance = SmartShuffleService()
        private const val RECENT_SONGS_LIMIT = 12
        private const val RECENT_ARTISTS_LIMIT = 8
        private const val POOL_LIMIT = 15
    }

    private val rng = Random()
    private val songsByPath = mutableMapOf<String, Track>()
    private val playCounts = mutableMapOf<String, Int>()
    private val recentSongPaths = mutableListOf<String>()
    private val recentArtists = mutableListOf<String>()
    private var prefsLoaded = false

    fun warmUp(songs: List<Track>, context: Context) {
        songsByPath.clear()
        songs.forEach { songsByPath[it.uri] = it }
        loadState(context)
    }

    fun recordPlay(song: Track, context: Context) {
        songsByPath[song.uri] = song
        playCounts[song.uri] = (playCounts[song.uri] ?: 0) + 1
        pushRecent(recentSongPaths, song.uri, RECENT_SONGS_LIMIT)
        if (song.artist.trim().isNotEmpty()) {
            pushRecent(recentArtists, song.artist.trim().lowercase(), RECENT_ARTISTS_LIMIT)
        }
        saveState(context)
    }

    fun nextSmartSong(
        currentUri: String,
        candidates: List<String>,
        played: Set<String>,
        context: Context
    ): String? {
        if (candidates.isEmpty()) return null
        val currentSong = songsByPath[currentUri] ?: return fallbackNext(candidates, played, context)
        val candidateSongs = candidates
            .mapNotNull { songsByPath[it] }
            .filter { it.uri != currentUri }
        if (candidateSongs.isEmpty()) {
            return fallbackNext(candidates, played, context)
        }
        val scored = mutableListOf<Pair<Track, Double>>()
        for (song in candidateSongs) {
            if (played.contains(song.uri)) continue
            val score = scoreCandidate(currentSong, song)
            scored.add(Pair(song, score))
        }
        if (scored.isEmpty()) {
            return fallbackNext(candidates, played, context)
        }
        scored.sortByDescending { it.second }
        val topLimit = min(POOL_LIMIT, scored.size)
        val topPool = scored.subList(0, topLimit)
        val chosen = topPool[rng.nextInt(topPool.size)].first
        noteSelection(chosen, context)
        return chosen.uri
    }

    private fun scoreCandidate(current: Track, candidate: Track): Double {
        var score = 0.0
        val currentArtist = current.artist.trim().lowercase()
        val candidateArtist = candidate.artist.trim().lowercase()
        if (currentArtist.isNotEmpty() && currentArtist == candidateArtist) {
            score += 0.50
        }
        val currentGenre = (current.genre ?: "").trim().lowercase()
        val candidateGenre = (candidate.genre ?: "").trim().lowercase()
        if (currentGenre.isNotEmpty() && currentGenre == candidateGenre) {
            score += 0.25
        }
        val currentAlbum = current.album.trim().lowercase()
        val candidateAlbum = candidate.album.trim().lowercase()
        if (currentAlbum.isNotEmpty() && currentAlbum == candidateAlbum) {
            score += 0.15
        }
        val currentDuration = current.durationMs
        val candidateDuration = candidate.durationMs
        if (currentDuration > 0 && candidateDuration > 0) {
            val diff = abs(currentDuration - candidateDuration)
            val durationScore = 1.0 - (diff.toDouble() / 300000.0)
            score += durationScore.coerceIn(0.0, 1.0) * 0.10
        }
        val playCount = playCounts[candidate.uri] ?: 0
        if (playCount > 0) {
            score += min(0.10, log((playCount + 1).toDouble(), Math.E) / 20.0)
        }
        val recentPenalty = recentSongPaths.indexOf(candidate.uri)
        if (recentPenalty >= 0) {
            score -= max(0.0, 0.18 - (recentPenalty * 0.02))
        }
        if (recentArtists.contains(candidateArtist) && candidateArtist.isNotEmpty()) {
            score -= 0.20
        }
        return score
    }

    private fun noteSelection(song: Track, context: Context) {
        pushRecent(recentSongPaths, song.uri, RECENT_SONGS_LIMIT)
        val artist = song.artist.trim().lowercase()
        if (artist.isNotEmpty()) {
            pushRecent(recentArtists, artist, RECENT_ARTISTS_LIMIT)
        }
        playCounts[song.uri] = (playCounts[song.uri] ?: 0) + 1
        saveState(context)
    }

    private fun fallbackNext(candidates: List<String>, played: Set<String>, context: Context): String? {
        val remaining = candidates.filter { !played.contains(it) }
        if (remaining.isEmpty()) return null
        val scored = remaining
            .mapNotNull { songsByPath[it] }
            .map { Pair(it, fallbackScore(it)) }
            .sortedByDescending { it.second }
        val pool = if (scored.isEmpty()) {
            remaining
        } else {
            val topLimit = min(POOL_LIMIT, scored.size)
            scored.subList(0, topLimit).map { it.first.uri }
        }
        val chosenUri = pool[rng.nextInt(pool.size)]
        val song = songsByPath[chosenUri]
        if (song != null) {
            noteSelection(song, context)
        }
        return chosenUri
    }

    private fun fallbackScore(song: Track): Double {
        var score = 0.0
        val artist = song.artist.trim().lowercase()
        if (recentArtists.contains(artist) && artist.isNotEmpty()) {
            score -= 0.15
        }
        val recentIndex = recentSongPaths.indexOf(song.uri)
        if (recentIndex >= 0) {
            score -= max(0.0, 0.10 - (recentIndex * 0.015))
        }
        val playCount = playCounts[song.uri] ?: 0
        if (playCount > 0) {
            score += min(0.05, log((playCount + 1).toDouble(), Math.E) / 30.0)
        }
        return score
    }

    private fun pushRecent(items: MutableList<String>, value: String, limit: Int) {
        items.remove(value)
        items.add(0, value)
        while (items.size > limit) {
            items.removeAt(items.size - 1)
        }
    }

    private fun loadState(context: Context) {
        if (prefsLoaded) return
        val prefs = context.getSharedPreferences("smart_shuffle_prefs", Context.MODE_PRIVATE)
        recentSongPaths.clear()
        recentSongPaths.addAll(prefs.getStringSet("recent_songs", emptySet()) ?: emptySet())
        recentArtists.clear()
        recentArtists.addAll(prefs.getStringSet("recent_artists", emptySet()) ?: emptySet())
        playCounts.clear()
        val rawPlayCounts = prefs.getString("play_counts", null)
        if (rawPlayCounts != null) {
            try {
                val json = JSONObject(rawPlayCounts)
                json.keys().forEach { key ->
                    playCounts[key] = json.getInt(key)
                }
            } catch (e: Exception) {
            }
        }
        prefsLoaded = true
    }

    private fun saveState(context: Context) {
        val prefs = context.getSharedPreferences("smart_shuffle_prefs", Context.MODE_PRIVATE)
        val json = JSONObject()
        playCounts.forEach { (key, value) ->
            json.put(key, value)
        }
        prefs.edit().apply {
            putStringSet("recent_songs", recentSongPaths.toSet())
            putStringSet("recent_artists", recentArtists.toSet())
            putString("play_counts", json.toString())
            apply()
        }
    }
}
