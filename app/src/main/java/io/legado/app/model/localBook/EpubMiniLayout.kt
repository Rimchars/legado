package io.legado.app.model.localBook

import android.graphics.BitmapFactory
import android.text.TextPaint
import android.util.Base64
import android.util.Size
import org.jsoup.Jsoup
import kotlin.math.ceil
import kotlin.math.max

internal object EpubMiniLayout {

    fun layoutTitle(
        html: String,
        viewportWidth: Int,
        viewportHeight: Int,
        basePaint: TextPaint
    ): EpubLayoutDocument {
        val doc = Jsoup.parse(html)
        val body = doc.body()
        val dom = EpubDomBuilder(
            loadCss = { _, _ -> "" },
            resolveHref = { _, href -> href }
        ).build(
            doc = doc,
            body = body,
            baseHref = "advanced-title.xhtml"
        )
        return EpubLayoutEngine(
            imageSizeResolver = ::resolveDataImageSize,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            basePaint = basePaint
        ).layout(dom)
    }

    fun contentHeight(
        commands: List<EpubDrawCommand>,
        minHeight: Float,
        maxHeight: Float
    ): Float {
        val bottom = commands.maxOfOrNull { command ->
            when (command) {
                is EpubBlockBox -> command.y + command.height
                is EpubBullet -> command.baseline + command.size
                is EpubImageBox -> if (command.isBackground) 0f else command.y + command.height
                is EpubLinkArea -> command.y + command.height
                is EpubPageColor -> 0f
                is EpubRuleLine -> command.y + command.strokeWidth
                is EpubTextRun -> max(command.y + command.height, command.baseline + command.size)
            }
        } ?: 0f
        return ceil(bottom.coerceAtLeast(minHeight).coerceAtMost(maxHeight))
    }

    private fun resolveDataImageSize(src: String): Size? {
        if (!src.startsWith("data:", ignoreCase = true)) return null
        val bytes = runCatching {
            Base64.decode(src.substringAfter("base64,"), Base64.DEFAULT)
        }.getOrNull() ?: return null
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return Size(options.outWidth, options.outHeight)
    }
}
