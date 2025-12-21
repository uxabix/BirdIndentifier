package com.example.birdidentifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun CameraControlUI() {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("BirdPrefs", android.content.Context.MODE_PRIVATE)
    var selectedFolderUri by remember { 
        mutableStateOf(sharedPrefs.getString("save_folder_uri", null)) 
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java))
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Persist access permissions
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            
            val uriString = it.toString()
            sharedPrefs.edit().putString("save_folder_uri", uriString).apply()
            selectedFolderUri = uriString
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Save Folder: ${selectedFolderUri ?: "Default (App Movies)"}")
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(onClick = { folderLauncher.launch(null) }) {
            Text("Select Save Folder (SD Card support)")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                when (PackageManager.PERMISSION_GRANTED) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) -> {
                        ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java))
                    }
                    else -> cameraLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        ) {
            Text("Start Camera Stream")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                context.stopService(Intent(context, CameraService::class.java))
            }
        ) {
            Text("Stop Camera Stream")
        }
    }
}
