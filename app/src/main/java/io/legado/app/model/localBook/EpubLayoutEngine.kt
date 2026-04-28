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
    private var pageHasFullBackground = false

    fun layout(document: EpubDomDocument): EpubLayoutDocument {
        pages.clear()
        currentCommands = arrayListOf()
        cursorY = 0f
        firstLineIndent = 0f
        pageHasFullBackground = false
        val boxDocument = EpubBoxBuilder().build(document)
        layoutBlockNode(boxDocument.root, left = 0f, width = viewportWidth.toFloat())
        flushPageIfNeeded(force = true)
        return EpubLayoutDocument(
            href = document.href,
            pages = pages.mapIndexed { index, commands ->
                EpubLayoutPage(index = index, commands = commands, height = viewportHeight.toFloat())
            }
        )
    }

    private fun layoutBoxNode(node: EpubBoxNode, left: Float, width: Float) {
        when (node) {
            is EpubBlockNode -> layoutBlockNode(node, left, width)
            is EpubImageNode -> layoutImageNode(node, left, width)
            is EpubPageColorNode -> layoutPageColorNode(node)
            is EpubRuleNode -> layoutRuleNode(node, left, width)
            is EpubBreakNode -> cursorY += lineHeight(node.style)
            is EpubInlineNode -> layoutInlineItems(collectInlineItems(node), node.style, left, width)
            is EpubTextNode -> layoutInlineItems(listOf(InlineText(node.text, node.style, node.sourcePath)), node.style, left, width)
        }
    }

    private fun layoutBlockNode(node: EpubBlockNode, left: Float, width: Float) {
        val style = node.style
        val marginTop = style.verticalLengthPx("margin-top", width)
        val marginBottom = style.verticalLengthPx("margin-bottom", width)
        val paddingTop = style.verticalLengthPx("padding-top", width)
        val paddingBottom = style.verticalLengthPx("padding-bottom", width)
        val paddingLeft = style.lengthPx("padding-left", width)
        val paddingRight = style.lengthPx("padding-right", width)
        val marginLeft = style.lengthPx("margin-left", width)
        val marginRight = style.lengthPx("margin-right", width)
        val borderWidth = style.borderWidthPx()
        cursorY += marginTop
        var boxTop = cursorY
        val listMarkerWidth = if (node.tagName == "li") basePaint.textSize * 1.2f else 0f
        val contentLeft = left + marginLeft + borderWidth + paddingLeft + listMarkerWidth
        val contentWidth = (width - marginLeft - marginRight - borderWidth * 2 - paddingLeft - paddingRight - listMarkerWidth)
            .coerceAtLeast(width * 0.35f)
        val requestedHeight = style.verticalLengthPx("height", width)
            .takeIf { it > 0f }
            ?: style.verticalLengthPx("min-height", width).takeIf { it > 0f }
        if (requestedHeight != null && boxTop + requestedHeight > viewportHeight && currentCommands.isNotEmpty()) {
            flushPageIfNeeded(force = true)
            cursorY += marginTop
            boxTop = cursorY
        }
        cursorY += borderWidth + paddingTop
        val blockCommandIndex = currentCommands.size
        val blockStyle = style.blockStyle()
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
                    sourcePath = node.sourcePath
                )
            )
        }
        if (node.tagName == "li") {
            currentCommands.add(
                EpubBullet(
                    text = "•",
                    x = left + marginLeft + borderWidth + paddingLeft,
                    baseline = cursorY + style.fontSizePx(),
                    size = style.fontSizePx(),
                    color = style.colorInt(),
                    sourcePath = node.sourcePath
                )
            )
        }
        val previousTextIndent = firstLineIndent
        firstLineIndent = style.lengthPx("text-indent", contentWidth)
        val inlineItems = arrayListOf<InlineItem>()
        fun flushInlineItems() {
            if (inlineItems.isEmpty()) return
            layoutInlineItems(inlineItems.toList(), style, contentLeft, contentWidth)
            inlineItems.clear()
        }
        node.children.forEach { child ->
            when (child) {
                is EpubTextNode,
                is EpubInlineNode,
                is EpubBreakNode -> inlineItems.addAll(collectInlineItems(child))
                else -> {
                    flushInlineItems()
                    layoutBoxNode(child, contentLeft, contentWidth)
                }
            }
        }
        flushInlineItems()
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
        flushPageIfNeeded()
    }

    private fun collectInlineItems(node: EpubBoxNode): List<InlineItem> {
        val items = arrayListOf<InlineItem>()
        fun collect(current: EpubBoxNode) {
            when (current) {
                is EpubTextNode -> items.add(InlineText(current.text, current.style, current.sourcePath))
                is EpubBreakNode -> items.add(InlineBreak(current.style))
                is EpubInlineNode -> current.children.forEach(::collect)
                is EpubImageNode -> items.add(InlineImage(current))
                else -> Unit
            }
        }
        collect(node)
        return items
    }

    private fun layoutInlineItems(
        items: List<InlineItem>,
        parentStyle: EpubComputedStyle,
        left: Float,
        width: Float
    ) {
        if (items.isEmpty()) return
        val lineSegments = arrayListOf<LineSegment>()
        var lineWidth = 0f
        var currentLineHeight = lineHeight(parentStyle)
        var lineLeft = left + firstLineIndent
        var lineWidthLimit = (width - firstLineIndent).coerceAtLeast(width * 0.35f)

        fun flushLine(force: Boolean = false) {
            if (lineSegments.isEmpty() && !force) return
            if (lineSegments.isNotEmpty()) {
                addInlineLine(
                    segments = lineSegments.toList(),
                    left = lineLeft,
                    top = cursorY,
                    lineWidth = lineWidth,
                    availableWidth = lineWidthLimit,
                    lineHeight = currentLineHeight,
                    alignStyle = parentStyle
                )
            }
            cursorY += currentLineHeight
            flushPageIfNeedForHeight(cursorY + lineHeight(parentStyle))
            lineSegments.clear()
            lineWidth = 0f
            currentLineHeight = lineHeight(parentStyle)
            lineLeft = left
            lineWidthLimit = width
            firstLineIndent = 0f
        }

        items.forEach { item ->
            when (item) {
                is InlineBreak -> flushLine(force = true)
                is InlineImage -> {
                    flushLine()
                    layoutImageNode(item.image, left, width)
                }
                is InlineText -> {
                    val normalized = item.normalizedText()
                    if (normalized.isBlank()) return@forEach
                    val paint = item.style.toTextPaint()
                    val itemLineHeight = lineHeight(item.style)
                    normalized.forEach { char ->
                        val value = char.toString()
                        val charWidth = paint.measureText(value)
                        if (lineSegments.isNotEmpty() && lineWidth + charWidth > lineWidthLimit) {
                            flushLine()
                        }
                        lineSegments.add(
                            LineSegment(
                                text = value,
                                width = charWidth,
                                size = paint.textSize,
                                color = item.style.colorInt(),
                                bold = item.style.isBold(),
                                italic = item.style.isItalic(),
                                underline = item.style.hasTextDecoration("underline"),
                                strikeThrough = item.style.hasTextDecoration("line-through"),
                                baselineShift = item.style.baselineShiftPx(),
                                sourcePath = item.sourcePath
                            )
                        )
                        lineWidth += charWidth
                        currentLineHeight = maxOf(currentLineHeight, itemLineHeight)
                    }
                }
            }
        }
        flushLine()
    }

    private fun addInlineLine(
        segments: List<LineSegment>,
        left: Float,
        top: Float,
        lineWidth: Float,
        availableWidth: Float,
        lineHeight: Float,
        alignStyle: EpubComputedStyle
    ) {
        var x = when (alignStyle["text-align"]?.lowercase(Locale.ROOT)) {
            "center" -> left + ((availableWidth - lineWidth) / 2f).coerceAtLeast(0f)
            "right", "end" -> left + (availableWidth - lineWidth).coerceAtLeast(0f)
            else -> left
        }
        val baseline = top + (lineHeight * 0.82f)
        val merged = arrayListOf<LineSegment>()
        segments.forEach { segment ->
            val last = merged.lastOrNull()
            if (last != null &&
                last.size == segment.size &&
                last.color == segment.color &&
                last.bold == segment.bold &&
                last.italic == segment.italic &&
                last.underline == segment.underline &&
                last.strikeThrough == segment.strikeThrough &&
                last.baselineShift == segment.baselineShift &&
                last.sourcePath == segment.sourcePath
            ) {
                merged[merged.lastIndex] = last.copy(text = last.text + segment.text, width = last.width + segment.width)
            } else {
                merged.add(segment)
            }
        }
        merged.forEach { segment ->
            currentCommands.add(
                EpubTextRun(
                    text = segment.text,
                    x = x,
                    baseline = baseline,
                    size = segment.size,
                    color = segment.color,
                    bold = segment.bold,
                    italic = segment.italic,
                    underline = segment.underline,
                    strikeThrough = segment.strikeThrough,
                    baselineShift = segment.baselineShift,
                    sourcePath = segment.sourcePath
                )
            )
            x += segment.width
        }
    }

    private fun layoutPageColorNode(node: EpubPageColorNode) {
        node.colorValue.toEpubPageColor()?.let { color ->
            if (currentCommands.isNotEmpty()) flushPageIfNeeded(force = true)
            currentCommands.add(EpubPageColor(color = color, sourcePath = node.sourcePath))
        }
    }

    private fun layoutImageNode(node: EpubImageNode, left: Float, width: Float) {
        if (node.isBackground) {
            if (currentCommands.any { it !is EpubPageColor }) {
                flushPageIfNeeded(force = true)
            }
            currentCommands.add(
                EpubImageBox(
                    src = node.src,
                    x = 0f,
                    y = 0f,
                    width = viewportWidth.toFloat(),
                    height = viewportHeight.toFloat(),
                    isBackground = true,
                    sourcePath = node.sourcePath
                )
            )
            pageHasFullBackground = true
            return
        }
        val imageWidth = node.style.lengthPx("width", width)
            .takeIf { it > 0f }
            ?: node.attributes["data-legado-width"]?.toCssLengthPx(width)
            ?: node.attributes["width"]?.toCssLengthPx(width)
            ?: width
        val imageHeight = node.style.lengthPx("height", width)
            .takeIf { it > 0f }
            ?: node.attributes["height"]?.toCssLengthPx(width)
            ?: imageSizeResolver(node.src).scaledHeight(imageWidth)
        flushPageIfNeedForHeight(cursorY + imageHeight)
        currentCommands.add(
            EpubImageBox(
                src = node.src,
                x = left + ((width - imageWidth) / 2f).coerceAtLeast(0f),
                y = cursorY,
                width = imageWidth.coerceAtLeast(1f),
                height = imageHeight.coerceAtLeast(1f),
                isBackground = false,
                sourcePath = node.sourcePath
            )
        )
        cursorY += imageHeight
    }

    private fun layoutRuleNode(node: EpubRuleNode, left: Float, width: Float) {
        val marginTop = node.style.lengthPx("margin-top", width).takeIf { it > 0f } ?: lineHeight(node.style) * 0.5f
        val marginBottom = node.style.lengthPx("margin-bottom", width).takeIf { it > 0f } ?: lineHeight(node.style) * 0.5f
        val strokeWidth = node.style.borderWidthPx().takeIf { it > 0f } ?: 1f
        flushPageIfNeedForHeight(cursorY + marginTop + strokeWidth + marginBottom)
        cursorY += marginTop
        currentCommands.add(
            EpubRuleLine(
                x = left,
                y = cursorY,
                width = width,
                strokeWidth = strokeWidth,
                color = node.style.borderColor() ?: node.style.colorInt(),
                sourcePath = node.sourcePath
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
        pageHasFullBackground = false
    }

    private fun lineHeight(style: EpubComputedStyle): Float {
        val fontSize = style.fontSizePx()
        return style["line-height"]?.toLineHeightPx(fontSize)?.takeIf { it > 0f } ?: (fontSize * 1.35f)
    }

    private fun EpubComputedStyle.blockStyle(): BlockStyle? {
        val backgroundColor = colorInt("background-color") ?: backgroundColor()
        val borderColor = colorInt("border-color") ?: borderColor()
        if (backgroundColor == null && borderColor == null) return null
        return BlockStyle(
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            radius = lengthPx("border-radius", viewportWidth.toFloat())
        )
    }

    private fun EpubComputedStyle.toTextPaint(): TextPaint {
        return TextPaint(basePaint).apply {
            textSize = fontSizePx()
            isFakeBoldText = isBold()
            textSkewX = if (isItalic()) -0.25f else 0f
        }
    }

    private fun EpubComputedStyle.fontSizePx(): Float {
        val value = this["font-size"] ?: return basePaint.textSize
        val clean = value.trim().lowercase(Locale.ROOT)
        return when {
            clean.endsWith("%") -> clean.dropLast(1).toFloatOrNull()
                ?.let { basePaint.textSize * it / 100f }
            clean.endsWith("em") -> clean.dropLast(2).toFloatOrNull()
                ?.let { basePaint.textSize * it }
            clean.endsWith("rem") -> clean.dropLast(3).toFloatOrNull()
                ?.let { basePaint.textSize * it }
            clean == "xx-small" -> basePaint.textSize * 0.58f
            clean == "x-small" -> basePaint.textSize * 0.68f
            clean == "small" -> basePaint.textSize * 0.82f
            clean == "medium" -> basePaint.textSize
            clean == "large" -> basePaint.textSize * 1.18f
            clean == "x-large" -> basePaint.textSize * 1.36f
            clean == "xx-large" -> basePaint.textSize * 1.55f
            clean == "smaller" -> basePaint.textSize * 0.85f
            clean == "larger" -> basePaint.textSize * 1.18f
            clean.endsWith("pt") -> clean.dropLast(2).toFloatOrNull()
                ?.let { basePaint.textSize * it / 12f }
            clean.endsWith("px") -> clean.dropLast(2).toFloatOrNull()
                ?.let { basePaint.textSize * (it / 16f).coerceIn(0.55f, 2.2f) }
            else -> clean.toFloatOrNull()
                ?.let { size -> basePaint.textSize * (size / 16f).coerceIn(0.55f, 2.2f) }
        } ?: basePaint.textSize
    }

    private fun EpubComputedStyle.lengthPx(name: String, relativeTo: Float): Float {
        return this[name]?.toCssLengthPx(relativeTo) ?: 0f
    }

    private fun EpubComputedStyle.verticalLengthPx(name: String, width: Float): Float {
        val value = this[name] ?: return 0f
        val relativeTo = if (pageHasFullBackground && value.trim().endsWith("%")) {
            viewportHeight.toFloat()
        } else {
            width
        }
        return value.toCssLengthPx(relativeTo) ?: 0f
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

    private fun EpubComputedStyle.hasTextDecoration(name: String): Boolean {
        return this["text-decoration"]?.lowercase(Locale.ROOT)?.contains(name) == true ||
            this["text-decoration-line"]?.lowercase(Locale.ROOT)?.contains(name) == true
    }

    private fun EpubComputedStyle.baselineShiftPx(): Float {
        return when (this["vertical-align"]?.trim()?.lowercase(Locale.ROOT)) {
            "super", "top", "text-top" -> -fontSizePx() * 0.35f
            "sub", "bottom", "text-bottom" -> fontSizePx() * 0.25f
            "middle" -> -fontSizePx() * 0.12f
            else -> 0f
        }
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

    private fun String.toLineHeightPx(fontSize: Float): Float? {
        val clean = trim().lowercase(Locale.ROOT)
        if (clean.isBlank() || clean == "normal") return null
        return when {
            clean.endsWith("%") -> clean.dropLast(1).toFloatOrNull()?.let { fontSize * it / 100f }
            clean.endsWith("em") -> clean.dropLast(2).toFloatOrNull()?.let { fontSize * it }
            clean.endsWith("rem") -> clean.dropLast(3).toFloatOrNull()?.let { basePaint.textSize * it }
            clean.endsWith("px") -> clean.dropLast(2).toFloatOrNull()
            else -> clean.toFloatOrNull()?.let { value ->
                if (value in 0f..8f) fontSize * value else value
            }
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

    private sealed class InlineItem

    private data class InlineText(
        val rawText: String,
        val style: EpubComputedStyle,
        val sourcePath: String
    ) : InlineItem() {
        fun normalizedText(): String {
            return if (style["white-space"]?.contains("pre") == true) {
                rawText.replace("\r", "")
            } else {
                rawText.replace(Regex("\\s+"), " ")
            }
        }
    }

    private data class InlineImage(
        val image: EpubImageNode
    ) : InlineItem()

    private data class InlineBreak(
        val style: EpubComputedStyle
    ) : InlineItem()

    private data class LineSegment(
        val text: String,
        val width: Float,
        val size: Float,
        val color: Int?,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strikeThrough: Boolean,
        val baselineShift: Float,
        val sourcePath: String
    )

}
