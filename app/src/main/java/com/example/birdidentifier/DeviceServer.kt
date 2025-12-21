package com.example.birdidentifier

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

class DeviceServer(
    port: Int,
    private val frameProvider: AtomicReference<ByteArray>,
    private val context: Context
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/mjpeg" -> newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=--frame",
                MjpegInputStream()
            )

            "/play" -> {
                SoundPlayer.play(context)
                createHtmlResponse("Sound started")
            }

            "/stop" -> {
                SoundPlayer.stop()
                createHtmlResponse("Sound stopped")
            }

            "/" -> createHtmlResponse("Camera control")

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Not found"
            )
        }
    }

    private fun createHtmlResponse(status: String): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Bird Identifier Control</title>
                <style>
                    body { font-family: sans-serif; text-align: center; background: #f0f0f0; margin: 0; padding: 20px; }
                    .container { max-width: 600px; margin: auto; background: white; padding: 20px; border-radius: 15px; shadow: 0 4px 6px rgba(0,0,0,0.1); }
                    img { width: 100%; border-radius: 10px; border: 2px solid #333; margin-bottom: 20px; }
                    button { 
                        width: 80%; padding: 15px; margin: 10px; font-size: 18px; cursor: pointer;
                        border: none; border-radius: 8px; color: white; transition: opacity 0.2s;
                    }
                    .btn-play { background-color: #4CAF50; }
                    .btn-stop { background-color: #f44336; }
                    button:active { opacity: 0.7; }
                    #status { color: #666; font-size: 14px; margin-top: 10px; }
                </style>
                <script>
                    function sendCommand(path) {
                        fetch(path).then(() => {
                            document.getElementById('status').innerText = 'Command ' + path + ' was sent';
                        });
                    }
                </script>
            </head>
            <body>
                <div class="container">
                    <h2>$status</h2>
                    <img src="/mjpeg" alt="Camera Stream">
                    <br>
                    <button class="btn-play" onclick="sendCommand('/play')">🔊 PLAY SOUND</button>
                    <br>
                    <button class="btn-stop" onclick="sendCommand('/stop')">🛑 STOP</button>
                    <div id="status">Waiting for command...</div>
                </div>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
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
            return -1
        }
    }
}
