package com.example.birdidentifier

import android.content.Context
import android.graphics.BitmapFactory
import android.media.*
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A utility class responsible for encoding a sequence of JPEG frames into an MP4 video file.
 * Supports streaming frames directly to disk with pacing to avoid "fast-forward" effects at the start.
 */
class VideoWriter(private val context: Context) {

    private val TAG = "VideoWriter"
    private val BIT_RATE = 2000000
    private val FRAME_RATE = 30 
    private val FRAME_INTERVAL_MS = 1000L / FRAME_RATE

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var pfd: android.os.ParcelFileDescriptor? = null
    private var inputSurface: android.view.Surface? = null
    private var trackIndex = -1
    private val bufferInfo = MediaCodec.BufferInfo()
    
    private val isRecording = AtomicBoolean(false)
    private var writerThread: HandlerThread? = null
    private var writerHandler: Handler? = null
    
    private var lastFrameTimeMs: Long = 0

    /**
     * Starts a new video recording session.
     */
    fun startRecording(width: Int, height: Int) {
        if (isRecording.getAndSet(true)) return

        // Run storage cleanup in a separate thread to not block the writer initialization
        Thread { StorageManager.checkAndCleanup(context) }.start()

        writerThread = HandlerThread("VideoWriterThread").apply { start() }
        writerHandler = Handler(writerThread!!.looper)

        writerHandler?.post {
            try {
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val fileName = "bird_${dateFormat.format(Date())}.mp4"

                val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
                val folderUriString = sharedPrefs.getString("save_folder_uri", null)

                if (folderUriString != null) {
                    val treeUri = Uri.parse(folderUriString)
                    val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
                    val videoFile = pickedDir?.createFile("video/mp4", fileName)
                    if (videoFile != null) {
                        pfd = context.contentResolver.openFileDescriptor(videoFile.uri, "rw")
                        muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    }
                }

                if (muxer == null) {
                    val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                    val outputFile = File(outputDir, fileName)
                    muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = codec?.createInputSurface()
                codec?.start()
                
                trackIndex = -1
                lastFrameTimeMs = System.currentTimeMillis()
                Log.d(TAG, "Recording started: $fileName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                stopRecording()
            }
        }
    }

    /**
     * Adds a single JPEG frame with pacing to maintain correct playback speed.
     */
    fun addFrame(jpegData: ByteArray) {
        if (!isRecording.get()) return

        writerHandler?.post {
            val currentCodec = codec ?: return@post
            val currentMuxer = muxer ?: return@post
            val surface = inputSurface ?: return@post

            try {
                // Pacing: Ensure we don't process frames faster than the target frame rate
                // This prevents the "fast-forward" effect if frames were queued during init.
                val now = System.currentTimeMillis()
                val timeSinceLastFrame = now - lastFrameTimeMs
                if (timeSinceLastFrame < FRAME_INTERVAL_MS) {
                    Thread.sleep(FRAME_INTERVAL_MS - timeSinceLastFrame)
                }
                lastFrameTimeMs = System.currentTimeMillis()

                val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                if (bitmap != null) {
                    val canvas = surface.lockCanvas(null)
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                    surface.unlockCanvasAndPost(canvas)
                    bitmap.recycle()
                    
                    trackIndex = drainEncoder(currentCodec, currentMuxer, bufferInfo, trackIndex)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding frame", e)
            }
        }
    }

    /**
     * Finalizes the video recording.
     */
    fun stopRecording() {
        if (!isRecording.getAndSet(false)) return

        writerHandler?.post {
            try {
                codec?.signalEndOfInputStream()
                if (codec != null && muxer != null) {
                    drainEncoder(codec!!, muxer!!, bufferInfo, trackIndex, true)
                }
                Log.d(TAG, "Recording stopped and finalized.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording", e)
            } finally {
                releaseResources()
            }
        }
        writerThread?.quitSafely()
    }

    private fun releaseResources() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) { }
        codec = null

        try {
            muxer?.stop()
            muxer?.release()
        } catch (e: Exception) { }
        muxer = null

        try {
            pfd?.close()
        } catch (e: Exception) { }
        pfd = null
        
        inputSurface = null
        trackIndex = -1
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        currentTrackIndex: Int,
        endOfStream: Boolean = false
    ): Int {
        var track = currentTrackIndex
        val timeoutUs = if (endOfStream) 10000L else 0L
        
        while (true) {
            val outBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (track == -1) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
            } else if (outBufferIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outBufferIndex)
                if (encodedData != null && bufferInfo.size != 0 && track != -1) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(track, encodedData, bufferInfo)
                }
                codec.releaseOutputBuffer(outBufferIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (outBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else {
                break
            }
        }
        return track
    }
}
