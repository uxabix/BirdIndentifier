package com.example.birdidentifier

import android.content.Context
import android.graphics.BitmapFactory
import android.media.*
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class VideoWriter(private val context: Context) {

    private val TAG = "VideoWriter"
    private val BIT_RATE = 2000000 // 2 Mbps

    /**
     * Saves a list of JPEG frames with their respective capture timestamps.
     * This ensures the video matches the real-time capture speed.
     */
    fun saveVideoWithTimestamps(frames: List<Pair<ByteArray, Long>>) {
        if (frames.isEmpty()) return

        val thread = Thread {
            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            try {
                val totalTimeMs = frames.last().second - frames.first().second
                val averageFps = if (totalTimeMs > 0) (frames.size * 1000f / totalTimeMs) else 0f
                Log.d(TAG, "Recording session: ${frames.size} frames, total time: ${totalTimeMs}ms, avg capture FPS: $averageFps")

                // We'll use the average FPS for the encoder configuration, 
                // but the pacing logic will handle the real intervals.
                val configFps = if (averageFps > 1) averageFps.toInt() else 30

                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val dateString = dateFormat.format(Date(frames.first().second))
                
                val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                val outputFile = File(outputDir, "bird_${dateString}.mp4")
                
                Log.d(TAG, "Starting video save: ${outputFile.absolutePath}")

                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(frames[0].first, 0, frames[0].first.size, options)
                val width = options.outWidth
                val height = options.outHeight

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, configFps)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = codec?.createInputSurface() ?: return@Thread
                codec?.start()

                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var trackIndex = -1
                val bufferInfo = MediaCodec.BufferInfo()

                for (i in frames.indices) {
                    val frameData = frames[i].first
                    val currentTimestamp = frames[i].second
                    
                    // Pacing: Wait to match the real interval between frames
                    if (i > 0) {
                        val realInterval = currentTimestamp - frames[i-1].second
                        // We give a small buffer for processing time by subtracting a bit or just using sleep
                        if (realInterval > 0) {
                            Thread.sleep(realInterval)
                        }
                    }

                    val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
                    if (bitmap != null) {
                        val canvas = inputSurface.lockCanvas(null)
                        canvas.drawBitmap(bitmap, 0f, 0f, null)
                        inputSurface.unlockCanvasAndPost(canvas)
                        bitmap.recycle()
                    }

                    trackIndex = drainEncoder(codec!!, muxer!!, bufferInfo, trackIndex)
                }

                codec?.signalEndOfInputStream()
                drainEncoder(codec!!, muxer!!, bufferInfo, trackIndex, true)

                Log.d(TAG, "Video saved successfully: ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving video", e)
            } finally {
                try {
                    codec?.stop()
                    codec?.release()
                    muxer?.stop()
                    muxer?.release()
                } catch (e: Exception) { }
            }
        }
        thread.start()
    }

    private fun drainEncoder(
        codec: MediaCodec, 
        muxer: MediaMuxer, 
        bufferInfo: MediaCodec.BufferInfo, 
        currentTrackIndex: Int,
        endOfStream: Boolean = false
    ): Int {
        var trackIndex = currentTrackIndex
        val timeoutUs = if (endOfStream) 10000L else 0L

        while (true) {
            val outBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (trackIndex == -1) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
            } else if (outBufferIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outBufferIndex)
                if (encodedData != null && bufferInfo.size != 0 && trackIndex != -1) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                }
                codec.releaseOutputBuffer(outBufferIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (outBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else {
                break
            }
        }
        return trackIndex
    }
}
