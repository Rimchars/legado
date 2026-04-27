package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager

class NoSwipeViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewPager(context, attrs) {

    var userSwipeEnabled: Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return userSwipeEnabled && super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return userSwipeEnabled && super.onTouchEvent(ev)
    }
}
