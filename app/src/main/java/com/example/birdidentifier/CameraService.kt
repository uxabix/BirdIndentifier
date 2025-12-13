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


class CameraService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var mjpegServer: MjpegServer
    private var previousYPlane: ByteArray? = null // Store the Y plane of the previous frame

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        startForegroundNotification()
        startMjpegServer()
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        cameraExecutor.shutdown()
        mjpegServer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Compares the luminance (Y) planes of two images to detect motion.
    // Comparing raw luminance is more reliable than comparing compressed JPEGs.
    fun hasMotion(prevYPlane: ByteArray?, currYPlane: ByteArray, threshold: Int = 5): Boolean {
        if (prevYPlane == null || prevYPlane.size != currYPlane.size) {
            // If there's no previous frame or dimensions changed, assume motion.
            return true
        }
        if (currYPlane.isEmpty()) return false

        var diff = 0L
        for (i in currYPlane.indices) {
            diff += kotlin.math.abs(currYPlane[i].toInt() - prevYPlane[i].toInt())
        }
        val avgDifference = diff / currYPlane.size
        Log.d("CameraService", "Average pixel difference: $avgDifference")
        return avgDifference > threshold
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { image ->
                // Extract the luminance plane (Y)
                val yBuffer = image.planes[0].buffer
                val ySize = yBuffer.remaining()
                val currentYPlane = ByteArray(ySize)
                yBuffer.get(currentYPlane)

                // Perform motion detection on the Y plane
                if (hasMotion(previousYPlane, currentYPlane)) {
                    Log.d("CameraService", "Motion detected, updating frame.")
                    // If motion is detected, convert the full image to JPEG and update the buffer
                    val jpeg = imageToJpeg(image)
                    FrameBuffer.latestFrame.set(jpeg)
                }

                // Store the current Y plane for the next frame's comparison
                previousYPlane = currentYPlane
                image.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                imageAnalysis
            )

        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageToJpeg(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        // Rewind buffers before reading. The Y buffer might have been read for motion detection.
        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

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

    private fun startForegroundNotification() {
        val channelId = "camera_stream"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Camera Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MJPEG Camera")
            .setContentText("Streaming active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(1, notification)
    }

    private fun startMjpegServer() {
        mjpegServer = MjpegServer(8080, FrameBuffer.latestFrame)
        mjpegServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }
}
