package com.example.birdidentifier

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import kotlin.random.Random

object SoundPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private const val BOOST_GAIN = 3000

    private val sounds = listOf(
        R.raw.s2, R.raw.s3, R.raw.s4, R.raw.s5,
        R.raw.beer, R.raw.glass1, R.raw.glass2,
        R.raw.champagne, R.raw.champagne1, R.raw.champagne2,
        R.raw.champagne3, R.raw.champagne4, R.raw.champagne5, R.raw.champagne6,
        R.raw.predators, R.raw.predators1, R.raw.predators2,
        R.raw.predators3, R.raw.predators4, R.raw.predators5, R.raw.predators6,
        R.raw.long_creaking_door, R.raw.big_metal_door_slam,
        R.raw.fast_wooden_door_shut, R.raw.plastic_large_door_shut,
        R.raw.balloon_explosion_single_soft_impact
    )

    fun play(context: Context) {
        stop()

        val randomSound = sounds.random()

        mediaPlayer = MediaPlayer.create(context, randomSound)

        mediaPlayer?.let { mp ->
            val randomVolume = 1.0f - (Random.nextFloat() * 0.15f)
            mp.setVolume(randomVolume, randomVolume)

            try {
                loudnessEnhancer = LoudnessEnhancer(mp.audioSessionId).apply {
                    setTargetGain(BOOST_GAIN)
                    enabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            mp.setOnCompletionListener {
                stop()
            }
            mp.start()
        }
    }

    fun stop() {
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null

            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            mediaPlayer = null
            loudnessEnhancer = null
        }
    }
}
