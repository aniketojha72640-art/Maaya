package com.gesturecursor.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class GestureCursorService : AccessibilityService(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private lateinit var settings: CursorSettings
    private lateinit var overlayManager: OverlayCursorManager
    private var handAnalyzer: HandGestureAnalyzer? = null

    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Prevents overlapping gesture injections while a stroke is actively executing
    private val isDispatchingGesture = AtomicBoolean(false)
    private var isVisionPipelineRunning = false

    companion object {
        private const val TAG = "GestureCursorService"
        private const val NOTIFICATION_CHANNEL_ID = "gesture_cursor_camera_channel"
        private const val NOTIFICATION_ID = 4102

        @Volatile
        private var instance: GestureCursorService? = null

        fun isServiceActive(): Boolean = instance != null

        /**
         * Called by MainActivity when permissions are newly granted or the user requests
         * an immediate pipeline refresh.
         */
        fun requestPipelineRefresh() {
            instance?.mainHandler?.post {
                instance?.evaluateAndStartPipeline()
            }
        }

        /**
         * Called by MainActivity's Recenter button.
         */
        fun requestRecenter() {
            instance?.mainHandler?.post {
                instance?.overlayManager?.centerCursor()
                instance?.handAnalyzer?.recenterCalibration()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        settings = CursorSettings(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        overlayManager = OverlayCursorManager(
            context = this,
            onPauseChanged = { paused ->
                if (paused) {
                    stopCameraAnalysisOnly()
                } else {
                    startCameraAnalysis()
                }
            },
            onRecenterRequested = {
                handAnalyzer?.recenterCalibration()
                triggerHapticTick(isLongPress = false)
            }
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        promoteToForegroundCameraService()
        evaluateAndStartPipeline()
    }

    /**
     * Checks if both Overlay and Camera permissions are granted, and starts the
     * floating cursor and CameraX vision analyzer.
     */
    fun evaluateAndStartPipeline() {
        val canDrawOverlays = Settings.canDrawOverlays(this)
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (canDrawOverlays) {
            overlayManager.showOverlays()
        }

        if (canDrawOverlays && hasCameraPermission && !overlayManager.isPaused()) {
            startCameraAnalysis()
        } else {
            Log.i(
                TAG,
                "Waiting for permissions (Overlay=$canDrawOverlays, Camera=$hasCameraPermission)"
            )
        }
    }

    /**
     * Binds CameraX ImageAnalysis to this AccessibilityService's LifecycleOwner.
     */
    private fun startCameraAnalysis() {
        if (isVisionPipelineRunning) return

        val hasCamera = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera) return

        val executor = cameraExecutor ?: Executors.newSingleThreadExecutor().also {
            cameraExecutor = it
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                handAnalyzer?.close()
                val analyzer = HandGestureAnalyzer(
                    context = this,
                    settings = settings,
                    screenSizeProvider = { overlayManager.getScreenSize() },
                    onCursorStateUpdated = { frameState ->
                        handleCursorFrame(frameState)
                    },
                    onHandPresenceChanged = { detected ->
                        overlayManager.setHandDetected(detected)
                    }
                )
                handAnalyzer = analyzer

                // 640x480 provides fast 30 FPS MediaPipe inference with minimal battery/CPU load
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis.setAnalyzer(executor, analyzer)

                val cameraSelector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
                )

                isVisionPipelineRunning = true
                Log.i(TAG, "CameraX + MediaPipe HandLandmarker pipeline bound and running.")
            } catch (e: Exception) {
                isVisionPipelineRunning = false
                Log.e(TAG, "Failed to bind CameraX pipeline: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Unbinds CameraX when the user taps Pause on the floating pill to save battery
     * while keeping the floating pill visible on screen.
     */
    private fun stopCameraAnalysisOnly() {
        mainHandler.post {
            try {
                cameraProvider?.unbindAll()
            } catch (_: Exception) {}
            handAnalyzer?.close()
            handAnalyzer = null
            isVisionPipelineRunning = false
            overlayManager.setHandDetected(false)
        }
    }

    /**
     * Receives smoothed coordinates and gesture triggers from HandGestureAnalyzer,
     * updates the floating cursor overlay, and injects hardware touch gestures.
     */
    private fun handleCursorFrame(state: CursorFrameState) {
        if (overlayManager.isPaused()) return

        overlayManager.updateCursor(state)

        when (val action = state.action) {
            is GestureAction.Tap -> {
                performTap(action.x, action.y, durationMs = 55L, isLongPress = false)
            }
            is GestureAction.LongPress -> {
                performTap(action.x, action.y, durationMs = 620L, isLongPress = true)
            }
            is GestureAction.SwipeDrag -> {
                performSwipe(
                    startX = action.startX,
                    startY = action.startY,
                    endX = action.endX,
                    endY = action.endY,
                    durationMs = action.durationMs
                )
            }
            GestureAction.None -> Unit
        }
    }

    /**
     * Injects a hardware single tap or long-press at (x, y) via AccessibilityService.dispatchGesture().
     */
    private fun performTap(x: Float, y: Float, durationMs: Long, isLongPress: Boolean) {
        if (!isDispatchingGesture.compareAndSet(false, true)) return

        val (screenW, screenH) = overlayManager.getScreenSize()
        val safeX = x.coerceIn(1f, (screenW - 1).toFloat())
        val safeY = y.coerceIn(1f, (screenH - 1).toFloat())

        val clickPath = Path().apply {
            moveTo(safeX, safeY)
        }

        val stroke = GestureDescription.StrokeDescription(clickPath, 0L, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        triggerHapticTick(isLongPress = isLongPress)

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    isDispatchingGesture.set(false)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    isDispatchingGesture.set(false)
                }
            },
            null
        )

        if (!dispatched) {
            isDispatchingGesture.set(false)
        }
    }

    /**
     * Injects a continuous swipe/scroll stroke from (startX, startY) to (endX, endY).
     */
    private fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) {
        if (!isDispatchingGesture.compareAndSet(false, true)) return

        val (screenW, screenH) = overlayManager.getScreenSize()
        val sX = startX.coerceIn(1f, (screenW - 1).toFloat())
        val sY = startY.coerceIn(1f, (screenH - 1).toFloat())
        val eX = endX.coerceIn(1f, (screenW - 1).toFloat())
        val eY = endY.coerceIn(1f, (screenH - 1).toFloat())

        val swipePath = Path().apply {
            moveTo(sX, sY)
            lineTo(eX, eY)
        }

        val stroke = GestureDescription.StrokeDescription(
            swipePath,
            0L,
            durationMs.coerceIn(100L, 900L)
        )
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        triggerHapticTick(isLongPress = false)

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    isDispatchingGesture.set(false)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    isDispatchingGesture.set(false)
                }
            },
            null
        )

        if (!dispatched) {
            isDispatchingGesture.set(false)
        }
    }

    /**
     * Fires a crisp tactile vibration when a pinch click or long press executes.
     */
    private fun triggerHapticTick(isLongPress: Boolean) {
        if (!settings.isHapticsEnabled) return
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) return

            val duration = if (isLongPress) 48L else 18L
            val amplitude = if (isLongPress) 210 else 130
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } catch (_: Exception) {}
    }

    /**
     * Promotes the AccessibilityService to a Foreground Service with CAMERA type
     * so Android 12–14+ never throttles the front camera when switching apps.
     */
    private fun promoteToForegroundCameraService() {
        try {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GestureCursor Vision Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the front camera hand-tracking cursor active across apps"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)

            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Spatial hand cursor active — tap to open settings")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground promotion notice: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Spatial vision cursor does not scrape window text hierarchies.
    }

    override fun onInterrupt() {
        stopCameraAnalysisOnly()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupAllResources()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupAllResources()
        super.onDestroy()
    }

    private fun cleanupAllResources() {
        instance = null
        try {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        } catch (_: Exception) {}

        stopCameraAnalysisOnly()
        overlayManager.hideOverlays()

        try {
            cameraExecutor?.shutdownNow()
        } catch (_: Exception) {}
        cameraExecutor = null
    }
}
