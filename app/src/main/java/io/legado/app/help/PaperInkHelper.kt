package io.legado.app.help

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.EmbossMaskFilter
import android.graphics.Paint
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig

object PaperInkHelper {

    val strength: Int
        get() = ReadBookConfig.paperInkStrength

    private var embossStrength = -1
    private var embossMaskFilter: EmbossMaskFilter? = null

    fun drawBackground(canvas: Canvas, width: Int, height: Int, paint: Paint) {
        val strength = strength
        if (strength <= 0 || width <= 0 || height <= 0) return
        val ratio = strength / 100f
        paint.shader = null
        paint.color = if (AppConfig.isNightTheme) {
            Color.argb((3 + 8 * ratio).toInt(), 255, 255, 255)
        } else {
            Color.argb((3 + 8 * ratio).toInt(), 0, 0, 0)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        x: Float,
        y: Float,
        paint: Paint,
        enableBlend: Boolean = true
    ) {
        if (strength <= 0 || !enableBlend) {
            canvas.drawText(text, start, end, x, y, paint)
            return
        }
        drawTextBlock(canvas, paint) {
            canvas.drawText(text, start, end, x, y, paint)
        }
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        enableBlend: Boolean = true
    ) {
        drawText(canvas, text, 0, text.length, x, y, paint, enableBlend)
    }

    fun drawTextBlock(canvas: Canvas, paint: Paint, draw: () -> Unit) {
        val strength = strength
        if (strength <= 0) {
            draw()
            return
        }
        val ratio = strength / 100f
        val oldColor = paint.color
        val oldAlpha = paint.alpha
        val oldShader = paint.shader
        val oldMaskFilter = paint.maskFilter
        val oldStyle = paint.style

        val offset = 0.45f + 1.15f * ratio
        val blur = 0.25f + 0.85f * ratio
        val inset = strength <= 50

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.maskFilter = null
        paint.color = if (inset) reliefShadowColor(oldColor, ratio) else reliefHighlightColor(ratio)
        paint.alpha = (oldAlpha * (0.26f + 0.26f * ratio)).toInt().coerceIn(0, oldAlpha)
        canvas.save()
        canvas.translate(if (inset) -offset else offset, if (inset) -offset else offset)
        paint.setShadowLayer(blur, 0f, 0f, paint.color)
        draw()
        canvas.restore()

        paint.clearShadowLayer()
        paint.color = if (inset) reliefHighlightColor(ratio) else reliefShadowColor(oldColor, ratio)
        paint.alpha = (oldAlpha * (0.32f + 0.30f * ratio)).toInt().coerceIn(0, oldAlpha)
        canvas.save()
        canvas.translate(if (inset) offset else -offset, if (inset) offset else -offset)
        paint.setShadowLayer(blur, 0f, 0f, paint.color)
        draw()
        canvas.restore()

        paint.clearShadowLayer()
        paint.maskFilter = embossFilter(strength)
        paint.color = blendInkColor(oldColor, ratio)
        paint.alpha = (oldAlpha * (0.88f - 0.10f * ratio)).toInt().coerceIn(0, oldAlpha)
        draw()

        paint.style = oldStyle
        paint.maskFilter = oldMaskFilter
        paint.shader = oldShader
        paint.alpha = oldAlpha
        paint.color = oldColor
        paint.clearShadowLayer()
    }

    private fun blendInkColor(color: Int, strength: Float): Int {
        val bg = ReadBookConfig.bgMeanColor
        val ratio = (if (AppConfig.isNightTheme) 0.16f else 0.24f) * strength
        val inv = 1f - ratio
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * inv + Color.red(bg) * ratio).toInt().coerceIn(0, 255),
            (Color.green(color) * inv + Color.green(bg) * ratio).toInt().coerceIn(0, 255),
            (Color.blue(color) * inv + Color.blue(bg) * ratio).toInt().coerceIn(0, 255)
        )
    }

    private fun reliefHighlightColor(strength: Float): Int {
        val alpha = ((if (AppConfig.isNightTheme) 22 else 45) + 70 * strength).toInt()
        return Color.argb(alpha, 255, 255, 255)
    }

    private fun reliefShadowColor(color: Int, strength: Float): Int {
        val alpha = ((if (AppConfig.isNightTheme) 35 else 28) + 76 * strength).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun embossFilter(strength: Int): EmbossMaskFilter {
        if (embossStrength != strength || embossMaskFilter == null) {
            val ratio = strength / 100f
            embossStrength = strength
            embossMaskFilter = EmbossMaskFilter(
                floatArrayOf(-1f, -1f, 0.55f),
                0.45f + 0.25f * ratio,
                8f + 18f * ratio,
                0.8f + 1.4f * ratio
            )
        }
        return embossMaskFilter!!
    }

}
