package io.legado.app.ui.main.bookshelf

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.utils.dpToPx

class BookshelfShelfDecoration(
    context: Context,
    private val spanCountProvider: () -> Int
) : RecyclerView.ItemDecoration() {

    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plankPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contactShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plankRect = RectF()
    private val contactShadowRect = RectF()
    private val topPath = Path()
    private val frontPath = Path()
    private val sideInset = 18.dpToPx().toFloat()
    private val bookToPlankGap = (-2).dpToPx().toFloat()
    private val topHeight = 12.dpToPx().toFloat()
    private val frontHeight = 11.dpToPx().toFloat()
    private val shadowHeight = 18.dpToPx().toFloat()
    private val bottomEdgeHeight = 1.dpToPx().toFloat()
    private val frontBottomInset = 8.dpToPx().toFloat()
    private val shelfShadowInset = 18.dpToPx().toFloat()
    private val rowTopSpacing = 14.dpToPx()
    private val bottomSpacing = 12.dpToPx()
    private val surfaceColor: Int
    private val toneColor: Int
    private val shadowColor = 0xFF000000.toInt()
    private val topColor: Int
    private val frontColor: Int

    init {
        surfaceColor = context.bottomBackground
        toneColor = if (ColorUtils.calculateLuminance(surfaceColor) > 0.5) {
            0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }
        topColor = ColorUtils.blendARGB(surfaceColor, toneColor, 0.06f)
        frontColor = ColorUtils.blendARGB(surfaceColor, toneColor, 0.16f)
        highlightPaint.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 38)
        shadowPaint.color = ColorUtils.setAlphaComponent(0xFF707070.toInt(), 44)
        contactShadowPaint.color = ColorUtils.setAlphaComponent(shadowColor, 58)
        bottomEdgePaint.color = ColorUtils.setAlphaComponent(shadowColor, 112)
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
            // Item animations move the book views with translation. The shelf is a stable
            // background layer, so it must use layout bounds only and not follow translation.
            val coverTop = cover?.let { child.top + it.top }?.toFloat() ?: child.top.toFloat()
            val coverBottom = cover?.let { child.top + it.bottom }?.toFloat() ?: child.bottom.toFloat()
            rows[row] = rows[row]?.include(coverTop, coverBottom)
                ?: RowBounds(coverTop, coverBottom)
        }

        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        val rowBounds = rows.values.toList()
        rowBounds.forEach { bounds ->
            drawShelfCell(canvas, left, right, bounds)
        }
        drawFillShelves(canvas, parent, left, right, rowBounds)
    }

    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        if (!AppConfig.bookshelfShelfEffect || spanCountProvider() < 2) return
        val position = parent.getChildAdapterPosition(view)
        if (position >= spanCountProvider()) {
            outRect.top += rowTopSpacing
        }
        outRect.bottom += bottomSpacing
    }

    private fun drawShelfCell(canvas: Canvas, left: Float, right: Float, bounds: RowBounds) {
        val plankTop = bounds.bottom + bookToPlankGap
        val topBottom = plankTop + topHeight
        val frontBottom = topBottom + frontHeight
        val visualLeft = left
        val visualRight = right
        val topLeft = left + sideInset
        val topRight = right - sideInset

        contactShadowRect.set(topLeft, bounds.bottom - 1.dpToPx(), topRight, bounds.bottom + 6.dpToPx())
        canvas.drawRoundRect(contactShadowRect, 5.dpToPx().toFloat(), 5.dpToPx().toFloat(), contactShadowPaint)

        topPath.reset()
        topPath.moveTo(topLeft, plankTop)
        topPath.lineTo(topRight, plankTop)
        topPath.lineTo(visualRight, topBottom)
        topPath.lineTo(visualLeft, topBottom)
        topPath.close()
        topPaint.color = topColor
        canvas.drawPath(topPath, topPaint)

        frontPath.reset()
        frontPath.moveTo(visualLeft, topBottom)
        frontPath.lineTo(visualRight, topBottom)
        frontPath.lineTo(visualRight - frontBottomInset, frontBottom)
        frontPath.lineTo(visualLeft + frontBottomInset, frontBottom)
        frontPath.close()
        plankPaint.color = frontColor
        canvas.drawPath(frontPath, plankPaint)

        plankRect.set(visualLeft, topBottom, visualRight, topBottom + 1.dpToPx())
        canvas.drawRect(plankRect, highlightPaint)
        plankRect.set(
            visualLeft + frontBottomInset,
            frontBottom - bottomEdgeHeight,
            visualRight - frontBottomInset,
            frontBottom
        )
        canvas.drawRect(plankRect, bottomEdgePaint)

        plankRect.set(
            visualLeft + frontBottomInset + shelfShadowInset,
            frontBottom,
            visualRight - frontBottomInset - shelfShadowInset,
            frontBottom + shadowHeight
        )
        shadowPaint.shader = LinearGradient(
            0f,
            frontBottom,
            0f,
            frontBottom + shadowHeight,
            ColorUtils.setAlphaComponent(0xFF707070.toInt(), 46),
            ColorUtils.setAlphaComponent(0xFF707070.toInt(), 0),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            plankRect,
            10.dpToPx().toFloat(),
            10.dpToPx().toFloat(),
            shadowPaint
        )
        shadowPaint.shader = null
    }

    private fun drawFillShelves(
        canvas: Canvas,
        parent: RecyclerView,
        left: Float,
        right: Float,
        boundsList: List<RowBounds>
    ) {
        if (boundsList.isEmpty()) return
        val contentBottom = parent.height - parent.paddingBottom.toFloat()
        val first = boundsList.first()
        val rowStride = if (boundsList.size >= 2) {
            (boundsList[1].bottom - first.bottom).takeIf { it > 0f }
        } else {
            null
        } ?: ((first.bottom - first.top) + rowTopSpacing + topHeight + frontHeight + bottomSpacing)
        var nextTop = boundsList.last().top + rowStride
        var nextBottom = boundsList.last().bottom + rowStride
        while (nextBottom + topHeight + frontHeight <= contentBottom) {
            drawShelfCell(canvas, left, right, RowBounds(nextTop, nextBottom))
            nextTop += rowStride
            nextBottom += rowStride
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
