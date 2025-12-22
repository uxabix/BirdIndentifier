package com.example.birdidentifier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * The main entry point activity of the application.
 *
 * This activity sets up the Jetpack Compose UI by hosting [CameraControlUI].
 * It extends [ComponentActivity] as the foundation for modern Android UI.
 */
class MainActivity : ComponentActivity() {
    /**
     * Initializes the activity and sets the content to [CameraControlUI].
     *
     * @param savedInstanceState If the activity is being re-initialized after
     * previously being shut down then this Bundle contains the data it most
     * recently supplied in [onSaveInstanceState].
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraControlUI()
        }
    }
}