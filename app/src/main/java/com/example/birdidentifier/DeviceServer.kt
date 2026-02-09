package com.example.birdidentifier

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

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
        private const val TAG = "DeviceServer"
        /** Root path serving the main control panel. */
        const val ROUTE_ROOT = "/"
        /** Path for the MJPEG video stream. */
        const val ROUTE_MJPEG = "/mjpeg"
        /** Command to play a sound on the device. */
        const val ROUTE_PLAY = "/play"
        /** Command to stop the currently playing sound. */
        const val ROUTE_STOP = "/stop"
        /** Command to play a random sound on the external server. */
        const val ROUTE_EXTERNAL_PLAY_RANDOM = "/external-play-random"
        /** Command to stop playback on the external server. */
        const val ROUTE_EXTERNAL_STOP = "/external-stop"
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
        /** Updates the external audio server IP address. */
        const val ROUTE_UPDATE_SERVER_IP = "/update-server-ip"
        /** Returns the status of the external audio server. */
        const val ROUTE_SERVER_STATUS = "/server-status"
        /** Updates the audio output mode. */
        const val ROUTE_SET_AUDIO_MODE = "/set-audio-mode"

        private const val PREFS_NAME = "BirdPrefs"
        private const val KEY_EXTERNAL_SERVER_IP = "external_server_ip"
    }

    /**
     * Handles incoming HTTP sessions and routes them to appropriate handler functions.
     *
     * @param session The HTTP session details.
     * @return A [Response] object representing the result of the request.
     */
    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "Received request: ${session.method} ${session.uri} with params ${session.parameters}")
        // Get status message from query parameters for redirection handling
        val statusMessage = session.parameters["status"]?.firstOrNull()?.replace("+", " ") ?: ""

        return when (session.uri) {
            ROUTE_MJPEG -> {
                Log.d(TAG, "Serving MJPEG stream.")
                newChunkedResponse(
                    Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=--frame",
                    MjpegInputStream()
                )
            }

            ROUTE_PLAY -> {
                try {
                    Log.d(TAG, "Executing PLAY command on device.")
                    SoundPlayer.play(context)
                    redirectResponse("Sound command executed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing PLAY command", e)
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error playing sound: ${e.message}")
                }
            }

            ROUTE_STOP -> {
                Log.d(TAG, "Executing STOP command.")
                SoundPlayer.stop(context)
                redirectResponse("Stop command executed")
            }

            ROUTE_EXTERNAL_PLAY_RANDOM -> sendCommandToExternalServer("/play_random")
            ROUTE_EXTERNAL_STOP -> sendCommandToExternalServer("/stop")

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
                Log.d(TAG, "Starting manual recording.")
                FrameBuffer.isManualRecording.set(true)
                redirectResponse("Recording started")
            }

            ROUTE_STOP_REC -> {
                Log.d(TAG, "Stopping manual recording.")
                FrameBuffer.isManualRecording.set(false)
                redirectResponse("Recording stopping...")
            }

            ROUTE_REC_STATUS -> newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                FrameBuffer.isManualRecording.get().toString()
            )

            ROUTE_UPDATE_SERVER_IP -> updateServerIp(session.parameters["ip"]?.firstOrNull())
            ROUTE_SERVER_STATUS -> getServerStatusResponse()

            ROUTE_SET_AUDIO_MODE -> {
                val modeInt = session.parameters["mode"]?.firstOrNull()?.toIntOrNull() ?: 1
                SoundPlayer.setAudioMode(context, SoundPlayer.AudioMode.fromInt(modeInt))
                redirectResponse("Audio mode updated")
            }

            ROUTE_ROOT -> createHtmlResponse(statusMessage)
            else -> {
                Log.w(TAG, "Not found: ${session.uri}")
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }
    }

    /**
     * Helper to create a redirect response to the root page with a status message.
     * @param message The status message to display on the redirected page.
     */
    private fun redirectResponse(message: String): Response {
        val encodedMessage = Uri.encode(message)
        // Using REDIRECT_SEE_OTHER (303) instead of REDIRECT (301) to avoid browser caching
        return newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "").apply {
            addHeader("Location", "$ROUTE_ROOT?status=$encodedMessage")
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
            Log.e(TAG, "Error loading asset: $fileName", e)
            "Error loading template: ${e.message}"
        }
    }

    /**
     * Sends a simple GET command to the configured external server.
     *
     * @param command The API path to trigger (e.g., "/play_random").
     * @return An HTML response indicating the action's outcome.
     */
    private fun sendCommandToExternalServer(command: String): Response {
        val serverIp = getSavedServerIp()
        if (serverIp.isBlank()) {
            val errorMsg = "Error: External server IP not set."
            Log.w(TAG, "Cannot send command '$command': $errorMsg")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", errorMsg)
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://$serverIp$command")
            Log.d(TAG, "Sending command to external server: $url")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val message = "Command '$command' sent to external server successfully."
                Log.d(TAG, message)
                return redirectResponse(message)
            } else {
                val errorMsg = "Error sending command '$command': Server returned code $responseCode - $responseMessage"
                Log.e(TAG, errorMsg)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "Error sending command '$command': ${e.message}"
            Log.e(TAG, errorMsg, e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", errorMsg)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Marks a video as important or unimportant using [StorageManager].
     *
     * @param name The name of the file.
     * @param important Whether the video should be marked as important.
     * @return A redirect response to the videos list.
     */
    private fun markImportant(name: String?, important: Boolean): Response {
        if (name == null) {
            Log.w(TAG, "markImportant failed: Missing file name.")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing name")
        }
        Log.d(TAG, "Marking file '$name' as important=$important")
        if (StorageManager.markImportant(context, name, important)) {
            Log.d(TAG, "File '$name' marked successfully.")
        } else {
            Log.e(TAG, "Failed to mark file '$name'.")
        }
        return redirectResponse("Video '$name' importance updated.")
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
        Log.d(TAG, "Updating storage settings: maxTotal=${maxTotal}GB, minFree=${minFree}GB")
        StorageManager.saveSettings(context, maxTotal, minFree)
        return redirectResponse("Storage settings updated.")
    }

    /**
     * Resets the video storage folder to its default value.
     *
     * @return A redirect response to the root page.
     */
    private fun resetFolder(): Response {
        Log.d(TAG, "Resetting video storage folder to default.")
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit().remove("save_folder_uri").apply()
        return redirectResponse("Storage folder reset to default.")
    }

    /**
     * Retrieves the display name of the current video storage folder.
     *
     * @return The name of the folder or a default description.
     */
    private fun getCurrentFolderDisplayName(): String {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr =
            sharedPrefs.getString("save_folder_uri", null) ?: return "Default (Internal Movies)"
        return try {
            val treeUri = Uri.parse(uriStr)
            DocumentFile.fromTreeUri(context, treeUri)?.name ?: "Custom SD/Folder"
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
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val folderUriString = sharedPrefs.getString("save_folder_uri", null)

        if (folderUriString != null) {
             try {
                val treeUri = Uri.parse(folderUriString)
                val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
                val safFile = pickedDir?.findFile(fileName)
                if (safFile != null && safFile.exists()) {
                    Log.d(TAG, "Found file '$fileName' in SAF folder.")
                    return safFile
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error accessing SAF folder: $folderUriString", e)
            }
        }

        val internalFile = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName)
        if (internalFile.exists()) {
            Log.d(TAG, "Found file '$fileName' in internal storage.")
            return internalFile
        }

        Log.w(TAG, "File '$fileName' not found in any storage location.")
        return null
    }

    /**
     * Deletes a specific video file.
     *
     * @param fileName The name of the file to delete.
     * @return A redirect response to the videos list or an error response.
     */
    private fun deleteVideo(fileName: String?): Response {
        if (fileName == null) {
            Log.w(TAG, "deleteVideo failed: Missing file name.")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing name")
        }
        Log.d(TAG, "Attempting to delete file: $fileName")
        val fileObj = getFileFromAnywhere(fileName)

        val deleted = when (fileObj) {
            is DocumentFile -> fileObj.delete()
            is File -> fileObj.delete()
            else -> false
        }

        if(deleted) {
            Log.d(TAG, "Successfully deleted file: $fileName")
        } else {
            Log.e(TAG, "Failed to delete file: $fileName")
        }

        return redirectResponse("Video '$fileName' deleted.")
    }

    /**
     * Generates an HTML response listing all recorded MP4 videos.
     *
     * @return An HTML response with the list of videos.
     */
    private fun listVideos(): Response {
        Log.d(TAG, "Listing video files.")
        val allFiles = mutableListOf<Triple<String, Long, Boolean>>()

        // List from internal storage
        val internalDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        var internalFileCount = 0
        try {
            internalDir?.listFiles { f -> f.extension.lowercase() == "mp4" }?.forEach {
                allFiles.add(Triple(it.name, it.length(), StorageManager.isImportant(it.name)))
                internalFileCount++
            }
            Log.d(TAG, "Found $internalFileCount files in internal storage.")
        } catch(e: Exception) {
            Log.e(TAG, "Failed to list files in internal storage", e)
        }

        // List from SAF storage
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var safFileCount = 0
        sharedPrefs.getString("save_folder_uri", null)?.let { uriStr ->
            try {
                DocumentFile.fromTreeUri(context, Uri.parse(uriStr))?.listFiles()?.forEach { file ->
                    if (file.name?.lowercase()?.endsWith(".mp4") == true) {
                        allFiles.add(Triple(file.name!!, file.length(), StorageManager.isImportant(file.name!!)))
                        safFileCount++
                    }
                }
                Log.d(TAG, "Found $safFileCount files in SAF folder.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list files in SAF folder $uriStr", e)
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
        if (fileName == null) {
            Log.w(TAG, "serveVideo failed: Missing file name.")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing name")
        }
        Log.d(TAG, "Attempting to serve video: $fileName")
        val fileObj = getFileFromAnywhere(fileName)
        return try {
            val (inputStream, length) = when (fileObj) {
                is DocumentFile -> context.contentResolver.openInputStream(fileObj.uri) to fileObj.length()
                is File -> FileInputStream(fileObj) to fileObj.length()
                else -> null to 0L
            }
            if (inputStream != null) {
                Log.d(TAG, "Serving '$fileName' with length $length")
                newFixedLengthResponse(Response.Status.OK, "video/mp4", inputStream, length)
            } else {
                Log.w(TAG, "serveVideo failed: File not found after search: $fileName")
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serving video '$fileName'", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }

    /**
     * Retrieves the current battery level and charging status.
     *
     * @return A Pair containing the battery level (0-100) and whether it's charging.
     */
    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        return batteryPct to isCharging
    }

    /**
     * Saves the external server IP address to SharedPreferences.
     *
     * @param ip The IP address to save.
     * @return A response indicating success or failure.
     */
    private fun updateServerIp(ip: String?): Response {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!ip.isNullOrBlank()) {
            var cleanIp = ip.trim().removePrefix("http://").removePrefix("https://")
            while (cleanIp.endsWith("/")) cleanIp = cleanIp.dropLast(1)
            Log.d(TAG, "Updating external server IP to '$cleanIp' (original: '$ip')")
            sharedPrefs.edit().putString(KEY_EXTERNAL_SERVER_IP, cleanIp).apply()
            return redirectResponse("External server IP saved: $cleanIp")
        }
        Log.w(TAG, "updateServerIp failed: Invalid or blank IP provided.")
        return redirectResponse("Error: Invalid external server IP address provided.")
    }

    /**
     * Retrieves the saved external server IP address from SharedPreferences.
     *
     * @return The saved IP address or an empty string if not found.
     */
    private fun getSavedServerIp(): String {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPrefs.getString(KEY_EXTERNAL_SERVER_IP, "") ?: ""
    }

    /**
     * Retrieves the status of the external audio server (online, battery, sleep schedule).
     *
     * @return A JSON response with the server status.
     */
    private fun getServerStatusResponse(): Response {
        val serverIp = getSavedServerIp()
        if (serverIp.isBlank()) {
            Log.d(TAG, "getServerStatusResponse: External server IP not configured.")
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{ \"isOnline\": false, \"message\": \"Server IP not configured\" }")
        }

        var isOnline = false
        var batteryData: JSONObject? = null
        var sleepSchedule: JSONObject? = null
        var errorMessage: String? = null

        try {
            val pingUrl = URL("http://$serverIp/ping")
            Log.d(TAG, "Pinging external server at: $pingUrl")
            (pingUrl.openConnection() as? HttpURLConnection)?.run{
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2000
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    isOnline = true
                    Log.d(TAG, "Ping successful.")
                } else {
                    Log.w(TAG, "Ping failed with code: $responseCode. Message: ${responseMessage}")
                }
                disconnect()
            }

            if (isOnline) {
                val batteryUrl = URL("http://$serverIp/battery")
                Log.d(TAG, "Fetching battery status from $batteryUrl")
                (batteryUrl.openConnection() as? HttpURLConnection)?.run {
                    connectTimeout = 2000
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                         batteryData = JSONObject(inputStream.bufferedReader().use { it.readText() })
                         Log.d(TAG, "Battery data: $batteryData")
                    } else {
                        Log.w(TAG, "Failed to fetch battery data with code: $responseCode")
                    }
                    disconnect()
                }

                val sleepUrl = URL("http://$serverIp/sleep")
                Log.d(TAG, "Fetching sleep status from $sleepUrl")
                (sleepUrl.openConnection() as? HttpURLConnection)?.run {
                    connectTimeout = 2000
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        sleepSchedule = JSONObject(inputStream.bufferedReader().use { it.readText() })
                        Log.d(TAG, "Sleep schedule data: $sleepSchedule")
                    } else {
                        Log.w(TAG, "Failed to fetch sleep data with code: $responseCode")
                    }
                    disconnect()
                }
            }
        } catch (e: Exception) {
            errorMessage = e.toString()
            Log.e(TAG, "Error checking server status: $errorMessage")
        }

        val jsonResponse = JSONObject().apply {
            put("isOnline", isOnline)
            if (batteryData != null) put("battery", batteryData)
            if (sleepSchedule != null) put("sleep", sleepSchedule)
            if (errorMessage != null) put("error", errorMessage)
        }.toString()
        Log.d(TAG, "Server status response: $jsonResponse")
        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse)
    }


    /**
     * Generates the main control panel HTML by replacing placeholders in the template.
     *
     * @param status The status message to display on the page.
     * @return An HTML response for the main interface.
     */
    private fun createHtmlResponse(status: String): Response {
        Log.d(TAG, "createHtmlResponse: Starting with status='$status'")
        val folderName = getCurrentFolderDisplayName()
        val settings = StorageManager.getSettings(context)
        val storageStatus = StorageManager.getStorageStatus(context)
        val (batteryLevel, isCharging) = getBatteryInfo()
        val externalServerIp = getSavedServerIp()
        val audioMode = SoundPlayer.getAudioMode(context).value

        val usedGb = "%.2f".format(storageStatus.totalUsedByAppBytes / (1024.0 * 1024.0 * 1024.0))
        val freeGb = "%.2f".format(storageStatus.freeOnDiskBytes / (1024.0 * 1024.0 * 1024.0))

        val batteryText = if (batteryLevel != -1) "$batteryLevel%" else "Unknown"
        val chargingText = if (isCharging) " (Charging)" else ""

        val alertsHtml = StringBuilder()
        if (storageStatus.isLowDiskSpace) {
            alertsHtml.append("<div class='alert error'>⚠️ CRITICAL: Low Disk Space! ($freeGb GB left). Recording may stop.</div>")
        } else if (storageStatus.isApproachingMaxQuota) {
            alertsHtml.append("<div class='alert warning'>⚠️ Warning: Approaching Max Storage Quota. ($usedGb GB used).</div>")
        }

        if (batteryLevel != -1 && batteryLevel < 25 && !isCharging) {
            alertsHtml.append("<div class='alert warning'>⚠️ Warning: Low Battery ($batteryLevel%). Please connect a charger.</div>")
        }

        val html = readAsset("index.html")
            .replace("{{status}}", status) // Use the passed status message here
            .replace("{{alertsHtml}}", alertsHtml.toString())
            .replace("{{folderName}}", folderName)
            .replace("{{usedGb}}", usedGb)
            .replace("{{freeGb}}", freeGb)
            .replace("{{batteryStatus}}", "$batteryText$chargingText")
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
            .replace("{{ROUTE_UPDATE_SERVER_IP}}", ROUTE_UPDATE_SERVER_IP)
            .replace("{{ROUTE_SERVER_STATUS}}", ROUTE_SERVER_STATUS)
            .replace("{{EXTERNAL_SERVER_IP}}", externalServerIp)
            .replace("{{ROUTE_EXTERNAL_PLAY_RANDOM}}", ROUTE_EXTERNAL_PLAY_RANDOM)
            .replace("{{ROUTE_EXTERNAL_STOP}}", ROUTE_EXTERNAL_STOP)
            .replace("{{ROUTE_SET_AUDIO_MODE}}", ROUTE_SET_AUDIO_MODE)
            .replace("{{audioMode}}", audioMode.toString())

        Log.d(TAG, "createHtmlResponse: Finished creating HTML response.")
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
