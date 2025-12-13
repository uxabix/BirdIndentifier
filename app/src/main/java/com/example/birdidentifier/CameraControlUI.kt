package com.example.birdidentifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun CameraControlUI() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission Accepted: Start the service
            ContextCompat.startForegroundService(
                context,
                Intent(context, CameraService::class.java)
            )
        } else {
            // Permission Denied
            // You can show a message to the user here
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Button(
            onClick = {
                when (PackageManager.PERMISSION_GRANTED) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) -> {
                        // Permission is already granted, start the service
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, CameraService::class.java)
                        )
                    }
                    else -> {
                        // Permission is not granted, request it
                        launcher.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        ) {
            Text("Start Camera Stream")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                context.stopService(
                    Intent(context, CameraService::class.java)
                )
            }
        ) {
            Text("Stop Camera Stream")
        }
    }
}
