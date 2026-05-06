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
import io.legado.app.lib.theme.backgroundColor
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
    private val frontHeight = 16.dpToPx().toFloat()
    private val shadowHeight = 10.dpToPx().toFloat()
    private val rowTopSpacing = 14.dpToPx()
    private val bottomSpacing = 12.dpToPx()
    private val topStartColor: Int
    private val topEndColor: Int
    private val frontStartColor: Int
    private val frontMiddleColor: Int
    private val frontEndColor: Int
    private val frontBottomStartColor: Int
    private val frontBottomEndColor: Int

    init {
        val background = context.backgroundColor
        val darkBase = if (ColorUtils.calculateLuminance(background) > 0.5) {
            0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }
        topStartColor = ColorUtils.blendARGB(background, darkBase, 0.08f)
        topEndColor = ColorUtils.blendARGB(background, darkBase, 0.18f)
        frontStartColor = ColorUtils.blendARGB(background, darkBase, 0.16f)
        frontMiddleColor = ColorUtils.blendARGB(background, darkBase, 0.28f)
        frontEndColor = ColorUtils.blendARGB(background, darkBase, 0.40f)
        frontBottomStartColor = ColorUtils.blendARGB(background, darkBase, 0.30f)
        frontBottomEndColor = ColorUtils.blendARGB(background, darkBase, 0.52f)
        shadowPaint.color = ColorUtils.setAlphaComponent(darkBase, 42)
        highlightPaint.color = ColorUtils.setAlphaComponent(background, 72)
        contactShadowPaint.color = ColorUtils.setAlphaComponent(darkBase, 64)
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
            intArrayOf(topStartColor, topEndColor),
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
            intArrayOf(frontStartColor, frontMiddleColor, frontEndColor),
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
            frontBottomStartColor,
            frontBottomEndColor,
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
