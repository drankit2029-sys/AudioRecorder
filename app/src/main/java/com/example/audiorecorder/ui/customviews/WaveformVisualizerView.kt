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
import kotlin.math.pow

interface WaveformScrubListener {
    fun onScrubStart()
    fun onScrubbing(peakIndex: Int)
    fun onScrubStop(finalPeakIndex: Int)
}

class WaveformVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    private val playedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeCap = Paint.Cap.ROUND
    }

    private val unplayedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#455A64")
        strokeCap = Paint.Cap.ROUND
    }

    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3D71")
        strokeWidth = 2.5f * density
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2429")
        strokeWidth = 1f * density
    }

    private val peaks = ArrayList<Float>()
    private var playheadIndex = 0
    private var zoomFactor = 1.0f // 0.5f to 3.0f

    var scrubListener: WaveformScrubListener? = null
    private var isScrubbing = false
    private var lastTouchX = 0f

    fun setPeaks(initialPeaks: List<Float>) {
        peaks.clear()
        peaks.addAll(initialPeaks)
        playheadIndex = peaks.size
        postInvalidate()
    }

    fun addLivePeak(peak: Float) {
        peaks.add(min(1.0f, max(0.0f, peak)))
        playheadIndex = peaks.size
        postInvalidateOnAnimation()
    }

    fun setPlayheadIndex(index: Int) {
        playheadIndex = max(0, min(peaks.size, index))
        postInvalidateOnAnimation()
    }

    fun clear() {
        peaks.clear()
        playheadIndex = 0
        postInvalidate()
    }

    fun zoomIn() {
        zoomFactor = min(3.0f, zoomFactor * 1.25f)
        postInvalidate()
    }

    fun zoomOut() {
        zoomFactor = max(0.5f, zoomFactor / 1.25f)
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

                    val stepPx = (4.0f * density) * zoomFactor
                    val indexDelta = (deltaX / stepPx).toInt()
                    if (indexDelta != 0) {
                        playheadIndex = max(0, min(peaks.size, playheadIndex - indexDelta))
                        scrubListener?.onScrubbing(playheadIndex)
                        postInvalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isScrubbing) {
                    isScrubbing = false
                    scrubListener?.onScrubStop(playheadIndex)
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
        if (w <= 0f || h <= 0f) return

        val centerY = h / 2f
        val centerX = w / 2f

        // Center timeline axis
        canvas.drawLine(0f, centerY, w, centerY, baselinePaint)

        val barWidth = 2.5f * density * zoomFactor
        val barGap = 1.5f * density * zoomFactor
        val step = barWidth + barGap

        playedBarPaint.strokeWidth = barWidth
        unplayedBarPaint.strokeWidth = barWidth

        val maxHalfAmp = (h * 0.44f)

        // Draw waveform bars scrolling past the fixed center playhead
        for (i in peaks.indices) {
            val x = centerX + ((i - playheadIndex) * step)
            if (x < -10f || x > w + 10f) continue

            // Perceptual dynamic expansion: pow(peak, 0.6)
            val linearPeak = peaks[i]
            val scaledPeak = linearPeak.toDouble().pow(0.6).toFloat()
            val amp = max(2f * density, scaledPeak * maxHalfAmp)

            val paint = if (i <= playheadIndex) playedBarPaint else unplayedBarPaint
            canvas.drawLine(x, centerY - amp, x, centerY + amp, paint)
        }

        // Stationary Center Playhead
        canvas.drawLine(centerX, 0f, centerX, h, playheadPaint)
    }
}
