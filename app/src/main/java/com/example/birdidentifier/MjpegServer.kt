package com.example.birdidentifier

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

class MjpegServer(
    port: Int,
    private val frameProvider: AtomicReference<ByteArray>
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/mjpeg") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }

        return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--frame", MjpegInputStream())
    }

    inner class MjpegInputStream : InputStream() {
        private var frameStream: ByteArrayInputStream? = null
        private var lastFrame: ByteArray? = null

        private fun getNextFrameStream(): Boolean {
            try {
                var frame: ByteArray?
                while (true) {
                    frame = frameProvider.get()
                    if (frame != null && frame.isNotEmpty() && frame !== lastFrame) {
                        lastFrame = frame
                        break
                    }
                    Thread.sleep(50) // Wait for a new frame, ~20 FPS
                }

                val header = (
                        "--frame\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.size}\r\n" +
                        "\r\n"
                        ).toByteArray()
                frameStream = ByteArrayInputStream(header + frame + "\r\n".toByteArray())
                return true
            } catch (e: InterruptedException) {
                return false
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                if (frameStream == null || frameStream!!.available() == 0) {
                    if (!getNextFrameStream()) {
                        return -1
                    }
                }

                val readBytes = frameStream!!.read(b, off, len)
                if (readBytes > 0) {
                    return readBytes
                }
                frameStream = null
            }
        }

        override fun read(): Int {
            // Not used, but must be implemented
            return -1
        }
    }
}
