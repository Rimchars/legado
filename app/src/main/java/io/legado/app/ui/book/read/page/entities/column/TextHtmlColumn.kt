package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.text.TextPaint
import androidx.annotation.Keep
import io.legado.app.help.TextViewTagHandler.Companion.HR_PLACE_CHAR
import io.legado.app.help.TextViewTagHandler.Companion.HR_PLACE_STR
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 带html样式的文字列
 */
@Keep
data class TextHtmlColumn(
    override var start: Float,
    override var end: Float,
    override val charData: String,
    val mTextSize: Float,
    val mTextColor: Int?,
    val linkUrl: String?,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrikethrough: Boolean = false,
    val backgroundColor: Int? = null
) : TextBaseColumn {

    override var textLine: TextLine = emptyTextLine

    private val textPaint: TextPaint by lazy {
        TextPaint(ChapterProvider.contentPaint).apply {
            textSize = mTextSize
        }
    }

    override var selected: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
            }
            field = value
        }

    override var isSearchResult: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
                if (value) {
                    textLine.searchResultColumnCount++
                } else {
                    textLine.searchResultColumnCount--
                }
            }
            field = value
        }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val y = textLine.lineBase - textLine.lineTop
        if (linkUrl != null) {
            textPaint.run {
                color = ReadBookConfig.textAccentColor
                isUnderlineText = true
                isStrikeThruText = false
                isFakeBoldText = isBold
                textSkewX = if (isItalic) -0.25f else 0f
            }
            drawText(view, canvas, y, textPaint)
            return
        }
        textPaint.run {
            color = if (textLine.isReadAloud || isSearchResult) {
                ReadBookConfig.textAccentColor
            } else {
                mTextColor ?: ReadBookConfig.textColor
            }
            isUnderlineText = isUnderline
            isStrikeThruText = isStrikethrough
            isFakeBoldText = isBold
            textSkewX = if (isItalic) -0.25f else 0f
        }
        drawText(view, canvas, y, textPaint)
    }

    private fun drawText(view: ContentTextView, canvas: Canvas, y: Float, textPaint: TextPaint) {
        backgroundColor?.takeIf { it != Color.TRANSPARENT }?.let { color ->
            val oldColor = textPaint.color
            textPaint.color = color
            canvas.drawRect(start, 0f, end, textLine.height, textPaint)
            textPaint.color = oldColor
        }
        if (charData == HR_PLACE_STR) {
            canvas.drawRect(start, 0f, end, 3f, textPaint)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val letterSpacing = textPaint.letterSpacing * textPaint.textSize
            val letterSpacingHalf = letterSpacing * 0.5f
            canvas.drawText(charData, start + letterSpacingHalf, y, textPaint)
        } else {
            canvas.drawText(charData, start, y, textPaint)
        }
        if (selected) {
            canvas.drawRect(start, 0f, end, textLine.height, view.selectedPaint)
        }
    }

}
