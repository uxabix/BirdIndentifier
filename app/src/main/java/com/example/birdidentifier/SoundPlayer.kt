package com.example.birdidentifier

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import kotlin.random.Random

/**
 * A singleton utility for playing random notification or deterrent sounds.
 * 
 * It manages [MediaPlayer] instances and uses [LoudnessEnhancer] to boost 
 * audio output levels significantly.
 */
object SoundPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    
    /** 
     * Target gain for the loudness enhancer in mB (millibels). 
     * 3000 mB equals +30 dB boost.
     */
    private const val BOOST_GAIN = 3000

    /** List of resource IDs for the sounds to be played. */
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

    /**
     * Plays a random sound from the predefined [sounds] list.
     * 
     * Automatically applies a loudness boost, randomizes playback speed
     * within ±20% of the standard rate, and stops any currently 
     * playing sound before starting a new one.
     * 
     * @param context The Android context used to create the [MediaPlayer].
     */
    fun play(context: Context) {
        stop()

        val randomSound = sounds.random()
        mediaPlayer = MediaPlayer.create(context, randomSound)

        mediaPlayer?.let { mp ->
            // Apply slight random volume variation for natural feel
            val randomVolume = 1.0f - (Random.nextFloat() * 0.15f)
            mp.setVolume(randomVolume, randomVolume)

            // Randomize playback speed between 0.8x and 1.2x (±20%)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val randomSpeed = 0.8f + (Random.nextFloat() * 0.4f)
                    mp.playbackParams = PlaybackParams().apply {
                        speed = randomSpeed
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            try {
                // Boost audio session output
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

    /**
     * Stops the current playback and releases both [MediaPlayer] 
     * and [LoudnessEnhancer] resources.
     */
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
