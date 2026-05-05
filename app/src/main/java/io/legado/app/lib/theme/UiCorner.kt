package io.legado.app.lib.theme

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx

object UiCorner {

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

    fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    fun roundedStroke(color: Int, radius: Float, strokeWidth: Int, strokeColor: Int): GradientDrawable {
        return rounded(color, radius).apply {
            setStroke(strokeWidth, strokeColor)
        }
    }

    fun actionSelector(defaultColor: Int, pressedColor: Int, radius: Float): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(pressedColor, radius))
            addState(intArrayOf(android.R.attr.state_selected), rounded(pressedColor, radius))
            addState(intArrayOf(), rounded(defaultColor, radius))
        }
    }
}
