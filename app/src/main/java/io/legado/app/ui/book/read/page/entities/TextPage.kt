package io.legado.app.ui.book.read.page.entities

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.Keep
import androidx.core.graphics.withTranslation
import io.legado.app.R
import io.legado.app.help.PaintPool
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.EpubBlockBox
import io.legado.app.model.localBook.EpubBorderSide
import io.legado.app.model.localBook.EpubBullet
import io.legado.app.model.localBook.EpubDrawCommand
import io.legado.app.model.localBook.EpubImageBox
import io.legado.app.model.localBook.EpubLinkArea
import io.legado.app.model.localBook.EpubPageColor
import io.legado.app.model.localBook.EpubRuleLine
import io.legado.app.model.localBook.EpubTextRun
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextChapter.Companion.emptyTextChapter
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.canvasrecorder.recordIfNeeded
import io.legado.app.utils.dpToPx
import splitties.init.appCtx
import java.text.DecimalFormat
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

/**
 * 页面信息
 */
@Keep
@Suppress("unused", "MemberVisibilityCanBePrivate")
data class TextPage(
    var index: Int = 0,
    var text: String = appCtx.getString(R.string.data_loading),
    var title: String = appCtx.getString(R.string.data_loading),
    private val textLines: ArrayList<TextLine> = arrayListOf(),
    var chapterSize: Int = 0,
    var chapterIndex: Int = 0,
    var height: Float = 0f,
    var leftLineSize: Int = 0,
    var renderHeight: Int = 0
) {

    data class NativeTextSelection(
        val text: String,
        val rect: RectF,
        val expandedText: String? = null
    )

    companion object {
        val readProgressFormatter = DecimalFormat("0.0%")
        val emptyTextPage = TextPage()
    }

    val lines: List<TextLine> get() = textLines
    val lineSize: Int get() = textLines.size
    val charSize: Int get() = text.length.coerceAtLeast(1)
    val chapterPosition: Int get() = textLines.firstOrNull()?.chapterPosition ?: fallbackChapterPosition
    val searchResult = hashSetOf<TextBaseColumn>()
    var isMsgPage: Boolean = false
    var canvasRecorder = CanvasRecorderFactory.create(true)
    var doublePage = false
    var paddingTop = ChapterProvider.paddingTop
    var isCompleted = false
    var hasReadAloudSpan = false
    var epubBackgroundSrc: String? = null
    var epubBackgroundColor: Int? = null
    var epubBackgroundSize: String? = null
    var epubBackgroundPosition: String? = null
    var epubBackgroundRepeat: String? = null
    var epubLayoutSnapshotId: Int = 0
    var epubDrawOffsetX: Float = ChapterProvider.paddingLeft.toFloat()
    var epubDrawOffsetY: Float = ChapterProvider.paddingTop.toFloat()
    var fallbackChapterPosition: Int = 0
    var advancedTitleHtml: String? = null
    var advancedTitleTop: Float = 0f
    var advancedTitleHeight: Float = 0f
    val epubDecorations = arrayListOf<EpubDecoration>()
    internal val epubEmbeddedBlocks = arrayListOf<EpubEmbeddedBlock>()
    internal val epubNativeCommands = arrayListOf<EpubDrawCommand>()

    @JvmField
    var textChapter = emptyTextChapter
    val pageSize get() = textChapter.pageSize

    val paragraphs by lazy {
        paragraphsInternal
    }

    val paragraphsInternal: ArrayList<TextParagraph>
        get() {
            val paragraphs = arrayListOf<TextParagraph>()
            val lines = textLines.filter { it.paragraphNum > 0 }
            if (lines.isEmpty()) return paragraphs
            val offset = lines.first().paragraphNum - 1
            lines.forEach { line ->
                if (paragraphs.lastIndex < line.paragraphNum - offset - 1) {
                    paragraphs.add(TextParagraph(0))
                }
                paragraphs[line.paragraphNum - offset - 1].textLines.add(line)
            }
            return paragraphs
        }

    fun addLine(line: TextLine) {
        line.textPage = this
        textLines.add(line)
    }

    fun getLine(index: Int): TextLine {
        return textLines.getOrElse(index) {
            textLines.lastOrNull() ?: TextLine(chapterPosition = fallbackChapterPosition)
        }
    }

    /**
     * 底部对齐更新行位置
     */
    fun upLinesPosition() {
        if (hasEpubBackground() || epubEmbeddedBlocks.isNotEmpty()) return
        if (!ReadBookConfig.textBottomJustify) return
        if (textLines.size <= 1) return
        if (leftLineSize == 0) {
            leftLineSize = lineSize
        }
        ChapterProvider.run {
            val lastLine = textLines[leftLineSize - 1]
            if (lastLine.isImage) return@run
            val lastLineHeight = with(lastLine) { lineBottom - lineTop }
            val pageHeight = lastLine.lineBottom + contentPaintTextHeight * lineSpacingExtra
            if (visibleHeight - pageHeight >= lastLineHeight) return@run
            val surplus = (visibleBottom - lastLine.lineBottom)
            if (surplus == 0f) return@run
            height += surplus
            val tj = surplus / (leftLineSize - 1)
            for (i in 1 until leftLineSize) {
                val line = textLines[i]
                line.lineTop += tj * i
                line.lineBase += tj * i
                line.lineBottom += tj * i
            }
        }
        if (leftLineSize == lineSize) return
        ChapterProvider.run {
            val lastLine = textLines.last()
            if (lastLine.isImage) return@run
            val lastLineHeight = with(lastLine) { lineBottom - lineTop }
            val pageHeight = lastLine.lineBottom + contentPaintTextHeight * lineSpacingExtra
            if (visibleHeight - pageHeight >= lastLineHeight) return@run
            val surplus = (visibleBottom - lastLine.lineBottom)
            if (surplus == 0f) return@run
            val tj = surplus / (textLines.size - leftLineSize - 1)
            for (i in leftLineSize + 1 until textLines.size) {
                val line = textLines[i]
                val surplusIndex = i - leftLineSize
                line.lineTop += tj * surplusIndex
                line.lineBase += tj * surplusIndex
                line.lineBottom += tj * surplusIndex
            }
        }
    }

    /**
     * 计算文字位置,只用作单页面内容
     */
    @Suppress("DEPRECATION")
    fun format(): TextPage {
        if (isNativeEpubPage() || hasAdvancedTitleWeb()) {
            return this
        }
        if (textLines.isEmpty()) isMsgPage = true
        if (isMsgPage && ChapterProvider.viewWidth > 0) {
            textLines.clear()
            val visibleWidth = ChapterProvider.visibleRight - ChapterProvider.paddingLeft
            val paint = ChapterProvider.contentPaint
            val layout = StaticLayout(
                text, paint, visibleWidth,
                Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false
            )
            val letterSpacing = paint.letterSpacing * paint.textSize
            var y = (ChapterProvider.visibleHeight - layout.height) / 2f
            if (y < 0) y = 0f
            for (lineIndex in 0 until layout.lineCount) {
                val textLine = TextLine()
                textLine.lineTop = ChapterProvider.paddingTop + y + layout.getLineTop(lineIndex)
                textLine.lineBase =
                    ChapterProvider.paddingTop + y + layout.getLineBaseline(lineIndex)
                textLine.lineBottom =
                    ChapterProvider.paddingTop + y + layout.getLineBottom(lineIndex)
                var x = ChapterProvider.paddingLeft +
                        (visibleWidth - layout.getLineMax(lineIndex)) / 2
                textLine.text =
                    text.substring(layout.getLineStart(lineIndex), layout.getLineEnd(lineIndex))
                for (i in textLine.text.indices) {
                    val char = textLine.text[i].toString()
                    var cw = StaticLayout.getDesiredWidth(char, paint)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        cw += letterSpacing
                    }
                    val x1 = x + cw
                    textLine.addColumn(
                        TextColumn(start = x, end = x1, char)
                    )
                    x = x1
                }
                addLine(textLine)
            }
            height = ChapterProvider.visibleHeight.toFloat()
            upRenderHeight()
            invalidate()
            isCompleted = true
        }
        return this
    }

    /**
     * 移除朗读标志
     */
    fun removePageAloudSpan(): TextPage {
        if (!hasReadAloudSpan) {
            return this
        }
        hasReadAloudSpan = false
        for (i in textLines.indices) {
            textLines[i].isReadAloud = false
        }
        return this
    }

    /**
     * 更新朗读标志
     * @param aloudSpanStart 朗读文字开始位置
     */
    fun upPageAloudSpan(aloudSpanStart: Int) {
        removePageAloudSpan()
        var lineStart = 0
        for (index in textLines.indices) {
            val textLine = textLines[index]
            val lineLength = textLine.text.length + if (textLine.isParagraphEnd) 1 else 0
            if (aloudSpanStart >= lineStart && aloudSpanStart < lineStart + lineLength) {
                for (i in index - 1 downTo 0) {
                    if (textLines[i].isParagraphEnd) {
                        break
                    } else {
                        textLines[i].isReadAloud = true
                    }
                }
                for (i in index until textLines.size) {
                    if (textLines[i].isParagraphEnd) {
                        textLines[i].isReadAloud = true
                        break
                    } else {
                        textLines[i].isReadAloud = true
                    }
                }
                break
            }
            lineStart += lineLength
        }
    }

    /**
     * 阅读进度
     */
    val readProgress: String
        get() {
            val df = readProgressFormatter
            if (chapterSize == 0 || pageSize == 0 && chapterIndex == 0) {
                return "0.0%"
            } else if (pageSize == 0) {
                return df.format((chapterIndex + 1.0f) / chapterSize.toDouble())
            }
            var percent =
                df.format(chapterIndex * 1.0f / chapterSize + 1.0f / chapterSize * (index + 1) / pageSize.toDouble())
            if (percent == "100.0%" && (chapterIndex + 1 != chapterSize || index + 1 != pageSize)) {
                percent = "99.9%"
            }
            return percent
        }

    /**
     * 根据行和列返回字符在本页的位置
     * @param lineIndex 字符在第几行
     * @param columnIndex 字符在第几列
     * @return 字符在本页位置
     */
    fun getPosByLineColumn(lineIndex: Int, columnIndex: Int): Int {
        var length = 0
        val maxIndex = min(lineIndex, lineSize - 1)
        for (index in 0 until maxIndex) {
            length += textLines[index].charSize
            if (textLines[index].isParagraphEnd) {
                length++
            }
        }
        val columns = textLines[maxIndex].columns
        for (index in 0 until columnIndex) {
            val column = columns[index]
            if (column is TextBaseColumn) {
                length += column.charData.length
            }
        }
        return length
    }

    /**
     * @return 页面所在章节
     */
    fun getTextChapter(): TextChapter {
        return textChapter
    }

    /**
     * 判断章节字符位置是否在这一页中
     *
     * @param chapterPos 章节字符位置
     * @return
     */
    fun containPos(chapterPos: Int): Boolean {
        if (lines.isEmpty()) return false
        val line = lines.first()
        val startPos = line.chapterPosition
        val endPos = startPos + charSize
        return chapterPos in startPos..<endPos
    }

    fun hasEpubBackground(): Boolean {
        return epubBackgroundSrc != null || epubBackgroundColor != null
    }

    fun hasEpubContent(): Boolean {
        return hasEpubBackground() || epubNativeCommands.isNotEmpty() || epubEmbeddedBlocks.isNotEmpty()
    }

    fun hasAdvancedTitleWeb(): Boolean {
        return !advancedTitleHtml.isNullOrBlank() && advancedTitleHeight > 0f
    }

    fun hasRenderableContent(): Boolean {
        return textLines.isNotEmpty() || hasEpubContent() || hasAdvancedTitleWeb()
    }

    fun isNativeEpubPage(): Boolean {
        return hasEpubContent()
    }

    fun findEpubLinkAt(x: Float, y: Float): String? {
        if (epubNativeCommands.isEmpty()) return null
        val localX = x - epubDrawOffsetX
        val localY = y - epubDrawOffsetY
        return epubNativeCommands.asReversed().firstNotNullOfOrNull { command ->
            when (command) {
                is EpubTextRun -> {
                    val href = command.linkHref?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
                    val rect = RectF(command.x, command.y, command.x + command.width, command.y + command.height)
                    if (rect.contains(localX, localY)) href else null
                }
                is EpubImageBox -> {
                    val href = command.linkHref?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
                    val rect = RectF(command.x, command.y, command.x + command.width, command.y + command.height)
                    if (rect.contains(localX, localY)) href else null
                }
                is EpubLinkArea -> {
                    val rect = RectF(command.x, command.y, command.x + command.width, command.y + command.height)
                    if (rect.contains(localX, localY)) command.href else null
                }
                else -> null
            }
        }
    }

    fun epubFootnoteLinks(): List<String> {
        if (epubNativeCommands.isEmpty()) return emptyList()
        return epubNativeCommands.mapNotNull { command ->
            when (command) {
                is EpubTextRun -> command.linkHref
                is EpubImageBox -> command.linkHref
                is EpubLinkArea -> command.href
                else -> null
            }?.takeIf { it.contains("#") }
        }.distinct()
    }

    fun epubLinkDiagnostics(): String {
        if (epubNativeCommands.isEmpty()) return "commands=0"
        var textLinks = 0
        var imageLinks = 0
        var areas = 0
        val samples = arrayListOf<String>()
        epubNativeCommands.forEach { command ->
            when (command) {
                is EpubTextRun -> command.linkHref?.takeIf { it.isNotBlank() }?.let {
                    textLinks++
                    if (samples.size < 3) samples.add("text:$it@${command.x},${command.y},${command.width},${command.height}")
                }
                is EpubImageBox -> command.linkHref?.takeIf { it.isNotBlank() }?.let {
                    imageLinks++
                    if (samples.size < 3) samples.add("image:$it@${command.x},${command.y},${command.width},${command.height}")
                }
                is EpubLinkArea -> {
                    areas++
                    if (samples.size < 3) samples.add("area:${command.href}@${command.x},${command.y},${command.width},${command.height}")
                }
                else -> Unit
            }
        }
        return "commands=${epubNativeCommands.size}, textLinks=$textLinks, imageLinks=$imageLinks, areas=$areas, samples=${samples.joinToString(";")}"
    }

    fun extractNativeText(): String {
        if (epubNativeCommands.isEmpty()) return ""
        val runs = epubNativeCommands.filterIsInstance<EpubTextRun>()
            .filter { it.text.isNotBlank() }
            .sortedWith(
                compareBy<EpubTextRun> { it.baseline }
                    .thenBy { it.y }
                    .thenBy { it.x }
            )
        if (runs.isEmpty()) return ""
        val builder = StringBuilder()
        var previous: EpubTextRun? = null
        runs.forEach { run ->
            val textValue = run.text
            val prev = previous
            if (prev != null) {
                val lineThreshold = max(prev.size, run.size) * 0.55f
                if (abs(run.baseline - prev.baseline) > lineThreshold) {
                    builder.append('\n')
                } else if (shouldInsertSpace(prev, run)) {
                    builder.append(' ')
                }
            }
            builder.append(textValue)
            previous = run
        }
        return builder.toString()
            .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
            .trim()
    }

    fun nativeTextBounds(): RectF? {
        val runs = epubNativeCommands.filterIsInstance<EpubTextRun>()
            .filter { it.text.isNotBlank() }
        if (runs.isEmpty()) return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = Float.MIN_VALUE
        var bottom = Float.MIN_VALUE
        runs.forEach { run ->
            left = min(left, run.x - run.backgroundPaddingLeft)
            top = min(top, run.y - run.backgroundPaddingTop)
            right = max(right, run.x + run.width + run.backgroundPaddingRight)
            bottom = max(bottom, run.y + run.height + run.backgroundPaddingBottom)
        }
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
            return null
        }
        return RectF(left, top, right, bottom)
    }

    fun findNativeTextSelectionAt(x: Float, y: Float): NativeTextSelection? {
        val runs = epubNativeCommands.filterIsInstance<EpubTextRun>()
            .filter { it.text.isNotBlank() }
        if (runs.isEmpty()) return null
        val localX = x - epubDrawOffsetX
        val localY = y - epubDrawOffsetY
        val targetRun = runs.firstOrNull { run ->
            RectF(run.x, run.y, run.x + run.width, run.y + run.height).contains(localX, localY)
        } ?: return null
        return resolveCharSelection(targetRun, localX)
    }

    private fun shouldInsertSpace(previous: EpubTextRun, current: EpubTextRun): Boolean {
        val prevLast = previous.text.lastOrNull() ?: return false
        val currFirst = current.text.firstOrNull() ?: return false
        if (prevLast.isWhitespace() || currFirst.isWhitespace()) return false
        if (!prevLast.isLetterOrDigit() || !currFirst.isLetterOrDigit()) return false
        val gap = current.x - (previous.x + previous.width)
        return gap > max(previous.size, current.size) * 0.18f
    }

    private fun resolveCharSelection(targetRun: EpubTextRun, localX: Float): NativeTextSelection? {
        val rawText = targetRun.text
        if (rawText.isBlank()) return null
        val startOffset = rawText.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
        val endOffset = rawText.indexOfLast { !it.isWhitespace() }.takeIf { it >= startOffset } ?: return null
        val cleanText = rawText.substring(startOffset, endOffset + 1)
        if (cleanText.isEmpty()) return null
        val targetX = (localX - targetRun.x).coerceIn(0f, targetRun.width)
        val charWidth = targetRun.width / cleanText.length.coerceAtLeast(1)
        val relativeIndex = if (charWidth > 0f) {
            (targetX / charWidth).toInt().coerceIn(0, cleanText.lastIndex)
        } else {
            0
        }
        val tappedChar = cleanText.getOrNull(relativeIndex)?.toString()?.takeIf { it.isNotBlank() }
            ?: cleanText
        val left = targetRun.x + relativeIndex * charWidth - targetRun.backgroundPaddingLeft
        val right = targetRun.x + (relativeIndex + 1) * charWidth + targetRun.backgroundPaddingRight
        val top = targetRun.y - targetRun.backgroundPaddingTop
        val bottom = targetRun.y + targetRun.height + targetRun.backgroundPaddingBottom
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
            return null
        }
        return NativeTextSelection(
            text = tappedChar,
            rect = RectF(left, top, right, bottom),
            expandedText = cleanText
        )
    }

    fun draw(view: ContentTextView, canvas: Canvas, relativeOffset: Float) {
        if (AppConfig.optimizeRender) {
            render(view)
            canvas.withTranslation(0f, relativeOffset) {
                canvasRecorder.draw(this)
            }
        } else {
            canvas.withTranslation(0f, relativeOffset) {
                drawPage(view, this)
            }
        }
    }

    private fun drawDebugInfo(canvas: Canvas) {
        ChapterProvider.run {
            val paint = PaintPool.obtain()
            paint.style = Paint.Style.STROKE
            canvas.drawRect(
                paddingLeft.toFloat(),
                0f,
                (paddingLeft + visibleWidth).toFloat(),
                height - 1.dpToPx(),
                paint
            )
            PaintPool.recycle(paint)
        }
    }

    private fun drawPage(view: ContentTextView, canvas: Canvas) {
        drawEpubBackground(view, canvas)
        drawEpubEmbeddedBlocks(view, canvas)
        drawEpubNativeCommands(view, canvas)
        drawEpubDecorations(canvas)
        for (i in lines.indices) {
            val line = lines[i]
            canvas.withTranslation(0f, line.lineTop) {
                line.draw(view, this)
            }
        }
    }

    private fun drawEpubBackground(view: ContentTextView, canvas: Canvas) {
        epubBackgroundColor?.let { color ->
            val paint = PaintPool.obtain()
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawColor(color)
            PaintPool.recycle(paint)
        }
        val src = epubBackgroundSrc ?: return
        val book = ReadBook.book ?: return
        val left = 0f
        val top = 0f
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val bitmap = ImageProvider.getImage(
            book = book,
            src = src,
            width = width.toInt(),
            height = height.toInt(),
            cacheKeySuffix = "epub-bg-${width.toInt()}x${height.toInt()}"
        )
        val (drawWidth, drawHeight) = resolveEpubBackgroundSize(
            sourceWidth = bitmap.width.toFloat(),
            sourceHeight = bitmap.height.toFloat(),
            targetWidth = width,
            targetHeight = height
        )
        val origin = resolveEpubBackgroundOrigin(
            drawWidth = drawWidth,
            drawHeight = drawHeight,
            targetWidth = width,
            targetHeight = height
        )
        canvas.save()
        canvas.clipRect(left, top, left + width, top + height)
        val repeat = epubBackgroundRepeat?.lowercase().orEmpty()
        if (repeat == "repeat" || repeat == "repeat-x" || repeat == "repeat-y") {
            val startX = if (repeat == "repeat-y") origin.first else origin.first.modTile(drawWidth)
            val startY = if (repeat == "repeat-x") origin.second else origin.second.modTile(drawHeight)
            var y = startY
            while (y < height) {
                var x = startX
                while (x < width) {
                    canvas.drawBitmap(bitmap, null, RectF(x, y, x + drawWidth, y + drawHeight), view.imagePaint)
                    if (repeat == "repeat-y") break
                    x += drawWidth
                }
                if (repeat == "repeat-x") break
                y += drawHeight
            }
        } else {
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(origin.first, origin.second, origin.first + drawWidth, origin.second + drawHeight),
                view.imagePaint
            )
        }
        canvas.restore()
    }

    private fun resolveEpubBackgroundSize(
        sourceWidth: Float,
        sourceHeight: Float,
        targetWidth: Float,
        targetHeight: Float,
        backgroundSize: String? = epubBackgroundSize
    ): Pair<Float, Float> {
        val size = backgroundSize?.trim()?.lowercase().orEmpty()
        if (sourceWidth <= 0f || sourceHeight <= 0f) return targetWidth to targetHeight
        return when (size) {
            "contain" -> {
                val scale = min(targetWidth / sourceWidth, targetHeight / sourceHeight)
                sourceWidth * scale to sourceHeight * scale
            }
            "", "cover" -> {
                val scale = max(targetWidth / sourceWidth, targetHeight / sourceHeight)
                sourceWidth * scale to sourceHeight * scale
            }
            else -> {
                val parts = size.split(' ', '\t').filter { it.isNotBlank() }
                val parsedWidth = parts.getOrNull(0)?.cssBackgroundLength(targetWidth)
                val parsedHeight = parts.getOrNull(1)?.cssBackgroundLength(targetHeight)
                when {
                    parsedWidth != null && parsedHeight != null -> parsedWidth to parsedHeight
                    parsedWidth != null -> parsedWidth to (sourceHeight * parsedWidth / sourceWidth)
                    parsedHeight != null -> (sourceWidth * parsedHeight / sourceHeight) to parsedHeight
                    else -> {
                        val scale = max(targetWidth / sourceWidth, targetHeight / sourceHeight)
                        sourceWidth * scale to sourceHeight * scale
                    }
                }
            }
        }
    }

    private fun resolveEpubBackgroundOrigin(
        drawWidth: Float,
        drawHeight: Float,
        targetWidth: Float,
        targetHeight: Float,
        backgroundPosition: String? = epubBackgroundPosition
    ): Pair<Float, Float> {
        val tokens = backgroundPosition
            ?.lowercase()
            ?.split(' ', '\t')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        var horizontal: Float? = null
        var vertical: Float? = null
        var nextLengthIsVertical = false
        tokens.forEach { token ->
            when (token) {
                "left" -> {
                    horizontal = 0f
                    nextLengthIsVertical = true
                }
                "center" -> {
                    if (horizontal == null) {
                        horizontal = (targetWidth - drawWidth) / 2f
                        nextLengthIsVertical = true
                    } else if (vertical == null) {
                        vertical = (targetHeight - drawHeight) / 2f
                    }
                }
                "right" -> {
                    horizontal = targetWidth - drawWidth
                    nextLengthIsVertical = true
                }
                "top" -> vertical = 0f
                "bottom" -> vertical = targetHeight - drawHeight
                else -> {
                    token.cssBackgroundLength(targetWidth)?.let { value ->
                        if (horizontal == null && !nextLengthIsVertical) {
                            horizontal = if (token.endsWith("%")) {
                                (targetWidth - drawWidth) * value / targetWidth
                            } else {
                                value
                            }
                            nextLengthIsVertical = true
                        } else {
                            val verticalValue = token.cssBackgroundLength(targetHeight) ?: value
                            vertical = if (token.endsWith("%")) {
                                (targetHeight - drawHeight) * verticalValue / targetHeight
                            } else {
                                verticalValue
                            }
                        }
                    }
                }
            }
        }
        return (horizontal ?: (targetWidth - drawWidth) / 2f) to
            (vertical ?: (targetHeight - drawHeight) / 2f)
    }

    private fun String.cssBackgroundLength(relativeTo: Float): Float? {
        val clean = trim().lowercase()
        if (clean == "auto") return null
        return when {
            clean.endsWith("%") -> clean.dropLast(1).toFloatOrNull()?.let { relativeTo * it / 100f }
            clean.endsWith("px") -> clean.dropLast(2).toFloatOrNull()
            clean.endsWith("em") -> clean.dropLast(2).toFloatOrNull()
            else -> clean.toFloatOrNull()
        }
    }

    private fun Float.modTile(tile: Float): Float {
        if (tile <= 0f) return this
        var value = this
        while (value > 0f) value -= tile
        return value
    }

    private fun drawEpubDecorations(canvas: Canvas) {
        if (epubDecorations.isEmpty()) return
        val paint = PaintPool.obtain()
        epubDecorations.forEach { decoration ->
            val rect = RectF(decoration.left, decoration.top, decoration.right, decoration.bottom)
            decoration.backgroundColor?.takeIf { it != Color.TRANSPARENT }?.let { color ->
                paint.style = Paint.Style.FILL
                paint.color = color
                canvas.drawRoundRect(rect, decoration.radius, decoration.radius, paint)
            }
            decoration.borderColor?.takeIf { it != Color.TRANSPARENT }?.let { color ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = decoration.borderWidth
                paint.color = color
                canvas.drawRoundRect(rect, decoration.radius, decoration.radius, paint)
            }
        }
        PaintPool.recycle(paint)
    }

    private fun drawEpubEmbeddedBlocks(view: ContentTextView, canvas: Canvas) {
        if (epubEmbeddedBlocks.isEmpty()) return
        val paint = PaintPool.obtain()
        val textPaint = TextPaint(ChapterProvider.contentPaint)
        epubEmbeddedBlocks.forEach { block ->
            canvas.save()
            canvas.clipRect(
                block.offsetX,
                block.offsetY,
                block.offsetX + block.width,
                block.offsetY + block.height
            )
            canvas.withTranslation(block.offsetX, block.offsetY) {
                block.commands.forEach { command ->
                    when (command) {
                        is EpubBlockBox -> drawEpubNativeBlock(this, paint, command)
                        is EpubBullet -> drawEpubNativeBullet(this, textPaint, command)
                        is EpubImageBox -> {
                            if (command.isBackground) {
                                drawEpubEmbeddedBackgroundImage(view, this, command, block.width, block.height)
                            } else {
                                drawEpubNativeImage(view, this, command)
                            }
                        }
                        is EpubLinkArea -> Unit
                        is EpubPageColor -> Unit
                        is EpubRuleLine -> drawEpubNativeRuleLine(this, paint, command)
                        is EpubTextRun -> drawEpubNativeText(this, textPaint, command)
                    }
                }
            }
            canvas.restore()
        }
        PaintPool.recycle(paint)
    }

    private fun drawEpubEmbeddedBackgroundImage(
        view: ContentTextView,
        canvas: Canvas,
        image: EpubImageBox,
        targetWidth: Float,
        targetHeight: Float
    ) {
        val book = ReadBook.book ?: return
        if (targetWidth <= 0f || targetHeight <= 0f) return
        val bitmap = ImageProvider.getImage(
            book = book,
            src = image.src,
            width = targetWidth.toInt().coerceAtLeast(1),
            height = targetHeight.toInt().coerceAtLeast(1),
            cacheKeySuffix = "epub-title-bg-${targetWidth.toInt()}x${targetHeight.toInt()}"
        )
        val (drawWidth, drawHeight) = resolveEpubBackgroundSize(
            sourceWidth = bitmap.width.toFloat(),
            sourceHeight = bitmap.height.toFloat(),
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            backgroundSize = image.backgroundSize
        )
        val origin = resolveEpubBackgroundOrigin(
            drawWidth = drawWidth,
            drawHeight = drawHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            backgroundPosition = image.backgroundPosition
        )
        val repeat = image.backgroundRepeat?.lowercase().orEmpty()
        if (repeat == "repeat" || repeat == "repeat-x" || repeat == "repeat-y") {
            val startX = if (repeat == "repeat-y") origin.first else origin.first.modTile(drawWidth)
            val startY = if (repeat == "repeat-x") origin.second else origin.second.modTile(drawHeight)
            var y = startY
            while (y < targetHeight) {
                var x = startX
                while (x < targetWidth) {
                    canvas.drawBitmap(bitmap, null, RectF(x, y, x + drawWidth, y + drawHeight), view.imagePaint)
                    if (repeat == "repeat-y") break
                    x += drawWidth
                }
                if (repeat == "repeat-x") break
                y += drawHeight
            }
        } else {
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(origin.first, origin.second, origin.first + drawWidth, origin.second + drawHeight),
                view.imagePaint
            )
        }
    }

    private fun drawEpubNativeCommands(view: ContentTextView, canvas: Canvas) {
        if (epubNativeCommands.isEmpty()) return
        val paint = PaintPool.obtain()
        val textPaint = TextPaint(ChapterProvider.contentPaint)
        canvas.withTranslation(epubDrawOffsetX, epubDrawOffsetY) {
            epubNativeCommands.forEach { command ->
                when (command) {
                    is EpubBlockBox -> drawEpubNativeBlock(this, paint, command)
                    is EpubBullet -> drawEpubNativeBullet(this, textPaint, command)
                    is EpubImageBox -> drawEpubNativeImage(view, this, command)
                    is EpubLinkArea -> Unit
                    is EpubPageColor -> Unit
                    is EpubRuleLine -> drawEpubNativeRuleLine(this, paint, command)
                    is EpubTextRun -> drawEpubNativeText(this, textPaint, command)
                }
            }
        }
        PaintPool.recycle(paint)
    }

    private fun drawEpubNativeBlock(canvas: Canvas, paint: Paint, block: EpubBlockBox) {
        val rect = RectF(block.x, block.y, block.x + block.width, block.y + block.height)
        val radius = when {
            block.clipTop || block.clipBottom -> 0f
            else -> block.radius
        }
        block.shadow?.let { shadow ->
            paint.style = Paint.Style.FILL
            paint.color = shadow.color
            val inset = shadow.blur.coerceAtLeast(0f) / 2f
            val shadowRect = RectF(
                rect.left + shadow.dx - inset,
                rect.top + shadow.dy - inset,
                rect.right + shadow.dx + inset,
                rect.bottom + shadow.dy + inset
            )
            canvas.drawRoundRect(shadowRect, radius, radius, paint)
        }
        block.backgroundColor?.takeIf { it != Color.TRANSPARENT }?.let { color ->
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        block.border?.let { border ->
            drawEpubBorderSide(canvas, paint, border.top, rect.left, rect.top, rect.right, rect.top)
            drawEpubBorderSide(canvas, paint, border.right, rect.right, rect.top, rect.right, rect.bottom)
            drawEpubBorderSide(canvas, paint, border.bottom, rect.left, rect.bottom, rect.right, rect.bottom)
            drawEpubBorderSide(canvas, paint, border.left, rect.left, rect.top, rect.left, rect.bottom)
        } ?: block.borderColor?.takeIf { it != Color.TRANSPARENT && block.borderWidth > 0f }?.let { color ->
            drawEpubBorderSide(
                canvas = canvas,
                paint = paint,
                side = EpubBorderSide(block.borderWidth, color, block.borderStyle),
                startX = rect.left,
                startY = rect.top,
                endX = rect.right,
                endY = rect.top
            )
        }
    }

    private fun drawEpubBorderSide(
        canvas: Canvas,
        paint: Paint,
        side: EpubBorderSide,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ) {
        val color = side.color ?: return
        if (color == Color.TRANSPARENT || side.width <= 0f) return
        if (side.style == "none" || side.style == "hidden") return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = side.width
        paint.color = color
        paint.pathEffect = when (side.style) {
            "dashed" -> DashPathEffect(floatArrayOf(side.width * 4f, side.width * 3f), 0f)
            "dotted" -> DashPathEffect(floatArrayOf(side.width, side.width * 2f), 0f)
            else -> null
        }
        canvas.drawLine(startX, startY, endX, endY, paint)
        paint.pathEffect = null
    }

    private fun drawEpubNativeImage(view: ContentTextView, canvas: Canvas, image: EpubImageBox) {
        if (image.isBackground) return
        val book = ReadBook.book ?: return
        val bitmap = ImageProvider.getImage(
            book = book,
            src = image.src,
            width = image.width.toInt().coerceAtLeast(1),
            height = image.height.toInt().coerceAtLeast(1),
            cacheKeySuffix = "epub-native-${image.width.toInt()}x${image.height.toInt()}"
        )
        val rect = RectF(image.x, image.y, image.x + image.width, image.y + image.height)
        val (sourceRect, destRect) = resolveEpubImageDrawRects(bitmap.width, bitmap.height, image, rect)
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawBitmap(bitmap, sourceRect, destRect, view.imagePaint)
        canvas.restore()
    }

    private fun resolveEpubImageDrawRects(
        bitmapWidth: Int,
        bitmapHeight: Int,
        image: EpubImageBox,
        target: RectF
    ): Pair<Rect?, RectF> {
        val fit = image.objectFit?.trim()?.lowercase().orEmpty().ifBlank { "fill" }
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || image.width <= 0f || image.height <= 0f) {
            return null to target
        }
        if (fit == "fill") return null to target
        val sourceRatio = bitmapWidth.toFloat() / bitmapHeight.toFloat()
        val targetRatio = image.width / image.height
        val position = image.objectPosition?.lowercase().orEmpty().ifBlank { "center" }
        if (fit == "cover") {
            val (cropWidth, cropHeight) = if (sourceRatio > targetRatio) {
                (bitmapHeight * targetRatio).toInt().coerceAtLeast(1) to bitmapHeight
            } else {
                bitmapWidth to (bitmapWidth / targetRatio).toInt().coerceAtLeast(1)
            }
            val left = when {
                position.contains("left") -> 0
                position.contains("right") -> bitmapWidth - cropWidth
                else -> (bitmapWidth - cropWidth) / 2
            }.coerceIn(0, (bitmapWidth - cropWidth).coerceAtLeast(0))
            val top = when {
                position.contains("top") -> 0
                position.contains("bottom") -> bitmapHeight - cropHeight
                else -> (bitmapHeight - cropHeight) / 2
            }.coerceIn(0, (bitmapHeight - cropHeight).coerceAtLeast(0))
            return Rect(left, top, left + cropWidth, top + cropHeight) to target
        }
        val scale = when (fit) {
            "contain" -> min(image.width / bitmapWidth, image.height / bitmapHeight)
            "scale-down" -> min(1f, min(image.width / bitmapWidth, image.height / bitmapHeight))
            "none" -> 1f
            else -> return null to target
        }
        val drawWidth = bitmapWidth * scale
        val drawHeight = bitmapHeight * scale
        val left = when {
            position.contains("left") -> target.left
            position.contains("right") -> target.right - drawWidth
            else -> target.left + (target.width() - drawWidth) / 2f
        }
        val top = when {
            position.contains("top") -> target.top
            position.contains("bottom") -> target.bottom - drawHeight
            else -> target.top + (target.height() - drawHeight) / 2f
        }
        return null to RectF(left, top, left + drawWidth, top + drawHeight)
    }

    private fun drawEpubNativeRuleLine(canvas: Canvas, paint: Paint, line: EpubRuleLine) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = line.strokeWidth
        paint.color = line.color ?: ChapterProvider.contentPaint.color
        canvas.drawLine(line.x, line.y, line.x + line.width, line.y, paint)
    }

    private fun drawEpubNativeBullet(canvas: Canvas, paint: TextPaint, bullet: EpubBullet) {
        paint.textSize = bullet.size
        paint.color = bullet.color ?: ChapterProvider.contentPaint.color
        paint.isFakeBoldText = false
        paint.textSkewX = 0f
        paint.typeface = ChapterProvider.contentPaint.typeface
        canvas.drawText(bullet.text, bullet.x, bullet.baseline, paint)
    }

    private fun drawEpubNativeText(canvas: Canvas, paint: TextPaint, text: EpubTextRun) {
        paint.textSize = text.size
        paint.color = text.color ?: ChapterProvider.contentPaint.color
        paint.isFakeBoldText = text.bold
        paint.textSkewX = if (text.italic) -0.25f else 0f
        paint.isUnderlineText = false
        paint.isStrikeThruText = false
        val baseTypeface = text.typeface ?: ChapterProvider.contentPaint.typeface
        paint.typeface = if (text.bold) {
            Typeface.create(baseTypeface, Typeface.BOLD)
        } else {
            baseTypeface
        }
        text.backgroundColor?.takeIf { it != Color.TRANSPARENT }?.let { color ->
            val bgPaint = PaintPool.obtain()
            bgPaint.style = Paint.Style.FILL
            bgPaint.color = color
            val rect = RectF(
                text.x - text.backgroundPaddingLeft,
                text.y,
                text.x + text.width + text.backgroundPaddingRight,
                text.y + text.height
            )
            if (text.backgroundRadius > 0f) {
                canvas.drawRoundRect(rect, text.backgroundRadius, text.backgroundRadius, bgPaint)
            } else {
                canvas.drawRect(rect, bgPaint)
            }
            PaintPool.recycle(bgPaint)
        }
        text.shadow?.let { shadow ->
            paint.setShadowLayer(shadow.blur, shadow.dx, shadow.dy, shadow.color)
        }
        canvas.drawText(text.text, text.x, text.baseline + text.baselineShift, paint)
        if (text.underline || text.overline || text.strikeThrough) {
            val oldColor = paint.color
            val oldStrokeWidth = paint.strokeWidth
            val oldPathEffect = paint.pathEffect
            text.decorationColor?.let { paint.color = it }
            paint.strokeWidth = (text.size / 18f).coerceAtLeast(1f)
            paint.pathEffect = when (text.decorationStyle) {
                "dashed" -> DashPathEffect(floatArrayOf(paint.strokeWidth * 4f, paint.strokeWidth * 3f), 0f)
                "dotted" -> DashPathEffect(floatArrayOf(paint.strokeWidth, paint.strokeWidth * 2f), 0f)
                else -> null
            }
            if (text.overline) {
                val y = text.y + text.height * 0.18f
                drawEpubTextDecoration(canvas, paint, text, y)
            }
            if (text.strikeThrough) {
                val y = text.baseline + text.baselineShift - text.size * 0.32f
                drawEpubTextDecoration(canvas, paint, text, y)
            }
            if (text.underline) {
                val y = text.baseline + text.baselineShift + text.size * 0.12f
                drawEpubTextDecoration(canvas, paint, text, y)
            }
            paint.pathEffect = oldPathEffect
            paint.strokeWidth = oldStrokeWidth
            paint.color = oldColor
        }
        paint.clearShadowLayer()
        paint.isUnderlineText = false
        paint.isStrikeThruText = false
    }

    private fun drawEpubTextDecoration(canvas: Canvas, paint: TextPaint, text: EpubTextRun, y: Float) {
        when (text.decorationStyle) {
            "double" -> {
                val offset = paint.strokeWidth * 1.5f
                canvas.drawLine(text.x, y - offset, text.x + text.width, y - offset, paint)
                canvas.drawLine(text.x, y + offset, text.x + text.width, y + offset, paint)
            }
            "wavy" -> {
                val step = (paint.strokeWidth * 4f).coerceAtLeast(4f)
                var x = text.x
                var up = true
                while (x < text.x + text.width) {
                    val nextX = (x + step).coerceAtMost(text.x + text.width)
                    val nextY = y + if (up) -paint.strokeWidth * 1.5f else paint.strokeWidth * 1.5f
                    canvas.drawLine(x, y, nextX, nextY, paint)
                    x = nextX
                    up = !up
                }
            }
            else -> canvas.drawLine(text.x, y, text.x + text.width, y, paint)
        }
    }

    fun render(view: ContentTextView): Boolean {
        if (!isCompleted) return false
        if (hasEpubContent() && !canvasRecorder.needRecord()) {
            return false
        }
        val pageHeight = if (hasEpubContent()) {
            height.toInt().coerceAtLeast(renderHeight).coerceAtLeast(1)
        } else {
            ChapterProvider.viewHeight
        }
        val recorderHeight = if (hasEpubBackground()) {
            max(renderHeight, pageHeight) + 10.dpToPx()
        } else {
            renderHeight + 10.dpToPx()
        }
        return canvasRecorder.recordIfNeeded(view.width, recorderHeight) { //高度留余，避免图片过高时被截断 下划线最远10dp
            drawPage(view, this)
        }
    }

    fun invalidate() {
        canvasRecorder.invalidate()
    }

    fun invalidateAll() {
        for (i in lines.indices) {
            lines[i].invalidateSelf()
        }
        invalidate()
    }

    fun recycleRecorders() {
        canvasRecorder.recycle()
        for (i in lines.indices) {
            lines[i].recycleRecorder()
        }
    }

    fun hasImageOrEmpty(): Boolean {
        return textLines.any { it.isImage } || textLines.isEmpty()
    }

    fun upRenderHeight() {
        renderHeight = if (lines.isEmpty()) {
            if (hasEpubContent()) ChapterProvider.viewHeight else 0
        } else {
            ceil(lines.last().lineBottom).toInt()
        }
        epubEmbeddedBlocks.maxOfOrNull { block ->
            block.offsetY + block.height
        }?.let { nativeBottom ->
            renderHeight = max(renderHeight, ceil(nativeBottom).toInt())
        }
        if (hasAdvancedTitleWeb()) {
            renderHeight = max(renderHeight, ceil(advancedTitleTop + advancedTitleHeight).toInt())
        }
        epubNativeCommands.maxOfOrNull { command ->
            when (command) {
                is EpubBlockBox -> epubDrawOffsetY + command.y + command.height
                is EpubBullet -> epubDrawOffsetY + command.baseline + command.size
                is EpubImageBox -> epubDrawOffsetY + command.y + command.height
                is EpubLinkArea -> epubDrawOffsetY + command.y + command.height
                is EpubPageColor -> height.coerceAtLeast(ChapterProvider.viewHeight.toFloat())
                is EpubRuleLine -> epubDrawOffsetY + command.y + command.strokeWidth
                is EpubTextRun -> epubDrawOffsetY + command.baseline + command.size
            }
        }?.let { nativeBottom ->
            renderHeight = max(renderHeight, ceil(nativeBottom).toInt())
        }
        if (hasEpubBackground()) {
            renderHeight = max(renderHeight, ChapterProvider.viewHeight)
        }
        if (leftLineSize > 0 && leftLineSize != lines.size) {
            val leftHeight = ceil(lines[leftLineSize - 1].lineBottom).toInt()
            renderHeight = max(renderHeight, leftHeight)
        }
    }

    data class EpubDecoration(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val backgroundColor: Int?,
        val borderColor: Int?,
        val borderWidth: Float,
        val radius: Float
    )

    internal data class EpubEmbeddedBlock(
        val offsetX: Float,
        val offsetY: Float,
        val width: Float,
        val height: Float,
        val commands: List<EpubDrawCommand>
    )
}
