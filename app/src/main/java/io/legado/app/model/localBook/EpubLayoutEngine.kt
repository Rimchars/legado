package io.legado.app.model.localBook

import android.graphics.Color
import android.text.TextPaint
import android.util.Size
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import java.util.Locale

internal class EpubLayoutEngine(
    private val imageSizeResolver: (String) -> Size? = { null },
    private val viewportWidth: Int = ChapterProvider.visibleWidth,
    private val viewportHeight: Int = ChapterProvider.visibleHeight,
    private val basePaint: TextPaint = ChapterProvider.contentPaint
) {

    private val pages = arrayListOf<MutableList<EpubDrawCommand>>()
    private var currentCommands = arrayListOf<EpubDrawCommand>()
    private var cursorY = 0f
    private var firstLineIndent = 0f

    fun layout(document: EpubDomDocument): EpubLayoutDocument {
        pages.clear()
        currentCommands = arrayListOf()
        cursorY = 0f
        firstLineIndent = 0f
        layoutChildren(document.body.children, document.body.style, left = 0f, width = viewportWidth.toFloat())
        flushPageIfNeeded(force = true)
        return EpubLayoutDocument(
            href = document.href,
            pages = pages.mapIndexed { index, commands ->
                EpubLayoutPage(index = index, commands = commands, height = viewportHeight.toFloat())
            }
        )
    }

    private fun layoutChildren(
        children: List<EpubDomNode>,
        parentStyle: EpubComputedStyle,
        left: Float,
        width: Float
    ) {
        children.forEach { node ->
            when (node) {
                is EpubDomElement -> layoutElement(node, left, width)
                is EpubDomText -> layoutText(node.text, parentStyle, left, width, node.sourcePath)
            }
        }
    }

    private fun layoutElement(element: EpubDomElement, left: Float, width: Float) {
        if (element.style["display"].equals("none", ignoreCase = true)) return
        element.attributes["data-epub-page-bg"]?.toEpubPageColor()?.let { color ->
            if (currentCommands.isNotEmpty()) flushPageIfNeeded(force = true)
            currentCommands.add(EpubPageColor(color = color, sourcePath = element.sourcePath))
            return
        }
        if (element.tagName == "br") {
            cursorY += lineHeight(element.style)
            return
        }
        if (element.tagName == "hr") {
            layoutRuleLine(element, left, width)
            return
        }
        if (element.tagName == "img" || element.tagName == "image") {
            layoutImage(element, left, width)
            return
        }
        val marginTop = element.style.lengthPx("margin-top", width)
        val marginBottom = element.style.lengthPx("margin-bottom", width)
        val paddingTop = element.style.lengthPx("padding-top", width)
        val paddingBottom = element.style.lengthPx("padding-bottom", width)
        val paddingLeft = element.style.lengthPx("padding-left", width)
        val paddingRight = element.style.lengthPx("padding-right", width)
        val marginLeft = element.style.lengthPx("margin-left", width)
        val marginRight = element.style.lengthPx("margin-right", width)
        val borderWidth = element.style.borderWidthPx()
        cursorY += marginTop
        val boxTop = cursorY
        val listMarkerWidth = if (element.tagName == "li") basePaint.textSize * 1.2f else 0f
        val contentLeft = left + marginLeft + borderWidth + paddingLeft + listMarkerWidth
        val contentWidth = (width - marginLeft - marginRight - borderWidth * 2 - paddingLeft - paddingRight - listMarkerWidth)
            .coerceAtLeast(width * 0.35f)
        val requestedHeight = element.style.lengthPx("height", viewportHeight.toFloat())
            .takeIf { it > 0f }
            ?: element.style.lengthPx("min-height", viewportHeight.toFloat()).takeIf { it > 0f }
        if (requestedHeight != null && boxTop + requestedHeight > viewportHeight && currentCommands.isNotEmpty()) {
            flushPageIfNeeded(force = true)
        }
        cursorY += borderWidth + paddingTop
        val blockCommandIndex = currentCommands.size
        val blockStyle = element.blockStyle()
        if (blockStyle != null) {
            currentCommands.add(
                EpubBlockBox(
                    x = left + marginLeft,
                    y = boxTop,
                    width = width - marginLeft - marginRight,
                    height = 0f,
                    backgroundColor = blockStyle.backgroundColor,
                    borderColor = blockStyle.borderColor,
                    borderWidth = borderWidth,
                    radius = blockStyle.radius,
                    sourcePath = element.sourcePath
                )
            )
        }
        if (element.tagName == "li") {
            currentCommands.add(
                EpubBullet(
                    text = "•",
                    x = left + marginLeft + borderWidth + paddingLeft,
                    baseline = cursorY + element.style.fontSizePx(),
                    size = element.style.fontSizePx(),
                    color = element.style.colorInt(),
                    sourcePath = element.sourcePath
                )
            )
        }
        val previousTextIndent = firstLineIndent
        firstLineIndent = element.style.lengthPx("text-indent", contentWidth)
        layoutChildren(element.children, element.style, contentLeft, contentWidth)
        firstLineIndent = previousTextIndent
        cursorY += paddingBottom + borderWidth
        if (blockStyle != null) {
            val old = currentCommands.getOrNull(blockCommandIndex) as? EpubBlockBox
            if (old != null) {
                val boxHeight = requestedHeight ?: (cursorY - boxTop)
                currentCommands[blockCommandIndex] = old.copy(height = boxHeight.coerceAtLeast(0f))
            }
        }
        requestedHeight?.let { height ->
            if (cursorY < boxTop + height) {
                cursorY = boxTop + height
            }
        }
        cursorY += marginBottom
        if (element.isBlockElement()) {
            cursorY += element.style.lengthPx("line-height", width).takeIf { it > 0f } ?: 0f
        }
        flushPageIfNeeded()
    }

    private fun layoutText(text: String, style: EpubComputedStyle, left: Float, width: Float, sourcePath: String) {
        val normalized = if (style["white-space"]?.contains("pre") == true) {
            text.replace("\r", "")
        } else {
            text.replace(Regex("\\s+"), " ")
        }
        if (normalized.isBlank()) return
        val paint = TextPaint(basePaint).apply {
            textSize = style.fontSizePx()
            isFakeBoldText = style.isBold()
            textSkewX = if (style.isItalic()) -0.25f else 0f
        }
        val color = style.colorInt()
        val lineHeight = lineHeight(style)
        var line = StringBuilder()
        var lineWidth = 0f
        var lineLeft = left + firstLineIndent
        var lineWidthLimit = (width - firstLineIndent).coerceAtLeast(width * 0.35f)
        normalized.forEach { char ->
            val value = char.toString()
            val charWidth = paint.measureText(value)
            if (line.isNotEmpty() && lineWidth + charWidth > lineWidthLimit) {
                addTextLine(line.toString(), lineLeft, cursorY, lineWidth, lineWidthLimit, paint.textSize, color, style, sourcePath)
                cursorY += lineHeight
                flushPageIfNeedForHeight(cursorY + lineHeight)
                line = StringBuilder()
                lineWidth = 0f
                lineLeft = left
                lineWidthLimit = width
            }
            line.append(char)
            lineWidth += charWidth
        }
        if (line.isNotEmpty()) {
            addTextLine(line.toString(), lineLeft, cursorY, lineWidth, lineWidthLimit, paint.textSize, color, style, sourcePath)
            cursorY += lineHeight
        }
        firstLineIndent = 0f
    }

    private fun addTextLine(
        text: String,
        left: Float,
        top: Float,
        lineWidth: Float,
        availableWidth: Float,
        size: Float,
        color: Int?,
        style: EpubComputedStyle,
        sourcePath: String
    ) {
        val x = when (style["text-align"]?.lowercase(Locale.ROOT)) {
            "center" -> left + ((availableWidth - lineWidth) / 2f).coerceAtLeast(0f)
            "right", "end" -> left + (availableWidth - lineWidth).coerceAtLeast(0f)
            else -> left
        }
        currentCommands.add(
            EpubTextRun(
                text = text,
                x = x,
                baseline = top + size,
                size = size,
                color = color,
                bold = style.isBold(),
                italic = style.isItalic(),
                sourcePath = sourcePath
            )
        )
    }

    private fun layoutImage(element: EpubDomElement, left: Float, width: Float) {
        val src = element.attributes["src"]
            ?: element.attributes["xlink:href"]
            ?: element.attributes["href"]
            ?: return
        if (element.attributes["data-epub-background"] == "true") {
            if (currentCommands.isNotEmpty()) flushPageIfNeeded(force = true)
            currentCommands.add(
                EpubImageBox(
                    src = src,
                    x = 0f,
                    y = 0f,
                    width = viewportWidth.toFloat(),
                    height = viewportHeight.toFloat(),
                    isBackground = true,
                    sourcePath = element.sourcePath
                )
            )
            return
        }
        val imageWidth = element.style.lengthPx("width", width)
            .takeIf { it > 0f }
            ?: element.attributes["width"]?.toCssLengthPx(width)
            ?: width
        val imageHeight = element.style.lengthPx("height", width)
            .takeIf { it > 0f }
            ?: element.attributes["height"]?.toCssLengthPx(width)
            ?: imageSizeResolver(src).scaledHeight(imageWidth)
        flushPageIfNeedForHeight(cursorY + imageHeight)
        currentCommands.add(
            EpubImageBox(
                src = src,
                x = left + ((width - imageWidth) / 2f).coerceAtLeast(0f),
                y = cursorY,
                width = imageWidth.coerceAtLeast(1f),
                height = imageHeight.coerceAtLeast(1f),
                isBackground = false,
                sourcePath = element.sourcePath
            )
        )
        cursorY += imageHeight
    }

    private fun layoutRuleLine(element: EpubDomElement, left: Float, width: Float) {
        val marginTop = element.style.lengthPx("margin-top", width).takeIf { it > 0f } ?: lineHeight(element.style) * 0.5f
        val marginBottom = element.style.lengthPx("margin-bottom", width).takeIf { it > 0f } ?: lineHeight(element.style) * 0.5f
        val strokeWidth = element.style.borderWidthPx().takeIf { it > 0f } ?: 1f
        flushPageIfNeedForHeight(cursorY + marginTop + strokeWidth + marginBottom)
        cursorY += marginTop
        currentCommands.add(
            EpubRuleLine(
                x = left,
                y = cursorY,
                width = width,
                strokeWidth = strokeWidth,
                color = element.style.borderColor() ?: element.style.colorInt(),
                sourcePath = element.sourcePath
            )
        )
        cursorY += strokeWidth + marginBottom
    }

    private fun flushPageIfNeedForHeight(requestHeight: Float) {
        if (requestHeight > viewportHeight && currentCommands.isNotEmpty()) {
            flushPageIfNeeded(force = true)
        }
    }

    private fun flushPageIfNeeded(force: Boolean = false) {
        if (!force && cursorY <= viewportHeight) return
        if (currentCommands.isEmpty()) {
            cursorY = 0f
            return
        }
        pages.add(currentCommands)
        currentCommands = arrayListOf()
        cursorY = 0f
        firstLineIndent = 0f
    }

    private fun lineHeight(style: EpubComputedStyle): Float {
        val fontSize = style.fontSizePx()
        return style.lengthPx("line-height", viewportWidth.toFloat()).takeIf { it > 0f } ?: (fontSize * 1.35f)
    }

    private fun EpubDomElement.isBlockElement(): Boolean {
        return tagName in blockTags
    }

    private fun EpubDomElement.blockStyle(): BlockStyle? {
        val backgroundColor = style.colorInt("background-color") ?: style.backgroundColor()
        val borderColor = style.colorInt("border-color") ?: style.borderColor()
        if (backgroundColor == null && borderColor == null) return null
        return BlockStyle(
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            radius = style.lengthPx("border-radius", viewportWidth.toFloat())
        )
    }

    private fun EpubComputedStyle.fontSizePx(): Float {
        return this["font-size"]?.toCssLengthPx(basePaint.textSize) ?: basePaint.textSize
    }

    private fun EpubComputedStyle.lengthPx(name: String, relativeTo: Float): Float {
        return this[name]?.toCssLengthPx(relativeTo) ?: 0f
    }

    private fun EpubComputedStyle.borderWidthPx(): Float {
        return this["border-width"]?.toCssLengthPx(viewportWidth.toFloat())
            ?: this["border"]?.extractCssLength()?.toCssLengthPx(viewportWidth.toFloat())
            ?: this["border-top-width"]?.toCssLengthPx(viewportWidth.toFloat())
            ?: 0f
    }

    private fun EpubComputedStyle.colorInt(name: String = "color"): Int? {
        return this[name]?.toCssColor()
    }

    private fun EpubComputedStyle.backgroundColor(): Int? {
        return this["background"]?.extractCssColor()?.toCssColor()
    }

    private fun EpubComputedStyle.borderColor(): Int? {
        return this["border"]?.extractCssColor()?.toCssColor()
            ?: this["border-top"]?.extractCssColor()?.toCssColor()
    }

    private fun EpubComputedStyle.isBold(): Boolean {
        val value = this["font-weight"]?.lowercase(Locale.ROOT) ?: return false
        return value == "bold" || (value.toIntOrNull() ?: 0) >= 600
    }

    private fun EpubComputedStyle.isItalic(): Boolean {
        val value = this["font-style"]?.lowercase(Locale.ROOT) ?: return false
        return value == "italic" || value == "oblique"
    }

    private fun Size?.scaledHeight(width: Float): Float {
        if (this == null || this.width <= 0 || this.height <= 0 || width <= 0f) {
            return ChapterProvider.contentPaintTextHeight
        }
        return this.height * width / this.width
    }

    private fun String.toCssLengthPx(relativeTo: Float): Float? {
        val clean = trim().lowercase(Locale.ROOT)
        if (clean.isBlank() || clean == "auto") return null
        return when {
            clean.endsWith("%") -> clean.dropLast(1).toFloatOrNull()?.let { relativeTo * it / 100f }
            clean.endsWith("em") -> clean.dropLast(2).toFloatOrNull()?.let { basePaint.textSize * it }
            clean.endsWith("rem") -> clean.dropLast(3).toFloatOrNull()?.let { basePaint.textSize * it }
            clean.endsWith("px") -> clean.dropLast(2).toFloatOrNull()
            else -> clean.toFloatOrNull()
        }
    }

    private fun String.extractCssLength(): String? {
        return trim().split(' ', '\t', '\n')
            .map { it.trim() }
            .firstOrNull { value ->
                value.endsWith("px", true) || value.endsWith("em", true) ||
                    value.endsWith("rem", true) || value.toFloatOrNull() != null
            }
    }

    private fun String.extractCssColor(): String? {
        val clean = trim()
        if (clean.startsWith("#") || clean.startsWith("rgb", true)) return clean
        val parts = clean.split(' ', ',', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return parts.firstOrNull { it.startsWith("#") || it.startsWith("rgb", true) || it.toNamedCssColor() != null }
    }

    private fun String.toCssColor(): Int? {
        val clean = trim()
        return when {
            clean.startsWith("rgba", true) || clean.startsWith("rgb", true) -> clean.parseRgbCssColor()
            clean.startsWith("#") -> runCatching { Color.parseColor(clean.normalizeHexColor()) }.getOrNull()
            else -> clean.toNamedCssColor()?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        }
    }

    private fun String.toEpubPageColor(): Int? {
        val clean = trim().removePrefix("#")
        val hex = when (clean.length) {
            6 -> "FF$clean"
            8 -> clean
            else -> return toCssColor()
        }
        return hex.toLongOrNull(16)?.toInt()
    }

    private fun String.normalizeHexColor(): String {
        val hex = trim().removePrefix("#")
        return when (hex.length) {
            3 -> "#" + hex.map { "$it$it" }.joinToString("")
            4 -> "#" + hex.map { "$it$it" }.joinToString("")
            else -> "#$hex"
        }
    }

    private fun String.parseRgbCssColor(): Int? {
        val start = indexOf('(')
        val end = lastIndexOf(')')
        if (start < 0 || end <= start) return null
        val parts = substring(start + 1, end)
            .split(',', ' ', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size < 3) return null
        fun component(value: String): Int {
            return if (value.endsWith("%")) {
                ((value.dropLast(1).toFloatOrNull() ?: 0f) * 2.55f).toInt()
            } else {
                value.toFloatOrNull()?.toInt() ?: 0
            }.coerceIn(0, 255)
        }
        val alpha = parts.getOrNull(3)?.let { value ->
            if (value.endsWith("%")) {
                ((value.dropLast(1).toFloatOrNull() ?: 100f) * 2.55f).toInt()
            } else {
                ((value.toFloatOrNull() ?: 1f) * 255f).toInt()
            }
        } ?: 255
        return Color.argb(alpha.coerceIn(0, 255), component(parts[0]), component(parts[1]), component(parts[2]))
    }

    private fun String.toNamedCssColor(): String? {
        return when (lowercase(Locale.ROOT)) {
            "black" -> "#000000"
            "white" -> "#FFFFFF"
            "red" -> "#FF0000"
            "green" -> "#008000"
            "blue" -> "#0000FF"
            "cyan", "aqua" -> "#00FFFF"
            "magenta", "fuchsia" -> "#FF00FF"
            "yellow" -> "#FFFF00"
            "gray", "grey" -> "#808080"
            "silver" -> "#C0C0C0"
            "maroon" -> "#800000"
            "purple" -> "#800080"
            "teal" -> "#008080"
            "navy" -> "#000080"
            "orange" -> "#FFA500"
            "transparent" -> "#00000000"
            else -> null
        }
    }

    private data class BlockStyle(
        val backgroundColor: Int?,
        val borderColor: Int?,
        val radius: Float
    )

    private companion object {
        val blockTags = setOf(
            "address", "article", "aside", "blockquote", "body", "center", "dd", "details",
            "dialog", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer",
            "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main",
            "nav", "ol", "p", "pre", "section", "table", "tbody", "td", "tfoot", "th",
            "thead", "tr", "ul"
        )
    }
}
