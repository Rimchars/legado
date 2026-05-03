package io.legado.app.ui.book.read

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import io.legado.app.ui.rss.read.VisibleWebView

class ReadContentWebView(
    context: Context,
    attrs: AttributeSet? = null
) : VisibleWebView(context, attrs) {

    var onSingleTap: (() -> Unit)? = null
    var onScrollMetricsChanged: ((offset: Int, range: Int) -> Unit)? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return false
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val range = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
        onScrollMetricsChanged?.invoke(t.coerceAtLeast(0), range)
    }
}
