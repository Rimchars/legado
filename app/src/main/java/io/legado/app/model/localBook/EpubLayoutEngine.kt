package io.legado.app.model.localBook

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Size
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import java.util.Locale

internal class EpubLayoutEngine(
    private val imageSizeResolver: (String) -> Size? = { null },
    private val fontResolver: (String, Boolean, Boolean, List<EpubFontFace>) -> Typeface? = { _, _, _, _ -> null },
    private val viewportWidth: Int = ChapterProvider.visibleWidth,
    private val viewportHeight: Int = ChapterProvider.visibleHeight,
    private val basePaint: TextPaint = ChapterProvider.contentPaint
) {

    private val pages = arrayListOf<MutableList<EpubDrawCommand>>()
    private var currentCommands = arrayListOf<EpubDrawCommand>()
    private var cursorY = 0f
    private var firstLineIndent = 0f
    private var pageHasFullBackground = false
    private val activeBlockStack = arrayListOf<ActiveBlockDecoration>()
    private var documentFontFaces: List<EpubFontFace> = emptyList()

    fun layout(document: EpubDomDocument): EpubLayoutDocument {
        pages.clear()
        currentCommands = arrayListOf()
        cursorY = 0f
        firstLineIndent = 0f
        pageHasFullBackground = false
        activeBlockStack.clear()
        documentFontFaces = document.fontFaces
        val boxDocument = EpubBoxBuilder().build(document)
        layoutBlockNode(boxDocument.root, left = 0f, width = viewportWidth.toFloat())
        flushPageIfNeeded(force = true)
        val snapshotId = buildLayoutSnapshotId(document.href)
        return EpubLayoutDocument(
            href = document.href,
            snapshotId = snapshotId,
            pages = pages.mapIndexed { index, commands ->
                EpubLayoutPage(
                    index = index,
                    commands = commands,
                    height = viewportHeight.toFloat(),
                    snapshotId = snapshotId
                )
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
        if (node.tagName == "table") {
            layoutTableNode(node, left, width)
            return
        }
        val style = node.style
        if (style.shouldForceBreakBefore() && currentCommands.isNotEmpty()) {
            flushPageIfNeeded(force = true)
        }
        if (style.shouldAvoidBreakInside() && currentCommands.isNotEmpty()) {
            val estimatedHeight = estimateBlockHeight(node, width)
            val remainingHeight = (viewportHeight - cursorY).coerceAtLeast(0f)
            if (estimatedHeight > remainingHeight && estimatedHeight < viewportHeight * 0.92f) {
                flushPageIfNeeded(force = true)
            }
        }
        val marginTop = style.verticalLengthPx("margin-top", width)
        val marginBottom = style.verticalLengthPx("margin-bottom", width)
        val paddingTop = style.verticalLengthPx("padding-top", width)
        val paddingBottom = style.verticalLengthPx("padding-bottom", width)
        val paddingLeft = style.lengthPx("padding-left", width)
        val paddingRight = style.lengthPx("padding-right", width)
        val requestedWidth = style.resolveHorizontalSize(width)
        val rawMarginLeft = style["margin-left"]
        val rawMarginRight = style["margin-right"]
        val marginLeftValue = rawMarginLeft?.toCssLengthPx(width) ?: 0f
        val marginRightValue = rawMarginRight?.toCssLengthPx(width) ?: 0f
        val borderWidth = style.borderWidthPx()
        cursorY += marginTop
        var boxTop = cursorY
        val listMarker = if (node.tagName == "li") style.listMarkerText() else null
        val listMarkerWidth = if (listMarker != null) basePaint.textSize * 1.2f else 0f
        val availableWidth = (width - listMarkerWidth).coerceAtLeast(1f)
        val outerWidth = requestedWidth
            ?.plus(borderWidth * 2)
            ?.plus(paddingLeft)
            ?.plus(paddingRight)
            ?.coerceAtMost(availableWidth)
            ?: (width - marginLeftValue - marginRightValue - listMarkerWidth)
        val remainingWidth = (width - outerWidth - listMarkerWidth).coerceAtLeast(0f)
        val marginLeft = when {
            rawMarginLeft.isAutoCssValue() && rawMarginRight.isAutoCssValue() -> remainingWidth / 2f
            rawMarginLeft.isAutoCssValue() -> remainingWidth
            else -> marginLeftValue
        }
        val marginRight = when {
            rawMarginLeft.isAutoCssValue() && rawMarginRight.isAutoCssValue() -> remainingWidth / 2f
            rawMarginRight.isAutoCssValue() -> remainingWidth
            else -> marginRightValue
        }
        val relativeOffsetX = style.relativeOffsetX(width)
        val relativeOffsetY = style.relativeOffsetY(viewportHeight.toFloat())
        val visualLeft = left + relativeOffsetX
        var visualBoxTop = boxTop + relativeOffsetY
        val contentLeft = visualLeft + marginLeft + borderWidth + paddingLeft + listMarkerWidth
        val contentWidth = (outerWidth - borderWidth * 2 - paddingLeft - paddingRight)
            .coerceAtLeast(1f)
        val requestedHeight = style.resolveVerticalSize(width)
        if (requestedHeight != null && boxTop + requestedHeight > viewportHeight && currentCommands.isNotEmpty()) {
            flushPageIfNeeded(force = true)
            cursorY += marginTop
            boxTop = cursorY
            visualBoxTop = boxTop + relativeOffsetY
        }
        cursorY += borderWidth + paddingTop
        val blockCommandIndex = currentCommands.size
        val blockStyle = style.blockStyle()
        if (blockStyle != null) {
            currentCommands.add(
                EpubBlockBox(
                    x = visualLeft + marginLeft,
                    y = visualBoxTop,
                    width = outerWidth,
                    height = 0f,
                    clipTop = false,
                    clipBottom = false,
                    backgroundColor = blockStyle.backgroundColor,
                    borderColor = blockStyle.borderColor,
                    borderWidth = borderWidth,
                    borderStyle = blockStyle.borderStyle,
                    border = blockStyle.border,
                    radius = blockStyle.radius,
                    shadow = blockStyle.shadow,
                    sourcePath = node.sourcePath
                )
            )
            activeBlockStack.add(
                ActiveBlockDecoration(
                    left = visualLeft + marginLeft,
                    width = outerWidth,
                    style = blockStyle,
                    sourcePath = node.sourcePath
                ).also { it.openCommandIndex = blockCommandIndex }
            )
        }
        if (listMarker != null) {
            currentCommands.add(
                EpubBullet(
                    text = listMarker,
                    x = visualLeft + marginLeft + borderWidth + paddingLeft,
                    baseline = cursorY + relativeOffsetY + style.fontSizePx(),
                    size = style.fontSizePx(),
                    color = style.colorInt(),
                    sourcePath = node.sourcePath
                )
            )
        }
        val contentCommandStartIndex = currentCommands.size
        val previousTextIndent = firstLineIndent
        if (relativeOffsetY != 0f) {
            cursorY += relativeOffsetY
        }
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
                    if (child.isOutOfFlowPositioned()) {
                        layoutPositionedNode(child, contentLeft, contentWidth, boxTop)
                    } else {
                        layoutBoxNode(child, contentLeft, contentWidth)
                    }
                }
            }
        }
        flushInlineItems()
        if (blockStyle != null && !currentCommands.hasVisibleContentAfter(contentCommandStartIndex)) {
            val fallbackText = node.plainText()
            if (fallbackText.isNotBlank()) {
                layoutInlineItems(
                    items = listOf(InlineText(fallbackText, style, node.sourcePath)),
                    parentStyle = style,
                    left = contentLeft,
                    width = contentWidth
                )
            }
        }
        firstLineIndent = previousTextIndent
        if (relativeOffsetY != 0f) {
            cursorY -= relativeOffsetY
        }
        cursorY += paddingBottom + borderWidth
        if (blockStyle != null) {
            val active = activeBlockStack.removeLastOrNull()
            val index = active?.openCommandIndex ?: blockCommandIndex
            val old = currentCommands.getOrNull(index) as? EpubBlockBox
            if (old != null) {
                val boxHeight = requestedHeight ?: (cursorY - boxTop)
                val height = if (old.clipTop) cursorY.coerceAtLeast(0f) else boxHeight.coerceAtLeast(0f)
                currentCommands[index] = old.copy(height = height)
            }
        }
        requestedHeight?.let { height ->
            if (cursorY < boxTop + height) {
                cursorY = boxTop + height
            }
        }
        cursorY += marginBottom
        if (style.shouldForceBreakAfter()) {
            flushPageIfNeeded(force = true)
            return
        }
        flushPageIfNeeded()
    }

    private fun layoutPositionedNode(
        node: EpubBoxNode,
        containingLeft: Float,
        containingWidth: Float,
        containingTop: Float
    ) {
        val style = node.style
        val oldCursorY = cursorY
        val oldFirstLineIndent = firstLineIndent
        val oldPageHasFullBackground = pageHasFullBackground
        val positionedWidth = style.resolveHorizontalSize(containingWidth)
            ?: style["width"]?.toCssLengthPx(containingWidth)
            ?: containingWidth
        val leftOffset = style["left"]?.toCssLengthPx(containingWidth)
        val rightOffset = style["right"]?.toCssLengthPx(containingWidth)
        val topOffset = style.verticalPositionPx("top", viewportHeight.toFloat())
        val bottomOffset = style.verticalPositionPx("bottom", viewportHeight.toFloat())
        val position = style["position"]?.trim()?.lowercase(Locale.ROOT)
        val baseTop = if (position == "fixed" || pageHasFullBackground) 0f else containingTop
        val x = when {
            leftOffset != null -> containingLeft + leftOffset
            rightOffset != null -> containingLeft + containingWidth - rightOffset - positionedWidth
            else -> containingLeft + style.lengthPx("margin-left", containingWidth)
        }
        val y = when {
            topOffset != null -> baseTop + topOffset
            bottomOffset != null -> viewportHeight - bottomOffset -
                (style.resolveVerticalSize(containingWidth) ?: estimateBlockHeight(node.asBlockNode(), positionedWidth))
            else -> oldCursorY
        }.coerceAtLeast(0f)
        cursorY = y
        firstLineIndent = 0f
        pageHasFullBackground = oldPageHasFullBackground
        layoutBoxNode(node, x, positionedWidth.coerceAtLeast(1f))
        cursorY = oldCursorY
        firstLineIndent = oldFirstLineIndent
        pageHasFullBackground = oldPageHasFullBackground
    }

    private fun layoutTableNode(node: EpubBlockNode, left: Float, width: Float) {
        val style = node.style
        val marginTop = style.verticalLengthPx("margin-top", width)
        val marginBottom = style.verticalLengthPx("margin-bottom", width)
        cursorY += marginTop
        flushPageIfNeedForHeight(cursorY + lineHeight(style))

        val rows = node.tableRows()
        val maxColumns = rows.maxOfOrNull { row -> row.tableCells().size } ?: 0
        val intrinsicWidth = node.intrinsicTableWidth(rows)
        val rawMarginLeft = style["margin-left"]
        val rawMarginRight = style["margin-right"]
        val marginLeftValue = rawMarginLeft?.toCssLengthPx(width) ?: 0f
        val marginRightValue = rawMarginRight?.toCssLengthPx(width) ?: 0f
        val requestedWidth = style.resolveHorizontalSize(width)
            ?: node.attributes["width"]?.toCssLengthPx(width)
            ?: intrinsicWidth
                .takeIf { it > 0f && (rawMarginLeft.isAutoCssValue() || rawMarginRight.isAutoCssValue()) }
            ?: (width - marginLeftValue - marginRightValue)
        val tableWidth = requestedWidth.coerceIn(1f, width)
        val remainingWidth = (width - tableWidth - marginLeftValue - marginRightValue).coerceAtLeast(0f)
        val marginLeft = when {
            rawMarginLeft.isAutoCssValue() && rawMarginRight.isAutoCssValue() -> remainingWidth / 2f
            rawMarginLeft.isAutoCssValue() -> remainingWidth
            else -> marginLeftValue
        }
        val tableLeft = left + marginLeft
        if (rows.isEmpty() || maxColumns <= 0) {
            node.children.forEach { child -> layoutBoxNode(child, tableLeft, tableWidth) }
            cursorY += marginBottom
            flushPageIfNeeded()
            return
        }

        val tableTop = cursorY
        val blockCommandIndex = style.blockStyle()?.let { blockStyle ->
            currentCommands.add(
                EpubBlockBox(
                    x = tableLeft,
                    y = tableTop,
                    width = tableWidth,
                    height = 0f,
                    clipTop = false,
                    clipBottom = false,
                    backgroundColor = blockStyle.backgroundColor,
                    borderColor = blockStyle.borderColor,
                    borderWidth = style.borderWidthPx(),
                    borderStyle = blockStyle.borderStyle,
                    border = blockStyle.border,
                    radius = blockStyle.radius,
                    shadow = blockStyle.shadow,
                    sourcePath = node.sourcePath
                )
            )
            currentCommands.lastIndex
        }
        val columnWidths = node.tableColumnWidths(rows, tableWidth, maxColumns)
        val rowSpanDebt = MutableList(maxColumns) { 0 }
        rows.forEach { row ->
            if (cursorY + lineHeight(row.style) > viewportHeight && currentCommands.isNotEmpty()) {
                flushPageIfNeeded(force = true)
            }
            val rowTop = cursorY
            var rowBottom = rowTop
            var cellLeft = tableLeft
            var columnIndex = 0
            row.tableCells().forEach { cell ->
                while (columnIndex < maxColumns && rowSpanDebt[columnIndex] > 0) {
                    cellLeft += columnWidths.getOrNull(columnIndex) ?: 0f
                    columnIndex++
                }
                val oldCursor = cursorY
                val span = cell.attributes["colspan"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val rowSpan = cell.attributes["rowspan"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val cellWidth = columnWidths
                    .drop(columnIndex)
                    .take(span)
                    .sum()
                    .takeIf { it > 0f }
                    ?: (tableWidth / maxColumns)
                cursorY = rowTop
                layoutBlockNode(
                    node = cell,
                    left = cellLeft,
                    width = cellWidth
                )
                rowBottom = maxOf(rowBottom, cursorY)
                cursorY = oldCursor
                cellLeft += cellWidth
                if (rowSpan > 1) {
                    for (index in columnIndex until (columnIndex + span).coerceAtMost(maxColumns)) {
                        rowSpanDebt[index] = maxOf(rowSpanDebt[index], rowSpan)
                    }
                }
                columnIndex += span
            }
            rowSpanDebt.indices.forEach { index ->
                if (rowSpanDebt[index] > 0) rowSpanDebt[index]--
            }
            cursorY = rowBottom
        }
        blockCommandIndex?.let { index ->
            val old = currentCommands.getOrNull(index) as? EpubBlockBox
            if (old != null) {
                currentCommands[index] = old.copy(height = (cursorY - tableTop).coerceAtLeast(0f))
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
                cursorY += currentLineHeight
            } else if (force) {
                cursorY += lineHeight(parentStyle) * 0.45f
            }
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
                    val inlineSize = item.image.inlineImageSize(width, currentLineHeight)
                    if (inlineSize != null) {
                        val (imageWidth, imageHeight) = inlineSize
                        if (lineSegments.isNotEmpty() && lineWidth + imageWidth > lineWidthLimit) {
                            flushLine()
                        }
                        lineSegments.add(
                            LineSegment(
                                text = "",
                                imageSrc = item.image.src,
                                width = imageWidth,
                                height = imageHeight,
                                size = imageHeight,
                                ascent = imageHeight,
                                descent = imageHeight * 0.2f,
                                color = null,
                                backgroundColor = null,
                                bold = false,
                                italic = false,
                                typeface = null,
                                underline = false,
                                overline = false,
                                strikeThrough = false,
                                decorationColor = null,
                                decorationStyle = null,
                                baselineShift = item.image.style.baselineShiftPx(),
                                backgroundRadius = 0f,
                                backgroundPaddingLeft = 0f,
                                backgroundPaddingTop = 0f,
                                backgroundPaddingRight = 0f,
                                backgroundPaddingBottom = 0f,
                                shadow = null,
                                sourcePath = item.image.sourcePath
                            )
                        )
                        lineWidth += imageWidth
                        currentLineHeight = maxOf(currentLineHeight, imageHeight * 1.2f)
                    } else {
                        flushLine()
                        layoutImageNode(item.image, left, width)
                    }
                }
                is InlineText -> {
                    val normalized = item.normalizedText().transformCssText(item.style)
                    if (normalized.isBlank()) return@forEach
                    val paint = item.style.toTextPaint()
                    val itemLineHeight = lineHeight(item.style)
                    val metrics = paint.fontMetrics
                    val ascent = -metrics.ascent
                    val descent = metrics.descent
                    normalized.forEach { char ->
                        val value = char.toString()
                        val charWidth = paint.measureText(value) + item.style.extraCharacterSpacing(value)
                        if (lineSegments.isNotEmpty() && lineWidth + charWidth > lineWidthLimit) {
                            flushLine()
                        }
                        lineSegments.add(
                            LineSegment(
                                text = value,
                                imageSrc = null,
                                width = charWidth,
                                height = itemLineHeight,
                                size = paint.textSize,
                                ascent = ascent,
                                descent = descent,
                                color = item.style.colorInt(),
                                backgroundColor = null,
                                bold = item.style.isBold(),
                                italic = item.style.isItalic(),
                                typeface = item.style.resolveTypeface(documentFontFaces),
                                underline = item.style.hasTextDecoration("underline"),
                                overline = item.style.hasTextDecoration("overline"),
                                strikeThrough = item.style.hasTextDecoration("line-through"),
                                decorationColor = item.style.colorInt("text-decoration-color"),
                                decorationStyle = item.style["text-decoration-style"]?.lowercase(Locale.ROOT),
                                baselineShift = item.style.baselineShiftPx(),
                                backgroundRadius = 0f,
                                backgroundPaddingLeft = 0f,
                                backgroundPaddingTop = 0f,
                                backgroundPaddingRight = 0f,
                                backgroundPaddingBottom = 0f,
                                shadow = item.style.textShadow(),
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
        var x = when (alignStyle["text-align"]?.trim()?.lowercase(Locale.ROOT)) {
            "center", "-webkit-center", "-moz-center" -> left + ((availableWidth - lineWidth) / 2f).coerceAtLeast(0f)
            "right", "end" -> left + (availableWidth - lineWidth).coerceAtLeast(0f)
            else -> left
        }
        val maxAscent = segments.maxOfOrNull { it.ascent.toDouble() }?.toFloat()
            ?: (lineHeight * 0.82f)
        val maxDescent = segments.maxOfOrNull { it.descent.toDouble() }?.toFloat()
            ?: (lineHeight * 0.18f)
        val contentHeight = maxAscent + maxDescent
        val lineBaseline = top + ((lineHeight - contentHeight) / 2f).coerceAtLeast(0f) + maxAscent
        val merged = arrayListOf<LineSegment>()
        segments.forEach { segment ->
            val last = merged.lastOrNull()
            if (last != null &&
                last.imageSrc == null &&
                segment.imageSrc == null &&
                last.size == segment.size &&
                last.color == segment.color &&
                last.backgroundColor == segment.backgroundColor &&
                last.bold == segment.bold &&
                last.italic == segment.italic &&
                last.typeface == segment.typeface &&
                last.underline == segment.underline &&
                last.overline == segment.overline &&
                last.strikeThrough == segment.strikeThrough &&
                last.decorationColor == segment.decorationColor &&
                last.decorationStyle == segment.decorationStyle &&
                last.baselineShift == segment.baselineShift &&
                last.backgroundRadius == segment.backgroundRadius &&
                last.backgroundPaddingLeft == segment.backgroundPaddingLeft &&
                last.backgroundPaddingTop == segment.backgroundPaddingTop &&
                last.backgroundPaddingRight == segment.backgroundPaddingRight &&
                last.backgroundPaddingBottom == segment.backgroundPaddingBottom &&
                last.shadow == segment.shadow
            ) {
                merged[merged.lastIndex] = last.copy(text = last.text + segment.text, width = last.width + segment.width)
            } else {
                merged.add(segment)
            }
        }
        merged.forEach { segment ->
            if (segment.imageSrc != null) {
                currentCommands.add(
                    EpubImageBox(
                        src = segment.imageSrc,
                        x = x,
                        y = lineBaseline - segment.ascent + segment.baselineShift,
                        width = segment.width,
                        height = segment.height,
                        isBackground = false,
                        sourcePath = segment.sourcePath
                    )
                )
            } else {
                currentCommands.add(
                    EpubTextRun(
                        text = segment.text,
                        x = x,
                        y = top,
                        baseline = lineBaseline - maxAscent + segment.ascent,
                        width = segment.width,
                        height = lineHeight,
                        size = segment.size,
                        color = segment.color,
                        backgroundColor = segment.backgroundColor,
                        bold = segment.bold,
                        italic = segment.italic,
                        typeface = segment.typeface,
                        underline = segment.underline,
                        overline = segment.overline,
                        strikeThrough = segment.strikeThrough,
                        decorationColor = segment.decorationColor,
                        decorationStyle = segment.decorationStyle,
                        baselineShift = segment.baselineShift,
                        backgroundRadius = segment.backgroundRadius,
                        backgroundPaddingLeft = segment.backgroundPaddingLeft,
                        backgroundPaddingTop = segment.backgroundPaddingTop,
                        backgroundPaddingRight = segment.backgroundPaddingRight,
                        backgroundPaddingBottom = segment.backgroundPaddingBottom,
                        shadow = segment.shadow,
                        sourcePath = segment.sourcePath
                    )
                )
            }
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
                    backgroundSize = node.style.backgroundSizeValue(),
                    backgroundPosition = node.style.backgroundPositionValue(),
                    backgroundRepeat = node.style.backgroundRepeatValue(),
                    objectFit = null,
                    objectPosition = null,
                    sourcePath = node.sourcePath
                )
            )
            pageHasFullBackground = true
            return
        }
        val rawImageWidth = node.style.lengthPx("width", width)
            .takeIf { it > 0f }
            ?: node.attributes["data-legado-width"]?.toCssLengthPx(width)
            ?: node.attributes["width"]?.toCssLengthPx(width)
            ?: width
        val rawImageHeight = node.style.lengthPx("height", width)
            .takeIf { it > 0f }
            ?: node.attributes["height"]?.toCssLengthPx(width)
            ?: imageSizeResolver(node.src).scaledHeight(rawImageWidth)
        val imageScale = if (rawImageWidth > width && rawImageWidth > 0f) {
            width / rawImageWidth
        } else {
            1f
        }
        val imageWidth = rawImageWidth * imageScale
        val imageHeight = rawImageHeight * imageScale
        flushPageIfNeedForHeight(cursorY + imageHeight)
        currentCommands.add(
            EpubImageBox(
                src = node.src,
                x = left + ((width - imageWidth) / 2f).coerceAtLeast(0f),
                y = cursorY,
                width = imageWidth.coerceAtLeast(1f),
                height = imageHeight.coerceAtLeast(1f),
                isBackground = false,
                objectFit = node.style["object-fit"],
                objectPosition = node.style["object-position"],
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
        closeActiveBlocksAtPageEnd()
        pages.add(currentCommands)
        currentCommands = arrayListOf()
        cursorY = 0f
        firstLineIndent = 0f
        pageHasFullBackground = false
        reopenActiveBlocksOnNextPage()
    }

    private fun closeActiveBlocksAtPageEnd() {
        if (activeBlockStack.isEmpty()) return
        currentCommands.indices.forEach { index ->
            val block = currentCommands[index] as? EpubBlockBox ?: return@forEach
            val active = activeBlockStack.lastOrNull { it.openCommandIndex == index } ?: return@forEach
            val height = (viewportHeight - block.y).coerceAtLeast(0f)
            currentCommands[index] = block.copy(height = height, clipBottom = true)
            active.openCommandIndex = null
        }
    }

    private fun reopenActiveBlocksOnNextPage() {
        if (activeBlockStack.isEmpty()) return
        activeBlockStack.forEach { active ->
            currentCommands.add(
                EpubBlockBox(
                    x = active.left,
                    y = 0f,
                    width = active.width,
                    height = 0f,
                    clipTop = true,
                    clipBottom = false,
                    backgroundColor = active.style.backgroundColor,
                    borderColor = active.style.borderColor,
                    borderWidth = active.style.borderWidth,
                    borderStyle = active.style.borderStyle,
                    border = active.style.border,
                    radius = active.style.radius,
                    shadow = active.style.shadow,
                    sourcePath = active.sourcePath
                )
            )
            active.openCommandIndex = currentCommands.lastIndex
        }
    }

    private fun lineHeight(style: EpubComputedStyle): Float {
        val fontSize = style.fontSizePx()
        return style["line-height"]?.toLineHeightPx(fontSize)?.takeIf { it > 0f } ?: (fontSize * 1.35f)
    }

    private fun EpubComputedStyle.blockStyle(): BlockStyle? {
        val backgroundColor = colorInt("background-color") ?: backgroundColor()
        val borderColor = colorInt("border-color") ?: borderColor()
        val borderStyle = borderStyle()
        val border = border()
        val shadow = boxShadow()
        val borderWidth = borderWidthPx()
        val hasVisibleBorder = border.hasVisibleBorder()
        if (backgroundColor == null && !hasVisibleBorder && shadow == null) return null
        return BlockStyle(
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            borderWidth = borderWidth,
            borderStyle = borderStyle,
            border = border,
            radius = border.radius.maxRadius(),
            shadow = shadow
        )
    }

    private fun EpubComputedStyle.toTextPaint(): TextPaint {
        return TextPaint(basePaint).apply {
            textSize = fontSizePx()
            isFakeBoldText = isBold()
            textSkewX = if (isItalic()) -0.25f else 0f
        }
    }

    private fun List<EpubDrawCommand>.hasVisibleContentAfter(index: Int): Boolean {
        if (index >= size) return false
        return drop(index).any { command ->
            command is EpubTextRun && command.text.isNotBlank() ||
                command is EpubImageBox && !command.isBackground ||
                command is EpubBullet && command.text.isNotBlank()
        }
    }

    private fun EpubBoxNode.plainText(): String {
        return when (this) {
            is EpubTextNode -> text
            is EpubBreakNode -> "\n"
            is EpubInlineNode -> children.joinToString("") { it.plainText() }
            is EpubBlockNode -> children.joinToString("") { it.plainText() }
            else -> ""
        }
    }

    private fun EpubBoxNode.isOutOfFlowPositioned(): Boolean {
        return style["position"]?.trim()?.lowercase(Locale.ROOT) in setOf("absolute", "fixed")
    }

    private fun EpubBoxNode.asBlockNode(): EpubBlockNode {
        return this as? EpubBlockNode ?: EpubBlockNode(
            tagName = "div",
            attributes = emptyMap(),
            style = style,
            children = listOf(this),
            sourcePath = sourcePath
        )
    }

    private fun estimateBlockHeight(node: EpubBlockNode, width: Float): Float {
        val style = node.style
        val borderWidth = style.borderWidthPx()
        val paddingVertical = style.verticalLengthPx("padding-top", width) +
            style.verticalLengthPx("padding-bottom", width)
        val marginVertical = style.verticalLengthPx("margin-top", width) +
            style.verticalLengthPx("margin-bottom", width)
        val contentWidth = (width -
            style.lengthPx("padding-left", width) -
            style.lengthPx("padding-right", width) -
            borderWidth * 2).coerceAtLeast(1f)
        val explicitHeight = style.resolveVerticalSize(width)
        if (explicitHeight != null) return explicitHeight + marginVertical
        var height = paddingVertical + borderWidth * 2 + marginVertical
        val text = node.plainText().trim()
        if (text.isNotBlank()) {
            val averageCharWidth = style.fontSizePx() * 0.58f
            val charsPerLine = (contentWidth / averageCharWidth).toInt().coerceAtLeast(1)
            val lineCount = ((text.length + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)
            height += lineCount * lineHeight(style)
        }
        node.children.filterIsInstance<EpubImageNode>().forEach { image ->
            val imageWidth = image.style.lengthPx("width", contentWidth)
                .takeIf { it > 0f }
                ?: image.attributes["data-legado-width"]?.toCssLengthPx(contentWidth)
                ?: image.attributes["width"]?.toCssLengthPx(contentWidth)
                ?: contentWidth
            height += imageSizeResolver(image.src).scaledHeight(imageWidth)
        }
        return height.coerceAtLeast(lineHeight(style))
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

    private fun EpubComputedStyle.verticalPositionPx(name: String, relativeTo: Float): Float? {
        return this[name]?.toCssLengthPx(relativeTo)
    }

    private fun EpubComputedStyle.relativeOffsetX(relativeTo: Float): Float {
        if (!this["position"].equals("relative", ignoreCase = true)) return 0f
        val left = this["left"]?.toCssLengthPx(relativeTo)
        val right = this["right"]?.toCssLengthPx(relativeTo)
        return left ?: right?.let { -it } ?: 0f
    }

    private fun EpubComputedStyle.relativeOffsetY(relativeTo: Float): Float {
        if (!this["position"].equals("relative", ignoreCase = true)) return 0f
        val top = this["top"]?.toCssLengthPx(relativeTo)
        val bottom = this["bottom"]?.toCssLengthPx(relativeTo)
        return top ?: bottom?.let { -it } ?: 0f
    }

    private fun EpubComputedStyle.borderWidthPx(): Float {
        if (borderStyle() == "none" || borderStyle() == "hidden") return 0f
        return maxOf(
            sideBorderWidthPx("top"),
            sideBorderWidthPx("right"),
            sideBorderWidthPx("bottom"),
            sideBorderWidthPx("left")
        )
    }

    private fun EpubComputedStyle.sideBorderWidthPx(side: String): Float {
        val sideStyle = sideBorderStyle(side)
        if (sideStyle == "none" || sideStyle == "hidden") return 0f
        return this["border-$side-width"]?.toCssBorderWidthPx(viewportWidth.toFloat())
            ?: this["border-width"]?.toCssBorderWidthPx(viewportWidth.toFloat())
            ?: this["border-$side"]?.extractCssLength()?.toCssLengthPx(viewportWidth.toFloat())
            ?: this["border"]?.extractCssLength()?.toCssLengthPx(viewportWidth.toFloat())
            ?: 0f
    }

    private fun EpubComputedStyle.colorInt(name: String = "color"): Int? {
        return this[name]?.toCssColor()?.withOpacity(opacity())
    }

    private fun EpubComputedStyle.backgroundColor(): Int? {
        return this["background"]?.extractCssColor()?.toCssColor()?.withOpacity(opacity())
    }

    private fun EpubComputedStyle.borderColor(): Int? {
        return (this["border"]?.extractCssColor()?.toCssColor()
            ?: this["border-top"]?.extractCssColor()?.toCssColor()
            ?: this["border-right"]?.extractCssColor()?.toCssColor()
            ?: this["border-bottom"]?.extractCssColor()?.toCssColor()
            ?: this["border-left"]?.extractCssColor()?.toCssColor()
            ?: this["border-top-color"]?.toCssColor()
            ?: this["border-right-color"]?.toCssColor()
            ?: this["border-bottom-color"]?.toCssColor()
            ?: this["border-left-color"]?.toCssColor())
            ?.withOpacity(opacity())
    }

    private fun EpubComputedStyle.sideBorderColor(side: String): Int? {
        return (this["border-$side-color"]?.toCssColor()
            ?: this["border-$side"]?.extractCssColor()?.toCssColor()
            ?: this["border-color"]?.toCssColor()
            ?: this["border"]?.extractCssColor()?.toCssColor())
            ?.withOpacity(opacity())
    }

    private fun EpubComputedStyle.sideBorderStyle(side: String): String? {
        return (this["border-$side-style"]
            ?: this["border-$side"]?.extractCssBorderStyle()
            ?: this["border-style"]
            ?: this["border"]?.extractCssBorderStyle())
            ?.lowercase(Locale.ROOT)
    }

    private fun EpubComputedStyle.border(): EpubBorder {
        return EpubBorder(
            top = EpubBorderSide(sideBorderWidthPx("top"), sideBorderColor("top"), sideBorderStyle("top")),
            right = EpubBorderSide(sideBorderWidthPx("right"), sideBorderColor("right"), sideBorderStyle("right")),
            bottom = EpubBorderSide(sideBorderWidthPx("bottom"), sideBorderColor("bottom"), sideBorderStyle("bottom")),
            left = EpubBorderSide(sideBorderWidthPx("left"), sideBorderColor("left"), sideBorderStyle("left")),
            radius = EpubBorderRadius(
                topLeft = lengthPx("border-top-left-radius", viewportWidth.toFloat())
                    .takeIf { it > 0f } ?: lengthPx("border-radius", viewportWidth.toFloat()),
                topRight = lengthPx("border-top-right-radius", viewportWidth.toFloat())
                    .takeIf { it > 0f } ?: lengthPx("border-radius", viewportWidth.toFloat()),
                bottomRight = lengthPx("border-bottom-right-radius", viewportWidth.toFloat())
                    .takeIf { it > 0f } ?: lengthPx("border-radius", viewportWidth.toFloat()),
                bottomLeft = lengthPx("border-bottom-left-radius", viewportWidth.toFloat())
                    .takeIf { it > 0f } ?: lengthPx("border-radius", viewportWidth.toFloat())
            )
        )
    }

    private fun EpubComputedStyle.inlinePadding(relativeTo: Float): InlinePadding {
        val padding = lengthPx("padding", relativeTo)
        val horizontal = lengthPx("padding-left", relativeTo)
            .takeIf { it > 0f }
            ?: lengthPx("padding-right", relativeTo)
                .takeIf { it > 0f }
            ?: padding
        val vertical = verticalLengthPx("padding-top", relativeTo)
            .takeIf { it > 0f }
            ?: verticalLengthPx("padding-bottom", relativeTo)
                .takeIf { it > 0f }
            ?: padding
        return InlinePadding(
            left = lengthPx("padding-left", relativeTo).takeIf { it > 0f } ?: horizontal,
            top = verticalLengthPx("padding-top", relativeTo).takeIf { it > 0f } ?: vertical,
            right = lengthPx("padding-right", relativeTo).takeIf { it > 0f } ?: horizontal,
            bottom = verticalLengthPx("padding-bottom", relativeTo).takeIf { it > 0f } ?: vertical
        )
    }

    private fun EpubComputedStyle.inlineBackgroundRadius(relativeTo: Float): Float {
        return lengthPx("border-radius", relativeTo)
            .takeIf { it > 0f }
            ?: lengthPx("border-top-left-radius", relativeTo).takeIf { it > 0f }
            ?: 0f
    }

    private fun EpubBorder.hasVisibleBorder(): Boolean {
        return listOf(top, right, bottom, left).any { side ->
            side.width > 0f && side.color != null && side.style != "none" && side.style != "hidden"
        }
    }

    private fun EpubBorderRadius.maxRadius(): Float {
        return maxOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun EpubComputedStyle.opacity(): Float {
        return this["opacity"]?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
    }

    private fun Int.withOpacity(opacity: Float): Int {
        if (opacity >= 1f) return this
        val alpha = (Color.alpha(this) * opacity).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
    }

    private fun EpubComputedStyle.textShadow(): EpubShadow? {
        return this["text-shadow"]?.toCssShadow()?.withOpacity(opacity())
    }

    private fun EpubComputedStyle.boxShadow(): EpubShadow? {
        return this["box-shadow"]?.toCssShadow()?.withOpacity(opacity())
    }

    private fun EpubComputedStyle.isBold(): Boolean {
        val value = this["font-weight"]?.lowercase(Locale.ROOT) ?: return false
        return value == "bold" || (value.toIntOrNull() ?: 0) >= 600
    }

    private fun EpubComputedStyle.isItalic(): Boolean {
        val value = this["font-style"]?.lowercase(Locale.ROOT) ?: return false
        return value == "italic" || value == "oblique"
    }

    private fun EpubComputedStyle.resolveTypeface(fontFaces: List<EpubFontFace>): Typeface? {
        val family = this["font-family"]
            ?.split(',')
            ?.map { it.trim().trim('\'', '"') }
            ?.firstOrNull { it.isNotBlank() }
            ?: return null
        return fontResolver(family, isBold(), isItalic(), fontFaces)
    }

    private fun EpubComputedStyle.hasTextDecoration(name: String): Boolean {
        return this["text-decoration"]?.lowercase(Locale.ROOT)?.contains(name) == true ||
            this["text-decoration-line"]?.lowercase(Locale.ROOT)?.contains(name) == true
    }

    private fun EpubComputedStyle.shouldForceBreakBefore(): Boolean {
        val value = this["break-before"] ?: this["page-break-before"] ?: return false
        return value.trim().lowercase(Locale.ROOT) in forcedBreakValues
    }

    private fun EpubComputedStyle.shouldForceBreakAfter(): Boolean {
        val value = this["break-after"] ?: this["page-break-after"] ?: return false
        return value.trim().lowercase(Locale.ROOT) in forcedBreakValues
    }

    private fun EpubComputedStyle.shouldAvoidBreakInside(): Boolean {
        val value = this["break-inside"] ?: this["page-break-inside"] ?: return false
        return value.trim().lowercase(Locale.ROOT) == "avoid"
    }

    private fun EpubComputedStyle.borderStyle(): String? {
        val style = this["border-style"]
            ?: this["border-top-style"]
            ?: this["border-right-style"]
            ?: this["border-bottom-style"]
            ?: this["border-left-style"]
            ?: this["border"]?.extractCssBorderStyle()
        return style?.lowercase(Locale.ROOT)
    }

    private fun EpubComputedStyle.listMarkerText(): String? {
        return when (this["list-style-type"]?.trim()?.lowercase(Locale.ROOT)) {
            "none" -> null
            "circle" -> "○"
            "square" -> "■"
            "decimal", "decimal-leading-zero" -> "•"
            else -> "•"
        }
    }

    private fun EpubComputedStyle.baselineShiftPx(): Float {
        return when (this["vertical-align"]?.trim()?.lowercase(Locale.ROOT)) {
            "super", "top", "text-top" -> -fontSizePx() * 0.35f
            "sub", "bottom", "text-bottom" -> fontSizePx() * 0.25f
            "middle" -> -fontSizePx() * 0.12f
            else -> 0f
        }
    }

    private fun EpubComputedStyle.resolveHorizontalSize(containingWidth: Float): Float? {
        val width = this["width"]?.toCssLengthPx(containingWidth)
            ?.takeIf { it > 0f }
            ?: return null
        val minWidth = this["min-width"]?.toCssLengthPx(containingWidth)
        val maxWidth = this["max-width"]?.toCssLengthPx(containingWidth)
        return width.applyMinMax(minWidth, maxWidth)
    }

    private fun EpubComputedStyle.resolveVerticalSize(containingWidth: Float): Float? {
        val height = this["height"]?.let { value ->
            verticalLengthPx("height", containingWidth).takeIf { it > 0f }
        }
        val minHeight = this["min-height"]?.let {
            verticalLengthPx("min-height", containingWidth).takeIf { value -> value > 0f }
        }
        val maxHeight = this["max-height"]?.let {
            verticalLengthPx("max-height", containingWidth).takeIf { value -> value > 0f }
        }
        return when {
            height != null -> height.applyMinMax(minHeight, maxHeight)
            minHeight != null -> minHeight
            else -> null
        }
    }

    private fun Float.applyMinMax(minValue: Float?, maxValue: Float?): Float {
        var value = this
        minValue?.let { value = maxOf(value, it) }
        maxValue?.let { value = minOf(value, it) }
        return value
    }

    private fun Size?.scaledHeight(width: Float): Float {
        if (this == null || this.width <= 0 || this.height <= 0 || width <= 0f) {
            return ChapterProvider.contentPaintTextHeight
        }
        return this.height * width / this.width
    }

    private fun EpubImageNode.inlineImageSize(lineWidth: Float, lineHeight: Float): Pair<Float, Float>? {
        val explicitWidth = style.lengthPx("width", lineWidth)
            .takeIf { it > 0f }
            ?: attributes["data-legado-width"]?.toCssLengthPx(lineWidth)
            ?: attributes["width"]?.toCssLengthPx(lineWidth)
        val explicitHeight = style.lengthPx("height", lineWidth)
            .takeIf { it > 0f }
            ?: attributes["height"]?.toCssLengthPx(lineWidth)
        val sourceSize = imageSizeResolver(src)
        val imageWidth = explicitWidth
            ?: explicitHeight?.let { height ->
                if (sourceSize != null && sourceSize.height > 0) {
                    sourceSize.width * height / sourceSize.height
                } else {
                    height
                }
            }
            ?: return null
        val imageHeight = explicitHeight
            ?: sourceSize.scaledHeight(imageWidth)
        val maxInlineHeight = lineHeight * 1.8f
        val maxInlineWidth = lineWidth * 0.35f
        if (imageWidth > maxInlineWidth || imageHeight > maxInlineHeight) return null
        return imageWidth.coerceAtLeast(1f) to imageHeight.coerceAtLeast(1f)
    }

    private fun String.toCssLengthPx(relativeTo: Float): Float? {
        val clean = trim().lowercase(Locale.ROOT)
        if (clean.isBlank() || clean == "auto") return null
        return when {
            clean.endsWith("%") -> clean.dropLast(1).toFloatOrNull()?.let { relativeTo * it / 100f }
            clean.endsWith("em") -> clean.dropLast(2).toFloatOrNull()?.let { basePaint.textSize * it }
            clean.endsWith("rem") -> clean.dropLast(3).toFloatOrNull()?.let { basePaint.textSize * it }
            clean.endsWith("px") -> clean.dropLast(2).toFloatOrNull()
            clean.endsWith("pt") -> clean.dropLast(2).toFloatOrNull()?.let { it * 4f / 3f }
            clean.endsWith("pc") -> clean.dropLast(2).toFloatOrNull()?.let { it * 16f }
            clean.endsWith("in") -> clean.dropLast(2).toFloatOrNull()?.let { it * 96f }
            clean.endsWith("cm") -> clean.dropLast(2).toFloatOrNull()?.let { it * 96f / 2.54f }
            clean.endsWith("mm") -> clean.dropLast(2).toFloatOrNull()?.let { it * 96f / 25.4f }
            clean.endsWith("vw") -> clean.dropLast(2).toFloatOrNull()?.let { viewportWidth * it / 100f }
            clean.endsWith("vh") -> clean.dropLast(2).toFloatOrNull()?.let { viewportHeight * it / 100f }
            clean.endsWith("vmin") -> clean.dropLast(4).toFloatOrNull()?.let { minOf(viewportWidth, viewportHeight) * it / 100f }
            clean.endsWith("vmax") -> clean.dropLast(4).toFloatOrNull()?.let { maxOf(viewportWidth, viewportHeight) * it / 100f }
            else -> clean.toFloatOrNull()
        }
    }

    private fun EpubComputedStyle.extraCharacterSpacing(text: String): Float {
        val letterSpacing = this["letter-spacing"]?.toCssLengthPx(basePaint.textSize) ?: 0f
        val wordSpacing = if (text == " ") {
            this["word-spacing"]?.toCssLengthPx(basePaint.textSize) ?: 0f
        } else {
            0f
        }
        return letterSpacing + wordSpacing
    }

    private fun String.toCssBorderWidthPx(relativeTo: Float): Float? {
        return when (trim().lowercase(Locale.ROOT)) {
            "thin" -> 1f
            "medium" -> 2f
            "thick" -> 4f
            else -> toCssLengthPx(relativeTo)
        }
    }

    private fun String?.isAutoCssValue(): Boolean {
        return this?.trim()?.equals("auto", ignoreCase = true) == true
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

    private fun String.extractCssBorderStyle(): String? {
        return EpubCss.splitValueList(this)
            .firstOrNull { token -> token.lowercase(Locale.ROOT) in borderStyleTokens }
    }

    private fun String.extractCssColor(): String? {
        val clean = trim()
        if (clean.startsWith("#") || clean.startsWith("rgb", true)) return clean
        clean.extractGradientColor()?.let { return it }
        val parts = clean.split(' ', ',', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return parts.firstOrNull { it.startsWith("#") || it.startsWith("rgb", true) || it.toNamedCssColor() != null }
    }

    private fun String.extractGradientColor(): String? {
        if (!contains("gradient(", ignoreCase = true)) return null
        val colorPattern = Regex(
            "(#[0-9a-fA-F]{3,8}|rgba?\\([^)]*\\)|hsla?\\([^)]*\\)|\\b(?:black|white|red|green|blue|cyan|aqua|magenta|fuchsia|yellow|gray|grey|silver|maroon|purple|teal|navy|orange|transparent)\\b)",
            RegexOption.IGNORE_CASE
        )
        return colorPattern.find(this)?.value
    }

    private fun String.toCssShadow(): EpubShadow? {
        val clean = trim()
        if (clean.isBlank() || clean.equals("none", ignoreCase = true)) return null
        val tokens = EpubCss.splitValueList(clean)
        var color: Int? = null
        val lengths = arrayListOf<Float>()
        tokens.forEach { token ->
            val parsedColor = token.toCssColor()
            if (parsedColor != null) {
                color = parsedColor
            } else {
                token.toCssLengthPx(basePaint.textSize)?.let { lengths.add(it) }
            }
        }
        if (lengths.size < 2) return null
        return EpubShadow(
            dx = lengths.getOrNull(0) ?: 0f,
            dy = lengths.getOrNull(1) ?: 0f,
            blur = lengths.getOrNull(2) ?: 0f,
            color = color ?: Color.argb(120, 0, 0, 0)
        )
    }

    private fun EpubShadow.withOpacity(opacity: Float): EpubShadow {
        return copy(color = color.withOpacity(opacity))
    }

    private fun String.transformCssText(style: EpubComputedStyle): String {
        val variant = style["font-variant-caps"] ?: style["font-variant"]
        if (variant?.contains("small-caps", ignoreCase = true) == true) {
            return uppercase(Locale.ROOT)
        }
        return when (style["text-transform"]?.trim()?.lowercase(Locale.ROOT)) {
            "uppercase" -> uppercase(Locale.ROOT)
            "lowercase" -> lowercase(Locale.ROOT)
            "capitalize" -> splitTextWords().joinToString("") { part ->
                if (part.firstOrNull()?.isLetter() == true) {
                    part.replaceFirstChar { char -> char.uppercase(Locale.ROOT) }
                } else {
                    part
                }
            }
            else -> this
        }
    }

    private fun String.splitTextWords(): List<String> {
        if (isEmpty()) return emptyList()
        val result = arrayListOf<String>()
        val builder = StringBuilder()
        var previousWasLetterOrDigit: Boolean? = null
        forEach { char ->
            val isLetterOrDigit = char.isLetterOrDigit()
            if (previousWasLetterOrDigit != null && previousWasLetterOrDigit != isLetterOrDigit) {
                result.add(builder.toString())
                builder.clear()
            }
            builder.append(char)
            previousWasLetterOrDigit = isLetterOrDigit
        }
        if (builder.isNotEmpty()) result.add(builder.toString())
        return result
    }

    private fun String.toCssColor(): Int? {
        val clean = trim()
        return when {
            clean.startsWith("rgba", true) || clean.startsWith("rgb", true) -> clean.parseRgbCssColor()
            clean.startsWith("hsla", true) || clean.startsWith("hsl", true) -> clean.parseHslCssColor()
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

    private fun String.parseHslCssColor(): Int? {
        val start = indexOf('(')
        val end = lastIndexOf(')')
        if (start < 0 || end <= start) return null
        val parts = substring(start + 1, end)
            .replace(",", " ")
            .split(' ', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size < 3) return null
        val hue = parts[0].removeSuffix("deg").toFloatOrNull()?.let { ((it % 360f) + 360f) % 360f } ?: return null
        val saturation = parts[1].removeSuffix("%").toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: return null
        val lightness = parts[2].removeSuffix("%").toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: return null
        val alpha = parts.getOrNull(3)?.let { value ->
            if (value.endsWith("%")) {
                ((value.dropLast(1).toFloatOrNull() ?: 100f) * 2.55f).toInt()
            } else {
                ((value.toFloatOrNull() ?: 1f) * 255f).toInt()
            }
        } ?: 255
        val chroma = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
        val x = chroma * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
        val m = lightness - chroma / 2f
        val (r1, g1, b1) = when {
            hue < 60f -> Triple(chroma, x, 0f)
            hue < 120f -> Triple(x, chroma, 0f)
            hue < 180f -> Triple(0f, chroma, x)
            hue < 240f -> Triple(0f, x, chroma)
            hue < 300f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        fun channel(value: Float): Int = ((value + m) * 255f).toInt().coerceIn(0, 255)
        return Color.argb(alpha.coerceIn(0, 255), channel(r1), channel(g1), channel(b1))
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

    private fun EpubComputedStyle.backgroundSizeValue(): String? {
        return this["background-size"]
            ?: this["background"]?.extractBackgroundSize()
    }

    private fun EpubComputedStyle.backgroundPositionValue(): String? {
        return this["background-position"]
            ?: listOfNotNull(this["background-position-x"], this["background-position-y"])
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
            ?: this["background"]?.extractBackgroundPosition()
    }

    private fun EpubComputedStyle.backgroundRepeatValue(): String? {
        return this["background-repeat"]
            ?: this["background"]?.extractBackgroundRepeat()
    }

    private fun String.extractBackgroundRepeat(): String? {
        val clean = lowercase(Locale.ROOT)
        return when {
            clean.contains("no-repeat") -> "no-repeat"
            clean.contains("repeat-x") -> "repeat-x"
            clean.contains("repeat-y") -> "repeat-y"
            clean.contains("repeat") -> "repeat"
            else -> null
        }
    }

    private fun String.extractBackgroundSize(): String? {
        val slash = indexOf('/')
        if (slash < 0) return null
        return substring(slash + 1)
            .split(' ', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
    }

    private fun String.extractBackgroundPosition(): String? {
        val tokens = lowercase(Locale.ROOT)
            .replace(Regex("url\\([^)]*\\)"), " ")
            .split(' ', '\t', '\n', ',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val positionTokens = tokens.filter { token ->
            token in backgroundPositionKeywords ||
                token.endsWith("%") ||
                token.endsWith("px") ||
                token.endsWith("em") ||
                token.toFloatOrNull() != null
        }
        return positionTokens.take(2).joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun EpubBlockNode.tableRows(): List<EpubBlockNode> {
        if (tagName == "tr") return listOf(this)
        return children.flatMap { child ->
            when (child) {
                is EpubBlockNode -> child.tableRows()
                else -> emptyList()
            }
        }
    }

    private fun EpubBlockNode.tableCells(): List<EpubBlockNode> {
        return children.filterIsInstance<EpubBlockNode>()
            .filter { it.tagName == "td" || it.tagName == "th" }
    }

    private fun EpubBlockNode.tableColumnWidths(
        rows: List<EpubBlockNode>,
        tableWidth: Float,
        maxColumns: Int
    ): List<Float> {
        if (maxColumns <= 0) return emptyList()
        val widths = MutableList(maxColumns) { 0f }
        rows.forEach { row ->
            row.tableCells().forEachIndexed { index, cell ->
                if (index >= widths.size) return@forEachIndexed
                val width = cell.style.resolveHorizontalSize(tableWidth)
                    ?: cell.attributes["width"]?.toCssLengthPx(tableWidth)
                    ?: 0f
                if (width > widths[index]) {
                    widths[index] = width
                }
            }
        }
        val fixedTotal = widths.sum()
        if (fixedTotal <= 0f || fixedTotal > tableWidth) {
            return List(maxColumns) { tableWidth / maxColumns }
        }
        val autoColumns = widths.count { it <= 0f }.coerceAtLeast(1)
        val autoWidth = (tableWidth - fixedTotal).coerceAtLeast(0f) / autoColumns
        return widths.map { width -> if (width > 0f) width else autoWidth }
    }

    private fun EpubBlockNode.intrinsicTableWidth(rows: List<EpubBlockNode>): Float {
        return rows.maxOfOrNull { row ->
            row.tableCells().sumOf { cell ->
                val span = cell.attributes["colspan"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val width = cell.style.resolveHorizontalSize(viewportWidth.toFloat())
                    ?: cell.attributes["width"]?.toCssLengthPx(viewportWidth.toFloat())
                    ?: 0f
                (width * span).toDouble()
            }.toFloat()
        } ?: 0f
    }

    private fun buildLayoutSnapshotId(href: String): Int {
        var result = href.hashCode()
        result = 31 * result + viewportWidth
        result = 31 * result + viewportHeight
        result = 31 * result + basePaint.textSize.toBits()
        result = 31 * result + basePaint.color
        result = 31 * result + pages.size
        pages.forEach { commands ->
            result = 31 * result + commands.size
        }
        return result
    }

    private data class BlockStyle(
        val backgroundColor: Int?,
        val borderColor: Int?,
        val borderWidth: Float,
        val borderStyle: String?,
        val border: EpubBorder,
        val radius: Float,
        val shadow: EpubShadow?
    )

    private data class ActiveBlockDecoration(
        val left: Float,
        val width: Float,
        val style: BlockStyle,
        val sourcePath: String,
        var openCommandIndex: Int? = null
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
        val imageSrc: String?,
        val width: Float,
        val height: Float,
        val size: Float,
        val ascent: Float,
        val descent: Float,
        val color: Int?,
        val backgroundColor: Int?,
        val bold: Boolean,
        val italic: Boolean,
        val typeface: Typeface?,
        val underline: Boolean,
        val overline: Boolean,
        val strikeThrough: Boolean,
        val decorationColor: Int?,
        val decorationStyle: String?,
        val baselineShift: Float,
        val backgroundRadius: Float,
        val backgroundPaddingLeft: Float,
        val backgroundPaddingTop: Float,
        val backgroundPaddingRight: Float,
        val backgroundPaddingBottom: Float,
        val shadow: EpubShadow?,
        val sourcePath: String
    )

    private data class InlinePadding(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private companion object {
        val backgroundPositionKeywords = setOf("left", "center", "right", "top", "bottom")
        val borderStyleTokens = setOf(
            "none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset"
        )
        val forcedBreakValues = setOf("always", "page", "left", "right", "recto", "verso")
    }

}
