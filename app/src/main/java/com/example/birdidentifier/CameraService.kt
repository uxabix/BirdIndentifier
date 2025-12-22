package com.example.birdidentifier

import android.app.*
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * A foreground service that manages the camera lifecycle, performs motion detection,
 * and handles video recording logic.
 *
 * This service implements [LifecycleOwner] to integrate with CameraX lifecycle-aware 
 * components. It starts an MJPEG server via [DeviceServer] and handles frame processing 
 * in a dedicated background thread.
 */
class CameraService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var deviceServer: DeviceServer
    private lateinit var videoWriter: VideoWriter
    
    /** Stores the Y-plane of the previous frame to calculate pixel differences for motion detection. */
    private var previousYPlane: ByteArray? = null
    
    /** Countdown of frames to continue recording after the last motion event was detected. */
    private var framesRemainingAfterMotion = 0
    
    /** Post-delay duration after motion: 2 minutes assuming 30 FPS. */
    private val MOTION_POST_DELAY_FRAMES = 30 * 60 * 2
    
    /** Safety limit for a single recording: 20 minutes assuming 30 FPS. */
    private val MAX_RECORDING_FRAMES = 30 * 60 * 20

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    /**
     * Initializes the service, sets up the [Lifecycle], and starts background tasks.
     */
    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        videoWriter = VideoWriter(this)
        startForegroundNotification()
        startMjpegServer()
        startCamera()
    }

    /**
     * Marks the service as started and ensures it remains running.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        return START_STICKY
    }

    /**
     * Cleans up resources, stops the server, and shuts down the camera executor.
     */
    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        cameraExecutor.shutdown()
        if (::deviceServer.isInitialized) {
            deviceServer.stop()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Detects motion by comparing the average luminance difference between two frames.
     *
     * @param prevYPlane The Y-plane (brightness) data from the previous frame.
     * @param currYPlane The Y-plane data from the current frame.
     * @param threshold Sensitivity threshold; higher values require more movement.
     * @return True if motion is detected, false otherwise.
     */
    fun hasMotion(prevYPlane: ByteArray?, currYPlane: ByteArray, threshold: Int = 10): Boolean {
        if (prevYPlane == null || prevYPlane.size != currYPlane.size) return true
        if (currYPlane.isEmpty()) return false

        var diff = 0L
        // Sample every 4th pixel to reduce CPU load
        for (i in currYPlane.indices step 4) {
            diff += kotlin.math.abs(currYPlane[i].toInt() - prevYPlane[i].toInt())
        }
        val avgDifference = diff / (currYPlane.size / 4)
        return avgDifference > threshold
    }

    /**
     * Configures CameraX [ImageAnalysis] and binds it to the service lifecycle.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { image ->
                val yBuffer = image.planes[0].buffer
                val currentYPlane = ByteArray(yBuffer.remaining())
                yBuffer.get(currentYPlane)

                val motionDetected = hasMotion(previousYPlane, currentYPlane)
                val jpeg = imageToJpeg(image)
                val timestamp = System.currentTimeMillis()
                
                // Update live stream frame
                FrameBuffer.latestFrame.set(jpeg)

                val manualRec = FrameBuffer.isManualRecording.get()

                if (motionDetected || manualRec) {
                    if (framesRemainingAfterMotion == 0 && !manualRec && FrameBuffer.recordingBuffer.isEmpty()) {
                        Log.d("CameraService", "Motion detected! Starting recording.")
                    }

                    if (motionDetected) {
                        framesRemainingAfterMotion = MOTION_POST_DELAY_FRAMES
                        FrameBuffer.lastMotionTime.set(timestamp)
                    }

                    // Append frame to memory buffer
                    if (FrameBuffer.recordingBuffer.size < MAX_RECORDING_FRAMES) {
                        FrameBuffer.recordingBuffer.add(jpeg to timestamp)
                    } else if (FrameBuffer.recordingBuffer.size == MAX_RECORDING_FRAMES) {
                        Log.w("CameraService", "Max recording length reached. Finalizing.")
                        finalizeRecording()
                    }
                } else if (framesRemainingAfterMotion > 0) {
                    // Continue recording for the post-delay period
                    framesRemainingAfterMotion--
                    FrameBuffer.recordingBuffer.add(jpeg to timestamp)

                    if (framesRemainingAfterMotion == 0 && !manualRec) {
                        finalizeRecording()
                    }
                }

                previousYPlane = currentYPlane
                image.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Hands over the accumulated frame buffer to [VideoWriter] to save as a file.
     * Clears the memory buffer and resets recording states.
     */
    private fun finalizeRecording() {
        if (FrameBuffer.recordingBuffer.isEmpty()) return
        Log.d("CameraService", "Finalizing recording session.")
        val framesToSave = FrameBuffer.recordingBuffer.toList()
        FrameBuffer.recordingBuffer.clear()
        framesRemainingAfterMotion = 0
        FrameBuffer.isManualRecording.set(false)
        videoWriter.saveVideoWithTimestamps(framesToSave)
    }

    /**
     * Converts a CameraX [ImageProxy] (typically YUV_420_888) to a JPEG byte array.
     *
     * @param image The image frame from the camera.
     * @return A byte array containing the JPEG-compressed image.
     */
    private fun imageToJpeg(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        yBuffer.rewind(); uBuffer.rewind(); vBuffer.rewind()
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        return out.toByteArray()
    }

    /**
     * Starts the service in the foreground and creates a persistent notification.
     */
    private fun startForegroundNotification() {
        val channelId = "camera_stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Camera Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bird Identifier")
            .setContentText("Monitoring for birds...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(1, notification)
    }

    /**
     * Starts the local [DeviceServer] on port 8080.
     */
    private fun startMjpegServer() {
        deviceServer = DeviceServer(8080, FrameBuffer.latestFrame, this)
        deviceServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }
}
