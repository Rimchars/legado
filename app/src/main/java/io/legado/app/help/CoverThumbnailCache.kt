package io.legado.app.help

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

object CoverThumbnailCache {

    private const val thumbWidth = 240
    private const val thumbHeight = 320
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun file(context: Context, key: String?): File? {
        if (key.isNullOrBlank()) return null
        val dir = File(context.cacheDir, "cover_thumbs")
        return File(dir, "${MD5Utils.md5Encode(key)}.jpg")
    }

    fun existing(context: Context, key: String?): File? {
        return file(context, key)?.takeIf { it.length() > 0L && it.exists() }
    }

    fun saveAsync(context: Context, key: String?, drawable: Drawable) {
        if (key.isNullOrBlank()) return
        val source = (drawable as? BitmapDrawable)?.bitmap ?: return
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                val target = file(appContext, key) ?: return@runCatching
                if (target.length() > 0L && target.exists()) return@runCatching
                target.parentFile?.mkdirs()
                val thumb = Bitmap.createScaledBitmap(source, thumbWidth, thumbHeight, true)
                FileOutputStream(target).use { out ->
                    thumb.compress(Bitmap.CompressFormat.JPEG, 86, out)
                }
                if (thumb !== source) {
                    thumb.recycle()
                }
            }
        }
    }
}
