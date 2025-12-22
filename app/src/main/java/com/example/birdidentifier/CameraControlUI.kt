package com.example.birdidentifier

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
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
    val sharedPrefs =
        context.getSharedPreferences("BirdPrefs", android.content.Context.MODE_PRIVATE)

    // State that reacts to SharedPreferences changes
    var selectedFolderUri by remember {
        mutableStateOf(sharedPrefs.getString("save_folder_uri", null))
    }

    // Effect to listen for changes from outside (e.g., DeviceServer/Browser)
    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "save_folder_uri") {
                selectedFolderUri = prefs.getString(key, null)
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CameraService::class.java)
            )
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)

            sharedPrefs.edit().putString("save_folder_uri", it.toString()).apply()
            selectedFolderUri = it.toString()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Current Storage Location:")
        Text(
            text = selectedFolderUri?.let { Uri.parse(it).path }
                ?: "Default (App Internal Storage)",
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Button(onClick = { folderLauncher.launch(null) }) {
            Text("Change Save Folder (SD Card)")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            modifier = Modifier.fillMaxWidth(0.8f),
            onClick = {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, CameraService::class.java)
                    )
                } else {
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        ) {
            Text("Start Camera Stream")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            modifier = Modifier.fillMaxWidth(0.8f),
            onClick = {
                context.stopService(Intent(context, CameraService::class.java))
            }
        ) {
            Text("Stop Camera Stream")
        }
    }
}
