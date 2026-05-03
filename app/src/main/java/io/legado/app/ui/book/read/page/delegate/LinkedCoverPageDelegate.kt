package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.animation.PathInterpolator
import android.view.animation.Interpolator
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection

class LinkedCoverPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    private val edgeShadowDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x66111111, 0x00000000)
    ).apply {
        gradientType = GradientDrawable.LINEAR_GRADIENT
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linkedOffset get() = viewWidth * 0.2f
    private val maxMaskAlpha = 102
    private val maxCurrentMaskAlpha = 76

    override fun scrollInterpolator(): Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    override fun onDraw(canvas: Canvas) {
        if (!isRunning) return
        val offsetX = touchX - startX
        if ((mDirection == PageDirection.NEXT && offsetX > 0) ||
            (mDirection == PageDirection.PREV && offsetX < 0)
        ) {
            return
        }

        when (mDirection) {
            PageDirection.NEXT -> drawNext(canvas, offsetX)
            PageDirection.PREV -> drawPrev(canvas, offsetX)
            else -> Unit
        }
    }

    private fun drawNext(canvas: Canvas, offsetX: Float) {
        val progress = displayProgress((-offsetX / viewWidth).coerceIn(0f, 1f))
        val currentLeft = -viewWidth * progress
        val currentRight = viewWidth + currentLeft
        val nextLeft = linkedOffset * (1f - progress)

        if (currentRight < viewWidth) {
            canvas.withClip(currentRight, 0f, viewWidth.toFloat(), viewHeight.toFloat()) {
                withTranslation(nextLeft) {
                    nextRecorder.draw(this)
                }
                drawNextMask(this, currentRight, viewWidth.toFloat(), progress)
            }
        }
        canvas.withTranslation(currentLeft) {
            curRecorder.draw(this)
        }
        addEdgeShadow(currentRight, canvas)
    }

    private fun drawPrev(canvas: Canvas, offsetX: Float) {
        val progress = displayProgress((offsetX / viewWidth).coerceIn(0f, 1f))
        val prevLeft = -viewWidth + viewWidth * progress
        val currentLeft = linkedOffset * progress

        canvas.withTranslation(currentLeft) {
            curRecorder.draw(this)
            drawCurrentMask(this, progress)
        }
        canvas.withTranslation(prevLeft) {
            prevRecorder.draw(this)
        }
        addEdgeShadow((prevLeft + viewWidth).coerceIn(0f, viewWidth.toFloat()), canvas)
    }

    private fun displayProgress(progress: Float): Float {
        return if (isStarted) smoothProgress(progress) else progress
    }

    private fun smoothProgress(progress: Float): Float {
        val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        return (0.2f * progress + 0.8f * eased).coerceIn(0f, 1f)
    }

    private fun drawNextMask(canvas: Canvas, left: Float, right: Float, progress: Float) {
        val alpha = (maxMaskAlpha * (1f - progress)).toInt().coerceIn(0, maxMaskAlpha)
        if (alpha <= 0 || right <= left) return
        maskPaint.color = alpha shl 24
        canvas.drawRect(left, 0f, right, viewHeight.toFloat(), maskPaint)
    }

    private fun drawCurrentMask(canvas: Canvas, progress: Float) {
        val alpha = (maxCurrentMaskAlpha * progress).toInt().coerceIn(0, maxCurrentMaskAlpha)
        if (alpha <= 0) return
        maskPaint.color = alpha shl 24
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), maskPaint)
    }

    private fun addEdgeShadow(left: Float, canvas: Canvas) {
        if (left <= 0f || left >= viewWidth) return
        canvas.withTranslation(left) {
            edgeShadowDrawable.draw(canvas)
        }
    }

    override fun setViewSize(width: Int, height: Int) {
        super.setViewSize(width, height)
        edgeShadowDrawable.setBounds(0, 0, 36, viewHeight)
    }

    override fun onAnimStop() {
        if (!isCancel) {
            readView.fillPage(mDirection)
        }
    }

    override fun onAnimStart(animationSpeed: Int) {
        val distanceX = when (mDirection) {
            PageDirection.NEXT -> {
                if (isCancel) {
                    var dis = viewWidth - startX + touchX
                    if (dis > viewWidth) {
                        dis = viewWidth.toFloat()
                    }
                    viewWidth - dis
                } else {
                    -(touchX + (viewWidth - startX))
                }
            }

            else -> {
                if (isCancel) {
                    -(touchX - startX)
                } else {
                    viewWidth - (touchX - startX)
                }
            }
        }
        startScroll(
            touchX.toInt(),
            0,
            distanceX.toInt(),
            0,
            (animationSpeed * 1.35f).toInt()
        )
    }

}
