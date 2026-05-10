package io.legado.app.utils

import android.app.Activity
import android.app.Dialog
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import androidx.core.view.forEach
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.dialogSurfaceBackground
import splitties.systemservices.windowManager

fun AlertDialog.applyTint(): AlertDialog {
    applyModernWindowStyle()
    val colorStateList = Selector.colorBuild()
        .setDefaultColor(ThemeStore.accentColor(context))
        .setPressedColor(ColorUtils.darkenColor(ThemeStore.accentColor(context)))
        .create()
    if (getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
        getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(colorStateList)
    }
    if (getButton(AlertDialog.BUTTON_POSITIVE) != null) {
        getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(colorStateList)
    }
    if (getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
        getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(colorStateList)
    }
    applyMaxWidthIfFloating()
    window?.decorView?.post {
        listView?.forEach {
            it.applyTint(context.accentColor)
        }
    }
    return this
}

fun Dialog.applyModernWindowStyle() {
    if (AppConfig.isEInkMode) return
    window?.let { window ->
        val attr = window.attributes
        attr.windowAnimations = R.style.AnimDialogCenter
        window.attributes = attr
        window.setBackgroundDrawable(context.dialogSurfaceBackground)
    }
}

fun AlertDialog.requestInputMethod() {
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
}

fun DialogFragment.setLayout(widthMix: Float, heightMix: Float) {
    dialog?.setLayout(widthMix, heightMix)
}

fun Dialog.setLayout(widthMix: Float, heightMix: Float) {
    val dm = context.windowManager.windowSize
    val height = (dm.heightPixels * heightMix).toInt()
    window?.setLayout(
        resolveFloatingDialogWidth((dm.widthPixels * widthMix).toInt(), height),
        height
    )
}

fun DialogFragment.setLayout(width: Int, heightMix: Float) {
    dialog?.setLayout(width, heightMix)
}

fun Dialog.setLayout(width: Int, heightMix: Float) {
    val dm = context.windowManager.windowSize
    val height = (dm.heightPixels * heightMix).toInt()
    window?.setLayout(
        resolveFloatingDialogWidth(width, height),
        height
    )
}

fun DialogFragment.setLayout(widthMix: Float, height: Int) {
    dialog?.setLayout(widthMix, height)
}

fun Dialog.setLayout(widthMix: Float, height: Int) {
    val dm = context.windowManager.windowSize
    window?.setLayout(
        resolveFloatingDialogWidth((dm.widthPixels * widthMix).toInt(), height),
        height
    )
}

fun DialogFragment.setLayout(width: Int, height: Int) {
    dialog?.setLayout(width, height)
}

fun Dialog.setLayout(width: Int, height: Int) {
    window?.setLayout(resolveFloatingDialogWidth(width, height), height)
}

private fun Dialog.applyMaxWidthIfFloating() {
    val attrs = window?.attributes ?: return
    val width = attrs.width
    val height = attrs.height
    if (width > 0 || width == WindowManager.LayoutParams.MATCH_PARENT) {
        window?.setLayout(resolveFloatingDialogWidth(width, height), height)
    }
}

private fun Dialog.resolveFloatingDialogWidth(width: Int, height: Int): Int {
    val attrs = window?.attributes ?: return width
    val isSheet = attrs.gravity and Gravity.BOTTOM == Gravity.BOTTOM ||
            attrs.gravity and Gravity.TOP == Gravity.TOP
    val isFullScreen = height == WindowManager.LayoutParams.MATCH_PARENT
    if (isSheet || isFullScreen) return width
    val dm = context.windowManager.windowSize
    val maxWidth = minOf((dm.widthPixels * 0.88f).toInt(), 520.dpToPx())
    return when {
        width == WindowManager.LayoutParams.MATCH_PARENT -> maxWidth
        width > maxWidth -> maxWidth
        else -> width
    }
}

fun Dialog.toggleSystemBar(show: Boolean) {
    window?.let { window ->
        WindowCompat.getInsetsController(window, window.decorView).run {
            if (show) {
                show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            } else {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            }
        }
    }
}

fun Dialog.keepScreenOn(on: Boolean) {
    window?.let { window ->
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
