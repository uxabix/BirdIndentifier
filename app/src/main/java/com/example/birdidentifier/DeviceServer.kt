package com.example.birdidentifier

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
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
            "/mjpeg" -> newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--frame", MjpegInputStream())
            "/play" -> { SoundPlayer.play(context); createHtmlResponse("Sound played") }
            "/stop" -> { SoundPlayer.stop(); createHtmlResponse("Sound stopped") }
            "/motion-status" -> newFixedLengthResponse(Response.Status.OK, "text/plain", FrameBuffer.lastMotionTime.get().toString())
            "/videos" -> listVideos()
            "/video" -> serveVideo(session.parameters["name"]?.firstOrNull())
            "/delete-video" -> deleteVideo(session.parameters["name"]?.firstOrNull())
            "/reset-folder" -> resetFolder()
            "/" -> createHtmlResponse("Camera control")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun resetFolder(): Response {
        val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().remove("save_folder_uri").apply()
        return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply { addHeader("Location", "/") }
    }

    private fun getCurrentFolderDisplayName(): String {
        val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        val uriStr = sharedPrefs.getString("save_folder_uri", null) ?: return "Default (Internal Movies)"
        return try {
            val treeUri = Uri.parse(uriStr)
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
            pickedDir?.name ?: "Custom SD/Folder"
        } catch (e: Exception) {
            "Custom Folder"
        }
    }

    private fun getFileFromAnywhere(fileName: String): Any? {
        val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        val folderUriString = sharedPrefs.getString("save_folder_uri", null)
        
        if (folderUriString != null) {
            val treeUri = Uri.parse(folderUriString)
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
            val safFile = pickedDir?.findFile(fileName)
            if (safFile != null && safFile.exists()) return safFile
        }

        val internalFile = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName)
        if (internalFile.exists()) return internalFile
        
        return null
    }

    private fun deleteVideo(fileName: String?): Response {
        if (fileName == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing name")
        val fileObj = getFileFromAnywhere(fileName)
        
        val deleted = when (fileObj) {
            is DocumentFile -> fileObj.delete()
            is File -> fileObj.delete()
            else -> false
        }

        return if (deleted) {
            newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply { addHeader("Location", "/videos") }
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Could not delete file")
        }
    }

    private fun listVideos(): Response {
        val allFiles = mutableListOf<Pair<String, Long>>()
        val internalDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        internalDir?.listFiles { f -> f.extension.lowercase() == "mp4" }?.forEach { allFiles.add(it.name to it.length()) }

        val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        sharedPrefs.getString("save_folder_uri", null)?.let { uriStr ->
            DocumentFile.fromTreeUri(context, Uri.parse(uriStr))?.listFiles()?.forEach { 
                if (it.name?.lowercase()?.endsWith(".mp4") == true) { allFiles.add(it.name!! to it.length()) }
            }
        }

        val sortedFiles = allFiles.distinctBy { it.first }.sortedByDescending { it.first }
        val listHtml = sortedFiles.joinToString("") { (name, size) ->
            "<li><div class='video-info'><a href='/video?name=$name'>$name</a><span class='file-size'>(${size / 1024} KB)</span></div>" +
            "<button class='delete-btn' onclick=\"if(confirm('Delete $name?')) location.href='/delete-video?name=$name'\">🗑️</button></li>"
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
                    .video-info { display: flex; flex-direction: column; padding-right: 10px; }
                    .file-size { color:#888; font-size: 0.85em; margin-top: 4px; }
                    a { text-decoration: none; color: #2196F3; font-weight: bold; word-break: break-all; }
                    .delete-btn { background: #fff5f5; border: 1px solid #ffcdd2; color: #d32f2f; padding: 8px 12px; border-radius: 6px; cursor: pointer; font-size: 0.9em; font-weight: bold; flex-shrink: 0; }
                    .back-link { display: inline-block; margin-bottom: 20px; color: #666; font-weight: bold; text-decoration: none; }
                </style>
            </head>
            <body>
                <div class="container">
                    <a href="/" class="back-link">← Back to Stream</a>
                    <h2>Recorded Bird Fragments</h2>
                    <ul>$listHtml</ul>
                    ${if (sortedFiles.isEmpty()) "<p style='text-align:center; color:#999;'>No videos recorded yet.</p>" else ""}
                </div>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveVideo(fileName: String?): Response {
        if (fileName == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing name")
        val fileObj = getFileFromAnywhere(fileName)
        return try {
            val (inputStream, length) = when (fileObj) {
                is DocumentFile -> context.contentResolver.openInputStream(fileObj.uri) to fileObj.length()
                is File -> FileInputStream(fileObj) to fileObj.length()
                else -> null to 0L
            }
            if (inputStream != null) newFixedLengthResponse(Response.Status.OK, "video/mp4", inputStream, length)
            else newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        } catch (e: Exception) { newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message) }
    }

    private fun createHtmlResponse(status: String): Response {
        val folderName = getCurrentFolderDisplayName()
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
                    button { width: 80%; padding: 15px; margin: 10px; font-size: 18px; cursor: pointer; border: none; border-radius: 8px; color: white; transition: opacity 0.2s; }
                    .btn-play { background-color: #4CAF50; }
                    .btn-stop { background-color: #f44336; }
                    .btn-videos { background-color: #2196F3; }
                    .btn-reset { background-color: #607D8B; font-size: 14px; padding: 10px; width: auto; }
                    .info-panel { background: #eee; padding: 10px; border-radius: 8px; margin: 10px; font-size: 16px; }
                    .folder-panel { background: #e3f2fd; padding: 10px; border-radius: 8px; margin: 10px; font-size: 14px; color: #1565C0; }
                    #motion-time { font-weight: bold; color: #d32f2f; }
                    #status { color: #666; font-size: 14px; margin-top: 10px; }
                </style>
                <script>
                    function sendCommand(path) { fetch(path).then(() => { document.getElementById('status').innerText = 'Command ' + path + ' was sent'; }); }
                    function updateMotionStatus() {
                        fetch('/motion-status').then(r => r.text()).then(t => {
                            if (t === "0") { document.getElementById('motion-time').innerText = "No movement detected"; } else {
                                const d = new Date(parseInt(t));
                                document.getElementById('motion-time').innerText = d.toLocaleTimeString() + "." + String(d.getMilliseconds()).padStart(3, '0');
                            }
                        });
                    }
                    setInterval(updateMotionStatus, 500);
                </script>
            </head>
            <body>
                <div class="container">
                    <h2>$status</h2>
                    <img src="/mjpeg" alt="Camera Stream">
                    <div class="info-panel">Last movement: <span id="motion-time">Loading...</span></div>
                    <div class="folder-panel">
                        Saving to: <strong>$folderName</strong><br>
                        <button class="btn-reset" onclick="if(confirm('Reset to internal storage?')) location.href='/reset-folder'">Reset to Default</button>
                    </div>
                    <button class="btn-play" onclick="sendCommand('/play')">🔊 PLAY</button><br>
                    <button class="btn-stop" onclick="sendCommand('/stop')">🛑 STOP</button><br>
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
                    if (frame != null && frame.isNotEmpty() && frame !== lastFrame) { lastFrame = frame; break }
                    Thread.sleep(10)
                }
                val header = ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n").toByteArray()
                frameStream = ByteArrayInputStream(header + frame + "\r\n".toByteArray())
                return true
            } catch (e: InterruptedException) { return false }
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                if (frameStream == null || frameStream!!.available() == 0) { if (!getNextFrameStream()) return -1 }
                val readBytes = frameStream!!.read(b, off, len)
                if (readBytes > 0) return readBytes
                frameStream = null
            }
        }
        override fun read(): Int = -1
    }
}
