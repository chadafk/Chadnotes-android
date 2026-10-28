package com.xnotes.canvas

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.input.motionprediction.MotionEventPredictor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================
// 1. STYLUS BUTTON REMAPPING, PARAMETRIC PRESSURE CURVES & SCRIBBLE-TO-ERASE
// ============================================================================

enum class CanvasToolMode {
    PEN,
    HIGHLIGHTER,
    ERASER,
    LASSO,
    HAND_PAN
}

enum class StylusRemapAction {
    NONE,
    TOGGLE_ERASER,
    HOLD_ERASER,
    HOLD_LASSO,
    SWITCH_PREVIOUS_TOOL,
    CYCLE_PEN_COLOR,
    UNDO,
    REDO
}

class StylusButtonController(
    var holdAction: StylusRemapAction = StylusRemapAction.HOLD_ERASER,
    var doubleTapAction: StylusRemapAction = StylusRemapAction.TOGGLE_ERASER,
    var doubleTapWindowMs: Long = 320L
) {
    private var isPrimaryButtonHeld = false
    private var toolBeforeHold: CanvasToolMode? = null
    private var lastValidNonEraserTool: CanvasToolMode = CanvasToolMode.PEN
    private var lastButtonPressTimestamp = 0L

    interface Callbacks {
        fun getCurrentTool(): CanvasToolMode
        fun setCurrentTool(tool: CanvasToolMode)
        fun cyclePenColor()
        fun performUndo()
        fun performRedo()
        fun triggerHapticTick()
    }

    fun processMotionEvent(event: MotionEvent, callbacks: Callbacks) {
        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) {
            return
        }

        val currentTool = callbacks.getCurrentTool()
        if (currentTool != CanvasToolMode.ERASER && currentTool != CanvasToolMode.LASSO) {
            lastValidNonEraserTool = currentTool
        }

        val buttonPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ||
            toolType == MotionEvent.TOOL_TYPE_ERASER

        if (buttonPressed && !isPrimaryButtonHeld) {
            isPrimaryButtonHeld = true
            val now = SystemClock.uptimeMillis()

            if (now - lastButtonPressTimestamp in 40L..doubleTapWindowMs) {
                lastButtonPressTimestamp = 0L
                executeDiscreteAction(doubleTapAction, callbacks)
            } else {
                lastButtonPressTimestamp = now
                when (holdAction) {
                    StylusRemapAction.HOLD_ERASER -> {
                        toolBeforeHold = callbacks.getCurrentTool()
                        callbacks.setCurrentTool(CanvasToolMode.ERASER)
                    }
                    StylusRemapAction.HOLD_LASSO -> {
                        toolBeforeHold = callbacks.getCurrentTool()
                        callbacks.setCurrentTool(CanvasToolMode.LASSO)
                    }
                    else -> Unit
                }
            }
        } else if (!buttonPressed && isPrimaryButtonHeld) {
            isPrimaryButtonHeld = false
            toolBeforeHold?.let { previous ->
                callbacks.setCurrentTool(previous)
                toolBeforeHold = null
            }
        }
    }

    fun processKeyEvent(keyCode: Int, event: KeyEvent, callbacks: Callbacks): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val isStylusDoubleTapKey = keyCode == KeyEvent.KEYCODE_F19 ||
            keyCode == KeyEvent.KEYCODE_F20 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_1 ||
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY

        if (!isStylusDoubleTapKey) return false

        if (keyCode == KeyEvent.KEYCODE_F19 || keyCode == KeyEvent.KEYCODE_F20) {
            executeDiscreteAction(doubleTapAction, callbacks)
            return true
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastButtonPressTimestamp in 40L..doubleTapWindowMs) {
            lastButtonPressTimestamp = 0L
            executeDiscreteAction(doubleTapAction, callbacks)
        } else {
            lastButtonPressTimestamp = now
        }
        return true
    }

    private fun executeDiscreteAction(action: StylusRemapAction, callbacks: Callbacks) {
        when (action) {
            StylusRemapAction.TOGGLE_ERASER -> {
                val current = callbacks.getCurrentTool()
                if (current == CanvasToolMode.ERASER) {
                    callbacks.setCurrentTool(lastValidNonEraserTool)
                } else {
                    lastValidNonEraserTool = current
                    callbacks.setCurrentTool(CanvasToolMode.ERASER)
                }
                callbacks.triggerHapticTick()
            }
            StylusRemapAction.SWITCH_PREVIOUS_TOOL -> {
                val current = callbacks.getCurrentTool()
                callbacks.setCurrentTool(lastValidNonEraserTool)
                lastValidNonEraserTool = current
                callbacks.triggerHapticTick()
            }
            StylusRemapAction.CYCLE_PEN_COLOR -> {
                callbacks.cyclePenColor()
                callbacks.triggerHapticTick()
            }
            StylusRemapAction.UNDO -> {
                callbacks.performUndo()
                callbacks.triggerHapticTick()
            }
            StylusRemapAction.REDO -> {
                callbacks.performRedo()
                callbacks.triggerHapticTick()
            }
            else -> Unit
        }
    }
}

enum class PressureCurvePreset {
    LINEAR,
    SOFT_LIGHT,
    FIRM_CONTROL,
    S_CURVE,
    CUSTOM_BEZIER
}

data class PressureCurveConfig(
    val preset: PressureCurvePreset = PressureCurvePreset.S_CURVE,
    val customGamma: Float = 1.0f,
    val minDeadzone: Float = 0.03f,
    val maxSaturation: Float = 0.92f,
    val minOutputFloor: Float = 0.12f,
    val smoothingFactor: Float = 0.35f,
    val bezierP1: PointF = PointF(0.25f, 0.10f),
    val bezierP2: PointF = PointF(0.75f, 0.90f)
)

class StylusPressureProcessor(
    var config: PressureCurveConfig = PressureCurveConfig()
) {
    private var smoothedPressure = -1f

    fun resetStroke() {
        smoothedPressure = -1f
    }

    fun evaluatePressure(rawPressure: Float, velocityPxPerMs: Float = 0f): Float {
        val effectiveRaw = if (rawPressure <= 0.0001f || (rawPressure >= 0.9999f && smoothedPressure < 0f)) {
            (1f - (velocityPxPerMs / 4.5f).coerceIn(0f, 0.65f)).coerceIn(0.25f, 0.95f)
        } else {
            rawPressure
        }

        val span = (config.maxSaturation - config.minDeadzone).coerceAtLeast(0.05f)
        val normalized = ((effectiveRaw - config.minDeadzone) / span).coerceIn(0f, 1f)

        val curved = when (config.preset) {
            PressureCurvePreset.LINEAR -> normalized
            PressureCurvePreset.SOFT_LIGHT -> normalized.pow(0.60f)
            PressureCurvePreset.FIRM_CONTROL -> normalized.pow(1.65f)
            PressureCurvePreset.S_CURVE -> {
                val s = normalized * normalized * (3f - 2f * normalized)
                s.pow(config.customGamma)
            }
            PressureCurvePreset.CUSTOM_BEZIER -> evaluateCubicBezier(
                t = normalized,
                p1 = config.bezierP1,
                p2 = config.bezierP2
            )
        }

        val targetOutput = config.minOutputFloor + curved * (1f - config.minOutputFloor)

        smoothedPressure = if (smoothedPressure < 0f) {
            targetOutput
        } else {
            val alpha = config.smoothingFactor.coerceIn(0.05f, 1.0f)
            smoothedPressure + alpha * (targetOutput - smoothedPressure)
        }

        return smoothedPressure.coerceIn(0.05f, 1.0f)
    }

    private fun evaluateCubicBezier(t: Float, p1: PointF, p2: PointF): Float {
        val u = 1f - t
        val tt = t * t
        val uu = u * u
        val ttt = tt * t
        return (3f * uu * t * p1.y) + (3f * u * tt * p2.y) + ttt
    }
}

class ScribbleToEraseDetector(
    var enabled: Boolean = true,
    private val minSegmentDistancePx: Float = 24f,
    private val minReversalCount: Int = 3,
    private val minPathToDiagonalRatio: Float = 2.8f,
    private val maxGestureDurationMs: Long = 2200L
) {
    private val sampledPoints = ArrayList<PointF>(64)
    private val bounds = RectF()
    private var totalPathLength = 0f
    private var reversalCount = 0
    private var strokeStartTimeMs = 0L
    private var isCandidateFailed = false

    fun onStrokeStart(x: Float, y: Float, timestampMs: Long = SystemClock.uptimeMillis()) {
        sampledPoints.clear()
        sampledPoints.add(PointF(x, y))
        bounds.set(x, y, x, y)
        totalPathLength = 0f
        reversalCount = 0
        strokeStartTimeMs = timestampMs
        isCandidateFailed = !enabled
    }

    fun onStrokeMove(x: Float, y: Float, timestampMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (isCandidateFailed) return false
        if (timestampMs - strokeStartTimeMs > maxGestureDurationMs) {
            isCandidateFailed = true
            return false
        }

        val last = sampledPoints.last()
        val dx = x - last.x
        val dy = y - last.y
        val dist = hypot(dx, dy)

        if (dist < minSegmentDistancePx) return isScribbleGestureValid()

        totalPathLength += dist
        bounds.union(x, y)

        if (sampledPoints.size >= 2) {
            val prev = sampledPoints[sampledPoints.size - 2]
            val v1x = last.x - prev.x
            val v1y = last.y - prev.y
            val mag1 = hypot(v1x, v1y)
            if (mag1 > 0.001f && dist > 0.001f) {
                val cosTheta = (v1x * dx + v1y * dy) / (mag1 * dist)
                if (cosTheta < -0.707f) {
                    reversalCount++
                }
            }
        }

        sampledPoints.add(PointF(x, y))
        return isScribbleGestureValid()
    }

    fun isScribbleGestureValid(): Boolean {
        if (isCandidateFailed || sampledPoints.size < 5) return false
        if (reversalCount < minReversalCount) return false

        val diagonal = hypot(bounds.width(), bounds.height())
        if (diagonal < minSegmentDistancePx * 1.5f) return false

        return (totalPathLength / diagonal) >= minPathToDiagonalRatio
    }

    fun getScribbleHitBounds(paddingPx: Float = 12f): RectF = RectF(
        bounds.left - paddingPx,
        bounds.top - paddingPx,
        bounds.right + paddingPx,
        bounds.bottom + paddingPx
    )

    fun intersectsStroke(strokePoints: List<PointF>, strokeBounds: RectF, hitTolerancePx: Float = 18f): Boolean {
        val expanded = getScribbleHitBounds(hitTolerancePx)
        if (!RectF.intersects(expanded, strokeBounds)) return false

        val tolSq = hitTolerancePx * hitTolerancePx
        for (pt in strokePoints) {
            if (!expanded.contains(pt.x, pt.y)) continue
            for (i in 0 until sampledPoints.size - 1) {
                val a = sampledPoints[i]
                val b = sampledPoints[i + 1]
                if (pointToSegmentDistanceSq(pt.x, pt.y, a.x, a.y, b.x, b.y) <= tolSq) {
                    return true
                }
            }
        }
        return false
    }

    private fun pointToSegmentDistanceSq(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay
        val abLenSq = abx * abx + aby * aby
        if (abLenSq == 0f) return apx * apx + apy * apy
        val t = ((apx * abx + apy * aby) / abLenSq).coerceIn(0f, 1f)
        val dx = px - (ax + t * abx)
        val dy = py - (ay + t * aby)
        return dx * dx + dy * dy
    }
}

// ============================================================================
// 2. LOW-LATENCY FRONT BUFFER + MOTION EVENT PREDICTOR ENGINE
// ============================================================================

data class FrontBufferStrokeSegment(
    val fromMidX: Float,
    val fromMidY: Float,
    val controlX: Float,
    val controlY: Float,
    val toMidX: Float,
    val toMidY: Float,
    val startWidthPx: Float,
    val endWidthPx: Float,
    val colorArgb: Int,
    val isHighlighter: Boolean = false
)

data class PredictedStrokeTip(
    val path: Path,
    val strokeWidthPx: Float,
    val colorArgb: Int
)

@RequiresApi(Build.VERSION_CODES.Q)
class LowLatencyInkController {
    private var frontBufferedRenderer: CanvasFrontBufferedRenderer<FrontBufferStrokeSegment>? = null
    private var motionPredictor: MotionEventPredictor? = null

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastWidthPx = 4f
    private var hasActiveStroke = false

    var predictedTip by mutableStateOf<PredictedStrokeTip?>(null)
        private set

    private val workerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val workerPath = Path()

    private val rendererCallback = object : CanvasFrontBufferedRenderer.Callback<FrontBufferStrokeSegment> {
        override fun onDrawFrontBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            param: FrontBufferStrokeSegment
        ) {
            drawSegment(canvas, param)
        }

        override fun onDrawMultiBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            params: Collection<FrontBufferStrokeSegment>
        ) {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
    }

    fun attachSurfaceView(surfaceView: SurfaceView) {
        release()
        surfaceView.setZOrderOnTop(true)
        surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        frontBufferedRenderer = CanvasFrontBufferedRenderer(surfaceView, rendererCallback)
        motionPredictor = MotionEventPredictor.newInstance(surfaceView)
    }

    fun release() {
        predictedTip = null
        hasActiveStroke = false
        frontBufferedRenderer?.release(cancelPending = true)
        frontBufferedRenderer = null
        motionPredictor = null
    }

    fun startStroke(
        event: MotionEvent,
        screenX: Float,
        screenY: Float,
        strokeWidthPx: Float,
        colorArgb: Int,
        isHighlighter: Boolean = false
    ) {
        motionPredictor?.record(event)
        lastRawX = screenX
        lastRawY = screenY
        lastMidX = screenX
        lastMidY = screenY
        lastWidthPx = strokeWidthPx
        hasActiveStroke = true
        predictedTip = null

        frontBufferedRenderer?.renderFrontBufferedLayer(
            FrontBufferStrokeSegment(
                fromMidX = screenX,
                fromMidY = screenY,
                controlX = screenX,
                controlY = screenY,
                toMidX = screenX + 0.1f,
                toMidY = screenY + 0.1f,
                startWidthPx = strokeWidthPx,
                endWidthPx = strokeWidthPx,
                colorArgb = colorArgb,
                isHighlighter = isHighlighter
            )
        )
    }

    fun updateStroke(
        event: MotionEvent,
        pressureProcessor: StylusPressureProcessor,
        baseMaxWidthPx: Float,
        colorArgb: Int,
        isHighlighter: Boolean = false,
        onSampleProcessed: (screenX: Float, screenY: Float, curvedPressure: Float, eventTime: Long) -> Unit
    ) {
        if (!hasActiveStroke) return
        motionPredictor?.record(event)

        for (h in 0 until event.historySize) {
            val hx = event.getHistoricalX(h)
            val hy = event.getHistoricalY(h)
            val hTime = event.getHistoricalEventTime(h)
            val hPressure = pressureProcessor.evaluatePressure(event.getHistoricalPressure(h))
            val hWidth = (hPressure * baseMaxWidthPx).coerceAtLeast(1.2f)
            emitConfirmedPoint(hx, hy, hWidth, colorArgb, isHighlighter)
            onSampleProcessed(hx, hy, hPressure, hTime)
        }

        val cx = event.x
        val cy = event.y
        val currentPressure = pressureProcessor.evaluatePressure(event.pressure)
        val currentWidth = (currentPressure * baseMaxWidthPx).coerceAtLeast(1.2f)
        emitConfirmedPoint(cx, cy, currentWidth, colorArgb, isHighlighter)
        onSampleProcessed(cx, cy, currentPressure, event.eventTime)

        val predictedEvent = motionPredictor?.predict()
        if (predictedEvent != null) {
            try {
                buildPredictedTip(
                    predictedEvent = predictedEvent,
                    startMidX = lastMidX,
                    startMidY = lastMidY,
                    controlX = lastRawX,
                    controlY = lastRawY,
                    currentWidthPx = currentWidth,
                    colorArgb = colorArgb
                )
            } finally {
                predictedEvent.recycle()
            }
        } else {
            predictedTip = null
        }
    }

    fun finishStroke(
        screenX: Float,
        screenY: Float,
        endWidthPx: Float,
        colorArgb: Int,
        isHighlighter: Boolean = false
    ) {
        if (!hasActiveStroke) return
        predictedTip = null
        frontBufferedRenderer?.renderFrontBufferedLayer(
            FrontBufferStrokeSegment(
                fromMidX = lastMidX,
                fromMidY = lastMidY,
                controlX = lastRawX,
                controlY = lastRawY,
                toMidX = screenX,
                toMidY = screenY,
                startWidthPx = lastWidthPx,
                endWidthPx = endWidthPx,
                colorArgb = colorArgb,
                isHighlighter = isHighlighter
            )
        )
        hasActiveStroke = false
        frontBufferedRenderer?.commit()
    }

    fun cancelStroke() {
        predictedTip = null
        if (!hasActiveStroke) return
        hasActiveStroke = false
        frontBufferedRenderer?.cancel()
    }

    private fun emitConfirmedPoint(x: Float, y: Float, widthPx: Float, colorArgb: Int, isHighlighter: Boolean) {
        if (hypot(x - lastRawX, y - lastRawY) < 0.8f) return
        val newMidX = (lastRawX + x) * 0.5f
        val newMidY = (lastRawY + y) * 0.5f

        frontBufferedRenderer?.renderFrontBufferedLayer(
            FrontBufferStrokeSegment(
                fromMidX = lastMidX,
                fromMidY = lastMidY,
                controlX = lastRawX,
                controlY = lastRawY,
                toMidX = newMidX,
                toMidY = newMidY,
                startWidthPx = lastWidthPx,
                endWidthPx = widthPx,
                colorArgb = colorArgb,
                isHighlighter = isHighlighter
            )
        )
        lastMidX = newMidX
        lastMidY = newMidY
        lastRawX = x
        lastRawY = y
        lastWidthPx = widthPx
    }

    private fun buildPredictedTip(
        predictedEvent: MotionEvent,
        startMidX: Float,
        startMidY: Float,
        controlX: Float,
        controlY: Float,
        currentWidthPx: Float,
        colorArgb: Int
    ) {
        val px = predictedEvent.x
        val py = predictedEvent.y
        val dist = hypot(px - controlX, py - controlY)
        if (dist < 1.5f || dist > 36f) {
            predictedTip = null
            return
        }
        val tipPath = Path().apply {
            moveTo(startMidX, startMidY)
            var prevX = controlX
            var prevY = controlY
            for (h in 0 until predictedEvent.historySize) {
                val hx = predictedEvent.getHistoricalX(h)
                val hy = predictedEvent.getHistoricalY(h)
                quadTo(prevX, prevY, (prevX + hx) * 0.5f, (prevY + hy) * 0.5f)
                prevX = hx
                prevY = hy
            }
            quadTo(prevX, prevY, px, py)
        }
        predictedTip = PredictedStrokeTip(
            path = tipPath,
            strokeWidthPx = (currentWidthPx * 0.82f).coerceAtLeast(1.2f),
            colorArgb = colorArgb
        )
    }

    private fun drawSegment(canvas: Canvas, seg: FrontBufferStrokeSegment) {
        workerPaint.color = if (seg.isHighlighter) {
            (seg.colorArgb and 0x00FFFFFF) or 0x66000000
        } else {
            seg.colorArgb
        }
        if (abs(seg.endWidthPx - seg.startWidthPx) < 0.75f) {
            workerPath.reset()
            workerPath.moveTo(seg.fromMidX, seg.fromMidY)
            workerPath.quadTo(seg.controlX, seg.controlY, seg.toMidX, seg.toMidY)
            workerPaint.strokeWidth = (seg.startWidthPx + seg.endWidthPx) * 0.5f
            canvas.drawPath(workerPath, workerPaint)
        } else {
            val steps = 4
            var prevX = seg.fromMidX
            var prevY = seg.fromMidY
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val u = 1f - t
                val bx = u * u * seg.fromMidX + 2f * u * t * seg.controlX + t * t * seg.toMidX
                val by = u * u * seg.fromMidY + 2f * u * t * seg.controlY + t * t * seg.toMidY
                workerPaint.strokeWidth = seg.startWidthPx + (seg.endWidthPx - seg.startWidthPx) * t
                canvas.drawLine(prevX, prevY, bx, by, workerPaint)
                prevX = bx
                prevY = by
            }
        }
    }
}
