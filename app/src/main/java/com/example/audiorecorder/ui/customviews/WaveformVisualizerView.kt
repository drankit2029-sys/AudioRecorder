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
