package com.example.birdidentifier

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A singleton utility for playing random notification or deterrent sounds.
 *
 * It manages [MediaPlayer] instances and uses [LoudnessEnhancer] to boost
 * audio output levels significantly.
 * It also supports streaming audio to an external server.
 */
object SoundPlayer {
    private const val TAG = "SoundPlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    /**
     * Target gain for the loudness enhancer in mB (millibels).
     * 3000 mB equals +30 dB boost.
     */
    private const val BOOST_GAIN = 10000

    private const val PREFS_NAME = "BirdPrefs"
    private const val KEY_AUDIO_MODE = "audio_mode"
    private const val KEY_EXTERNAL_SERVER_IP = "external_server_ip"

    /**
     * Audio output modes.
     */
    enum class AudioMode(val value: Int) {
        PHONE_ONLY(1),
        EXTERNAL_ONLY(2),
        BOTH(3);

        companion object {
            fun fromInt(value: Int) = values().find { it.value == value } ?: PHONE_ONLY
        }
    }

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
     * Sets the audio output mode.
     */
    fun setAudioMode(context: Context, mode: AudioMode) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit().putInt(KEY_AUDIO_MODE, mode.value).apply()
        Log.d(TAG, "Audio mode set to $mode")
    }

    /**
     * Gets the current audio output mode.
     */
    fun getAudioMode(context: Context): AudioMode {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AudioMode.fromInt(sharedPrefs.getInt(KEY_AUDIO_MODE, AudioMode.PHONE_ONLY.value))
    }

    /**
     * Plays a random sound from the predefined [sounds] list based on the current mode.
     */
    @Synchronized
    fun play(context: Context) {
        Log.d(TAG, "Play command received.")
        val mode = getAudioMode(context)
        val randomSound = sounds.random()

        if (mode == AudioMode.PHONE_ONLY || mode == AudioMode.BOTH) {
            playOnPhone(context, randomSound)
        }

        if (mode == AudioMode.EXTERNAL_ONLY || mode == AudioMode.BOTH) {
            streamToExternalServer(context, randomSound)
        }
    }

    /**
     * Stops playback based on the current audio mode.
     */
    @Synchronized
    fun stop(context: Context) {
        Log.d(TAG, "Stop command received.")
        val mode = getAudioMode(context)

        if (mode == AudioMode.PHONE_ONLY || mode == AudioMode.BOTH) {
            stopPhonePlayback()
        }

        if (mode == AudioMode.EXTERNAL_ONLY || mode == AudioMode.BOTH) {
            stopExternalStream(context)
        }
    }

    /**
     * Plays a sound on the phone's speaker.
     */
    private fun playOnPhone(context: Context, resourceId: Int) {
        stopPhonePlayback()
        Log.d(TAG, "Attempting to create MediaPlayer for sound resource ID: $resourceId")

        mediaPlayer = try {
            MediaPlayer.create(context, resourceId)
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer.create() threw an exception for resource ID $resourceId", e)
            null
        }

        if (mediaPlayer == null) {
            Log.e(TAG, "MediaPlayer.create() returned null for resource ID $resourceId")
            return
        }

        mediaPlayer?.let { mp ->
            val randomVolume = 1.0f - (Random.nextFloat() * 0.15f)
            mp.setVolume(randomVolume, randomVolume)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val randomSpeed = 0.8f + (Random.nextFloat() * 0.4f)
                    mp.playbackParams = PlaybackParams().apply { speed = randomSpeed }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set playback speed", e)
                }
            }

            try {
                loudnessEnhancer = LoudnessEnhancer(mp.audioSessionId).apply {
                    setTargetGain(BOOST_GAIN)
                    enabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "LoudnessEnhancer initialization failed", e)
            }

            mp.setOnCompletionListener {
                Log.d(TAG, "Phone playback completed.")
                stopPhonePlayback()
            }

            mp.setOnErrorListener { _, _, _ ->
                stopPhonePlayback()
                true
            }

            mp.start()
            Log.d(TAG, "Phone playback started.")
        }
    }

    /**
     * Streams a sound to the external server.
     */
    private fun streamToExternalServer(context: Context, resourceId: Int) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverIp = sharedPrefs.getString(KEY_EXTERNAL_SERVER_IP, "") ?: ""
        if (serverIp.isBlank()) {
            Log.w(TAG, "Cannot stream: External server IP not set.")
            return
        }

        thread {
            try {
                Log.d(TAG, "Starting audio conversion for streaming: $resourceId")
                val wavData = AudioConverter.convertToWav22050(context, resourceId)
                if (wavData == null) {
                    Log.e(TAG, "Failed to convert audio for streaming.")
                    return@thread
                }

                val url = URL("http://$serverIp/stream")
                Log.d(TAG, "Streaming to external server: $url (${wavData.size} bytes)")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setRequestProperty("Content-Length", wavData.size.toString())
                connection.connectTimeout = 5000
                connection.readTimeout = 30000

                connection.outputStream.use { it.write(wavData) }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Stream successful. Server response: ${connection.inputStream.bufferedReader().readText()}")
                } else {
                    Log.e(TAG, "Stream failed. Server returned code $responseCode: ${connection.responseMessage}")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error during streaming to external server", e)
            }
        }
    }

    /**
     * Sends a stop command to the configured external server.
     */
    private fun stopExternalStream(context: Context) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverIp = sharedPrefs.getString(KEY_EXTERNAL_SERVER_IP, "") ?: ""
        if (serverIp.isBlank()) {
            Log.w(TAG, "Cannot send stop command: External server IP not set.")
            return
        }

        thread {
            try {
                val url = URL("http://$serverIp/stop")
                Log.d(TAG, "Sending STOP command to external server: $url")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "External server stop command successful.")
                } else {
                    Log.w(TAG, "External server stop command failed with code: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending stop command to external server", e)
            }
        }
    }

    private fun stopPhonePlayback() {
        if (mediaPlayer == null && loudnessEnhancer == null) return
        Log.d(TAG, "Stopping phone playback.")
        try {
            loudnessEnhancer?.apply { enabled = false; release() }
            loudnessEnhancer = null
            mediaPlayer?.apply { if (isPlaying) stop(); release() }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Exception during stopPhonePlayback()", e)
            mediaPlayer = null
            loudnessEnhancer = null
        }
    }
}
