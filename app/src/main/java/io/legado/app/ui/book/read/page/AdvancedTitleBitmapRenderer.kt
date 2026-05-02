package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.ui.book.read.page.entities.TextPage
import java.util.concurrent.ConcurrentHashMap

object AdvancedTitleBitmapRenderer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }
    private val renderingKeys = ConcurrentHashMap.newKeySet<String>()

    fun key(html: String, width: Int, height: Int): String {
        return "${html.hashCode()}|${width}x$height|${AppConfig.isNightTheme}"
    }

    fun cached(key: String): Bitmap? {
        return synchronized(cache) {
            cache.get(key)
        }
    }

    fun request(
        context: Context,
        page: TextPage,
        key: String,
        html: String,
        width: Int,
        height: Int,
        onReady: () -> Unit
    ) {
        cached(key)?.let { bitmap ->
            page.advancedTitleBitmap = bitmap
            page.advancedTitleBitmapKey = key
            return
        }
        if (!renderingKeys.add(key)) return
        mainHandler.post {
            render(context, page, key, html, width, height, onReady)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun render(
        context: Context,
        page: TextPage,
        key: String,
        html: String,
        width: Int,
        height: Int,
        onReady: () -> Unit
    ) {
        if (width <= 0 || height <= 0) {
            renderingKeys.remove(key)
            return
        }
        val pooledWebView = WebViewPool.acquire(context)
        val webView = pooledWebView.realWebView
        webView.configureForAdvancedTitle()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mainHandler.postDelayed({
                    runCatching {
                        val bitmap = drawWebViewToBitmap(webView, width, height)
                        synchronized(cache) {
                            cache.put(key, bitmap)
                        }
                        page.advancedTitleBitmap = bitmap
                        page.advancedTitleBitmapKey = key
                        page.invalidate()
                        onReady()
                    }.also {
                        renderingKeys.remove(key)
                        WebViewPool.release(pooledWebView)
                    }
                }, 160L)
            }
        }
        webView.loadDataWithBaseURL(
            "https://advanced-title.local/",
            html,
            "text/html",
            "utf-8",
            null
        )
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun WebView.configureForAdvancedTitle() {
        (parent as? ViewGroup)?.removeView(this)
        setBackgroundColor(Color.TRANSPARENT)
        background = null
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
        }
    }

    private fun drawWebViewToBitmap(webView: WebView, width: Int, height: Int): Bitmap {
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        webView.draw(canvas)
        return bitmap
    }
}
