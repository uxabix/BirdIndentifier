# Bird Identifier & Deterrent System

An advanced Android application that transforms a smartphone into a smart bird-watching and monitoring station. It features live network streaming, motion-triggered recording, and remote management through a web interface.

## 🚀 Key Features

*   **Live MJPEG Streaming**: Stream real-time camera footage over your local network.
*   **Smart Motion Detection**: Automatically detects movement by analyzing frame luminance differences.
*   **Motion-Triggered Recording**: Automatically records MP4 video fragments when birds or movement are detected, including a configurable post-event delay.
*   **Remote Web Interface**: A comprehensive control panel accessible via any web browser on the same network:
    *   View the live camera feed.
    *   Start and stop recording manually.
    *   Control camera zoom.
    *   Play deterrent sounds on the phone or an external device.
    *   Browse, play, and manage recorded videos.
    *   Configure storage quotas and automatic cleanup.
*   **Audio Deterrents**: Plays randomized sounds to prevent bird habituation, with options for local and remote playback.
*   **Advanced Storage Management**:
    *   Supports both internal storage and SD cards via Storage Access Framework (SAF).
    *   Automatic cleanup logic based on disk space and size quotas.
    *   "Mark as Important" feature to protect specific clips from deletion.
*   **Persistent Monitoring**: Runs as a Foreground Service to ensure uninterrupted operation, complete with wake locks.

## 🔌 Optional Integration: WiFiSoundNode

This project is designed to work with [WiFiSoundNode](https://github.com/uxabix/WiFiSoundNode/tree/main), an external ESP32-based audio playback device. This integration allows the app to trigger sounds on a separate, dedicated speaker over WiFi, which can be useful for placing audio deterrents in remote locations.

However, **WiFiSoundNode is not required** for the core functionality of this application. The app is fully self-sufficient and can play sounds through the phone's speaker if the external server is not configured.

## 🛠 Tech Stack

*   **Language**: Kotlin
*   **Camera Engine**: CameraX (For reliable image analysis and frame processing).
*   **Web Server**: NanoHTTPD (A lightweight embedded HTTP server for the web UI).
*   **Video Encoding**: MediaCodec API (Hardware-accelerated H.264/AVC encoding).
*   **Architecture**: Service-oriented processing with a singleton (`FrameBuffer`) for state management.
*   **Documentation**: KDoc + Dokka for automated documentation generation.

## 📋 Installation & Setup

1.  **Build**: Clone the repository and build the project using Android Studio.
2.  **Run**: Launch the app and grant the necessary Camera and Storage permissions.
3.  **Start**: Tap "Start Camera Stream" to activate the monitoring service.
4.  **Connect**: Open a browser on a device in the same network and navigate to the IP address displayed in the app (e.g., `http://192.168.1.5:8080`).

## 📂 Project Structure

*   `CameraService`: The core service managing the camera lifecycle, motion detection, and zoom control.
*   `DeviceServer`: Handles all HTTP requests for the web interface, serving the UI and processing commands for recording, audio, zoom, and file management.
*   `VideoWriter`: Performs low-level encoding of JPEG sequences into MP4 video files using `MediaCodec`.
*   `StorageManager`: Implements the logic for file persistence, SAF integration, and automated cleanup.
*   `SoundPlayer`: A utility for managing audio playback on both the phone and the external `WiFiSoundNode` server.
*   `FrameBuffer`: A thread-safe singleton acting as a data bridge between components for camera frames, recording status, zoom levels, etc.

## 📖 Documentation

The project is fully documented using KDoc. To generate the HTML documentation:
```bash
./gradlew dokkaGenerate
```
The output will be available in `app/build/dokka/html/`.

## 📜 License

MIT Licence [Licence](LICENSE)
