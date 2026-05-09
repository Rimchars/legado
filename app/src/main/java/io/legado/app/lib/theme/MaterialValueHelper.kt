@file:Suppress("unused")

package io.legado.app.lib.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.dpToPx

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
@ColorInt
fun Context.getPrimaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_primary_text)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getSecondaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_secondary)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getPrimaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    }
}

@ColorInt
fun Context.getSecondaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_light
        )
    } else {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_dark
        )
    }
}

val Context.primaryColor: Int
    get() = ThemeStore.primaryColor(this)

val Context.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(this)

val Context.accentColor: Int
    get() = ThemeStore.accentColor(this)

val Context.backgroundColor: Int
    get() = if (!AppConfig.isEInkMode && ThemeConfig.hasUsableBgImage(this)) {
        Color.TRANSPARENT
    } else {
        ThemeStore.backgroundColor(this)
    }

val Context.bottomBackground: Int
    get() = ThemeStore.bottomBackground(this)

val Context.primaryTextColor: Int
    get() = getPrimaryTextColor(!AppConfig.isNightTheme)

val Context.transparentNavBar: Boolean
    get() = ThemeStore.transparentNavBar(this)

val Context.secondaryTextColor: Int
    get() = getSecondaryTextColor(!AppConfig.isNightTheme)

val Context.primaryDisabledTextColor: Int
    get() = getPrimaryDisabledTextColor(!AppConfig.isNightTheme)

val Context.secondaryDisabledTextColor: Int
    get() = getSecondaryDisabledTextColor(!AppConfig.isNightTheme)

val Fragment.primaryColor: Int
    get() = ThemeStore.primaryColor(requireContext())

val Fragment.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(requireContext())

val Fragment.accentColor: Int
    get() = ThemeStore.accentColor(requireContext())

val Fragment.backgroundColor: Int
    get() = requireContext().backgroundColor

val Fragment.bottomBackground: Int
    get() = ThemeStore.bottomBackground(requireContext())

val Fragment.primaryTextColor: Int
    get() = requireContext().getPrimaryTextColor(!AppConfig.isNightTheme)

val Fragment.secondaryTextColor: Int
    get() = requireContext().getSecondaryTextColor(!AppConfig.isNightTheme)

val Fragment.primaryDisabledTextColor: Int
    get() = requireContext().getPrimaryDisabledTextColor(!AppConfig.isNightTheme)

val Fragment.secondaryDisabledTextColor: Int
    get() = requireContext().getSecondaryDisabledTextColor(!AppConfig.isNightTheme)

val Context.buttonDisabledColor: Int
    get() = if (AppConfig.isNightTheme) {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    }

val Context.isDarkTheme: Boolean
    get() = AppConfig.isNightTheme

val Fragment.isDarkTheme: Boolean
    get() = requireContext().isDarkTheme

val Context.elevation: Float
    @SuppressLint("PrivateResource")
    get() {
        return if (AppConfig.elevation < 0) {
            ThemeUtils.resolveFloat(
                this,
                android.R.attr.elevation,
                resources.getDimension(com.google.android.material.R.dimen.design_appbar_elevation)
            )
        } else {
            AppConfig.elevation.toFloat().dpToPx()
        }
    }

val Context.filletBackground: GradientDrawable
    get() {
        return UiCorner.rounded(backgroundColor, UiCorner.panelRadius(this))
    }

val Context.dialogSurfaceBackground: GradientDrawable
    get() {
        return UiCorner.opaqueRounded(ContextCompat.getColor(this, R.color.dialog_surface), UiCorner.panelRadius(this))
    }

fun Context.filletTopBackground(@ColorInt color: Int): GradientDrawable {
    val radius = UiCorner.scaledDp(16f)
    return GradientDrawable().apply {
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        setColor(UiCorner.surfaceColor(color))
    }
}
