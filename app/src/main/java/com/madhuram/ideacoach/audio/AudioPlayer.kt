package com.madhuram.ideacoach.audio

import android.media.AudioAttributes
import android.media.MediaPlayer

class AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var currentPath: String? = null

    fun toggle(path: String, onStateChange: (Boolean) -> Unit) {
        try {
            val player = mediaPlayer
            if (player == null || currentPath != path) {
                stop()
                val newPlayer = MediaPlayer()
                newPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                newPlayer.setDataSource(path)
                newPlayer.prepare()
                newPlayer.start()
                newPlayer.setOnCompletionListener {
                    stop()
                    onStateChange(false)
                }
                mediaPlayer = newPlayer
                currentPath = path
                onStateChange(true)
                return
            }

            if (player.isPlaying) {
                player.pause()
                onStateChange(false)
            } else {
                player.start()
                onStateChange(true)
            }
        } catch (_: Exception) {
            stop()
            onStateChange(false)
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer?.seekTo(positionMs)
        } catch (_: Exception) {
        }
    }

    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentPath = null
    }
}
