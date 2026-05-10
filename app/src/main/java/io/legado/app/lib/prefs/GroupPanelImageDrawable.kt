package io.legado.app.lib.prefs

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.UiCorner

class GroupPanelImageDrawable(
    private val bitmap: Bitmap,
    private val mode: String,
    private val groupHeight: Int,
    private val offsetY: Int,
    private val topRadius: Float,
    private val bottomRadius: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = (UiCorner.layoutAlpha() * 255).toInt().coerceIn(0, 255)
    }
    private val clampShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private val rect = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || groupHeight <= 0) return
        rect.set(bounds)
        path.reset()
        path.addRoundRect(
            rect,
            floatArrayOf(
                topRadius, topRadius,
                topRadius, topRadius,
                bottomRadius, bottomRadius,
                bottomRadius, bottomRadius
            ),
            Path.Direction.CW
        )
        val groupWidth = bounds.width().toFloat().coerceAtLeast(1f)
        val groupHeightF = groupHeight.toFloat().coerceAtLeast(1f)
        val scale = if (mode == ThemeConfig.PANEL_BG_FIT) {
            minOf(groupWidth / bitmap.width, groupHeightF / bitmap.height)
        } else {
            maxOf(groupWidth / bitmap.width, groupHeightF / bitmap.height)
        }
        val dx = bounds.left + (groupWidth - bitmap.width * scale) / 2f
        val dy = bounds.top - offsetY + (groupHeightF - bitmap.height * scale) / 2f
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        paint.shader = clampShader
        paint.shader?.setLocalMatrix(matrix)
        val saveCount = canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(rect, paint)
        canvas.restoreToCount(saveCount)
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
