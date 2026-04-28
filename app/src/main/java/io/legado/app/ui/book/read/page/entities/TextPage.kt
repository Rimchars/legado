package io.legado.app.ui.book.read.page.entities

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import io.legado.app.model.localBook.EpubBullet
import io.legado.app.model.localBook.EpubDrawCommand
import io.legado.app.model.localBook.EpubImageBox
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
    var fallbackChapterPosition: Int = 0
    val epubDecorations = arrayListOf<EpubDecoration>()
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
        if (hasEpubBackground()) return
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
        val scale = max(width / bitmap.width, height / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val rect = RectF(
            left + (width - drawWidth) / 2f,
            top + (height - drawHeight) / 2f,
            left + (width + drawWidth) / 2f,
            top + (height + drawHeight) / 2f
        )
        canvas.save()
        canvas.clipRect(left, top, left + width, top + height)
        canvas.drawBitmap(bitmap, null, rect, view.imagePaint)
        canvas.restore()
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

    private fun drawEpubNativeCommands(view: ContentTextView, canvas: Canvas) {
        if (epubNativeCommands.isEmpty()) return
        val paint = PaintPool.obtain()
        val textPaint = TextPaint(ChapterProvider.contentPaint)
        canvas.withTranslation(ChapterProvider.paddingLeft.toFloat(), ChapterProvider.paddingTop.toFloat()) {
            epubNativeCommands.forEach { command ->
                when (command) {
                    is EpubBlockBox -> drawEpubNativeBlock(this, paint, command)
                    is EpubBullet -> drawEpubNativeBullet(this, textPaint, command)
                    is EpubImageBox -> drawEpubNativeImage(view, this, command)
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
        block.backgroundColor?.takeIf { it != Color.TRANSPARENT }?.let { color ->
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawRoundRect(rect, block.radius, block.radius, paint)
        }
        block.borderColor?.takeIf { it != Color.TRANSPARENT && block.borderWidth > 0f }?.let { color ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = block.borderWidth
            paint.color = color
            canvas.drawRoundRect(rect, block.radius, block.radius, paint)
        }
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
        canvas.drawBitmap(bitmap, null, rect, view.imagePaint)
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
        paint.isUnderlineText = text.underline
        paint.isStrikeThruText = text.strikeThrough
        paint.typeface = if (text.bold) {
            Typeface.create(ChapterProvider.contentPaint.typeface, Typeface.BOLD)
        } else {
            ChapterProvider.contentPaint.typeface
        }
        canvas.drawText(text.text, text.x, text.baseline + text.baselineShift, paint)
        paint.isUnderlineText = false
        paint.isStrikeThruText = false
    }

    fun render(view: ContentTextView): Boolean {
        if (!isCompleted) return false
        val recorderHeight = if (hasEpubBackground()) {
            max(renderHeight, ChapterProvider.viewHeight) + 10.dpToPx()
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
            if (hasEpubBackground() || epubNativeCommands.isNotEmpty()) ChapterProvider.viewHeight else 0
        } else {
            ceil(lines.last().lineBottom).toInt()
        }
        epubNativeCommands.maxOfOrNull { command ->
            when (command) {
                is EpubBlockBox -> ChapterProvider.paddingTop + command.y + command.height
                is EpubBullet -> ChapterProvider.paddingTop + command.baseline + command.size
                is EpubImageBox -> ChapterProvider.paddingTop + command.y + command.height
                is EpubPageColor -> ChapterProvider.viewHeight.toFloat()
                is EpubRuleLine -> ChapterProvider.paddingTop + command.y + command.strokeWidth
                is EpubTextRun -> ChapterProvider.paddingTop + command.baseline + command.size
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
}
