package io.legado.app.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.max

class CenterCropBitmapDrawable(
    resources: Resources,
    private val bitmap: Bitmap
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val drawRect = RectF()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = max(
            bounds.width().toFloat() / bitmap.width.toFloat(),
            bounds.height().toFloat() / bitmap.height.toFloat()
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = bounds.left + (bounds.width() - width) / 2f
        val top = bounds.top + (bounds.height() - height) / 2f
        drawRect.set(left, top, left + width, top + height)
        canvas.drawBitmap(bitmap, null, drawRect, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
