package com.example.lume.services

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.lume.MainActivity
import com.example.lume.PlayerViewModel

class PlaybackService : MediaSessionService() {

    companion object {
        @Volatile
        var playerViewModelInstance: PlayerViewModel? = null
        @Volatile
        var playerInstance: ExoPlayer? = null
            internal set

        private var enhancer: android.media.audiofx.LoudnessEnhancer? = null

                fun createLumePlayer(context: android.content.Context): ExoPlayer {
            val attrs = androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            val p = ExoPlayer.Builder(context.applicationContext)
                .setAudioAttributes(attrs, true)
                .setHandleAudioBecomingNoisy(true)
                .build()
            p.addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    attachEnhancer(audioSessionId)
                }
            })
            attachEnhancer(p.audioSessionId)
            return p
        }

        private fun attachEnhancer(sessionId: Int) {
            if (sessionId == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
            try {
                enhancer?.release()
                enhancer = android.media.audiofx.LoudnessEnhancer(sessionId).apply {
                    setTargetGain(600) // +6 dB para pistas con volumen bajo
                    enabled = true
                }
            } catch (_: Exception) {}
        }

        private val initLock = Object()
        private var initStarted = false
        private var initComplete = false
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        synchronized(initLock) {
            if (!initStarted) {
                initStarted = true
                playerInstance = createLumePlayer(this)
            }
        }
        val player = playerInstance!!


        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun seekToNext() {
                playerViewModelInstance?.next()
            }
            override fun seekToNextMediaItem() {
                playerViewModelInstance?.next()
            }
            override fun seekToPrevious() {
                playerViewModelInstance?.previous()
            }
            override fun seekToPreviousMediaItem() {
                playerViewModelInstance?.previous()
            }


            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(pendingIntent)
            .build()

        addSession(mediaSession!!)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        mediaSession?.run {
            removeSession(this)
            player.release()
            release()
            mediaSession = null
        }
        synchronized(initLock) {
            playerInstance = null
            playerViewModelInstance = null
            initStarted = false
            initComplete = false
        }
        super.onDestroy()
    }
}
