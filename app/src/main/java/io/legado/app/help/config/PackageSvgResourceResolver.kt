package io.legado.app.help.config

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import com.caverock.androidsvg.SVGExternalFileResolver
import java.io.File

internal class PackageSvgResourceResolver(
    private val root: File,
    private val resources: List<PackageResource>,
    private val targetWidth: Int,
    private val targetHeight: Int
) : SVGExternalFileResolver() {

    override fun resolveImage(filename: String): Bitmap? {
        val file = runCatching {
            PackageResourcePolicy.resolve(
                root,
                resources,
                filename,
                PackageResourcePolicy.TYPE_IMAGE
            )
        }.getOrNull() ?: return null
        if (file.extension.equals("svg", ignoreCase = true)) return null
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
        val file = runCatching {
            PackageResourcePolicy.resolve(
                root,
                resources,
                fontName,
                PackageResourcePolicy.TYPE_FONT
            )
        }.getOrNull() ?: return null
        val base = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return null
        val bold = fontWeight >= 600
        val italic = fontStyle.contains("italic", ignoreCase = true) ||
            fontStyle.contains("oblique", ignoreCase = true)
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(base, style)
    }

    override fun isFormatSupported(mimeType: String): Boolean {
        return mimeType.startsWith("image/", ignoreCase = true)
    }
}
