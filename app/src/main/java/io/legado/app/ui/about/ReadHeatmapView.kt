package io.legado.app.ui.about

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.min

data class ReadHeatmapCell(
    val date: LocalDate,
    val readTime: Long
)

class ReadHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellRect = RectF()
    private val cellGap = 4f.dpToPx()
    private val minCellSize = 8f.dpToPx()
    private val cornerRadius = 4f.dpToPx()

    private var cells: List<ReadHeatmapCell> = emptyList()
    private var accentColor: Int = 0
    private var surfaceColor: Int = 0
    private var emptyCellColor: Int = 0

    fun submit(
        entries: List<ReadHeatmapCell>,
        accentColor: Int,
        surfaceColor: Int
    ) {
        cells = entries
        this.accentColor = accentColor
        this.surfaceColor = surfaceColor
        emptyCellColor = ColorUtils.blendColors(surfaceColor, accentColor, 0.08f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cells.isEmpty()) return

        val columns = ceil(cells.size / 7f).toInt().coerceAtLeast(1)
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (availableWidth <= 0f || availableHeight <= 0f) return

        val cellSizeByWidth = (availableWidth - cellGap * (columns - 1)) / columns
        val cellSizeByHeight = (availableHeight - cellGap * 6) / 7f
        val cellSize = min(cellSizeByWidth, cellSizeByHeight).coerceAtLeast(minCellSize)
        val contentWidth = cellSize * columns + cellGap * (columns - 1)
        val contentHeight = cellSize * 7 + cellGap * 6
        val startX = paddingLeft + (availableWidth - contentWidth) / 2f
        val startY = paddingTop + (availableHeight - contentHeight) / 2f
        val maxReadTime = cells.maxOfOrNull { it.readTime }?.coerceAtLeast(1L) ?: 1L

        cells.forEachIndexed { index, cell ->
            val column = index / 7
            val row = index % 7
            val left = startX + column * (cellSize + cellGap)
            val top = startY + row * (cellSize + cellGap)
            cellRect.set(left, top, left + cellSize, top + cellSize)
            cellPaint.color = resolveCellColor(cell.readTime, maxReadTime)
            canvas.drawRoundRect(cellRect, cornerRadius, cornerRadius, cellPaint)
        }
    }

    private fun resolveCellColor(readTime: Long, maxReadTime: Long): Int {
        if (readTime <= 0L) return emptyCellColor
        val ratio = (readTime.toFloat() / maxReadTime.toFloat()).coerceIn(0f, 1f)
        val fraction = when {
            ratio >= 0.85f -> 0.92f
            ratio >= 0.65f -> 0.78f
            ratio >= 0.4f -> 0.6f
            else -> 0.36f
        }
        return ColorUtils.blendColors(surfaceColor, accentColor, fraction)
    }
}
