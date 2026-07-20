package io.legado.app.utils

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.PictureDrawable
import android.util.Size
import java.io.FileInputStream
import java.io.InputStream
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGExternalFileResolver

@Suppress("WeakerAccess", "MemberVisibilityCanBePrivate")
object SvgUtils {

    private val externalResolverLock = Any()

    /**
     * 从Svg中解码bitmap
     */
    
    fun createBitmap(filePath: String, width: Int, height: Int? = null): Bitmap? {
        return kotlin.runCatching {
            val inputStream = FileInputStream(filePath)
            createBitmap(inputStream, width, height)
        }.getOrNull()
    }

    fun createBitmap(inputStream: InputStream, width: Int, height: Int? = null): Bitmap? {
        return kotlin.runCatching {
            createBitmapOrThrow(inputStream, width, height)
        }.getOrNull()
    }

    fun createBitmap(
        inputStream: InputStream,
        width: Int,
        height: Int? = null,
        resolver: SVGExternalFileResolver
    ): Bitmap? {
        return kotlin.runCatching {
            createBitmapOrThrow(inputStream, width, height, resolver)
        }.getOrNull()
    }

    internal fun createBitmapOrThrow(
        inputStream: InputStream,
        width: Int,
        height: Int? = null,
        resolver: SVGExternalFileResolver? = null
    ): Bitmap = synchronized(externalResolverLock) {
        if (resolver != null) SVG.registerExternalFileResolver(resolver)
        try {
            val svg = SVG.getFromInputStream(inputStream)
            createBitmap(svg, width, height)
        } finally {
            if (resolver != null) SVG.deregisterExternalFileResolver()
        }
    }

    fun createDrawable(inputStream: InputStream): Pair<PictureDrawable, Size>? {
        return kotlin.runCatching {
            synchronized(externalResolverLock) {
                val svg = SVG.getFromInputStream(inputStream)
                val size = getSize(svg)
                val picture = svg.renderToPicture()
                Pair(PictureDrawable(picture), size)
            }
        }.getOrNull()
    }

    //获取svg图片大小
    fun getSize(filePath: String): Size? {
        return kotlin.runCatching {
            val inputStream = FileInputStream(filePath)
            getSize(inputStream)
        }.getOrNull()
    }

    fun getSize(inputStream: InputStream): Size? {
        return kotlin.runCatching {
            synchronized(externalResolverLock) {
                val svg = SVG.getFromInputStream(inputStream)
                getSize(svg)
            }
        }.getOrNull()
    }

    /////// private method
    private fun createBitmap(svg: SVG, width: Int? = null, height: Int? = null): Bitmap {
        val size = getSize(svg)
        val bitmapSize = requireNotNull(
            SvgBitmapSizePolicy.fitWithin(size.width, size.height, width, height)
        ) { "invalid SVG dimensions" }

        val viewBox: RectF? = svg.documentViewBox
        if (viewBox == null && size.width > 0 && size.height > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }

        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")

        val bitmap = Bitmap.createBitmap(bitmapSize.width, bitmapSize.height, Bitmap.Config.ARGB_8888)

        svg.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    private fun getSize(svg: SVG): Size {
        val width = svg.documentWidth.toInt().takeIf { it > 0 }
            ?: (svg.documentViewBox.right - svg.documentViewBox.left).toInt()
        val height = svg.documentHeight.toInt().takeIf { it > 0 }
            ?: (svg.documentViewBox.bottom - svg.documentViewBox.top).toInt()
        return Size(width, height)      
    }

}
