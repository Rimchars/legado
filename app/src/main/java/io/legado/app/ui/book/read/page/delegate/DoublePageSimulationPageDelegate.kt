package io.legado.app.ui.book.read.page.delegate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.utils.screenshot
import kotlin.math.abs
import kotlin.math.cos

class DoublePageSimulationPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val seamShadow = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x22111111, 0x00000000, 0x22111111)
    )
    private val pageEdgeShadow = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x55111111, 0x00111111)
    )

    private var curBitmap: Bitmap? = null
    private var prevBitmap: Bitmap? = null
    private var nextBitmap: Bitmap? = null
    private val bitmapCanvas = Canvas()

    override fun setBitmap() {
        when (mDirection) {
            PageDirection.PREV -> {
                prevBitmap = prevPage.screenshot(prevBitmap, bitmapCanvas)
                curBitmap = curPage.screenshot(curBitmap, bitmapCanvas)
            }

            PageDirection.NEXT -> {
                nextBitmap = nextPage.screenshot(nextBitmap, bitmapCanvas)
                curBitmap = curPage.screenshot(curBitmap, bitmapCanvas)
            }

            else -> Unit
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!isRunning || viewWidth <= 0 || viewHeight <= 0) return
        when (mDirection) {
            PageDirection.NEXT -> drawNext(canvas)
            PageDirection.PREV -> drawPrev(canvas)
            else -> Unit
        }
    }

    private fun drawNext(canvas: Canvas) {
        val cur = curBitmap ?: return
        val next = nextBitmap ?: return
        val halfWidth = viewWidth / 2f
        val progress = displayProgress((-horizontalOffset() / viewWidth).coerceIn(0f, 1f))
        val foldWidth = projectedWidth(halfWidth, progress)

        drawHalf(canvas, cur, leftPage = true, left = 0f, right = halfWidth)
        drawHalf(canvas, next, leftPage = false, left = halfWidth, right = viewWidth.toFloat())
        drawSeam(canvas, halfWidth)

        if (progress < 0.5f) {
            drawHalf(canvas, cur, leftPage = false, left = halfWidth, right = halfWidth + foldWidth)
            drawMovingPageShade(canvas, halfWidth, halfWidth + foldWidth, progress)
            drawEdgeShadow(canvas, halfWidth + foldWidth, leftFacing = false)
        } else {
            drawHalf(canvas, next, leftPage = true, left = halfWidth - foldWidth, right = halfWidth)
            drawMovingPageShade(canvas, halfWidth - foldWidth, halfWidth, progress)
            drawEdgeShadow(canvas, halfWidth - foldWidth, leftFacing = true)
        }
    }

    private fun drawPrev(canvas: Canvas) {
        val cur = curBitmap ?: return
        val prev = prevBitmap ?: return
        val halfWidth = viewWidth / 2f
        val progress = displayProgress((horizontalOffset() / viewWidth).coerceIn(0f, 1f))
        val foldWidth = projectedWidth(halfWidth, progress)

        drawHalf(canvas, prev, leftPage = true, left = 0f, right = halfWidth)
        drawHalf(canvas, cur, leftPage = false, left = halfWidth, right = viewWidth.toFloat())
        drawSeam(canvas, halfWidth)

        if (progress < 0.5f) {
            drawHalf(canvas, cur, leftPage = true, left = halfWidth - foldWidth, right = halfWidth)
            drawMovingPageShade(canvas, halfWidth - foldWidth, halfWidth, progress)
            drawEdgeShadow(canvas, halfWidth - foldWidth, leftFacing = true)
        } else {
            drawHalf(canvas, prev, leftPage = false, left = halfWidth, right = halfWidth + foldWidth)
            drawMovingPageShade(canvas, halfWidth, halfWidth + foldWidth, progress)
            drawEdgeShadow(canvas, halfWidth + foldWidth, leftFacing = false)
        }
    }

    private fun horizontalOffset(): Float {
        return touchX - startX
    }

    private fun displayProgress(progress: Float): Float {
        if (!isStarted) return progress
        val eased = 1f - (1f - progress) * (1f - progress)
        return (0.25f * progress + 0.75f * eased).coerceIn(0f, 1f)
    }

    private fun projectedWidth(halfWidth: Float, progress: Float): Float {
        val radians = progress * Math.PI
        return (halfWidth * abs(cos(radians))).toFloat().coerceAtLeast(1f)
    }

    private fun drawHalf(
        canvas: Canvas,
        bitmap: Bitmap,
        leftPage: Boolean,
        left: Float,
        right: Float
    ) {
        if (right <= left) return
        val sourceHalf = bitmap.width / 2
        if (leftPage) {
            srcRect.set(0, 0, sourceHalf, bitmap.height)
        } else {
            srcRect.set(sourceHalf, 0, bitmap.width, bitmap.height)
        }
        dstRect.set(left, 0f, right, viewHeight.toFloat())
        canvas.drawBitmap(bitmap, srcRect, dstRect, pagePaint)
    }

    private fun drawSeam(canvas: Canvas, centerX: Float) {
        val half = 18
        seamShadow.setBounds(
            (centerX - half).toInt(),
            0,
            (centerX + half).toInt(),
            viewHeight
        )
        seamShadow.draw(canvas)
    }

    private fun drawMovingPageShade(canvas: Canvas, left: Float, right: Float, progress: Float) {
        if (right <= left) return
        val alpha = (42 * (1f - abs(progress - 0.5f) * 2f)).toInt().coerceIn(0, 42)
        if (alpha <= 0) return
        shadePaint.color = alpha shl 24
        canvas.drawRect(left, 0f, right, viewHeight.toFloat(), shadePaint)
    }

    private fun drawEdgeShadow(canvas: Canvas, edgeX: Float, leftFacing: Boolean) {
        val width = 34
        val left = if (leftFacing) edgeX - width else edgeX
        val right = if (leftFacing) edgeX else edgeX + width
        if (right <= 0f || left >= viewWidth) return
        pageEdgeShadow.setOrientation(
            if (leftFacing) {
                GradientDrawable.Orientation.RIGHT_LEFT
            } else {
                GradientDrawable.Orientation.LEFT_RIGHT
            }
        )
        pageEdgeShadow.setBounds(left.toInt(), 0, right.toInt(), viewHeight)
        pageEdgeShadow.draw(canvas)
    }

    override fun onAnimStart(animationSpeed: Int) {
        val distanceX = when (mDirection) {
            PageDirection.NEXT -> {
                if (isCancel) {
                    (startX - touchX).coerceAtLeast(0f)
                } else {
                    -(touchX + (viewWidth - startX))
                }
            }

            PageDirection.PREV -> {
                if (isCancel) {
                    -(touchX - startX).coerceAtLeast(0f)
                } else {
                    viewWidth - (touchX - startX)
                }
            }

            else -> 0f
        }
        startScroll(touchX.toInt(), 0, distanceX.toInt(), 0, animationSpeed)
    }

    override fun onAnimStop() {
        if (!isCancel) {
            readView.fillPage(mDirection)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        curBitmap?.recycle()
        prevBitmap?.recycle()
        nextBitmap?.recycle()
        curBitmap = null
        prevBitmap = null
        nextBitmap = null
    }
}
