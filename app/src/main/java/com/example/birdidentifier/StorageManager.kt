package com.example.birdidentifier

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

object StorageManager {
    private const val TAG = "StorageManager"

    fun getSettings(context: Context): StorageSettings {
        val prefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        return StorageSettings(
            maxTotalSizeGb = prefs.getFloat("max_total_size_gb", 5.0f),
            minFreeSpaceGb = prefs.getFloat("min_free_space_gb", 1.0f)
        )
    }

    fun saveSettings(context: Context, maxTotal: Float, minFree: Float) {
        val prefs = context.getSharedPreferences("BirdPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("max_total_size_gb", maxTotal)
            .putFloat("min_free_space_gb", minFree)
            .apply()
    }

    fun isImportant(fileName: String): Boolean {
        return fileName.contains("_IMPORTANT", ignoreCase = true)
    }

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

    fun getStorageStatus(context: Context): StorageStatus {
        val settings = getSettings(context)
        val maxTotalBytes = (settings.maxTotalSizeGb * 1024 * 1024 * 1024).toLong()
        val minFreeBytes = (settings.minFreeSpaceGb * 1024 * 1024 * 1024).toLong()
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

    private fun getFreeSpaceSaf(context: Context, treeUri: Uri): Long {
        return try {
            // Correct way to get a FileDescriptor for a Tree URI is to build its document URI
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
            // Fallback: try internal dir free space as a last resort
            context.getExternalFilesDir(null)?.freeSpace ?: 0L
        }
    }

    fun hasEnoughSpace(context: Context): Boolean {
        return !getStorageStatus(context).isLowDiskSpace
    }

    data class StorageSettings(
        val maxTotalSizeGb: Float,
        val minFreeSpaceGb: Float
    )

    data class StorageStatus(
        val totalUsedByAppBytes: Long,
        val freeOnDiskBytes: Long,
        val isLowDiskSpace: Boolean,
        val isApproachingMaxQuota: Boolean,
        val maxQuotaBytes: Long,
        val minFreeBytes: Long
    )
}
