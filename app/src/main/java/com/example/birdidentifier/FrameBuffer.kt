package com.example.birdidentifier

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object FrameBuffer {
    val latestFrame = AtomicReference<ByteArray>()
    val lastMotionTime = AtomicLong(0)
}
