package com.example.birdidentifier

import android.content.Context
import android.graphics.BitmapFactory
import android.media.*
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class VideoWriter(private val context: Context) {

    private val TAG = "VideoWriter"
    private val BIT_RATE = 2000000 // 2 Mbps

    fun saveVideoWithTimestamps(frames: List<Pair<ByteArray, Long>>) {
        if (frames.isEmpty()) return

        val thread = Thread {
            // Run cleanup before saving
            StorageManager.checkAndCleanup(context)

            if (!StorageManager.hasEnoughSpace(context)) {
                Log.e(TAG, "Cannot save video: Not enough free space even after cleanup!")
                return@Thread
            }

            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var pfd: android.os.ParcelFileDescriptor? = null
            try {
                val totalTimeMs = frames.last().second - frames.first().second
                val averageFps = if (totalTimeMs > 0) (frames.size * 1000f / totalTimeMs) else 0f
                val configFps = if (averageFps > 1) averageFps.toInt() else 30

                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val dateString = dateFormat.format(Date(frames.first().second))
                val fileName = "bird_${dateString}.mp4"

                val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
                val folderUriString = sharedPrefs.getString("save_folder_uri", null)

                if (folderUriString != null) {
                    val treeUri = Uri.parse(folderUriString)
                    val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
                    val videoFile = pickedDir?.createFile("video/mp4", fileName)
                    if (videoFile != null) {
                        pfd = context.contentResolver.openFileDescriptor(videoFile.uri, "rw")
                        muxer = MediaMuxer(
                            pfd!!.fileDescriptor,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                        )
                    }
                }

                if (muxer == null) {
                    val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                    val outputFile = File(outputDir, fileName)
                    muxer = MediaMuxer(
                        outputFile.absolutePath,
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    )
                }

                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(frames[0].first, 0, frames[0].first.size, options)
                val width = options.outWidth
                val height = options.outHeight

                val format =
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, configFps)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = codec?.createInputSurface() ?: return@Thread
                codec?.start()

                var trackIndex = -1
                val bufferInfo = MediaCodec.BufferInfo()

                for (i in frames.indices) {
                    val startTime = System.currentTimeMillis()
                    if (i > 0) {
                        val interval = frames[i].second - frames[i - 1].second
                        if (interval > 0) Thread.sleep(interval)
                    }

                    val bitmap =
                        BitmapFactory.decodeByteArray(frames[i].first, 0, frames[i].first.size)
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
                Log.d(TAG, "Video saved successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving video", e)
            } finally {
                try {
                    codec?.stop(); codec?.release()
                    muxer?.stop(); muxer?.release()
                    pfd?.close()
                } catch (e: Exception) {
                }
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
