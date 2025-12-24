# Bird Identifier & Deterrent System

An advanced Android application that transforms a smartphone into a smart bird-watching and monitoring station. It features live network streaming, motion-triggered recording, and remote management through a web interface.

## 🚀 Key Features

*   **Live MJPEG Streaming**: Stream real-time camera footage over your local network.
*   **Smart Motion Detection**: Automatically detects movement by analyzing frame luminance differences.
*   **Motion-Triggered Recording**: Automatically records MP4 video fragments when birds or movement are detected, including a configurable post-event delay.
*   **Remote Web Interface**: Full control panel accessible via any web browser on the same network:
    *   View live stream.
    *   Manage recorded videos (preview, delete, mark as important).
    *   Configure storage quotas and cleanup settings.
    *   Trigger audio deterrents.
*   **Audio Deterrents**: Plays randomized sounds (predators, glass, etc.) with randomized speed and volume to prevent bird habituation.
*   **Advanced Storage Management**:
    *   Supports both internal storage and SD cards via Storage Access Framework (SAF).
    *   Automatic cleanup logic based on disk space and size quotas.
    *   "Mark as Important" feature to protect specific clips from deletion.
*   **Persistent Monitoring**: Runs as a Foreground Service to ensure uninterrupted operation.

## 🛠 Tech Stack

*   **Language**: Kotlin
*   **UI Framework**: Jetpack Compose (Modern, declarative UI).
*   **Camera Engine**: CameraX (Reliable image analysis and frame processing).
*   **Web Server**: NanoHTTPD (Lightweight embedded HTTP server).
*   **Video Encoding**: MediaCodec API (Hardware-accelerated AVC/H.264 encoding).
*   **Architecture**: Singleton-based data bridges (FrameBuffer) and service-oriented processing.
*   **Documentation**: KDoc + Dokka for automated documentation generation.

## 📋 Installation & Setup

1.  **Build**: Clone the repository and build the project using Android Studio.
2.  **Run**: Launch the app and tap "Start Camera Stream".
3.  **Permissions**: Grant Camera and Storage permissions when prompted.
4.  **Connect**: Open a browser on a device in the same network and navigate to the IP address displayed or `http://[DEVICE_IP]:8080`.

## 📂 Project Structure

*   `CameraService`: The core engine managing the camera lifecycle and frame analysis.
*   `DeviceServer`: Handles HTTP requests and serves the web interface from `assets`.
*   `VideoWriter`: Low-level encoding of JPEG sequences into MP4 files.
*   `StorageManager`: Logic for file persistence and automated cleanup.
*   `SoundPlayer`: Utility for audio feedback and deterrence.
*   `FrameBuffer`: Thread-safe singleton for high-speed data exchange between components.

## 📖 Documentation

The project is fully documented using KDoc. To generate the HTML documentation:
```bash
./gradlew dokkaGenerate
```
The output will be available in `app/build/dokka/html/`.

## 📜 License

MIT Licence [Licence](LICENSE)
