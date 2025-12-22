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
            "/mjpeg" -> newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=--frame",
                MjpegInputStream()
            )

            "/play" -> {
                SoundPlayer.play(context); createHtmlResponse("Sound played")
            }

            "/stop" -> {
                SoundPlayer.stop(); createHtmlResponse("Sound stopped")
            }

            "/motion-status" -> newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                FrameBuffer.lastMotionTime.get().toString()
            )

            "/videos" -> listVideos()
            "/video" -> serveVideo(session.parameters["name"]?.firstOrNull())
            "/delete-video" -> deleteVideo(session.parameters["name"]?.firstOrNull())
            "/mark-important" -> markImportant(
                session.parameters["name"]?.firstOrNull(),
                session.parameters["important"]?.firstOrNull() == "true"
            )

            "/update-storage-settings" -> updateStorageSettings(session.parameters)
            "/reset-folder" -> resetFolder()
            "/start-rec" -> {
                FrameBuffer.isManualRecording.set(true)
                createHtmlResponse("Recording started")
            }

            "/stop-rec" -> {
                FrameBuffer.isManualRecording.set(false)
                createHtmlResponse("Recording stopping...")
            }

            "/rec-status" -> newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                FrameBuffer.isManualRecording.get().toString()
            )

            "/" -> createHtmlResponse("Camera control")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun readAsset(fileName: String): String {
        return try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Error loading template: ${e.message}"
        }
    }

    private fun markImportant(name: String?, important: Boolean): Response {
        if (name == null) return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "text/plain",
            "Missing name"
        )
        StorageManager.markImportant(context, name, important)
        return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
            addHeader(
                "Location",
                "/videos"
            )
        }
    }

    private fun updateStorageSettings(params: Map<String, List<String>>): Response {
        val maxTotal = params["max_total"]?.firstOrNull()?.toFloatOrNull() ?: 5.0f
        val minFree = params["min_free"]?.firstOrNull()?.toFloatOrNull() ?: 1.0f
        StorageManager.saveSettings(context, maxTotal, minFree)
        return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
            addHeader(
                "Location",
                "/"
            )
        }
    }

    private fun resetFolder(): Response {
        val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().remove("save_folder_uri").apply()
        return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
            addHeader(
                "Location",
                "/"
            )
        }
    }

    private fun getCurrentFolderDisplayName(): String {
        val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        val uriStr =
            sharedPrefs.getString("save_folder_uri", null) ?: return "Default (Internal Movies)"
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
        if (fileName == null) return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "text/plain",
            "Missing name"
        )
        val fileObj = getFileFromAnywhere(fileName)

        val deleted = when (fileObj) {
            is DocumentFile -> fileObj.delete()
            is File -> fileObj.delete()
            else -> false
        }

        return if (deleted) {
            newFixedLengthResponse(
                Response.Status.REDIRECT,
                "text/plain",
                ""
            ).apply { addHeader("Location", "/videos") }
        } else {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Could not delete file"
            )
        }
    }

    private fun listVideos(): Response {
        val allFiles = mutableListOf<Triple<String, Long, Boolean>>()
        val internalDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        internalDir?.listFiles { f -> f.extension.lowercase() == "mp4" }?.forEach {
            allFiles.add(Triple(it.name, it.length(), StorageManager.isImportant(it.name)))
        }

        val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        sharedPrefs.getString("save_folder_uri", null)?.let { uriStr ->
            DocumentFile.fromTreeUri(context, Uri.parse(uriStr))?.listFiles()?.forEach {
                if (it.name?.lowercase()?.endsWith(".mp4") == true) {
                    allFiles.add(
                        Triple(
                            it.name!!,
                            it.length(),
                            StorageManager.isImportant(it.name!!)
                        )
                    )
                }
            }
        }

        val sortedFiles = allFiles.distinctBy { it.first }.sortedByDescending { it.first }
        val listHtml = sortedFiles.joinToString("") { (name, size, important) ->
            val impBtn = if (important)
                "<button class='imp-btn active' onclick=\"location.href='/mark-important?name=$name&important=false'\">★</button>"
            else
                "<button class='imp-btn' onclick=\"location.href='/mark-important?name=$name&important=true'\">☆</button>"

            "<li><div class='video-info'><a href='/video?name=$name'>$name</a><span class='file-size'>(${size / 1024} KB)</span></div>" +
                    "<div class='actions'>$impBtn <button class='delete-btn' onclick=\"if(confirm('Delete $name?')) location.href='/delete-video?name=$name'\">🗑️</button></div></li>"
        }

        val emptyMsg =
            if (sortedFiles.isEmpty()) "<p style='text-align:center; color:#999;'>No videos recorded yet.</p>" else ""

        val html = readAsset("videos.html")
            .replace("{{listHtml}}", listHtml)
            .replace("{{emptyMessage}}", emptyMsg)

        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveVideo(fileName: String?): Response {
        if (fileName == null) return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "text/plain",
            "Missing name"
        )
        val fileObj = getFileFromAnywhere(fileName)
        return try {
            val (inputStream, length) = when (fileObj) {
                is DocumentFile -> context.contentResolver.openInputStream(fileObj.uri) to fileObj.length()
                is File -> FileInputStream(fileObj) to fileObj.length()
                else -> null to 0L
            }
            if (inputStream != null) newFixedLengthResponse(
                Response.Status.OK,
                "video/mp4",
                inputStream,
                length
            )
            else newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }

    private fun createHtmlResponse(status: String): Response {
        val folderName = getCurrentFolderDisplayName()
        val settings = StorageManager.getSettings(context)
        val storageStatus = StorageManager.getStorageStatus(context)

        val usedGb = "%.2f".format(storageStatus.totalUsedByAppBytes / (1024.0 * 1024.0 * 1024.0))
        val freeGb = "%.2f".format(storageStatus.freeOnDiskBytes / (1024.0 * 1024.0 * 1024.0))

        val alertsHtml = StringBuilder()
        if (storageStatus.isLowDiskSpace) {
            alertsHtml.append("<div class='alert error'>⚠️ CRITICAL: Low Disk Space! ($freeGb GB left). Recording may stop.</div>")
        } else if (storageStatus.isApproachingMaxQuota) {
            alertsHtml.append("<div class='alert warning'>⚠️ Warning: Approaching Max Storage Quota. ($usedGb GB used).</div>")
        }

        val html = readAsset("index.html")
            .replace("{{status}}", status)
            .replace("{{alertsHtml}}", alertsHtml.toString())
            .replace("{{folderName}}", folderName)
            .replace("{{usedGb}}", usedGb)
            .replace("{{freeGb}}", freeGb)
            .replace("{{maxTotalSizeGb}}", settings.maxTotalSizeGb.toString())
            .replace("{{minFreeSpaceGb}}", settings.minFreeSpaceGb.toString())

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
                        lastFrame = frame; break
                    }
                    Thread.sleep(10)
                }
                val header =
                    ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n").toByteArray()
                frameStream = ByteArrayInputStream(header + frame + "\r\n".toByteArray())
                return true
            } catch (e: InterruptedException) {
                return false
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                if ((frameStream == null || frameStream!!.available() == 0) && !getNextFrameStream()) return -1
                val readBytes = frameStream!!.read(b, off, len)
                if (readBytes > 0) return readBytes
                frameStream = null
            }
        }

        override fun read(): Int = -1
    }
}
