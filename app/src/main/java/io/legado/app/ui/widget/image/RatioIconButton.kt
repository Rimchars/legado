package io.legado.app.ui.widget.image

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton
import kotlin.math.roundToInt

class RatioIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageButton(context, attrs) {

    private val iconRatio = 0.58f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val iconSize = (minOf(w, h) * iconRatio).roundToInt()
        val horizontal = ((w - iconSize) / 2f).roundToInt().coerceAtLeast(0)
        val vertical = ((h - iconSize) / 2f).roundToInt().coerceAtLeast(0)
        setPadding(horizontal, vertical, horizontal, vertical)
    }
}
