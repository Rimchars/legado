package io.legado.app.lib.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefString
import java.io.File

object UiCorner {

    private var panelBitmapKey: String? = null
    private var panelBitmap: Bitmap? = null

    fun scale(): Float {
        return AppConfig.uiCornerScale.coerceIn(0f, 3f)
    }

    fun panelRadius(context: Context): Float {
        return context.resources.getDimension(R.dimen.ui_panel_radius) * scale()
    }

    fun actionRadius(context: Context): Float {
        return context.resources.getDimension(R.dimen.ui_action_radius) * scale()
    }

    fun scaledDp(value: Float): Float {
        return value.dpToPx() * scale()
    }

    fun searchRadius(value: Float): Float {
        return if (AppConfig.uiCornerSearchFollow) {
            scaledDp(value)
        } else {
            value.dpToPx()
        }
    }

    fun replyRadius(value: Float): Float {
        return if (AppConfig.uiCornerReplyFollow) {
            scaledDp(value)
        } else {
            value.dpToPx()
        }
    }

    fun effectMode(): String = "solid"

    fun layoutAlpha(): Float {
        return AppConfig.uiLayoutAlpha.coerceIn(0, 100) / 100f
    }

    fun surfaceColor(color: Int, pressed: Boolean = false): Int {
        val alpha = (layoutAlpha() + if (pressed) 0.08f else 0f).coerceIn(0f, 1f)
        return ColorUtils.setAlphaComponent(color, (alpha * 255).toInt())
    }

    fun effectStrokeColor(color: Int): Int {
        val base = if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE
        val alpha = 0.10f
        return ColorUtils.setAlphaComponent(base, (alpha.coerceIn(0f, 0.5f) * 255).toInt())
    }

    private fun roundedColor(color: Int, radius: Float, pressed: Boolean, transparent: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(if (transparent) surfaceColor(color, pressed) else color)
        }
    }

    fun rounded(color: Int, radius: Float): GradientDrawable {
        return roundedColor(color, radius, false, true)
    }

    fun opaqueRounded(color: Int, radius: Float): GradientDrawable {
        return roundedColor(color, radius, false, false)
    }

    fun panelRounded(context: Context, color: Int, radius: Float): Drawable {
        val image = panelImageDrawable(context, radius)
        if (image == null) {
            return rounded(color, radius)
        }
        return LayerDrawable(
            arrayOf(
                rounded(color, radius),
                image
            )
        )
    }

    fun panelRoundedStroke(
        context: Context,
        color: Int,
        radius: Float,
        strokeWidth: Int,
        strokeColor: Int
    ): Drawable {
        val base = roundedStroke(color, radius, strokeWidth, strokeColor)
        val image = panelImageDrawable(context, radius) ?: return base
        return LayerDrawable(arrayOf(base, image))
    }

    fun panelImageDrawable(context: Context, radius: Float): Drawable? {
        val path = context.getPrefString(
            if (AppConfig.isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage
        )
        val bitmap = loadPanelBitmap(path) ?: return null
        val mode = context.getPrefString(
            if (AppConfig.isNightTheme) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType
        ) ?: ThemeConfig.PANEL_BG_CROP
        return RoundedBitmapDrawable(bitmap, radius, mode == ThemeConfig.PANEL_BG_FIT)
    }

    fun roundedStroke(color: Int, radius: Float, strokeWidth: Int, strokeColor: Int): GradientDrawable {
        return rounded(color, radius).apply {
            setStroke(strokeWidth, strokeColor)
        }
    }

    fun opaqueRoundedStroke(color: Int, radius: Float, strokeWidth: Int, strokeColor: Int): GradientDrawable {
        return opaqueRounded(color, radius).apply {
            setStroke(strokeWidth, strokeColor)
        }
    }

    fun actionSelector(defaultColor: Int, pressedColor: Int, radius: Float): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedColor(pressedColor, radius, true, false))
            addState(intArrayOf(android.R.attr.state_selected), roundedColor(pressedColor, radius, true, false))
            addState(intArrayOf(), opaqueRounded(defaultColor, radius))
        }
    }

    fun actionStrokeSelector(
        defaultColor: Int,
        pressedColor: Int,
        radius: Float,
        strokeWidth: Int,
        strokeColor: Int
    ): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedColor(pressedColor, radius, true, false).apply {
                    setStroke(strokeWidth, strokeColor)
                }
            )
            addState(
                intArrayOf(android.R.attr.state_selected),
                roundedColor(pressedColor, radius, true, false).apply {
                    setStroke(strokeWidth, strokeColor)
                }
            )
            addState(intArrayOf(), opaqueRoundedStroke(defaultColor, radius, strokeWidth, strokeColor))
        }
    }

    fun loadPanelBitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank() || path.startsWith("http", ignoreCase = true)) {
            panelBitmapKey = null
            panelBitmap = null
            return null
        }
        val file = File(path)
        if (!file.exists()) {
            panelBitmapKey = null
            panelBitmap = null
            return null
        }
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        panelBitmap?.takeIf { panelBitmapKey == key && !it.isRecycled }?.let { return it }
        return kotlin.runCatching {
            BitmapUtils.decodeBitmap(file.absolutePath, 1080, 1080)
        }.getOrNull()?.also {
            panelBitmapKey = key
            panelBitmap = it
        }
    }

    class RoundedBitmapDrawable(
        private val bitmap: Bitmap,
        private val radius: Float,
        private val fitInside: Boolean
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            alpha = (layoutAlpha() * 255).toInt().coerceIn(0, 255)
        }
        private val rect = RectF()
        private val matrix = Matrix()

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return
            rect.set(bounds)
            val scale = if (fitInside) {
                minOf(bounds.width() / bitmap.width.toFloat(), bounds.height() / bitmap.height.toFloat())
            } else {
                maxOf(bounds.width() / bitmap.width.toFloat(), bounds.height() / bitmap.height.toFloat())
            }
            val dx = bounds.left + (bounds.width() - bitmap.width * scale) / 2f
            val dy = bounds.top + (bounds.height() - bitmap.height * scale) / 2f
            matrix.reset()
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            paint.shader?.setLocalMatrix(matrix)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
