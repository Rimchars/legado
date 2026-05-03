package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

class PassiveWebView(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return false
    }

    override fun performClick(): Boolean {
        return false
    }

    override fun performLongClick(): Boolean {
        return false
    }
}
