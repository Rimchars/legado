package io.legado.app.lib.prefs

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

class PreferenceGroupBackgroundDrawable(
    @ColorInt private val normalColor: Int,
    @ColorInt private val pressedColor: Int,
    @ColorInt private val dividerColor: Int,
    private val radius: Float,
    private val hasPrev: Boolean,
    private val hasNext: Boolean,
    private val dividerInset: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var pressed = false

    fun hasSameConfig(
        @ColorInt normalColor: Int,
        @ColorInt pressedColor: Int,
        @ColorInt dividerColor: Int,
        radius: Float,
        hasPrev: Boolean,
        hasNext: Boolean,
        dividerInset: Float
    ): Boolean {
        return this.normalColor == normalColor &&
                this.pressedColor == pressedColor &&
                this.dividerColor == dividerColor &&
                this.radius == radius &&
                this.hasPrev == hasPrev &&
                this.hasNext == hasNext &&
                this.dividerInset == dividerInset
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        path.reset()
        path.addRoundRect(rect, cornerRadii(), Path.Direction.CW)
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) pressedColor else normalColor
        canvas.drawPath(path, paint)
        if (hasNext) {
            paint.color = dividerColor
            val y = bounds.bottom - 1f
            canvas.drawLine(bounds.left + dividerInset, y, bounds.right - dividerInset, y, paint)
        }
    }

    private fun cornerRadii(): FloatArray {
        val top = if (hasPrev) 0f else radius
        val bottom = if (hasNext) 0f else radius
        return floatArrayOf(
            top, top,
            top, top,
            bottom, bottom,
            bottom, bottom
        )
    }

    override fun isStateful(): Boolean = normalColor != pressedColor

    override fun onStateChange(state: IntArray): Boolean {
        if (!isStateful) return false
        val newPressed = state.any {
            it == android.R.attr.state_pressed || it == android.R.attr.state_focused
        }
        if (pressed != newPressed) {
            pressed = newPressed
            invalidateSelf()
            return true
        }
        return false
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
