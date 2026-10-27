package com.gesturecursor.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * First-order exponential low-pass filter used internally by the 1-Euro Filter.
 */
class LowPassFilter(private var alpha: Double = 0.5) {
    private var y: Double = 0.0
    private var initialized: Boolean = false

    fun filter(value: Double, customAlpha: Double = this.alpha): Double {
        this.alpha = customAlpha.coerceIn(0.0001, 1.0)
        if (!initialized) {
            y = value
            initialized = true
            return y
        }
        y = this.alpha * value + (1.0 - this.alpha) * y
        return y
    }

    fun hasLastRawValue(): Boolean = initialized

    fun lastValue(): Double = y

    fun reset() {
        initialized = false
        y = 0.0
    }
}

/**
 * Speed-adaptive 1-Euro Filter (Casiez et al., CHI 2012).
 * - Low velocity (holding hand still): Low cutoff -> heavy smoothing, zero jitter.
 * - High velocity (moving across screen): High cutoff -> near-zero latency.
 */
class OneEuroFilter(
    var minCutOff: Double = 1.0,
    var beta: Double = 0.015,
    private val dCutOff: Double = 1.0
) {
    private val xFilter = LowPassFilter()
    private val dxFilter = LowPassFilter()
    private var lastTimestampMs: Long = -1L

    private fun computeAlpha(cutoff: Double, freqHz: Double): Double {
        val tau = 1.0 / (2.0 * PI * max(0.01, cutoff))
        val te = 1.0 / max(1.0, freqHz)
        return 1.0 / (1.0 + tau / te)
    }

    fun filter(value: Double, timestampMs: Long = System.currentTimeMillis()): Double {
        val freqHz: Double = if (lastTimestampMs > 0L && timestampMs > lastTimestampMs) {
            val dtSec = (timestampMs - lastTimestampMs) / 1000.0
            (1.0 / dtSec).coerceIn(5.0, 120.0)
        } else {
            30.0
        }
        lastTimestampMs = timestampMs

        val prevX = if (xFilter.hasLastRawValue()) xFilter.lastValue() else value
        val dx = (value - prevX) * freqHz
        val edx = dxFilter.filter(dx, computeAlpha(dCutOff, freqHz))

        val dynamicCutoff = minCutOff + beta * abs(edx)
        return xFilter.filter(value, computeAlpha(dynamicCutoff, freqHz))
    }

    fun reset() {
        xFilter.reset()
        dxFilter.reset()
        lastTimestampMs = -1L
    }
}

/**
 * 2D Coordinate wrapper for (X, Y) screen filtering.
 */
class OneEuroFilter2D(
    minCutOff: Double = 1.0,
    beta: Double = 0.015
) {
    private val xFilter = OneEuroFilter(minCutOff = minCutOff, beta = beta)
    private val yFilter = OneEuroFilter(minCutOff = minCutOff, beta = beta)

    fun updateParameters(minCutOff: Double, beta: Double = 0.015) {
        xFilter.minCutOff = minCutOff.coerceIn(0.1, 10.0)
        xFilter.beta = beta
        yFilter.minCutOff = minCutOff.coerceIn(0.1, 10.0)
        yFilter.beta = beta
    }

    fun filter(
        rawX: Float,
        rawY: Float,
        timestampMs: Long = System.currentTimeMillis()
    ): Pair<Float, Float> {
        val sx = xFilter.filter(rawX.toDouble(), timestampMs).toFloat()
        val sy = yFilter.filter(rawY.toDouble(), timestampMs).toFloat()
        return Pair(sx, sy)
    }

    fun reset() {
        xFilter.reset()
        yFilter.reset()
    }
}

/**
 * High-level touch actions emitted by the Pinch Hysteresis State Machine
 * and consumed by GestureCursorService.dispatchGesture().
 */
sealed class GestureAction {
    object None : GestureAction()
    data class Tap(val x: Float, val y: Float) : GestureAction()
    data class LongPress(val x: Float, val y: Float) : GestureAction()
    data class SwipeDrag(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long
    ) : GestureAction()
}

/**
 * Snapshot emitted on every camera frame to drive both the Overlay UI and AccessibilityService.
 */
data class CursorFrameState(
    val screenX: Float,
    val screenY: Float,
    val pinchProgress: Float, // 0.0f (open hand) -> 1.0f (pinched closed)
    val isPinched: Boolean,
    val isDragging: Boolean,
    val action: GestureAction
)

/**
 * Schmitt-Trigger Hysteresis State Machine for Pinch Gestures:
 * - Uses dual thresholds (enter vs. exit) so finger tremor at the boundary never double-clicks.
 * - Anchors (locks) the cursor position at the onset of a pinch so closing your thumb & index
 *   finger doesn't pull the cursor off a small target button.
 */
class PinchGestureStateMachine {

    private enum class State {
        IDLE,
        PINCH_HOLDING,
        LONG_PRESS_FIRED,
        DRAGGING
    }

    private var state: State = State.IDLE
    private var pinchStartTimeMs: Long = 0L
    private var lastClickTimeMs: Long = 0L

    private var anchorX: Float = 0f
    private var anchorY: Float = 0f

    companion object {
        private const val HYSTERESIS_EXIT_MULTIPLIER = 1.38f
        private const val LONG_PRESS_DURATION_MS = 550L
        private const val CLICK_COOLDOWN_MS = 220L
        private const val DRAG_SLOP_PX = 55f
    }

    fun update(
        smoothedX: Float,
        smoothedY: Float,
        normalizedPinchDistance: Float,
        pinchThreshold: Float,
        timestampMs: Long = System.currentTimeMillis()
    ): CursorFrameState {
        val enterThreshold = pinchThreshold.coerceIn(0.02f, 0.15f)
        val exitThreshold = enterThreshold * HYSTERESIS_EXIT_MULTIPLIER

        // Map normalized distance into 0.0f..1.0f progress for the visual reticle ring
        val maxOpenDistance = enterThreshold * 2.8f
        val progress = ((maxOpenDistance - normalizedPinchDistance) / (maxOpenDistance - enterThreshold))
            .coerceIn(0.0f, 1.0f)

        var emittedAction: GestureAction = GestureAction.None
        var outputX = smoothedX
        var outputY = smoothedY

        when (state) {
            State.IDLE -> {
                if (normalizedPinchDistance <= enterThreshold &&
                    (timestampMs - lastClickTimeMs) >= CLICK_COOLDOWN_MS
                ) {
                    state = State.PINCH_HOLDING
                    pinchStartTimeMs = timestampMs
                    anchorX = smoothedX
                    anchorY = smoothedY
                    outputX = anchorX
                    outputY = anchorY
                }
            }

            State.PINCH_HOLDING -> {
                val distFromAnchor = hypot(smoothedX - anchorX, smoothedY - anchorY)
                val heldDuration = timestampMs - pinchStartTimeMs

                if (normalizedPinchDistance >= exitThreshold) {
                    // Quick pinch release -> Fire clean single Tap at the locked anchor coordinate
                    emittedAction = GestureAction.Tap(anchorX, anchorY)
                    lastClickTimeMs = timestampMs
                    state = State.IDLE
                    outputX = anchorX
                    outputY = anchorY
                } else if (distFromAnchor > DRAG_SLOP_PX) {
                    // User pinched and moved hand beyond drag slop -> enter continuous drag mode
                    state = State.DRAGGING
                } else if (heldDuration >= LONG_PRESS_DURATION_MS) {
                    // User held pinch steady for 550ms -> fire Long Press at anchor
                    emittedAction = GestureAction.LongPress(anchorX, anchorY)
                    lastClickTimeMs = timestampMs
                    state = State.LONG_PRESS_FIRED
                    outputX = anchorX
                    outputY = anchorY
                } else {
                    // Keep cursor magnetized to anchor while user finishes pinching
                    outputX = anchorX
                    outputY = anchorY
                }
            }

            State.LONG_PRESS_FIRED -> {
                // Keep cursor steady until fingers open past exit threshold
                outputX = anchorX
                outputY = anchorY
                if (normalizedPinchDistance >= exitThreshold) {
                    state = State.IDLE
                }
            }

            State.DRAGGING -> {
                // In drag mode, cursor follows live hand position to show swipe path
                outputX = smoothedX
                outputY = smoothedY
                if (normalizedPinchDistance >= exitThreshold) {
                    val dragDuration = (timestampMs - pinchStartTimeMs).coerceIn(120L, 800L)
                    emittedAction = GestureAction.SwipeDrag(
                        startX = anchorX,
                        startY = anchorY,
                        endX = smoothedX,
                        endY = smoothedY,
                        durationMs = dragDuration
                    )
                    lastClickTimeMs = timestampMs
                    state = State.IDLE
                }
            }
        }

        val isCurrentlyPinched = state != State.IDLE
        val isCurrentlyDragging = state == State.DRAGGING

        return CursorFrameState(
            screenX = outputX,
            screenY = outputY,
            pinchProgress = if (isCurrentlyPinched) 1.0f else progress,
            isPinched = isCurrentlyPinched,
            isDragging = isCurrentlyDragging,
            action = emittedAction
        )
    }

    fun reset() {
        state = State.IDLE
    }
}
