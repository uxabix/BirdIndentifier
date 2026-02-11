package com.example.birdidentifier

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections

/**
 * A global singleton that acts as a synchronized data bridge between the 
 * [CameraService], the [DeviceServer], and the [VideoWriter].
 *
 * It holds the latest camera frame for live streaming and a buffer of frames 
 * for video recording.
 */
object FrameBuffer {
    /**
     * The most recent camera frame captured, stored as a JPEG byte array.
     * Used primarily by [DeviceServer] for MJPEG streaming.
     */
    val latestFrame = AtomicReference<ByteArray>()

    /**
     * The timestamp (in milliseconds) of the last detected motion.
     * Updated by [CameraService] when motion exceeds the sensitivity threshold.
     */
    val lastMotionTime = AtomicLong(0)

    /**
     * A synchronized list of frames currently held in memory for video recording.
     * Each element is a [Pair] containing the JPEG byte array and its capture timestamp.
     * This buffer is consumed and cleared by [VideoWriter] or [CameraService].
     */
    val recordingBuffer: MutableList<Pair<ByteArray, Long>> = 
        Collections.synchronizedList(mutableListOf())

    /**
     * A flag indicating whether manual recording has been triggered via 
     * the web interface.
     */
    val isManualRecording = AtomicBoolean(false)

    /**
     * The current zoom ratio of the camera.
     * A value of 1.0 means no zoom.
     */
    val zoomLevel = AtomicReference(1.0f)

    /**
     * A flag to signal that the zoom level has been changed from the web interface.
     */
    val zoomChanged = AtomicBoolean(false)
}
