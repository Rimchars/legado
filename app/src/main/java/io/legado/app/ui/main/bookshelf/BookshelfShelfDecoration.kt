package io.legado.app.ui.main.bookshelf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

class BookshelfShelfDecoration(
    context: Context,
    private val spanCountProvider: () -> Int
) : RecyclerView.ItemDecoration() {

    private val shelfPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shelfRect = RectF()
    private val backRect = RectF()
    private val shelfHeight = 20.dpToPx().toFloat()
    private val shelfGap = 10.dpToPx().toFloat()
    private val sideWidth = 8.dpToPx().toFloat()
    private val horizontalInset = 8.dpToPx().toFloat()
    private val radius = UiCorner.scaledDp(12f)

    init {
        val cardColor = context.getCompatColor(R.color.background_card)
        val textColor = context.getCompatColor(R.color.primaryText)
        backPaint.color = ColorUtils.blendARGB(cardColor, textColor, 0.035f)
        shelfPaint.color = ColorUtils.blendARGB(cardColor, textColor, 0.07f)
        sidePaint.color = ColorUtils.setAlphaComponent(textColor, 18)
        shadowPaint.color = ColorUtils.setAlphaComponent(textColor, 36)
        highlightPaint.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 96)
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (!AppConfig.bookshelfShelfEffect) return
        val spanCount = spanCountProvider()
        if (spanCount < 2 || parent.childCount == 0) return

        val rows = linkedMapOf<Int, RowBounds>()
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val row = position / spanCount
            val cover = child.findViewById<View>(R.id.iv_cover)
            val coverTop = cover?.let { child.top + it.top + child.translationY }
                ?: (child.top + child.translationY)
            val coverBottom = cover?.let { child.top + it.bottom + child.translationY }
                ?: (child.bottom + child.translationY)
            rows[row] = rows[row]?.include(coverTop, coverBottom)
                ?: RowBounds(coverTop, coverBottom)
        }

        val left = parent.paddingLeft + horizontalInset
        val right = parent.width - parent.paddingRight - horizontalInset
        rows.values.forEach { bounds ->
            val backTop = bounds.top - shelfGap
            val shelfTop = bounds.bottom + shelfGap
            val shelfBottom = shelfTop + shelfHeight
            val backBottom = shelfBottom

            backRect.set(left, backTop, right, backBottom)
            canvas.drawRoundRect(backRect, radius, radius, backPaint)

            shelfRect.set(left + sideWidth, shelfTop, right - sideWidth, shelfBottom)
            canvas.drawRoundRect(shelfRect, radius * 0.55f, radius * 0.55f, shadowPaint)
            shelfRect.set(left + sideWidth, shelfTop - 1.dpToPx(), right - sideWidth, shelfTop + 3.dpToPx())
            canvas.drawRoundRect(shelfRect, radius * 0.45f, radius * 0.45f, highlightPaint)
            shelfRect.set(left + sideWidth, shelfTop, right - sideWidth, shelfBottom - 3.dpToPx())
            canvas.drawRoundRect(shelfRect, radius * 0.55f, radius * 0.55f, shelfPaint)

            canvas.drawRect(left, backTop + radius, left + sideWidth, shelfBottom - radius * 0.4f, sidePaint)
            canvas.drawRect(right - sideWidth, backTop + radius, right, shelfBottom - radius * 0.4f, sidePaint)
        }
    }

    private data class RowBounds(
        val top: Float,
        val bottom: Float
    ) {
        fun include(otherTop: Float, otherBottom: Float): RowBounds {
            return RowBounds(minOf(top, otherTop), maxOf(bottom, otherBottom))
        }
    }
}
