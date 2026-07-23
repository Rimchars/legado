package io.legado.app.help.config

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import com.caverock.androidsvg.SVGExternalFileResolver
import io.legado.app.help.AppFont
import io.legado.app.utils.SvgUtils
import java.io.File
import java.io.FileInputStream

internal class PackageSvgResourceResolver(
    private val root: File?,
    private val resources: List<PackageResource>,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val fallbackTypeface: () -> Typeface? = { AppFont.reader() },
) : SVGExternalFileResolver() {

    private val resolvingImages = ThreadLocal.withInitial { hashSetOf<String>() }

    override fun resolveImage(filename: String): Bitmap? {
        val resourceRoot = root ?: return null
        val file = runCatching {
            PackageResourcePolicy.resolve(
                resourceRoot,
                resources,
                filename,
                PackageResourcePolicy.TYPE_IMAGE
            )
        }.getOrNull() ?: return null
        val identity = file.canonicalPath
        val resolving = requireNotNull(resolvingImages.get())
        if (!resolving.add(identity)) return null
        return try {
            if (file.extension.equals("svg", ignoreCase = true)) {
                FileInputStream(file).use { input ->
                    SvgUtils.createBitmap(input, targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
                }
            } else {
                decodeRaster(file)
            }
        } finally {
            resolving.remove(identity)
            if (resolving.isEmpty()) resolvingImages.remove()
        }
    }

    private fun decodeRaster(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val width = targetWidth.coerceAtLeast(1)
        val height = targetHeight.coerceAtLeast(1)
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= width &&
            bounds.outHeight / (sampleSize * 2) >= height
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    }

    override fun resolveFont(fontName: String, fontWeight: Int, fontStyle: String): Typeface? {
        val bold = fontWeight >= 600
        val italic = fontStyle.contains("italic", ignoreCase = true) ||
            fontStyle.contains("oblique", ignoreCase = true)
        val style = AppFont.Style(bold = bold, italic = italic)

        // 1) package resource (including package: aliases)
        val file = root?.let { resourceRoot ->
            runCatching {
                PackageResourcePolicy.resolve(
                    resourceRoot,
                    resources,
                    fontName,
                    PackageResourcePolicy.TYPE_FONT
                )
            }.getOrNull()
        }
        if (file != null) {
            val base = runCatching { Typeface.createFromFile(file) }.getOrNull()
            if (base != null) {
                return AppFont.typeface(null, style, fallback = base)
            }
        }

        // 2) @font:Name / reader / system / path via AppFont
        val preferred = runCatching { fallbackTypeface() }.getOrNull()
        return AppFont.typeface(fontName, style, fallback = preferred ?: AppFont.systemDefault())
    }

    override fun isFormatSupported(mimeType: String): Boolean {
        return mimeType.startsWith("image/", ignoreCase = true)
    }
}
