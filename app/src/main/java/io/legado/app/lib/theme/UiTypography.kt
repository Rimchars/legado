package io.legado.app.lib.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.dpToPx
import java.io.File

private fun Context.baseSystemTypeface(): Typeface {
    return when (AppConfig.systemTypefaces) {
        1 -> Typeface.SERIF
        2 -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
}

fun Context.uiTypeface(): Typeface {
    val fontPath = AppConfig.uiFontPath
    if (fontPath.isNotBlank()) {
        loadUiTypeface(fontPath)?.let {
            return it
        }
    }
    return baseSystemTypeface()
}

fun Context.titleTypeface(): Typeface {
    val fontPath = AppConfig.titleFontPath
    if (fontPath.isNotBlank()) {
        loadUiTypeface(fontPath)?.let {
            return it
        }
    }
    return baseSystemTypeface()
}

fun Context.loadUiTypeface(fontPath: String): Typeface? {
    if (fontPath.isBlank()) {
        return null
    }
    return UiTypefaceCache.get(this, fontPath)
}

private object UiTypefaceCache {

    private val cache = linkedMapOf<String, Typeface>()

    fun get(context: Context, fontPath: String): Typeface? {
        cache[fontPath]?.let { return it }
        return runCatching {
            when {
                fontPath.startsWith("content://", ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    context.contentResolver.openFileDescriptor(android.net.Uri.parse(fontPath), "r")?.use {
                        Typeface.Builder(it.fileDescriptor).build()
                    }
                }
                fontPath.startsWith("content://", ignoreCase = true) -> {
                    RealPathUtil.getPath(context, android.net.Uri.parse(fontPath))?.let {
                        Typeface.createFromFile(it)
                    }
                }
                File(fontPath).exists() -> Typeface.createFromFile(fontPath)
                else -> null
            }
        }.getOrNull().also {
            if (it != null) {
                cache[fontPath] = it
                if (cache.size > 8) {
                    cache.remove(cache.keys.first())
                }
            }
        }
    }
}

fun View.applyUiTypefaceDeep(typeface: Typeface) {
    when (this) {
        is TextView -> {
            if (this.typeface != typeface) {
                this.typeface = typeface
            }
        }
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiTypefaceDeep(typeface)
            }
        }
    }
}

fun View.applyUiBodyTypefaceDeep(typeface: Typeface) {
    when (this) {
        is TitleBar -> return
        is TextView -> {
            if (getTag(R.id.ui_title_typeface_role) == true) return
            if (this.typeface != typeface) {
                this.typeface = typeface
            }
        }
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiBodyTypefaceDeep(typeface)
            }
        }
    }
}

fun TextView.applyUiTitleTypeface(context: Context) {
    setTag(R.id.ui_title_typeface_role, true)
    typeface = context.titleTypeface()
}

fun TextView.applyUiMenuItemTypeface(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
}

fun View.applyUiMenuTypefaceDeep(context: Context) {
    when (this) {
        is TextView -> applyUiMenuItemTypeface(context)
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiMenuTypefaceDeep(context)
            }
        }
    }
}

fun TextView.applyUiLabelStyle(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 14f
    setTextColor(ContextCompat.getColor(context, R.color.primaryText))
}

fun TextView.applyUiSectionTitleStyle(context: Context) {
    applyUiTitleTypeface(context)
    textSize = 15f
    setTextColor(ContextCompat.getColor(context, R.color.primaryText))
}

fun TextView.applyUiSubtleButtonStyle(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 14f
    minHeight = 40.dpToPx()
    setTextColor(ContextCompat.getColor(context, R.color.primaryText))
}

fun EditText.applyUiInputStyle(context: Context, minLines: Int = 1) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 15f
    this.minLines = minLines
    maxLines = if (minLines > 1) 8 else 2
    setSingleLine(minLines == 1)
    minHeight = if (minLines > 1) 92.dpToPx() else 44.dpToPx()
    val horizontal = 12.dpToPx()
    val vertical = if (minLines > 1) 10.dpToPx() else 8.dpToPx()
    setPadding(horizontal, vertical, horizontal, vertical)
}
