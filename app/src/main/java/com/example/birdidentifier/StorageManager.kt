package com.example.birdidentifier

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * A comprehensive storage management singleton for the Bird Identifier app.
 *
 * This object handles:
 * - Tracking and enforcing storage quotas (max total size).
 * - Monitoring free disk space.
 * - Cleaning up old video recordings to make room for new ones.
 * - Marking files as "Important" to protect them from automatic deletion.
 * - Abstracting file operations between internal storage and the Storage Access Framework (SAF).
 */
object StorageManager {
    private const val TAG = "StorageManager"

    /**
     * Retrieves the user-configured storage settings from [android.content.SharedPreferences].
     *
     * @param context The Android context used to access preferences.
     * @return A [StorageSettings] object containing current limits.
     */
    fun getSettings(context: Context): StorageSettings {
        val prefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        return StorageSettings(
            maxTotalSizeGb = prefs.getFloat("max_total_size_gb", 5.0f),
            minFreeSpaceGb = prefs.getFloat("min_free_space_gb", 1.0f)
        )
    }

    /**
     * Persists new storage quota settings to [android.content.SharedPreferences].
     *
     * @param context The Android context used to access preferences.
     * @param maxTotal The maximum total size in GB allowed for all videos.
     * @param minFree The minimum free space in GB required on disk.
     */
    fun saveSettings(context: Context, maxTotal: Float, minFree: Float) {
        val prefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("max_total_size_gb", maxTotal)
            .putFloat("min_free_space_gb", minFree)
            .apply()
    }

    /**
     * Checks if a filename indicates that the video is marked as important.
     *
     * @param fileName The name of the file to check.
     * @return True if the filename contains the "_IMPORTANT" tag.
     */
    fun isImportant(fileName: String): Boolean {
        return fileName.contains("_IMPORTANT", ignoreCase = true)
    }

    /**
     * Renames a video file to include or remove the "_IMPORTANT" tag.
     *
     * Files marked as important are excluded from the automatic cleanup process.
     *
     * @param context The Android context.
     * @param fileName The current name of the file.
     * @param important Whether to mark (true) or unmark (false) the file.
     * @return True if the rename operation was successful.
     */
    fun markImportant(context: Context, fileName: String, important: Boolean): Boolean {
        val fileObj = getFileFromAnywhere(context, fileName) ?: return false
        val newName = if (important) {
            if (isImportant(fileName)) return true
            fileName.replace(".mp4", "_IMPORTANT.mp4")
        } else {
            if (!isImportant(fileName)) return true
            fileName.replace("_IMPORTANT.mp4", ".mp4")
        }

        return when (fileObj) {
            is DocumentFile -> fileObj.renameTo(newName)
            is File -> {
                val newFile = File(fileObj.parent, newName)
                fileObj.renameTo(newFile)
            }
            else -> false
        }
    }

    /**
     * Finds a file in either the custom SAF folder or the default internal movies directory.
     *
     * @param context The Android context.
     * @param fileName The name of the file to locate.
     * @return A [DocumentFile], [File], or null if the file does not exist.
     */
    private fun getFileFromAnywhere(context: Context, fileName: String): Any? {
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
     * Triggers the storage cleanup process based on current settings.
     *
     * This method will delete the oldest non-important videos until the total size 
     * is within the quota AND the minimum free disk space is restored.
     *
     * @param context The Android context.
     */
    fun checkAndCleanup(context: Context) {
        val settings = getSettings(context)
        val maxTotalBytes = (settings.maxTotalSizeGb * 1024 * 1024 * 1024).toLong()
        val minFreeBytes = (settings.minFreeSpaceGb * 1024 * 1024 * 1024).toLong()

        val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        val folderUriString = sharedPrefs.getString("save_folder_uri", null)

        if (folderUriString != null) {
            cleanupFolder(context, Uri.parse(folderUriString), maxTotalBytes, minFreeBytes)
        } else {
            val internalDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return
            cleanupInternalFolder(internalDir, maxTotalBytes, minFreeBytes)
        }
    }

    /**
     * Performs cleanup on a folder accessed via the Storage Access Framework (SAF).
     */
    private fun cleanupFolder(context: Context, treeUri: Uri, maxTotal: Long, minFree: Long) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return
        val files = root.listFiles()
            .filter { it.name?.lowercase()?.endsWith(".mp4") == true }
            .sortedBy { it.lastModified() }

        var totalSize = files.sumOf { it.length() }
        var freeBytes = getFreeSpaceSaf(context, treeUri)

        for (file in files) {
            if (totalSize <= maxTotal && freeBytes >= minFree) break
            if (isImportant(file.name ?: "")) continue

            val size = file.length()
            if (file.delete()) {
                totalSize -= size
                freeBytes += size
                Log.d(TAG, "Deleted old SAF video: ${file.name}")
            }
        }
    }

    /**
     * Performs cleanup on a standard Java [File] directory (internal storage).
     */
    private fun cleanupInternalFolder(dir: File, maxTotal: Long, minFree: Long) {
        val files = dir.listFiles { f -> f.extension.lowercase() == "mp4" }
            ?.sortedBy { it.lastModified() } ?: return

        var totalSize = files.sumOf { it.length() }

        val stat = StatFs(dir.path)
        var freeBytes = stat.availableBlocksLong * stat.blockSizeLong

        for (file in files) {
            if (totalSize <= maxTotal && freeBytes >= minFree) break
            if (isImportant(file.name)) continue

            val size = file.length()
            if (file.delete()) {
                totalSize -= size
                freeBytes += size
                Log.d(TAG, "Deleted old internal video: ${file.name}")
            }
        }
    }

    /**
     * Calculates the current storage usage and disk health.
     *
     * @param context The Android context.
     * @return A [StorageStatus] object containing metrics and warning flags.
     */
    fun getStorageStatus(context: Context): StorageStatus {
        val settings = getSettings(context)
        val maxTotalBytes = (settings.maxTotalSizeGb * 1024 * 1024 * 1024).toLong()
        val minFreeBytes = (settings.minFreeSpaceGb * 1024 * 1024 * 1024).toLong()
        
        /** Threshold to warn user before they hit the absolute quota limit (1 GB). */
        val warningThresholdBytes = (1.0f * 1024 * 1024 * 1024).toLong()

        val sharedPrefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        val folderUriString = sharedPrefs.getString("save_folder_uri", null)

        var totalUsedByApp = 0L
        var freeOnDisk = 0L

        if (folderUriString != null) {
            val treeUri = Uri.parse(folderUriString)
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
            totalUsedByApp = pickedDir?.listFiles()
                ?.filter { it.name?.lowercase()?.endsWith(".mp4") == true }
                ?.sumOf { it.length() } ?: 0L

            freeOnDisk = getFreeSpaceSaf(context, treeUri)
        } else {
            val internalDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (internalDir != null) {
                val stat = StatFs(internalDir.path)
                freeOnDisk = stat.availableBlocksLong * stat.blockSizeLong
                totalUsedByApp = internalDir.listFiles { f -> f.extension.lowercase() == "mp4" }
                    ?.sumOf { it.length() } ?: 0L
            }
        }

        val lowDiskSpace = freeOnDisk < minFreeBytes
        val approachingMaxQuota = (maxTotalBytes - totalUsedByApp) < warningThresholdBytes

        return StorageStatus(
            totalUsedByAppBytes = totalUsedByApp,
            freeOnDiskBytes = freeOnDisk,
            isLowDiskSpace = lowDiskSpace,
            isApproachingMaxQuota = approachingMaxQuota,
            maxQuotaBytes = maxTotalBytes,
            minFreeBytes = minFreeBytes
        )
    }

    /**
     * Specialized helper to get free space from a Storage Access Framework URI.
     * Uses [Os.fstatvfs] via a file descriptor.
     */
    private fun getFreeSpaceSaf(context: Context, treeUri: Uri): Long {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

            val pfd = context.contentResolver.openFileDescriptor(docUri, "r")
            if (pfd != null) {
                val stats = Os.fstatvfs(pfd.fileDescriptor)
                val bytes = stats.f_bavail * stats.f_frsize
                pfd.close()
                if (bytes > 0) return bytes
            }
            0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get free space for SAF via fstatvfs: ${e.message}")
            context.getExternalFilesDir(null)?.freeSpace ?: 0L
        }
    }

    /**
     * Convenience method to check if a new recording can be safely started.
     */
    fun hasEnoughSpace(context: Context): Boolean {
        return !getStorageStatus(context).isLowDiskSpace
    }

    /**
     * Data class representing storage configuration limits.
     */
    data class StorageSettings(
        val maxTotalSizeGb: Float,
        val minFreeSpaceGb: Float
    )

    /**
     * Data class representing current storage metrics and health flags.
     */
    data class StorageStatus(
        val totalUsedByAppBytes: Long,
        val freeOnDiskBytes: Long,
        val isLowDiskSpace: Boolean,
        val isApproachingMaxQuota: Boolean,
        val maxQuotaBytes: Long,
        val minFreeBytes: Long
    )
}
