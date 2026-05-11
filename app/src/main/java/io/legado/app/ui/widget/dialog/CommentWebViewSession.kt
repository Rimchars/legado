package io.legado.app.ui.widget.dialog

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebViewPool
import splitties.init.appCtx

class CommentWebViewSession {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pooledWebView: PooledWebView? = null
    private var destroyed = false

    val isPrepared: Boolean
        get() = pooledWebView?.isDestroyed == false

    fun prepare(context: Context) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            prepareOnMain(context.applicationContext)
        } else {
            mainHandler.post { prepareOnMain(context.applicationContext) }
        }
    }

    fun acquire(context: Context): PooledWebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CommentWebViewSession.acquire must run on main thread"
        }
        destroyed = false
        val pooled = pooledWebView?.takeIf { !it.isDestroyed } ?: WebViewPool.acquire(context).also {
            pooledWebView = it
        }
        pooled.upContext(context)
        pooled.realWebView.apply {
            visibility = View.VISIBLE
            alpha = 1f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        return pooled
    }

    fun detachForReuse(pooled: PooledWebView) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detachForReuseOnMain(pooled)
        } else {
            mainHandler.post { detachForReuseOnMain(pooled) }
        }
    }

    fun destroy(releaseToPool: Boolean = true) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyOnMain(releaseToPool)
        } else {
            mainHandler.post { destroyOnMain(releaseToPool) }
        }
    }

    private fun prepareOnMain(context: Context) {
        if (destroyed || pooledWebView?.isDestroyed == false) return
        pooledWebView = WebViewPool.acquire(context).also { pooled ->
            pooled.realWebView.apply {
                visibility = View.INVISIBLE
                alpha = 0f
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                onPause()
            }
        }
    }

    private fun detachForReuseOnMain(pooled: PooledWebView) {
        if (pooledWebView !== pooled) {
            WebViewPool.releaseForFastReuse(pooled)
            return
        }
        pooled.realWebView.apply {
            (parent as? ViewGroup)?.removeView(this)
            animate().cancel()
            clearAnimation()
            stopLoading()
            clearFocus()
            setOnLongClickListener(null)
            setOnTouchListener(null)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                setOnScrollChangeListener(null)
            }
            setDownloadListener(null)
            outlineProvider = null
            clipToOutline = false
            webChromeClient = null
            webViewClient = WebViewClient()
            removeJavascriptInterface(WebJsExtensions.nameBasic)
            removeJavascriptInterface(WebJsExtensions.nameJava)
            removeJavascriptInterface(WebJsExtensions.nameSource)
            removeJavascriptInterface(WebJsExtensions.nameCache)
            settings.apply {
                blockNetworkImage = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = false
                loadWithOverviewMode = false
                textZoom = 100
            }
            alpha = 0f
            translationY = 0f
            visibility = View.INVISIBLE
            onPause()
        }
        pooled.upContext(appCtx)
    }

    private fun destroyOnMain(releaseToPool: Boolean) {
        destroyed = true
        val pooled = pooledWebView ?: return
        pooledWebView = null
        if (releaseToPool) {
            WebViewPool.release(pooled)
        } else {
            WebViewPool.discard(pooled)
        }
    }
}