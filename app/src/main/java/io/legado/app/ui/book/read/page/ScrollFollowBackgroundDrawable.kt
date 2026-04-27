package io.legado.app.ui.book.read.page

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

class ScrollFollowBackgroundDrawable(
    bitmap: Bitmap,
    private val offsetProvider: () -> Int
) : Drawable() {

    private val matrix = Matrix()
    private val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        this.shader = this@ScrollFollowBackgroundDrawable.shader
    }
    private val bitmapWidth = bitmap.width.coerceAtLeast(1)
    private val bitmapHeight = bitmap.height.coerceAtLeast(1)
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val scale = bounds.width().toFloat() / bitmapWidth.toFloat()
        val tileHeight = bitmapHeight * scale
        val rawOffset = offsetProvider().toFloat()
        val translateY = if (tileHeight > 0f) {
            val offset = rawOffset % tileHeight
            if (offset > 0f) offset - tileHeight else offset
        } else {
            0f
        }
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat() + translateY)
        shader.setLocalMatrix(matrix)
        paint.alpha = drawableAlpha
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int {
        return drawableAlpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }
}
