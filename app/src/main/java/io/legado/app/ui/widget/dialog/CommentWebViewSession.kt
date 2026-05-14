package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.ui.rss.read.VisibleWebView
import io.legado.app.utils.setDarkeningAllowed
import splitties.init.appCtx
import kotlin.random.Random

/**
 * 阅读页段评专用 WebView 会话。
 *
 * 这个类不接入全局 WebViewPool，避免正文段评与 startBrowser、登录、RSS 等其它 WebView 共用实例。
 * WebView 创建和操作仍必须在主线程执行；规则解析和网络请求继续由调用方放到 IO 线程。
 */
class CommentWebViewSession {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pooledWebView: PooledWebView? = null
    private var delayedDestroyRunnable: Runnable? = null
    private var destroyed = false

    val isPrepared: Boolean
        get() = pooledWebView != null && !destroyed

    fun prepare(context: Context) {
        runOnMain {
            prepareOnMain(context.applicationContext)
        }
    }

    fun acquire(context: Context): PooledWebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CommentWebViewSession.acquire must run on main thread"
        }
        cancelDelayedDestroyOnMain()
        destroyed = false
        val pooled = pooledWebView ?: createNewWebView().also {
            pooledWebView = it
        }
        pooled.upContext(context)
        pooled.isInUse = true
        pooled.realWebView.run {
            animate().cancel()
            settings.setDarkeningAllowed(AppConfig.isNightTheme)
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 1f
            visibility = WebView.VISIBLE
            translationY = 0f
            resumeTimers()
            onResume()
        }
        return pooled
    }

    fun detachForReuse(pooled: PooledWebView) {
        runOnMain {
            if (pooledWebView !== pooled) {
                destroyPooled(pooled)
                return@runOnMain
            }
            detachForReuseOnMain(pooled)
        }
    }

    fun releaseAfterDelay(delayMillis: Long = RELEASE_AFTER_READING_DELAY) {
        runOnMain {
            if (pooledWebView == null) return@runOnMain
            cancelDelayedDestroyOnMain()
            delayedDestroyRunnable = Runnable {
                delayedDestroyRunnable = null
                destroyOnMain()
            }
            mainHandler.postDelayed(delayedDestroyRunnable!!, delayMillis)
        }
    }

    fun destroy() {
        runOnMain {
            destroyOnMain()
        }
    }

    fun trimMemory(level: Int) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        destroy()
    }

    private fun prepareOnMain(context: Context) {
        if (destroyed) {
            pooledWebView = null
            destroyed = false
        }
        cancelDelayedDestroyOnMain()
        if (pooledWebView == null) {
            pooledWebView = createNewWebView().also { pooled ->
                pooled.upContext(context)
                pooled.realWebView.run {
                    setBackgroundColor(Color.TRANSPARENT)
                    visibility = WebView.INVISIBLE
                    alpha = 0f
                    onPause()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewWebView(): PooledWebView {
        val webView = VisibleWebView(MutableContextWrapper(appCtx))
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.settings.apply {
            javaScriptEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        return PooledWebView(webView, generateId())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun detachForReuseOnMain(pooled: PooledWebView) {
        val webView = pooled.realWebView
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.run {
            animate().cancel()
            clearAnimation()
            stopLoading()
            clearFocus()
            setOnLongClickListener(null)
            setOnTouchListener(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
            loadUrl(BLANK_HTML)
            clearHistory()
            alpha = 1f
            visibility = WebView.VISIBLE
            translationY = 0f
            onPause()
        }
        pooled.isInUse = false
        pooled.lastUseTime = System.currentTimeMillis()
        pooled.upContext(appCtx)
        releaseAfterDelay()
    }

    private fun destroyOnMain() {
        cancelDelayedDestroyOnMain()
        destroyed = true
        val pooled = pooledWebView ?: return
        pooledWebView = null
        destroyPooled(pooled)
    }

    private fun destroyPooled(pooled: PooledWebView) {
        kotlin.runCatching {
            val webView = pooled.realWebView
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.clearHistory()
            webView.clearCache(false)
            webView.removeAllViews()
            webView.destroy()
        }
    }

    private fun cancelDelayedDestroyOnMain() {
        delayedDestroyRunnable?.let(mainHandler::removeCallbacks)
        delayedDestroyRunnable = null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun generateId(): String {
        return "comment_web_${System.currentTimeMillis()}_${Random.nextLong()}"
    }

    companion object {
        val shared: CommentWebViewSession by lazy { CommentWebViewSession() }
        private const val RELEASE_AFTER_READING_DELAY = 60_000L
        private const val BLANK_HTML = "about:blank"
    }
}