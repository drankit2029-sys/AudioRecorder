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
