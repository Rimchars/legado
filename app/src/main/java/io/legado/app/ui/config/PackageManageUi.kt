package io.legado.app.ui.config

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiInputStyle
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface

object PackageManageUi {

    fun nameInput(context: Context, text: String, hint: String): EditText {
        return EditText(context).apply {
            tag = "name"
            setText(text)
            this.hint = hint
            applyUiInputStyle(context)
            background = UiCorner.opaqueRounded(
                ContextCompat.getColor(context, R.color.background_card),
                UiCorner.actionRadius(context)
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dp(context))
        }
    }

    fun optionRow(context: Context, title: String, value: String, onClick: () -> Unit): View {
        return optionRow(context, title, value, null, onClick)
    }

    fun optionRow(context: Context, title: String, value: String, colorPreview: Int?, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(context), 0, 14.dp(context), 0)
            background = UiCorner.opaqueRounded(
                ContextCompat.getColor(context, R.color.background_card),
                UiCorner.actionRadius(context)
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dp(context)).apply {
                topMargin = 8.dp(context)
            }
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(context.primaryTextColor)
                typeface = context.uiTypeface()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (colorPreview != null) {
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colorPreview)
                        setStroke(1.dp(context), ContextCompat.getColor(context, R.color.background_menu))
                    }
                    layoutParams = LinearLayout.LayoutParams(18.dp(context), 18.dp(context)).apply {
                        marginEnd = 8.dp(context)
                    }
                })
            }
            addView(TextView(context).apply {
                text = value
                textSize = 13f
                setTextColor(context.secondaryTextColor)
                typeface = context.uiTypeface()
            })
            setOnClickListener { onClick() }
        }
    }

    private fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
}
