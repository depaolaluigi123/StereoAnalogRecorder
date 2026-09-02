package com.stereoanalogrecorder.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.log10

/**
 * Base for the two meter styles. Holds the animated level in [0,1] (0=−60 dBFS,
 * 1=0 dBFS) and resolves theme colors. Subclasses draw the faceplate.
 */
abstract class MeterViewBase @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    protected var animatedLevel = 0f
    private var animator: ValueAnimator? = null

    /** Maps the live peak dB (−Inf..0) to [0,1] over a −60..0 dB scale. */
    open fun setDb(db: Float) {
        val target = dbToLevel(db)
        if (target == animatedLevel) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animatedLevel, target).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animatedLevel = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    open fun setDbImmediate(db: Float) {
        animator?.cancel()
        animatedLevel = dbToLevel(db)
        invalidate()
    }

    protected fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    companion object {
        const val FLOOR_DB = -60f
        const val HEADROOM_DB = 6f // warn zone above −6, peak above 0 dBFS is clipping

        fun dbToLevel(db: Float): Float {
            if (db.isNaN()) return 0f
            if (db <= FLOOR_DB) return 0f
            if (db >= 0f) return 1f
            return ((db - FLOOR_DB) / (0f - FLOOR_DB)).coerceIn(0f, 1f)
        }

        fun linearToDb(linear: Float): Float {
            if (linear <= 0f) return Float.NEGATIVE_INFINITY
            return 20f * log10(linear)
        }
    }
}
