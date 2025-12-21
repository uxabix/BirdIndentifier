package com.example.birdidentifier

import android.content.Context
import android.media.MediaPlayer

object SoundPlayer {
    private var mediaPlayer: MediaPlayer? = null

    fun play(context: Context) {
        stop()
        mediaPlayer = MediaPlayer.create(context, R.raw.beer)
        mediaPlayer?.start()
    }

    fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}