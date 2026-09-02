package com.stereoanalogrecorder.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import com.stereoanalogrecorder.app.R

/**
 * DAW-style segmented level meter. Vertical stack of lit segments; green / yellow / red zones
 * based on the dB scale, with a peak-hold marker that decays after a hold time.
 */
class DigitalMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MeterViewBase(context, attrs, defStyleAttr) {

    private val segmentCount = 22

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val safePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segRect = RectF()

    private var peakHoldLevel = 0f
    private var peakHoldAtMs = 0L

    init {
        reloadThemeColors()
    }

    fun reloadThemeColors() {
        trackPaint.color = resolveThemeColor(R.attr.meterTrackColor)
        safePaint.color = resolveThemeColor(R.attr.meterSafeColor)
        warnPaint.color = resolveThemeColor(R.attr.meterWarnColor)
        peakPaint.color = resolveThemeColor(R.attr.meterPeakColor)
        invalidate()
    }

    override fun setDbImmediate(db: Float) {
        val level = dbToLevel(db)
        if (level > peakHoldLevel) {
            peakHoldLevel = level
            peakHoldAtMs = SystemClock.uptimeMillis()
        }
        super.setDbImmediate(db)
    }

    override fun setDb(db: Float) {
        val level = dbToLevel(db)
        if (level > peakHoldLevel) {
            peakHoldLevel = level
            peakHoldAtMs = SystemClock.uptimeMillis()
        }
        super.setDb(db)
    }

    /** Reset the peak hold (used when a new recording starts). */
    fun resetPeakHold() {
        peakHoldLevel = 0f
        peakHoldAtMs = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Decay peak hold: hold 1.0s then fall at 12 dB/s (~0.2 level/s over our scale).
        val now = SystemClock.uptimeMillis()
        if (peakHoldLevel > 0f && now - peakHoldAtMs > 1000L) {
            val dt = (now - peakHoldAtMs - 1000L).coerceAtLeast(0L) / 1000f
            peakHoldLevel = (peakHoldLevel - dt * 0.25f).coerceAtLeast(0f)
            peakHoldAtMs = now - 1000L
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 2f * (w / 24f).coerceIn(1f, 3f)
        val segH = (h - gap * (segmentCount - 1)) / segmentCount
        val segW = w * 0.7f
        val xc = w / 2f

        // 0..1 level → how many segments lit from the bottom
        val lit = (animatedLevel * segmentCount).toInt().coerceIn(0, segmentCount)

        for (i in 0 until segmentCount) {
            val fromBottom = i
            val yTop = h - (fromBottom + 1) * segH - fromBottom * gap
            segRect.set(xc - segW / 2f, yTop, xc + segW / 2f, yTop + segH)
            val litThis = fromBottom < lit
            // Segment color depends on vertical zone (top = red).
            val ratio = i.toFloat() / (segmentCount - 1)
            val paint = if (ratio >= 0.86f) peakPaint else if (ratio >= 0.7f) warnPaint else safePaint
            if (litThis) {
                canvas.drawRoundRect(segRect, segH / 3f, segH / 3f, paint)
            } else {
                canvas.drawRoundRect(segRect, segH / 3f, segH / 3f, trackPaint)
            }
        }

        // Peak hold thin marker
        if (peakHoldLevel > 0f) {
            val yPeak = h - peakHoldLevel * h
            val markerW = segW + 6f
            peakPaint.style = Paint.Style.FILL
            canvas.drawRect(xc - markerW / 2f, yPeak - 2f, xc + markerW / 2f, yPeak + 2f, peakPaint)
        }
    }
}
