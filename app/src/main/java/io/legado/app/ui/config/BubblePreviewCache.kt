package io.legado.app.ui.config

import android.graphics.Bitmap
import android.util.LruCache
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.PackageSvgResourceResolver
import io.legado.app.utils.SvgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

internal class BubblePreviewCache(
    maxBytes: Int = DEFAULT_MAX_BYTES
) {
    private data class Key(
        val dirName: String,
        val updatedAt: Long,
        val svgHash: Int,
        val svgLength: Int,
        val resourcesHash: Int,
        val color: String,
        val sidePx: Int
    )

    private val renderMutex = Mutex()
    private val cache = object : LruCache<Key, Bitmap>(maxBytes.coerceAtLeast(1)) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun load(
        entry: BubblePackageManager.Entry,
        sidePx: Int,
        isNightTheme: Boolean
    ): Bitmap? = withContext(Dispatchers.Default) {
        val config = entry.config
        val color = if (isNightTheme) {
            config.nightNormalColor
        } else {
            config.dayNormalColor
        }?.takeIf { it.isNotBlank() } ?: BubblePackageManager.DEFAULT_NORMAL_COLOR
        val key = Key(
            dirName = entry.dirName,
            updatedAt = maxOf(config.updatedAt, entry.remoteUpdatedAt),
            svgHash = config.svgTemplate.hashCode(),
            svgLength = config.svgTemplate.length,
            resourcesHash = config.resources.hashCode(),
            color = color,
            sidePx = sidePx
        )
        cache.get(key) ?: renderMutex.withLock {
            cache.get(key) ?: render(entry, color, sidePx)?.also {
                cache.put(key, it)
            }
        }
    }

    private fun render(entry: BubblePackageManager.Entry, color: String, sidePx: Int): Bitmap? {
        if (sidePx <= 0) return null
        val svg = entry.config.svgTemplate
            .replace("\${color}", color)
            .replace("\${num}", "12")
        return ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8)).use { input ->
            val root = entry.localDir
            if (root == null) {
                SvgUtils.createBitmap(input, sidePx, sidePx)
            } else {
                SvgUtils.createBitmap(
                    input,
                    sidePx,
                    sidePx,
                    PackageSvgResourceResolver(root, entry.config.resources, sidePx, sidePx)
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 8 * 1024 * 1024
    }
}
