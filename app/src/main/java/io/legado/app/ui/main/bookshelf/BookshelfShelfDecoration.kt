package io.legado.app.ui.main.bookshelf

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx

class BookshelfShelfDecoration(
    context: Context,
    private val spanCountProvider: () -> Int
) : RecyclerView.ItemDecoration() {

    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plankPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plankFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contactShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plankRect = RectF()
    private val contactShadowRect = RectF()
    private val topPath = Path()
    private val sideInset = 18.dpToPx().toFloat()
    private val bookToPlankGap = (-2).dpToPx().toFloat()
    private val topHeight = 12.dpToPx().toFloat()
    private val frontHeight = 18.dpToPx().toFloat()
    private val shadowHeight = 10.dpToPx().toFloat()
    private val bottomSpacing = 10.dpToPx()

    init {
        context.resources.displayMetrics
        shadowPaint.color = 0x4A2B160B
        highlightPaint.color = 0x42FFF4DA
        contactShadowPaint.color = 0x59331B10
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

        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        rows.values.forEach { bounds ->
            drawShelfCell(canvas, left, right, bounds)
        }
    }

    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        if (!AppConfig.bookshelfShelfEffect || spanCountProvider() < 2) return
        outRect.bottom += bottomSpacing
    }

    private fun drawShelfCell(canvas: Canvas, left: Float, right: Float, bounds: RowBounds) {
        val plankTop = bounds.bottom + bookToPlankGap
        val topBottom = plankTop + topHeight
        val frontBottom = topBottom + frontHeight
        val visualLeft = left - sideInset
        val visualRight = right + sideInset
        val topLeft = left + sideInset
        val topRight = right - sideInset

        contactShadowRect.set(topLeft, bounds.bottom - 2.dpToPx(), topRight, bounds.bottom + 7.dpToPx())
        canvas.drawRoundRect(contactShadowRect, 6.dpToPx().toFloat(), 6.dpToPx().toFloat(), contactShadowPaint)

        topPath.reset()
        topPath.moveTo(topLeft, plankTop)
        topPath.lineTo(topRight, plankTop)
        topPath.lineTo(visualRight, topBottom)
        topPath.lineTo(visualLeft, topBottom)
        topPath.close()
        topPaint.shader = LinearGradient(
            0f,
            plankTop,
            0f,
            topBottom,
            intArrayOf(0xFFE8BF82.toInt(), 0xFFC58C52.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(topPath, topPaint)
        topPaint.shader = null

        plankRect.set(visualLeft, topBottom, visualRight, frontBottom)
        plankPaint.shader = LinearGradient(
            visualLeft,
            topBottom,
            visualRight,
            frontBottom,
            intArrayOf(0xFFC28B54.toInt(), 0xFFA86B3B.toInt(), 0xFF744225.toInt()),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(plankRect, plankPaint)
        plankPaint.shader = null

        plankRect.set(visualLeft, plankTop, visualRight, plankTop + 1.dpToPx())
        canvas.drawRect(plankRect, highlightPaint)
        plankRect.set(visualLeft, frontBottom - 7.dpToPx(), visualRight, frontBottom)
        plankFrontPaint.shader = LinearGradient(
            0f,
            frontBottom - 7.dpToPx(),
            0f,
            frontBottom,
            0xFF9C6840.toInt(),
            0xFF5A301B.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(plankRect, plankFrontPaint)
        plankFrontPaint.shader = null

        plankRect.set(visualLeft + 8.dpToPx(), frontBottom, visualRight - 8.dpToPx(), frontBottom + shadowHeight)
        canvas.drawRoundRect(plankRect, shadowHeight, shadowHeight, shadowPaint)
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
