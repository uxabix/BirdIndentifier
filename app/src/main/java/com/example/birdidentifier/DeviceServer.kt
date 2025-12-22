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

/**
 * A local HTTP server that provides a web interface for camera control,
 * MJPEG streaming, and video management.
 *
 * This server uses [NanoHTTPD] to handle incoming requests and serves
 * HTML content from the application's assets.
 *
 * @param port The port number to listen on.
 * @param frameProvider A thread-safe reference to the latest camera frame as a JPEG byte array.
 * @param context The Android context used to access assets, resources, and storage.
 */
class DeviceServer(
    port: Int,
    private val frameProvider: AtomicReference<ByteArray>,
    private val context: Context
) : NanoHTTPD(port) {

    /**
     * Constants representing the various HTTP routes supported by the server.
     */
    companion object {
        /** Root path serving the main control panel. */
        const val ROUTE_ROOT = "/"
        /** Path for the MJPEG video stream. */
        const val ROUTE_MJPEG = "/mjpeg"
        /** Command to play a sound on the device. */
        const val ROUTE_PLAY = "/play"
        /** Command to stop the currently playing sound. */
        const val ROUTE_STOP = "/stop"
        /** Returns the timestamp of the last detected motion. */
        const val ROUTE_MOTION_STATUS = "/motion-status"
        /** Path for the recorded videos list page. */
        const val ROUTE_VIDEOS_LIST = "/videos"
        /** Serves a specific video file. */
        const val ROUTE_VIDEO_SERVE = "/video"
        /** Deletes a specific video file. */
        const val ROUTE_VIDEO_DELETE = "/delete-video"
        /** Marks or unmarks a video as important. */
        const val ROUTE_MARK_IMPORTANT = "/mark-important"
        /** Updates storage quota and cleanup settings. */
        const val ROUTE_UPDATE_SETTINGS = "/update-storage-settings"
        /** Resets the video storage folder to the default location. */
        const val ROUTE_RESET_FOLDER = "/reset-folder"
        /** Starts manual video recording. */
        const val ROUTE_START_REC = "/start-rec"
        /** Stops manual video recording. */
        const val ROUTE_STOP_REC = "/stop-rec"
        /** Returns the current manual recording status. */
        const val ROUTE_REC_STATUS = "/rec-status"
    }

    /**
     * Handles incoming HTTP sessions and routes them to appropriate handler functions.
     *
     * @param session The HTTP session details.
     * @return A [Response] object representing the result of the request.
     */
    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            ROUTE_MJPEG -> newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=--frame",
                MjpegInputStream()
            )

            ROUTE_PLAY -> {
                SoundPlayer.play(context)
                createHtmlResponse("Sound played")
            }

            ROUTE_STOP -> {
                SoundPlayer.stop()
                createHtmlResponse("Sound stopped")
            }

            ROUTE_MOTION_STATUS -> newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                FrameBuffer.lastMotionTime.get().toString()
            )

            ROUTE_VIDEOS_LIST -> listVideos()
            ROUTE_VIDEO_SERVE -> serveVideo(session.parameters["name"]?.firstOrNull())
            ROUTE_VIDEO_DELETE -> deleteVideo(session.parameters["name"]?.firstOrNull())
            ROUTE_MARK_IMPORTANT -> markImportant(
                session.parameters["name"]?.firstOrNull(),
                session.parameters["important"]?.firstOrNull() == "true"
            )

            ROUTE_UPDATE_SETTINGS -> updateStorageSettings(session.parameters)
            ROUTE_RESET_FOLDER -> resetFolder()
            ROUTE_START_REC -> {
                FrameBuffer.isManualRecording.set(true)
                createHtmlResponse("Recording started")
            }

            ROUTE_STOP_REC -> {
                FrameBuffer.isManualRecording.set(false)
                createHtmlResponse("Recording stopping...")
            }

            ROUTE_REC_STATUS -> newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                FrameBuffer.isManualRecording.get().toString()
            )

            ROUTE_ROOT -> createHtmlResponse("Camera control")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    /**
     * Reads a text file from the application's assets folder.
     *
     * @param fileName The name of the file in the assets folder.
     * @return The content of the file as a string, or an error message if it fails.
     */
    private fun readAsset(fileName: String): String {
        return try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Error loading template: ${e.message}"
        }
    }

    /**
     * Marks a video as important or unimportant using [StorageManager].
     *
     * @param name The name of the video file.
     * @param important Whether the video should be marked as important.
     * @return A redirect response to the videos list.
     */
    private fun markImportant(name: String?, important: Boolean): Response {
        if (name == null) return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "text/plain",
            "Missing name"
        )
        StorageManager.markImportant(context, name, important)
        return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
            addHeader("Location", ROUTE_VIDEOS_LIST)
        }
    }

    /**
     * Updates storage settings such as maximum total size and minimum free space.
     *
     * @param params Map of request parameters containing 'max_total' and 'min_free'.
     * @return A redirect response to the root page.
     */
    private fun updateStorageSettings(params: Map<String, List<String>>): Response {
        val maxTotal = params["max_total"]?.firstOrNull()?.toFloatOrNull() ?: 5.0f
        val minFree = params["min_free"]?.firstOrNull()?.toFloatOrNull() ?: 1.0f
        StorageManager.saveSettings(context, maxTotal, minFree)
        return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
            addHeader("Location", ROUTE_ROOT)
        }
    }

    /**
     * Resets the video storage folder to its default value.
     *
     * @return A redirect response to the root page.
     */
    private fun resetFolder(): Response {
        val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().remove("save_folder_uri").apply()
        return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
            addHeader("Location", ROUTE_ROOT)
        }
    }

    /**
     * Retrieves the display name of the current video storage folder.
     *
     * @return The name of the folder or a default description.
     */
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

    /**
     * Attempts to find a file by name either in the custom storage folder (SAF)
     * or the internal application movies directory.
     *
     * @param fileName The name of the file to find.
     * @return Either a [DocumentFile] or a [File] object, or null if not found.
     */
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

    /**
     * Deletes a specific video file.
     *
     * @param fileName The name of the file to delete.
     * @return A redirect response to the videos list or an error response.
     */
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
            ).apply { addHeader("Location", ROUTE_VIDEOS_LIST) }
        } else {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Could not delete file"
            )
        }
    }

    /**
     * Generates an HTML response listing all recorded MP4 videos.
     *
     * @return An HTML response with the list of videos.
     */
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
                "<button class='imp-btn active' onclick=\"location.href='$ROUTE_MARK_IMPORTANT?name=$name&important=false'\">★</button>"
            else
                "<button class='imp-btn' onclick=\"location.href='$ROUTE_MARK_IMPORTANT?name=$name&important=true'\">☆</button>"

            "<li><div class='video-info'><a href='$ROUTE_VIDEO_SERVE?name=$name'>$name</a><span class='file-size'>(${size / 1024} KB)</span></div>" +
                    "<div class='actions'>$impBtn <button class='delete-btn' onclick=\"if(confirm('Delete $name?')) location.href='$ROUTE_VIDEO_DELETE?name=$name'\">🗑️</button></div></li>"
        }

        val emptyMsg =
            if (sortedFiles.isEmpty()) "<p style='text-align:center; color:#999;'>No videos recorded yet.</p>" else ""

        val html = readAsset("videos.html")
            .replace("{{ROUTE_ROOT}}", ROUTE_ROOT)
            .replace("{{listHtml}}", listHtml)
            .replace("{{emptyMessage}}", emptyMsg)

        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    /**
     * Serves an MP4 video file as an HTTP response.
     *
     * @param fileName The name of the video file to serve.
     * @return A response with the video stream or an error message.
     */
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

    /**
     * Generates the main control panel HTML by replacing placeholders in the template.
     *
     * @param status The status message to display on the page.
     * @return An HTML response for the main interface.
     */
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
            .replace("{{ROUTE_START_REC}}", ROUTE_START_REC)
            .replace("{{ROUTE_STOP_REC}}", ROUTE_STOP_REC)
            .replace("{{ROUTE_MOTION_STATUS}}", ROUTE_MOTION_STATUS)
            .replace("{{ROUTE_REC_STATUS}}", ROUTE_REC_STATUS)
            .replace("{{ROUTE_MJPEG}}", ROUTE_MJPEG)
            .replace("{{ROUTE_RESET_FOLDER}}", ROUTE_RESET_FOLDER)
            .replace("{{ROUTE_UPDATE_SETTINGS}}", ROUTE_UPDATE_SETTINGS)
            .replace("{{ROUTE_PLAY}}", ROUTE_PLAY)
            .replace("{{ROUTE_STOP}}", ROUTE_STOP)
            .replace("{{ROUTE_VIDEOS_LIST}}", ROUTE_VIDEOS_LIST)

        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    /**
     * An [InputStream] implementation that provides a continuous stream of JPEG frames
     * in the multipart/x-mixed-replace format (MJPEG).
     */
    inner class MjpegInputStream : InputStream() {
        private var frameStream: ByteArrayInputStream? = null
        private var lastFrame: ByteArray? = null

        /**
         * Waits for and retrieves the next available camera frame from the [frameProvider].
         *
         * @return True if a new frame was successfully retrieved, false if interrupted.
         */
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
                    ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame!!.size}\r\n\r\n").toByteArray()
                frameStream = ByteArrayInputStream(header + frame + "\r\n".toByteArray())
                return true
            } catch (e: InterruptedException) {
                return false
            }
        }

        /**
         * Reads the MJPEG stream data into the provided byte array.
         *
         * @param b The buffer into which the data is read.
         * @param off The start offset in array `b`.
         * @param len The maximum number of bytes to read.
         * @return The total number of bytes read into the buffer, or -1 if the end of stream.
         */
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                if ((frameStream == null || frameStream!!.available() == 0) && !getNextFrameStream()) return -1
                val readBytes = frameStream!!.read(b, off, len)
                if (readBytes > 0) return readBytes
                frameStream = null
            }
        }

        /**
         * Single byte read is not supported for the MJPEG stream.
         * @return Always -1.
         */
        override fun read(): Int = -1
    }
}
