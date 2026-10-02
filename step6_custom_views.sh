#!/bin/sh
set -e

BASE="app/src/main/java/com/example/audiorecorder"

echo "==> 1. Writing DbfsMeterView.kt..."
cat << 'METER' > "$BASE/ui/customviews/DbfsMeterView.kt"
package com.example.audiorecorder.ui.customviews

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class DbfsMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentDbfs = -60.0f
    private var peakHoldDbfs = -60.0f
    private var lastPeakTime = 0L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E2124")
    }

    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
    }

    private val yellowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600")
    }

    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
    }

    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
    }

    private val segmentRect = RectF()

    fun setLevels(rmsDbfs: Float, peakDbfs: Float) {
        currentDbfs = max(-60.0f, min(0.0f, rmsDbfs))

        val now = System.currentTimeMillis()
        if (peakDbfs >= peakHoldDbfs) {
            peakHoldDbfs = peakDbfs
            lastPeakTime = now
        } else if (now - lastPeakTime > 800) { // Peak decay after 800ms
            peakHoldDbfs = max(currentDbfs, peakHoldDbfs - 1.5f)
        }

        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Background gutter
        segmentRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(segmentRect, 8f, 8f, bgPaint)

        // 2. Active meter fill (bottom to top)
        val normalized = (currentDbfs + 60.0f) / 60.0f // 0.0 to 1.0
        val fillHeight = h * normalized
        val topY = h - fillHeight

        // Segment thresholds
        val yellowThresholdY = h * (1.0f - (48.0f / 60.0f)) // -12 dBFS mark
        val redThresholdY = h * (1.0f - (57.0f / 60.0f))    // -3 dBFS mark

        val fillPaint = when {
            topY <= redThresholdY -> redPaint
            topY <= yellowThresholdY -> yellowPaint
            else -> greenPaint
        }

        segmentRect.set(0f, topY, w, h)
        canvas.drawRoundRect(segmentRect, 8f, 8f, fillPaint)

        // 3. Peak hold tick
        val peakNormalized = (peakHoldDbfs + 60.0f) / 60.0f
        val peakY = h * (1.0f - peakNormalized)
        canvas.drawLine(0f, peakY, w, peakY, peakPaint)
    }
}
METER

echo "==> 2. Writing WaveformVisualizerView.kt..."
cat << 'WAVE' > "$BASE/ui/customviews/WaveformVisualizerView.kt"
package com.example.audiorecorder.ui.customviews

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

interface WaveformScrubListener {
    fun onScrubStart()
    fun onScrubbing(sampleOffset: Long)
    fun onScrubStop(finalSampleOffset: Long)
}

class WaveformVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#448AFF")
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        strokeWidth = 4f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2E33")
        strokeWidth = 1f
    }

    // Historical peak amplitudes (min/max pairs normalized -1.0 to 1.0)
    private val samplePeaks = mutableListOf<Float>()
    private var zoomScale = 1.0f // 0.25f (zoomed out) to 4.0f (zoomed in)
    private var playheadSampleIndex = 0L
    private var totalSamples = 0L

    var scrubListener: WaveformScrubListener? = null
    private var isScrubbing = false
    private var lastTouchX = 0f

    fun addLiveSamplePeak(peak: Float) {
        samplePeaks.add(min(1.0f, max(-1.0f, peak)))
        totalSamples++
        playheadSampleIndex = totalSamples
        postInvalidateOnAnimation()
    }

    fun setPlaybackPosition(sampleIndex: Long, total: Long) {
        playheadSampleIndex = sampleIndex
        totalSamples = total
        postInvalidateOnAnimation()
    }

    fun zoomIn() {
        zoomScale = min(4.0f, zoomScale * 1.25f)
        postInvalidate()
    }

    fun zoomOut() {
        zoomScale = max(0.25f, zoomScale / 1.25f)
        postInvalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isScrubbing = true
                lastTouchX = event.x
                scrubListener?.onScrubStart()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScrubbing) {
                    val deltaX = event.x - lastTouchX
                    lastTouchX = event.x

                    // Translate pixels into sample units based on zoom
                    val samplesPerPixel = (100f / zoomScale).toLong()
                    val sampleDelta = (deltaX * samplesPerPixel).toLong()

                    playheadSampleIndex = max(0L, min(totalSamples, playheadSampleIndex - sampleDelta))
                    scrubListener?.onScrubbing(playheadSampleIndex)
                    postInvalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isScrubbing) {
                    isScrubbing = false
                    scrubListener?.onScrubStop(playheadSampleIndex)
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        val centerX = w / 2f

        // 1. Center guideline
        canvas.drawLine(0f, centerY, w, centerY, gridPaint)

        // 2. Draw historical audio peaks scrolling under stationary center playhead
        val stepPixels = 4f * zoomScale
        val playheadFloat = playheadSampleIndex.toFloat()

        if (samplePeaks.isNotEmpty()) {
            for (i in samplePeaks.indices) {
                // Determine horizontal offset relative to center playhead
                val x = centerX + ((i - playheadFloat) * (stepPixels / 100f))
                if (x < -10f || x > w + 10f) continue // Frustum culling

                val amplitude = abs(samplePeaks[i]) * (h * 0.45f)
                canvas.drawLine(x, centerY - amplitude, x, centerY + amplitude, wavePaint)
            }
        }

        // 3. Fixed center playhead
        canvas.drawLine(centerX, 0f, centerX, h, playheadPaint)
    }
}
WAVE

echo "==> 3. Writing TeleprompterView.kt..."
cat << 'PROMPTER' > "$BASE/ui/customviews/TeleprompterView.kt"
package com.example.audiorecorder.ui.customviews

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

class TeleprompterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFD600") // Translucent amber guideline
        strokeWidth = 2f
    }

    private var scriptContent: String = "Tap 'Script' above to write or import your script."
    private var staticLayout: StaticLayout? = null

    private var scrollOffsetY = 0f
    private var scrollSpeedPixelsPerFrame = 1.5f
    private var isScrolling = false
    private var isMirrored = false

    fun setScript(text: String) {
        scriptContent = text
        rebuildLayout()
        postInvalidate()
    }

    fun setFontSize(sizeSp: Float) {
        textPaint.textSize = sizeSp * resources.displayMetrics.scaledDensity
        rebuildLayout()
        postInvalidate()
    }

    fun setScrollSpeed(speed: Float) {
        scrollSpeedPixelsPerFrame = speed
    }

    fun toggleMirror() {
        isMirrored = !isMirrored
        scaleX = if (isMirrored) -1f else 1f
        postInvalidate()
    }

    fun startAutoScroll() {
        isScrolling = true
        postInvalidateOnAnimation()
    }

    fun pauseAutoScroll() {
        isScrolling = false
    }

    fun resetScroll() {
        scrollOffsetY = 0f
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
    }

    private fun rebuildLayout() {
        val usableWidth = width - paddingLeft - paddingRight
        if (usableWidth <= 0) return

        staticLayout = StaticLayout.Builder.obtain(
            scriptContent,
            0,
            scriptContent.length,
            textPaint,
            usableWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(12f, 1.2f)
            .setIncludePad(true)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f

        // Central reading guideline
        canvas.drawLine(0f, centerY, w, centerY, guidePaint)

        val layout = staticLayout ?: return

        canvas.save()
        // Center text start position vertically
        canvas.translate(paddingLeft.toFloat(), centerY - scrollOffsetY)
        layout.draw(canvas)
        canvas.restore()

        if (isScrolling) {
            scrollOffsetY += scrollSpeedPixelsPerFrame
            if (scrollOffsetY > layout.height + centerY) {
                isScrolling = false // Finished end of text
            }
            postInvalidateOnAnimation()
        }
    }
}
PROMPTER

echo "==> Step 6 custom views generated successfully!"
