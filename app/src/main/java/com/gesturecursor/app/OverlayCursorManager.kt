package com.gesturecursor.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class OverlayCursorManager(
    private val context: Context,
    private val onPauseChanged: (Boolean) -> Unit = {},
    private val onRecenterRequested: () -> Unit = {}
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cursorCanvasView: CursorCanvasView? = null
    private var controlPillView: View? = null
    private var statusDotView: View? = null
    private var pauseButtonView: TextView? = null

    private var isOverlayShowing = false
    private var isPausedState = false
    private var isHandVisible = false

    /**
     * Returns the full physical screen dimensions (width, height) in pixels.
     */
    fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    /**
     * Attaches both the pass-through cursor canvas and the floating edge control pill.
     */
    fun showOverlays() {
        mainHandler.post {
            if (isOverlayShowing || !Settings.canDrawOverlays(context)) return@post

            val (screenW, screenH) = getScreenSize()

            // 1. Untouchable Full-Screen Hardware-Accelerated Cursor Layer
            val canvasView = CursorCanvasView(context).apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                cursorX = screenW / 2f
                cursorY = screenH / 2f
            }

            val cursorLayoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            // 2. Touchable Floating Dark-Glass Edge Pill
            val pillLayoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 16
                y = (screenH * 0.35f).toInt()
            }

            val pillView = buildControlPill(pillLayoutParams)

            try {
                windowManager.addView(canvasView, cursorLayoutParams)
                windowManager.addView(pillView, pillLayoutParams)
                cursorCanvasView = canvasView
                controlPillView = pillView
                isOverlayShowing = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Cleanly detaches all overlay views from the WindowManager.
     */
    fun hideOverlays() {
        mainHandler.post {
            if (!isOverlayShowing) return@post
            try {
                cursorCanvasView?.let { windowManager.removeViewImmediate(it) }
            } catch (_: Exception) {}
            try {
                controlPillView?.let { windowManager.removeViewImmediate(it) }
            } catch (_: Exception) {}

            cursorCanvasView = null
            controlPillView = null
            statusDotView = null
            pauseButtonView = null
            isOverlayShowing = false
        }
    }

    /**
     * Updates the cursor position, pinch ring progress, drag line, and click ripples on the Main Thread.
     */
    fun updateCursor(state: CursorFrameState) {
        mainHandler.post {
            val view = cursorCanvasView ?: return@post
            if (isPausedState) return@post

            view.cursorX = state.screenX
            view.cursorY = state.screenY
            view.pinchProgress = state.pinchProgress
            view.isPinched = state.isPinched
            view.isDragging = state.isDragging

            when (val action = state.action) {
                is GestureAction.Tap -> {
                    view.dragStartPoint = null
                    view.triggerClickRipple(action.x, action.y, isLongPress = false)
                }
                is GestureAction.LongPress -> {
                    view.triggerClickRipple(action.x, action.y, isLongPress = true)
                }
                is GestureAction.SwipeDrag -> {
                    view.dragStartPoint = null
                    view.triggerClickRipple(action.endX, action.endY, isLongPress = false)
                }
                GestureAction.None -> {
                    if (state.isDragging && view.dragStartPoint == null) {
                        view.dragStartPoint = PointF(state.screenX, state.screenY)
                    } else if (!state.isDragging) {
                        view.dragStartPoint = null
                    }
                }
            }

            view.invalidate()
        }
    }

    /**
     * Updates the visual tracking indicator when a hand enters or leaves the camera frame.
     */
    fun setHandDetected(detected: Boolean) {
        mainHandler.post {
            if (isHandVisible == detected) return@post
            isHandVisible = detected
            cursorCanvasView?.isHandTracked = detected
            cursorCanvasView?.invalidate()
            updateStatusDotColor()
        }
    }

    /**
     * Moves the cursor back to the center of the display.
     */
    fun centerCursor() {
        mainHandler.post {
            val (w, h) = getScreenSize()
            cursorCanvasView?.apply {
                cursorX = w / 2f
                cursorY = h / 2f
                invalidate()
            }
        }
    }

    fun isPaused(): Boolean = isPausedState

    fun setPaused(paused: Boolean) {
        mainHandler.post {
            isPausedState = paused
            cursorCanvasView?.isCursorPaused = paused
            cursorCanvasView?.invalidate()
            pauseButtonView?.text = if (paused) "▶" else "⏸"
            updateStatusDotColor()
            onPauseChanged(paused)
        }
    }

    private fun updateStatusDotColor() {
        val dot = statusDotView ?: return
        val color = when {
            isPausedState -> Color.parseColor("#F59E0B") // Amber: Paused
            isHandVisible -> Color.parseColor("#10B981") // Emerald: Active Hand Lock
            else -> Color.parseColor("#EF4444")          // Crimson: Searching for Hand
        }
        val bg = dot.background as? GradientDrawable ?: GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(color)
        dot.background = bg
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildControlPill(layoutParams: WindowManager.LayoutParams): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = dpToPx(8f)
            val padV = dpToPx(10f)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24f).toFloat()
                setColor(Color.parseColor("#CC0F172A")) // Glassmorphic deep slate
                setStroke(dpToPx(1f), Color.parseColor("#4D38BDF8")) // Subtle cyan border
            }
        }

        // 1. Live Hand-Tracking Status Dot (Also acts as drag handle)
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(10f), dpToPx(10f)).apply {
                bottomMargin = dpToPx(8f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EF4444"))
            }
        }
        statusDotView = dot
        container.addView(dot)

        // 2. Pause / Resume Toggle Button
        val pauseBtn = createPillButton("⏸") {
            setPaused(!isPausedState)
        }
        pauseButtonView = pauseBtn
        container.addView(pauseBtn)

        // 3. Recenter Cursor Button
        val centerBtn = createPillButton("◎") {
            centerCursor()
            onRecenterRequested()
        }
        container.addView(centerBtn)

        // 4. Open Dashboard Activity Button
        val settingsBtn = createPillButton("⚙") {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
        container.addView(settingsBtn)

        // Allow dragging the floating pill anywhere along the screen via touch
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) {
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(container, layoutParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }

        return container
    }

    private fun createPillButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = symbol
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            val size = dpToPx(34f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                topMargin = dpToPx(4f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#331E293B"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * Custom hardware-accelerated View that renders the glassmorphic reticle,
     * dynamic pinch gauge, drag vector line, and click shockwave ripples.
     */
    private inner class CursorCanvasView(context: Context) : View(context) {
        var cursorX: Float = 540f
        var cursorY: Float = 1200f
        var pinchProgress: Float = 0f
        var isPinched: Boolean = false
        var isDragging: Boolean = false
        var isHandTracked: Boolean = false
        var isCursorPaused: Boolean = false
        var dragStartPoint: PointF? = null

        private var rippleX: Float = 0f
        private var rippleY: Float = 0f
        private var rippleProgress: Float = 0f
        private var rippleIsLongPress: Boolean = false
        private var rippleAnimator: ValueAnimator? = null

        private val outerRingRadius = dpToPx(20f).toFloat()
        private val centerDotRadius = dpToPx(5.5f).toFloat()

        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(4.5f).toFloat()
            color = Color.parseColor("#99000000")
        }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2.5f).toFloat()
            color = Color.parseColor("#38BDF8") // Neon Sky Cyan
        }

        private val pinchArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(3.5f).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#10B981") // Emerald pinch gauge
        }

        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        private val dragLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2.5f).toFloat()
            color = Color.parseColor("#A855F7") // Purple drag vector
            pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        }

        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(3f).toFloat()
        }

        fun triggerClickRipple(x: Float, y: Float, isLongPress: Boolean) {
            rippleX = x
            rippleY = y
            rippleIsLongPress = isLongPress
            rippleAnimator?.cancel()
            rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (isLongPress) 520L else 320L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    rippleProgress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isCursorPaused) return

            val alphaMultiplier = if (isHandTracked) 255 else 110

            // 1. Draw Swipe/Drag Vector Line if user is pinching and dragging
            dragStartPoint?.let { start ->
                if (isDragging) {
                    dragLinePaint.alpha = alphaMultiplier
                    canvas.drawLine(start.x, start.y, cursorX, cursorY, dragLinePaint)
                    canvas.drawCircle(start.x, start.y, dpToPx(4f).toFloat(), dragLinePaint)
                }
            }

            // 2. Draw Expanding Click Shockwave Ripple
            if (rippleProgress in 0.01f..0.99f) {
                val maxRippleRadius = if (rippleIsLongPress) dpToPx(44f) else dpToPx(32f)
                val currentRadius = outerRingRadius * 0.6f + maxRippleRadius * rippleProgress
                ripplePaint.color = if (rippleIsLongPress) {
                    Color.parseColor("#F59E0B")
                } else {
                    Color.parseColor("#10B981")
                }
                ripplePaint.alpha = ((1f - rippleProgress) * 240).toInt().coerceIn(0, 255)
                canvas.drawCircle(rippleX, rippleY, currentRadius, ripplePaint)
            }

            // 3. Dynamic Reticle Radius (shrinks tightly onto target as user pinches)
            val dynamicRadius = outerRingRadius * (1.0f - (pinchProgress * 0.35f))

            // 4. Outer High-Contrast Shadow & Base Reticle Ring
            shadowPaint.alpha = (alphaMultiplier * 0.6f).toInt()
            canvas.drawCircle(cursorX, cursorY, dynamicRadius, shadowPaint)

            ringPaint.color = when {
                isDragging -> Color.parseColor("#A855F7") // Purple during drag
                isPinched -> Color.parseColor("#10B981")  // Emerald when pinched
                else -> Color.parseColor("#38BDF8")       // Cyan default
            }
            ringPaint.alpha = alphaMultiplier
            canvas.drawCircle(cursorX, cursorY, dynamicRadius, ringPaint)

            // 5. Radial Pinch Progress Arc (0..360 degrees as index & thumb close)
            if (pinchProgress > 0.05f) {
                val sweepAngle = pinchProgress * 360f
                pinchArcPaint.alpha = alphaMultiplier
                canvas.drawArc(
                    cursorX - dynamicRadius,
                    cursorY - dynamicRadius,
                    cursorX + dynamicRadius,
                    cursorY + dynamicRadius,
                    -90f,
                    sweepAngle,
                    false,
                    pinchArcPaint
                )
            }

            // 6. Precision Center Bullseye Dot
            centerDotPaint.color = if (isPinched) Color.parseColor("#10B981") else Color.WHITE
            centerDotPaint.alpha = alphaMultiplier
            canvas.drawCircle(cursorX, cursorY, centerDotRadius, centerDotPaint)
        }
    }
}

