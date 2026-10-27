package com.gesturecursor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max

class HandGestureAnalyzer(
    private val context: Context,
    private val settings: CursorSettings,
    private val screenSizeProvider: () -> Pair<Int, Int>,
    private val onCursorStateUpdated: (CursorFrameState) -> Unit,
    private val onHandPresenceChanged: (Boolean) -> Unit
) : ImageAnalysis.Analyzer {

    private var handLandmarker: HandLandmarker? = null
    private val isClosed = AtomicBoolean(false)

    // Signal filter & Schmitt-Trigger gesture state machine (from File 7)
    private val filter2D = OneEuroFilter2D(
        minCutOff = settings.smoothingCutoff.toDouble(),
        beta = 0.015
    )
    private val gestureStateMachine = PinchGestureStateMachine()

    // Monotonic timestamp tracker (MediaPipe LIVE_STREAM requires strictly increasing timestamps)
    private var lastTimestampMs: Long = 0L

    // Spatial calibration center (default slightly below vertical center for natural hand posture)
    @Volatile
    private var centerOffsetX: Float = 0.50f
    @Volatile
    private var centerOffsetY: Float = 0.56f

    @Volatile
    private var lastRawNormX: Float = 0.50f
    @Volatile
    private var lastRawNormY: Float = 0.56f
    @Volatile
    private var isHandCurrentlyDetected: Boolean = false

    companion object {
        private const val TAG = "HandGestureAnalyzer"
        private const val MODEL_ASSET_PATH = "hand_landmarker.task"

        // MediaPipe 21-point Hand Landmark indices
        private const val LANDMARK_THUMB_TIP = 4
        private const val LANDMARK_INDEX_MCP = 5
        private const val LANDMARK_INDEX_TIP = 8
    }

    init {
        setupHandLandmarker()
    }

    /**
     * Initializes MediaPipe HandLandmarker in asynchronous LIVE_STREAM mode.
     * Attempts hardware GPU acceleration first and falls back cleanly to CPU (XNNPACK) if needed.
     */
    private fun setupHandLandmarker() {
        handLandmarker = createLandmarkerWithDelegate(Delegate.GPU)
            ?: createLandmarkerWithDelegate(Delegate.CPU)
    }

    private fun createLandmarkerWithDelegate(delegate: Delegate): HandLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .setDelegate(delegate)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.55f)
                .setMinHandPresenceConfidence(0.55f)
                .setMinTrackingConfidence(0.55f)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::onHandsLandmarkResult)
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe HandLandmarker error: ${error.message}")
                }
                .build()

            HandLandmarker.createFromOptions(context, options)
        } catch (t: Throwable) {
            Log.w(TAG, "Delegate $delegate unavailable, falling back: ${t.message}")
            null
        }
    }

    /**
     * Receives frames from CameraX on the background executor thread,
     * applies sensor rotation and horizontal selfie mirroring, and dispatches to MediaPipe.
     */
    override fun analyze(imageProxy: ImageProxy) {
        if (isClosed.get()) {
            imageProxy.close()
            return
        }

        val landmarker = handLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }

        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val sourceBitmap = imageProxy.toBitmap()

            // Rotate upright and mirror horizontally so moving hand right moves cursor right
            val matrix = Matrix().apply {
                if (rotationDegrees != 0) {
                    postRotate(rotationDegrees.toFloat())
                }
                postScale(-1f, 1f)
            }

            val transformedBitmap: Bitmap = Bitmap.createBitmap(
                sourceBitmap,
                0,
                0,
                sourceBitmap.width,
                sourceBitmap.height,
                matrix,
                true
            )

            val mpImage: MPImage = BitmapImageBuilder(transformedBitmap).build()

            // Guarantee strictly increasing frame timestamps for MediaPipe LIVE_STREAM
            val nowMs = SystemClock.uptimeMillis()
            val frameTimestampMs = max(nowMs, lastTimestampMs + 1L)
            lastTimestampMs = frameTimestampMs

            landmarker.detectAsync(mpImage, frameTimestampMs)
        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis exception: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Asynchronous callback invoked by MediaPipe whenever hand landmarks are extracted.
     */
    private fun onHandsLandmarkResult(result: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: MPImage) {
        if (isClosed.get()) return

        val hands = result.landmarks()
        if (hands.isEmpty() || hands[0].size <= LANDMARK_INDEX_TIP) {
            if (isHandCurrentlyDetected) {
                isHandCurrentlyDetected = false
                gestureStateMachine.reset()
                onHandPresenceChanged(false)
            }
            return
        }

        if (!isHandCurrentlyDetected) {
            isHandCurrentlyDetected = true
            onHandPresenceChanged(true)
        }

        val landmarks = hands[0]
        val thumbTip = landmarks[LANDMARK_THUMB_TIP]
        val indexTip = landmarks[LANDMARK_INDEX_TIP]
        val indexMcp = landmarks[LANDMARK_INDEX_MCP]

        // Blend 70% Index Tip + 30% Index Knuckle (MCP) so pinching fingers together
        // keeps the base aiming point steady before the state-machine anchor locks in.
        val rawNormX = (indexTip.x() * 0.70f) + (indexMcp.x() * 0.30f)
        val rawNormY = (indexTip.y() * 0.70f) + (indexMcp.y() * 0.30f)

        lastRawNormX = rawNormX
        lastRawNormY = rawNormY

        // Apply active Region-of-Interest (ROI) sensitivity scaling around calibration center
        val sensitivity = settings.sensitivity.coerceIn(0.6f, 3.5f)
        val scaledNormX = ((rawNormX - centerOffsetX) * sensitivity + 0.5f).coerceIn(0.0f, 1.0f)
        val scaledNormY = ((rawNormY - centerOffsetY) * sensitivity + 0.5f).coerceIn(0.0f, 1.0f)

        val (screenW, screenH) = screenSizeProvider()
        val targetScreenX = scaledNormX * screenW.toFloat()
        val targetScreenY = scaledNormY * screenH.toFloat()

        // Sync live smoothing cutoff from user settings and run 1-Euro Filter
        filter2D.updateParameters(
            minCutOff = settings.smoothingCutoff.toDouble().coerceIn(0.2, 5.0),
            beta = 0.015
        )
        val timestampMs = SystemClock.uptimeMillis()
        val (smoothedX, smoothedY) = filter2D.filter(targetScreenX, targetScreenY, timestampMs)

        // Compute normalized 2D Euclidean distance between Index Finger Tip and Thumb Tip
        val pinchDx = indexTip.x() - thumbTip.x()
        val pinchDy = indexTip.y() - thumbTip.y()
        val normalizedPinchDist = hypot(pinchDx, pinchDy)

        // Update Schmitt-Trigger Pinch State Machine (Tap, LongPress, SwipeDrag)
        val frameState = gestureStateMachine.update(
            smoothedX = smoothedX.coerceIn(0f, screenW.toFloat()),
            smoothedY = smoothedY.coerceIn(0f, screenH.toFloat()),
            normalizedPinchDistance = normalizedPinchDist,
            pinchThreshold = settings.pinchThreshold,
            timestampMs = timestampMs
        )

        onCursorStateUpdated(frameState)
    }

    /**
     * Calibrates the neutral screen center to the user's current hand posture when
     * the Recenter (◎) button on the floating pill is pressed.
     */
    fun recenterCalibration() {
        if (isHandCurrentlyDetected) {
            centerOffsetX = lastRawNormX.coerceIn(0.20f, 0.80f)
            centerOffsetY = lastRawNormY.coerceIn(0.20f, 0.80f)
        } else {
            centerOffsetX = 0.50f
            centerOffsetY = 0.56f
        }
        filter2D.reset()
        gestureStateMachine.reset()
    }

    /**
     * Releases native MediaPipe C++ resources cleanly when the service stops or pauses.
     */
    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                handLandmarker?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing HandLandmarker: ${e.message}")
            } finally {
                handLandmarker = null
            }
        }
    }
}
