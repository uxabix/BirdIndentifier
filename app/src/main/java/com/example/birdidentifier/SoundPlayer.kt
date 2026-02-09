package com.example.birdidentifier

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import kotlin.random.Random

/**
 * A singleton utility for playing random notification or deterrent sounds.
 * 
 * It manages [MediaPlayer] instances and uses [LoudnessEnhancer] to boost 
 * audio output levels significantly.
 */
object SoundPlayer {
    private const val TAG = "SoundPlayer"
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
     * @throws IllegalStateException if the MediaPlayer cannot be created.
     */
    @Synchronized
    fun play(context: Context) {
        Log.d(TAG, "Play command received.")
        stop()

        val randomSound = sounds.random()
        Log.d(TAG, "Attempting to create MediaPlayer for sound resource ID: $randomSound")
        
        mediaPlayer = try {
            MediaPlayer.create(context, randomSound)
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer.create() threw an exception for resource ID $randomSound", e)
            throw IllegalStateException("Failed to load sound resource: ${e.message}", e)
        }

        if (mediaPlayer == null) {
            val errorMsg = "MediaPlayer.create() returned null for resource ID $randomSound. The file may be missing, corrupt, or in an unsupported format."
            Log.e(TAG, errorMsg)
            throw IllegalStateException(errorMsg)
        }

        mediaPlayer?.let { mp ->
            Log.d(TAG, "MediaPlayer created successfully. Audio session ID: ${mp.audioSessionId}")
            val randomVolume = 1.0f - (Random.nextFloat() * 0.15f)
            mp.setVolume(randomVolume, randomVolume)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val randomSpeed = 0.8f + (Random.nextFloat() * 0.4f)
                    mp.playbackParams = PlaybackParams().apply { speed = randomSpeed }
                    Log.d(TAG, "Playback speed set to ${randomSpeed}x")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set playback speed", e)
                }
            }

            // Temporarily disable LoudnessEnhancer for debugging as it can cause issues on some devices.
            /*
            try {
                loudnessEnhancer = LoudnessEnhancer(mp.audioSessionId).apply {
                    setTargetGain(BOOST_GAIN)
                    enabled = true
                }
                Log.d(TAG, "LoudnessEnhancer enabled with ${BOOST_GAIN}mB gain.")
            } catch (e: Exception) {
                Log.e(TAG, "LoudnessEnhancer initialization failed", e)
            }
            */

            mp.setOnCompletionListener {
                Log.d(TAG, "Playback completed.")
                stop()
            }

            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer internal error. What: $what, Extra: $extra")
                stop() // Stop and release resources on error
                true // Returning true indicates the error was handled
            }

            mp.start()
            Log.d(TAG, "Playback started.")
        }
    }

    /**
     * Stops the current playback and releases both [MediaPlayer] 
     * and [LoudnessEnhancer] resources.
     */
    @Synchronized
    fun stop() {
        if (mediaPlayer == null && loudnessEnhancer == null) {
            return // Nothing to do
        }
        Log.d(TAG, "Stop command received.")
        try {
            loudnessEnhancer?.let {
                it.enabled = false
                it.release()
                Log.d(TAG, "LoudnessEnhancer released.")
            }
            loudnessEnhancer = null

            mediaPlayer?.let{
                if (it.isPlaying) {
                    it.stop()
                    Log.d(TAG, "MediaPlayer stopped.")
                }
                it.release()
                Log.d(TAG, "MediaPlayer released.")
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Exception during stop()", e)
            mediaPlayer = null
            loudnessEnhancer = null
        }
    }
}
