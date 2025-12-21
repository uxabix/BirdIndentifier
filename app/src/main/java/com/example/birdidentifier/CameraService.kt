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
    private lateinit var deviceServer: DeviceServer
    private lateinit var videoWriter: VideoWriter
    private var previousYPlane: ByteArray? = null
    private var framesRemainingAfterMotion = 0
    private val MOTION_POST_DELAY_FRAMES = 600

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        videoWriter = VideoWriter(this)
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
        deviceServer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun hasMotion(prevYPlane: ByteArray?, currYPlane: ByteArray, threshold: Int = 10): Boolean {
        if (prevYPlane == null || prevYPlane.size != currYPlane.size) return true
        if (currYPlane.isEmpty()) return false

        var diff = 0L
        for (i in currYPlane.indices step 4) {
            diff += kotlin.math.abs(currYPlane[i].toInt() - prevYPlane[i].toInt())
        }
        val avgDifference = diff / (currYPlane.size / 4)
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
                val yBuffer = image.planes[0].buffer
                val currentYPlane = ByteArray(yBuffer.remaining())
                yBuffer.get(currentYPlane)

                val motionDetected = hasMotion(previousYPlane, currentYPlane)
                val jpeg = imageToJpeg(image)
                val timestamp = System.currentTimeMillis()
                FrameBuffer.latestFrame.set(jpeg)

                if (motionDetected) {
                    if (framesRemainingAfterMotion == 0) {
                        Log.d("CameraService", "Motion detected! Starting recording.")
                    }
                    framesRemainingAfterMotion = MOTION_POST_DELAY_FRAMES
                    FrameBuffer.recordingBuffer.add(jpeg to timestamp)
                    FrameBuffer.lastMotionTime.set(timestamp)
                } else if (framesRemainingAfterMotion > 0) {
                    framesRemainingAfterMotion--
                    FrameBuffer.recordingBuffer.add(jpeg to timestamp)
                    
                    if (framesRemainingAfterMotion == 0) {
                        Log.d("CameraService", "Motion stopped. Saving fragment.")
                        val framesToSave = FrameBuffer.recordingBuffer.toList()
                        FrameBuffer.recordingBuffer.clear()
                        videoWriter.saveVideoWithTimestamps(framesToSave)
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

    private fun startForegroundNotification() {
        val channelId = "camera_stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Camera Streaming", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bird Identifier")
            .setContentText("Monitoring for birds...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(1, notification)
    }

    private fun startMjpegServer() {
        deviceServer = DeviceServer(8080, FrameBuffer.latestFrame, this)
        deviceServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }
}
