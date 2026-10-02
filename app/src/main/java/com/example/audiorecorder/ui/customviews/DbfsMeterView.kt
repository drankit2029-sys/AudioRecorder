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
