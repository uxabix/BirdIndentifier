package com.example.birdidentifier

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility class for converting audio resources to a specific WAV format.
 */
object AudioConverter {
    private const val TAG = "AudioConverter"
    private const val TARGET_SAMPLE_RATE = 22050
    private const val TARGET_CHANNELS = 1

    /**
     * Converts a raw resource (e.g., MP3) to a WAV byte array (16-bit PCM, 22050 Hz, Mono).
     *
     * @param context The Android context.
     * @param resourceId The resource ID of the audio file.
     * @return A byte array containing the WAV file, or null if conversion fails.
     */
    fun convertToWav22050(context: Context, resourceId: Int): ByteArray? {
        val extractor = MediaExtractor()
        val afd = context.resources.openRawResourceFd(resourceId) ?: return null
        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()

            val trackIndex = selectTrack(extractor)
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmData = decodeToPcm(extractor, codec)
            codec.stop()
            codec.release()
            extractor.release()

            if (pcmData == null) return null

            val inputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val processedPcm = processPcm(pcmData, inputSampleRate, inputChannels, TARGET_SAMPLE_RATE, TARGET_CHANNELS)
            return addWavHeader(processedPcm, TARGET_SAMPLE_RATE, TARGET_CHANNELS)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting audio resource $resourceId", e)
            return null
        }
    }

    private fun selectTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return i
        }
        return -1
    }

    private fun decodeToPcm(extractor: MediaExtractor, codec: MediaCodec): ByteArray? {
        val info = MediaCodec.BufferInfo()
        val allData = mutableListOf<ByteArray>()
        var sawInputEOS = false
        var sawOutputEOS = false

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inputBufferIndex = codec.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputBufferIndex = codec.dequeueOutputBuffer(info, 10000)
            if (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue
                val chunk = ByteArray(info.size)
                outputBuffer.get(chunk)
                outputBuffer.clear()
                allData.add(chunk)
                codec.releaseOutputBuffer(outputBufferIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true
                }
            }
        }

        val totalSize = allData.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in allData) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }

    private fun processPcm(
        data: ByteArray,
        inSampleRate: Int,
        inChannels: Int,
        outSampleRate: Int,
        outChannels: Int
    ): ByteArray {
        // First, handle channel conversion (Stereo to Mono if needed)
        var pcm16 = data
        if (inChannels == 2 && outChannels == 1) {
            val monoData = ByteArray(data.size / 2)
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val monoBuffer = ByteBuffer.wrap(monoData).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.hasRemaining()) {
                val left = buffer.short.toInt()
                val right = buffer.short.toInt()
                val avg = (left + right) / 2
                monoBuffer.putShort(avg.toShort())
            }
            pcm16 = monoData
        }

        // Then, handle resampling
        if (inSampleRate == outSampleRate) {
            return pcm16
        }

        // Simple linear interpolation resampling
        val inBuffer = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val inCount = inBuffer.remaining()
        val outCount = (inCount.toLong() * outSampleRate / inSampleRate).toInt()
        val outData = ByteArray(outCount * 2)
        val outBuffer = ByteBuffer.wrap(outData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until outCount) {
            val pos = i.toDouble() * inSampleRate / outSampleRate
            val index = pos.toInt()
            val frac = pos - index
            
            if (index + 1 < inCount) {
                val s1 = inBuffer.get(index).toInt()
                val s2 = inBuffer.get(index + 1).toInt()
                val s = (s1 + frac * (s2 - s1)).toInt()
                outBuffer.put(s.toShort())
            } else {
                outBuffer.put(inBuffer.get(index))
            }
        }
        return outData
    }

    private fun addWavHeader(pcmData: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val totalDataLen = pcmData.size.toLong()
        val totalAudioLen = totalDataLen + 36
        val byteRate = (sampleRate * channels * 16 / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalAudioLen and 0xff).toByte()
        header[5] = (totalAudioLen shr 8 and 0xff).toByte()
        header[6] = (totalAudioLen shr 16 and 0xff).toByte()
        header[7] = (totalAudioLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16 // length of format chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate.toLong() and 0xff).toByte()
        header[25] = (sampleRate.toLong() shr 8 and 0xff).toByte()
        header[26] = (sampleRate.toLong() shr 16 and 0xff).toByte()
        header[27] = (sampleRate.toLong() shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalDataLen and 0xff).toByte()
        header[41] = (totalDataLen shr 8 and 0xff).toByte()
        header[42] = (totalDataLen shr 16 and 0xff).toByte()
        header[43] = (totalDataLen shr 24 and 0xff).toByte()

        val wav = ByteArray(header.size + pcmData.size)
        System.arraycopy(header, 0, wav, 0, header.size)
        System.arraycopy(pcmData, 0, wav, header.size, pcmData.size)
        return wav
    }
}
