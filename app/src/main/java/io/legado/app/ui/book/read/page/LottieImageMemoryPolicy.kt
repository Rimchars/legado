package io.legado.app.ui.book.read.page

import java.security.MessageDigest
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

internal data class LottieDecodeSize(val width: Int, val height: Int)

internal data class LottieImageCacheKey(
    val sourceSha256: String,
    val width: Int,
    val height: Int,
    val decoderVersion: Int = 1
)

internal object LottieImageMemoryPolicy {
    const val MAX_EDGE = 1200
    const val MAX_PIXELS = 1_440_000L
    private const val QUALITY_SCALE = 1.25f
    private const val MIN_CACHE_BYTES = 8 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
    private val sourceDigestCache = WeakHashMap<String, String>()

    fun decodeSize(
        assetWidth: Int,
        assetHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        compositionWidth: Int,
        compositionHeight: Int
    ): LottieDecodeSize? {
        if (assetWidth <= 0 || assetHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
        val compWidth = compositionWidth.takeIf { it > 0 } ?: assetWidth
        val compHeight = compositionHeight.takeIf { it > 0 } ?: assetHeight
        val fitScale = min(viewWidth.toDouble() / compWidth, viewHeight.toDouble() / compHeight)
        if (!fitScale.isFinite() || fitScale <= 0.0) return null
        var width = ceil(assetWidth * fitScale * QUALITY_SCALE).toInt().coerceAtLeast(1)
        var height = ceil(assetHeight * fitScale * QUALITY_SCALE).toInt().coerceAtLeast(1)
        val edgeScale = min(1.0, MAX_EDGE.toDouble() / maxOf(width, height))
        width = (width * edgeScale).toInt().coerceAtLeast(1)
        height = (height * edgeScale).toInt().coerceAtLeast(1)
        val pixels = width.toLong() * height
        if (pixels > MAX_PIXELS) {
            val pixelScale = sqrt(MAX_PIXELS.toDouble() / pixels)
            width = (width * pixelScale).toInt().coerceAtLeast(1)
            height = (height * pixelScale).toInt().coerceAtLeast(1)
        }
        return LottieDecodeSize(width, height)
    }

    fun fitSourceInto(sourceWidth: Int, sourceHeight: Int, box: LottieDecodeSize): LottieDecodeSize? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val scale = min(1.0, min(box.width.toDouble() / sourceWidth, box.height.toDouble() / sourceHeight))
        return LottieDecodeSize(
            width = (sourceWidth * scale).toInt().coerceAtLeast(1),
            height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        )
    }

    fun sampleSize(sourceWidth: Int, sourceHeight: Int, target: LottieDecodeSize): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || target.width <= 0 || target.height <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2L) >= target.width &&
            sourceHeight / (sample * 2L) >= target.height
        ) {
            sample *= 2
        }
        return sample
    }

    fun cacheBudgetBytes(maxHeapBytes: Long): Int {
        return (maxHeapBytes / 32L).coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong()).toInt()
    }

    fun chargeBytes(allocationBytes: Int, rowBytes: Int, height: Int): Int {
        if (allocationBytes > 0) return allocationBytes
        return (rowBytes.toLong().coerceAtLeast(0L) * height.coerceAtLeast(0))
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun sourceSha256(source: String): String {
        synchronized(sourceDigestCache) {
            sourceDigestCache[source]?.let { return it }
            return MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .also { sourceDigestCache[source] = it }
        }
    }
}
