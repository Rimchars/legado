package io.legado.app.lib.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.ColorUtils
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx

object TopBarSearchStyle {

    fun surfaceColor(): Int {
        return if (AppConfig.isNightTheme) {
            ColorUtils.setAlphaComponent(Color.rgb(52, 52, 56), (0.42f * 255).toInt())
        } else {
            ColorUtils.setAlphaComponent(Color.rgb(120, 120, 128), (0.18f * 255).toInt())
        }
    }

    fun pressedSurfaceColor(): Int {
        return if (AppConfig.isNightTheme) {
            ColorUtils.setAlphaComponent(Color.rgb(82, 82, 86), (0.50f * 255).toInt())
        } else {
            ColorUtils.setAlphaComponent(Color.rgb(120, 120, 128), (0.28f * 255).toInt())
        }
    }

    fun strokeColor(context: Context): Int {
        val alpha = if (AppConfig.isNightTheme) 0.10f else 0.08f
        return ColorUtils.setAlphaComponent(context.primaryTextColor, (alpha * 255).toInt())
    }

    fun radius(): Float {
        return UiCorner.searchRadius(18f)
    }

    fun searchViewBackground(context: Context): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius()
            setColor(surfaceColor())
            setStroke(1.dpToPx(), strokeColor(context))
        }
    }

    fun actionBackground(context: Context): Drawable {
        return UiCorner.actionSelector(surfaceColor(), pressedSurfaceColor(), radius())
    }

    fun apply(searchView: SearchView) {
        searchView.background = searchViewBackground(searchView.context)
    }
}
