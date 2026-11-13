package com.example.eatmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.Locale
import java.util.concurrent.Executors

class EatingMonitorService : LifecycleService() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_MAX_HOLD_SEC = "EXTRA_MAX_HOLD_SEC"
        const val EXTRA_SENSITIVITY = "EXTRA_SENSITIVITY"
        const val EXTRA_SHOW_DEBUG = "EXTRA_SHOW_DEBUG"
        var isRunning = false
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var overlayManager: BlankOverlayManager
    private lateinit var stateMachine: EatingStateMachine
    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private var remoteServer: RemoteControlServer? = null

    private var lastProcessedTimeMs = 0L
    private val frameIntervalMs = 150L // ~6.6 FPS
    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    override fun onCreate() {
        super.onCreate()
        overlayManager = BlankOverlayManager(this)
        stateMachine = EatingStateMachine()
        faceLandmarkerHelper = FaceLandmarkerHelper(this)

        remoteServer = RemoteControlServer(this, httpPort = 8080, udpDiscoveryPort = 8889) { cmd ->
            handleRemoteCommand(cmd)
        }.also { it.start() }
    }

    private fun handleRemoteCommand(cmd: String): String {
        val now = System.currentTimeMillis()
        when (cmd) {
            "TRIGGER_BLANK" -> {
                stateMachine.triggerBlankNow(now)
                overlayManager.showOverlay()
            }
            "FORCE_LOCK" -> {
                stateMachine.isForceLocked = true
                overlayManager.showOverlay()
            }
            "UNLOCK" -> {
                stateMachine.reset(now)
                overlayManager.hideOverlay()
            }
        }

        val idleSec = stateMachine.getCurrentIdleSec(now)
        val maxHoldSec = stateMachine.maxHoldingDurationMs / 1000f

        return String.format(
            Locale.US,
            "{\"device\":\"%s\",\"state\":\"%s\",\"mar\":%.3f,\"deltaMar\":%.3f,\"idleSec\":%.1f,\"maxHoldSec\":%.0f,\"forceLocked\":%b}",
            deviceName,
            stateMachine.currentState.name,
            stateMachine.lastMar,
            stateMachine.lastDeltaMar,
            idleSec,
            maxHoldSec,
            stateMachine.isForceLocked
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val maxHoldSec = intent?.getIntExtra(EXTRA_MAX_HOLD_SEC, 15) ?: 15
        val sensitivity = intent?.getFloatExtra(EXTRA_SENSITIVITY, 0.030f) ?: 0.030f
        val showDebug = intent?.getBooleanExtra(EXTRA_SHOW_DEBUG, true) ?: true

        stateMachine.maxHoldingDurationMs = maxHoldSec * 1000L
        stateMachine.chewDeltaThreshold = sensitivity
        stateMachine.reset()

        if (showDebug) {
            overlayManager.showDebugOverlay()
        } else {
            overlayManager.hideDebugOverlay()
        }

        if (!isRunning) {
            isRunning = true
            startForegroundNotification(maxHoldSec)
            startCameraAnalysis()
        }

        return START_STICKY
    }

    private fun startForegroundNotification(maxHoldSec: Int) {
        val channelId = "eating_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Eating Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, EatingMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("EatMonitor Aktif (Siap Di-Remote)")
            .setContentText("Batas mengemut: $maxHoldSec dtk | Auto-Discovery ON")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop Monitoring", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(101, notification)
        }
    }

    private fun startCameraAnalysis() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 360))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrameWithThrottle(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrameWithThrottle(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcessedTimeMs < frameIntervalMs) {
            imageProxy.close()
            return
        }
        lastProcessedTimeMs = now

        try {
            val landmarks = faceLandmarkerHelper.detectLandmarks(imageProxy)
            val telemetry = stateMachine.onFrameProcessed(landmarks, now)

            overlayManager.updateDebugStats(telemetry, stateMachine.chewDeltaThreshold)

            when (telemetry.state) {
                EatingState.SCREEN_LOCKED -> overlayManager.showOverlay()
                EatingState.CHEWING -> overlayManager.hideOverlay()
                else -> {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    override fun onDestroy() {
        isRunning = false
        remoteServer?.stop()
        overlayManager.hideAll()
        faceLandmarkerHelper.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
