package com.example.birdidentifier

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.Camera
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

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video
import org.opencv.android.OpenCVLoader

/**
 * A foreground service that manages the camera lifecycle, performs motion detection,
 * and handles video recording logic.
 */
class CameraService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var deviceServer: DeviceServer
    private lateinit var videoWriter: VideoWriter
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var camera: Camera? = null

    private var nv21: ByteArray? = null
    private val jpegOutputStream = ByteArrayOutputStream()

    private var framesRemainingAfterMotion = 0
    private var wasManualRecording = false
    private var isCurrentlyRecording = false

    // OpenCV related fields
    private lateinit var bgSubtractor: BackgroundSubtractorMOG2
    private var motionFramesCount = 0

    private val MOTION_POST_DELAY_FRAMES = 30 * 10 // 10 seconds at 30 FPS
    private val MIN_CONTOUR_AREA = 77.0 // Minimum contour area for motion detection
    private val MOTION_REQUIRED_FRAMES = 3 // Number of consecutive frames with motion to trigger recording

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        videoWriter = VideoWriter(this)
        acquireWakeLocks()
        startForegroundNotification()
        startMjpegServer()

        if (!OpenCVLoader.initDebug()) {
            Log.e("CameraService", "OpenCV initialization failed!")
        } else {
            Log.d("CameraService", "OpenCV initialization successful.")
            bgSubtractor = Video.createBackgroundSubtractorMOG2(500, 16.0, false)
        }
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
        if (::deviceServer.isInitialized) {
            deviceServer.stop()
        }
        if (isCurrentlyRecording) {
            videoWriter.stopRecording()
        }
        releaseWakeLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { image ->
                if (FrameBuffer.zoomChanged.compareAndSet(true, false)) {
                    val newZoom = FrameBuffer.zoomLevel.get()
                    camera?.cameraControl?.setZoomRatio(newZoom)
                }

                val jpeg = imageToJpeg(image)
                val timestamp = System.currentTimeMillis()

                FrameBuffer.latestFrame.set(jpeg)

                val manualRec = FrameBuffer.isManualRecording.get()

                var currentMotionDetected = false
                if (::bgSubtractor.isInitialized) {
                    val yBuffer = image.planes[0].buffer
                    val uBuffer = image.planes[1].buffer
                    val vBuffer = image.planes[2].buffer
                    yBuffer.rewind(); uBuffer.rewind(); vBuffer.rewind()

                    val ySize = yBuffer.remaining()
                    val uSize = uBuffer.remaining()
                    val vSize = vBuffer.remaining()

                    val yuvBytes = ByteArray(ySize + uSize + vSize)
                    yBuffer.get(yuvBytes, 0, ySize)
                    vBuffer.get(yuvBytes, ySize, vSize)
                    uBuffer.get(yuvBytes, ySize + vSize, uSize)

                    val yuvMat = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
                    yuvMat.put(0, 0, yuvBytes)

                    val bgrMat = Mat()
                    Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_NV21)

                    val resizedMat = Mat()
                    Imgproc.resize(bgrMat, resizedMat, Size(320.0, 240.0))

                    val grayMat = Mat()
                    Imgproc.cvtColor(resizedMat, grayMat, Imgproc.COLOR_BGR2GRAY)

                    val blurredMat = Mat()
                    Imgproc.GaussianBlur(grayMat, blurredMat, Size(3.0, 3.0), 0.0)

                    val fgMask = Mat()
                    bgSubtractor.apply(blurredMat, fgMask)

                    // Threshold (if needed, MOG2 often produces a binary mask directly)
                    // Imgproc.threshold(fgMask, fgMask, 127.0, 255.0, Imgproc.THRESH_BINARY);

                    val contours = ArrayList<MatOfPoint>()
                    val hierarchy = Mat()
                    Imgproc.findContours(fgMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                    for (contour in contours) {
                        val contourArea = Imgproc.contourArea(contour)
                        if (contourArea > MIN_CONTOUR_AREA) {
                            currentMotionDetected = true
                            break
                        }
                    }

                    yuvMat.release()
                    bgrMat.release()
                    resizedMat.release()
                    grayMat.release()
                    blurredMat.release()
                    fgMask.release()
                    hierarchy.release()
                    for (contour in contours) {
                        contour.release()
                    }
                }

                // Temporal filter
                if (currentMotionDetected) {
                    motionFramesCount++
                } else {
                    motionFramesCount = 0
                }
                val motionDetected = motionFramesCount >= MOTION_REQUIRED_FRAMES


                // Recording logic
                val shouldBeRecording = motionDetected || manualRec || framesRemainingAfterMotion > 0

                if (shouldBeRecording) {
                    if (!isCurrentlyRecording) {
                        Log.d("CameraService", "Starting recording to disk...")
                        videoWriter.startRecording(image.width, image.height)
                        isCurrentlyRecording = true
                    }
                    
                    videoWriter.addFrame(jpeg)

                    if (motionDetected) {
                        framesRemainingAfterMotion = MOTION_POST_DELAY_FRAMES
                        FrameBuffer.lastMotionTime.set(timestamp)
                    } else if (framesRemainingAfterMotion > 0 && !manualRec) {
                        framesRemainingAfterMotion--
                    }
                } else {
                    if (isCurrentlyRecording) {
                        Log.d("CameraService", "Stopping recording.")
                        videoWriter.stopRecording()
                        isCurrentlyRecording = false
                    }
                }

                if (wasManualRecording && !manualRec && isCurrentlyRecording) {
                     // Manual recording stopped, but we might still have motion. 
                     // If no motion, stop immediately.
                     if (!motionDetected && framesRemainingAfterMotion <= 0) {
                         videoWriter.stopRecording()
                         isCurrentlyRecording = false
                     }
                }
                wasManualRecording = manualRec
                
                image.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)

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

        val totalSize = ySize + uSize + vSize
        if (nv21 == null || nv21?.size != totalSize) {
            nv21 = ByteArray(totalSize)
        }

        val currentNv21 = nv21!!
        yBuffer.get(currentNv21, 0, ySize)
        vBuffer.get(currentNv21, ySize, vSize)
        uBuffer.get(currentNv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(currentNv21, ImageFormat.NV21, image.width, image.height, null)
        jpegOutputStream.reset()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, jpegOutputStream)
        return jpegOutputStream.toByteArray()
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

    private fun acquireWakeLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BirdIdentifier::WakeLock")
        wakeLock?.acquire()

        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BirdIdentifier::WifiLock")
        wifiLock?.acquire()
    }

    private fun releaseWakeLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        if (wifiLock?.isHeld == true) wifiLock?.release()
        wifiLock = null
    }
}
