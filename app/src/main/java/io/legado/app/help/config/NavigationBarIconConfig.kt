package io.legado.app.help.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.view.Menu
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import io.legado.app.R
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.min

object NavigationBarIconConfig {

    const val MODE_DAY = "day"
    const val MODE_NIGHT = "night"
    const val STATE_NORMAL = "normal"
    const val STATE_SELECTED = "selected"

    val rootDir: File
        get() = appCtx.externalFiles.getFile("navigationIcons")

    data class NavItem(
        val key: String,
        @StringRes val titleRes: Int,
        @IdRes val menuId: Int,
        @DrawableRes val defaultIconRes: Int
    )

    val items = listOf(
        NavItem("bookshelf", R.string.bookshelf, R.id.menu_bookshelf, R.drawable.ic_bottom_books),
        NavItem("discovery", R.string.discovery, R.id.menu_discovery, R.drawable.ic_bottom_explore),
        NavItem("rss", R.string.rss, R.id.menu_rss, R.drawable.ic_bottom_rss_feed),
        NavItem("readRecord", R.string.read_record, R.id.menu_read_record, R.drawable.ic_bottom_read_record),
        NavItem("my", R.string.my, R.id.menu_my_config, R.drawable.ic_bottom_person)
    )

    fun prefKey(mode: String, itemKey: String, state: String): String {
        return "navigationIcon_${mode}_${itemKey}_$state"
    }

    fun pathOf(mode: String, itemKey: String, state: String): String? {
        val path = appCtx.getPrefString(prefKey(mode, itemKey, state))
        return path?.takeIf { File(it).exists() }
    }

    fun hasCustomIcons(mode: String): Boolean {
        return items.any { item ->
            pathOf(mode, item.key, STATE_NORMAL) != null ||
                pathOf(mode, item.key, STATE_SELECTED) != null
        }
    }

    fun applyTo(menu: Menu, context: Context, isNight: Boolean): Boolean {
        val mode = if (isNight) MODE_NIGHT else MODE_DAY
        val hasCustom = hasCustomIcons(mode)
        items.forEach { item ->
            menu.findItem(item.menuId)?.icon = createMenuDrawable(context, mode, item)
        }
        return hasCustom
    }

    fun previewDrawable(context: Context, mode: String, item: NavItem, selected: Boolean): Drawable? {
        val state = if (selected) STATE_SELECTED else STATE_NORMAL
        return loadDrawable(context, pathOf(mode, item.key, state))
            ?: loadDrawable(context, pathOf(mode, item.key, STATE_NORMAL))
            ?: ContextCompat.getDrawable(context, item.defaultIconRes)
    }

    fun saveIcon(context: Context, uri: Uri, mode: String, itemKey: String, selected: Boolean, targetSize: Int): Boolean {
        val bitmap = decodeIconBitmap(context, uri, targetSize) ?: return false
        val output = drawCenteredIcon(bitmap, targetSize)
        if (bitmap !== output && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        val state = if (selected) STATE_SELECTED else STATE_NORMAL
        val file = iconFile(mode, itemKey, state)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use {
            output.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        if (!output.isRecycled) {
            output.recycle()
        }
        appCtx.putPrefString(prefKey(mode, itemKey, state), file.absolutePath)
        return true
    }

    fun clearIcon(mode: String, itemKey: String, selected: Boolean) {
        val state = if (selected) STATE_SELECTED else STATE_NORMAL
        pathOf(mode, itemKey, state)?.let { File(it).delete() }
        appCtx.removePref(prefKey(mode, itemKey, state))
    }

    fun clearMode(mode: String) {
        items.forEach { item ->
            clearIcon(mode, item.key, selected = false)
            clearIcon(mode, item.key, selected = true)
        }
    }

    private fun createMenuDrawable(context: Context, mode: String, item: NavItem): Drawable {
        val defaultColor = defaultIconColor(context)
        val selectedColor = ThemeStore.accentColor(context)
        val normal = loadDrawable(context, pathOf(mode, item.key, STATE_NORMAL))
            ?: defaultDrawable(context, item.defaultIconRes, defaultColor)
        val selected = loadDrawable(context, pathOf(mode, item.key, STATE_SELECTED))
            ?: loadDrawable(context, pathOf(mode, item.key, STATE_NORMAL))
            ?: defaultDrawable(context, item.defaultIconRes, selectedColor)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), selected)
            addState(intArrayOf(android.R.attr.state_selected), selected)
            addState(intArrayOf(), normal)
        }
    }

    private fun defaultDrawable(context: Context, @DrawableRes resId: Int, color: Int): Drawable {
        val drawable = ContextCompat.getDrawable(context, resId)!!.mutate()
        DrawableCompat.setTint(drawable, color)
        return drawable
    }

    private fun defaultIconColor(context: Context): Int {
        val bgColor = context.bottomBackground
        val textIsDark = ColorUtils.isColorLight(bgColor)
        return context.getSecondaryTextColor(textIsDark)
    }

    private fun loadDrawable(context: Context, path: String?): Drawable? {
        if (path.isNullOrBlank()) return null
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun iconFile(mode: String, itemKey: String, state: String): File {
        return File(rootDir, "${mode}_${itemKey}_$state.png")
    }

    private fun decodeIconBitmap(context: Context, uri: Uri, targetSize: Int): Bitmap? {
        val name = uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
        return if (name.endsWith(".svg")) {
            context.contentResolver.openInputStream(uri)?.use {
                SvgUtils.createBitmap(it, targetSize, targetSize)
            }
        } else if (name.endsWith(".ico")) {
            context.contentResolver.openInputStream(uri)?.use {
                decodeIcoPng(it.readBytes())
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        }
    }

    private fun decodeIcoPng(bytes: ByteArray): Bitmap? {
        if (bytes.size < 22) return null
        val type = leShort(bytes, 2)
        val count = leShort(bytes, 4)
        if (type != 1 || count <= 0) return null
        var bestOffset = 0
        var bestSize = 0
        var bestPixels = -1
        repeat(count) { index ->
            val entry = 6 + index * 16
            if (entry + 16 > bytes.size) return@repeat
            val width = bytes[entry].toInt().and(0xff).let { if (it == 0) 256 else it }
            val height = bytes[entry + 1].toInt().and(0xff).let { if (it == 0) 256 else it }
            val size = leInt(bytes, entry + 8)
            val offset = leInt(bytes, entry + 12)
            if (size <= 0 || offset <= 0 || offset + size > bytes.size) return@repeat
            val pixels = width * height
            if (pixels > bestPixels) {
                bestPixels = pixels
                bestOffset = offset
                bestSize = size
            }
        }
        if (bestSize <= 0) return null
        return BitmapFactory.decodeByteArray(bytes, bestOffset, bestSize)
    }

    private fun leShort(bytes: ByteArray, offset: Int): Int {
        return bytes[offset].toInt().and(0xff) or
            (bytes[offset + 1].toInt().and(0xff) shl 8)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        return bytes[offset].toInt().and(0xff) or
            (bytes[offset + 1].toInt().and(0xff) shl 8) or
            (bytes[offset + 2].toInt().and(0xff) shl 16) or
            (bytes[offset + 3].toInt().and(0xff) shl 24)
    }

    private fun drawCenteredIcon(source: Bitmap, targetSize: Int): Bitmap {
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.TRANSPARENT)
        val scale = min(targetSize.toFloat() / source.width, targetSize.toFloat() / source.height)
        val width = source.width * scale
        val height = source.height * scale
        val left = (targetSize - width) / 2f
        val top = (targetSize - height) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), paint)
        return output
    }
}
