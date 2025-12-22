package com.example.birdidentifier

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections

object FrameBuffer {
    val latestFrame = AtomicReference<ByteArray>()
    val lastMotionTime = AtomicLong(0)

    // Buffer for recording: stores Pair of (JPEG data, Timestamp in ms)
    val recordingBuffer = Collections.synchronizedList(mutableListOf<Pair<ByteArray, Long>>())

    // Flag for manual recording from web interface
    val isManualRecording = AtomicBoolean(false)
}
