package com.example.birdidentifier

import android.content.Context
import android.os.Environment
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
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
                createHtmlResponse("Sound played")
            }

            "/stop" -> {
                SoundPlayer.stop()
                createHtmlResponse("Sound stopped")
            }

            "/motion-status" -> {
                val time = FrameBuffer.lastMotionTime.get()
                newFixedLengthResponse(Response.Status.OK, "text/plain", time.toString())
            }

            "/videos" -> {
                listVideos()
            }

            "/video" -> {
                serveVideo(session.parameters["name"]?.firstOrNull())
            }

            "/delete-video" -> {
                deleteVideo(session.parameters["name"]?.firstOrNull())
            }

            "/" -> createHtmlResponse("Camera control")

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Not found"
            )
        }
    }

    private fun deleteVideo(fileName: String?): Response {
        if (fileName == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing name")
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val file = File(moviesDir, fileName)
        
        return if (file.exists() && file.delete()) {
            newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
                addHeader("Location", "/videos")
            }
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Could not delete file")
        }
    }

    private fun listVideos(): Response {
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val files = moviesDir?.listFiles { file -> file.extension.lowercase() == "mp4" }
            ?.sortedByDescending { it.name } ?: emptyList()
        
        val listHtml = files.joinToString("") { file ->
            """
            <li>
                <div class="video-info">
                    <a href='/video?name=${file.name}'>${file.name}</a> 
                    <span class="file-size">(${file.length() / 1024} KB)</span>
                </div>
                <button class="delete-btn" onclick="if(confirm('Delete ${file.name}?')) location.href='/delete-video?name=${file.name}'" title="Delete Video">DEL 🗑️</button>
            </li>
            """.trimIndent()
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Recorded Videos</title>
                <style>
                    body { font-family: sans-serif; padding: 20px; background: #f0f0f0; margin: 0; }
                    .container { max-width: 600px; margin: auto; background: white; padding: 20px; border-radius: 15px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }
                    h2 { color: #333; border-bottom: 2px solid #eee; padding-bottom: 10px; }
                    ul { list-style: none; padding: 0; }
                    li { padding: 15px; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; align-items: center; }
                    li:last-child { border-bottom: none; }
                    .video-info { display: flex; flex-direction: column; padding-right: 10px; }
                    .file-size { color:#888; font-size: 0.85em; margin-top: 4px; }
                    a { text-decoration: none; color: #2196F3; font-weight: bold; word-break: break-all; }
                    a:hover { text-decoration: underline; }
                    .delete-btn { 
                        background: #fff5f5; border: 1px solid #ffcdd2; color: #d32f2f; 
                        padding: 8px 12px; border-radius: 6px; cursor: pointer; font-size: 0.9em;
                        font-weight: bold; flex-shrink: 0;
                        transition: all 0.2s;
                    }
                    .delete-btn:hover { background: #ffebee; border-color: #f44336; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                    .back-link { display: inline-block; margin-bottom: 20px; color: #666; font-weight: bold; text-decoration: none; }
                </style>
            </head>
            <body>
                <div class="container">
                    <a href="/" class="back-link">← Back to Stream</a>
                    <h2>Recorded Bird Fragments</h2>
                    <ul>$listHtml</ul>
                    ${if (files.isEmpty()) "<p style='text-align:center; color:#999;'>No videos recorded yet.</p>" else ""}
                </div>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveVideo(fileName: String?): Response {
        if (fileName == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing name")
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val file = File(moviesDir, fileName)
        if (!file.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")

        return try {
            val fis = FileInputStream(file)
            newFixedLengthResponse(Response.Status.OK, "video/mp4", fis, file.length())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
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
                    .container { max-width: 600px; margin: auto; background: white; padding: 20px; border-radius: 15px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }
                    img { width: 100%; border-radius: 10px; border: 2px solid #333; margin-bottom: 20px; }
                    button { 
                        width: 80%; padding: 15px; margin: 10px; font-size: 18px; cursor: pointer;
                        border: none; border-radius: 8px; color: white; transition: opacity 0.2s;
                    }
                    .btn-play { background-color: #4CAF50; }
                    .btn-stop { background-color: #f44336; }
                    .btn-videos { background-color: #2196F3; }
                    button:active { opacity: 0.7; }
                    .info-panel { background: #eee; padding: 10px; border-radius: 8px; margin: 10px; font-size: 16px; }
                    #motion-time { font-weight: bold; color: #d32f2f; }
                    #status { color: #666; font-size: 14px; margin-top: 10px; }
                </style>
                <script>
                    function sendCommand(path) {
                        fetch(path).then(() => {
                            document.getElementById('status').innerText = 'Command ' + path + ' was sent';
                        });
                    }

                    function updateMotionStatus() {
                        fetch('/motion-status')
                            .then(response => response.text())
                            .then(timestamp => {
                                if (timestamp === "0") {
                                    document.getElementById('motion-time').innerText = "No movement detected";
                                } else {
                                    const date = new Date(parseInt(timestamp));
                                    const timeStr = date.toLocaleTimeString() + "." + String(date.getMilliseconds()).padStart(3, '0');
                                    document.getElementById('motion-time').innerText = timeStr;
                                    
                                    const diff = Date.now() - parseInt(timestamp);
                                    if (diff < 10000) {
                                        document.getElementById('motion-time').style.color = "red";
                                        document.getElementById('motion-time').style.fontSize = "20px";
                                    } else {
                                        document.getElementById('motion-time').style.color = "black";
                                        document.getElementById('motion-time').style.fontSize = "16px";
                                    }
                                }
                            });
                    }

                    // Refresh status each 500ms
                    setInterval(updateMotionStatus, 500);
                </script>
            </head>
            <body>
                <div class="container">
                    <h2>$status</h2>
                    <img src="/mjpeg" alt="Camera Stream">
                    
                    <div class="info-panel">
                        Last movement: <span id="motion-time">Loading...</span>
                    </div>

                    <button class="btn-play" onclick="sendCommand('/play')">🔊 PLAY</button>
                    <br>
                    <button class="btn-stop" onclick="sendCommand('/stop')">🛑 STOP</button>
                    <br>
                    <button class="btn-videos" onclick="location.href='/videos'">📂 VIEW RECORDINGS</button>
                    <div id="status">Waiting for commands...</div>
                </div>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
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
                    Thread.sleep(10)
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
