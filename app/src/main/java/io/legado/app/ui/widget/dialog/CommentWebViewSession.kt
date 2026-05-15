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
import java.util.ArrayDeque
import kotlin.random.Random

class CommentWebViewSession {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val allWebViews = mutableListOf<PooledWebView>()
    private val idlePool = ArrayDeque<PooledWebView>()
    private val resettingIds = mutableSetOf<String>()
    private val resetTokens = mutableMapOf<String, Int>()
    private var destroyed = false

    val isPrepared: Boolean
        get() = !destroyed && allWebViews.any { !it.isInUse }

    fun prepare(context: Context) {
        runOnMain {
            prepareOnMain(context.applicationContext)
        }
    }

    fun acquire(context: Context): PooledWebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CommentWebViewSession.acquire must run on main thread"
        }
        if (destroyed) {
            clearStateOnMain()
            destroyed = false
        }
        val pooled = pollIdle()
            ?: allWebViews.firstOrNull { !it.isInUse && it.id !in resettingIds }
            ?: if (allWebViews.size < MAX_WEB_VIEW_COUNT) {
                createNewWebView().also(allWebViews::add)
            } else {
                allWebViews
                    .filter { !it.isInUse }
                    .minByOrNull { it.lastUseTime }
                    ?: error("No idle comment WebView")
            }
        invalidateReset(pooled)
        idlePool.remove(pooled)
        resettingIds.remove(pooled.id)
        pooled.upContext(context)
        pooled.isInUse = true
        pooled.realWebView.run {
            animate().cancel()
            clearAnimation()
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
            if (!allWebViews.contains(pooled)) {
                destroyPooled(pooled)
                return@runOnMain
            }
            resetForReuseOnMain(pooled)
        }
    }

    fun releaseAfterDelay(delayMillis: Long = 0L) {
        destroy()
    }

    fun destroy() {
        runOnMain {
            destroyOnMain()
        }
    }

    fun trimMemory(level: Int) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        runOnMain {
            trimIdleOnMain()
        }
    }

    private fun prepareOnMain(context: Context) {
        if (destroyed) {
            clearStateOnMain()
            destroyed = false
        }
        if (idlePool.isNotEmpty() || allWebViews.size >= MAX_WEB_VIEW_COUNT) return
        createNewWebView().also { pooled ->
            pooled.upContext(context)
            pooled.isInUse = false
            pooled.lastUseTime = System.currentTimeMillis()
            pooled.realWebView.run {
                setBackgroundColor(Color.TRANSPARENT)
                visibility = WebView.INVISIBLE
                alpha = 0f
                onPause()
            }
            allWebViews.add(pooled)
            offerIdle(pooled)
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
    private fun resetForReuseOnMain(pooled: PooledWebView) {
        val webView = pooled.realWebView
        (webView.parent as? ViewGroup)?.removeView(webView)
        idlePool.remove(pooled)
        pooled.isInUse = false
        pooled.lastUseTime = System.currentTimeMillis()
        pooled.upContext(appCtx)
        val resetToken = nextResetToken(pooled)
        resettingIds.add(pooled.id)
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
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != BLANK_HTML || resetTokens[pooled.id] != resetToken || pooled.isInUse) {
                        return
                    }
                    view?.settings?.apply {
                        javaScriptEnabled = false
                        javaScriptEnabled = true
                        blockNetworkImage = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = false
                        loadWithOverviewMode = false
                        textZoom = 100
                    }
                    view?.onPause()
                    resettingIds.remove(pooled.id)
                    offerIdle(pooled)
                }
            }
            loadUrl(BLANK_HTML)
            clearHistory()
            alpha = 1f
            visibility = WebView.VISIBLE
            translationY = 0f
            onPause()
        }
    }

    private fun destroyOnMain() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        allWebViews.toList().forEach(::destroyPooled)
        clearStateOnMain()
    }

    private fun trimIdleOnMain() {
        allWebViews.filter { !it.isInUse }.toList().forEach(::destroyPooled)
        if (allWebViews.isEmpty()) {
            destroyed = true
        }
    }

    private fun destroyPooled(pooled: PooledWebView) {
        idlePool.remove(pooled)
        resettingIds.remove(pooled.id)
        resetTokens.remove(pooled.id)
        allWebViews.remove(pooled)
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

    private fun clearStateOnMain() {
        idlePool.clear()
        resettingIds.clear()
        resetTokens.clear()
        allWebViews.clear()
    }

    private fun pollIdle(): PooledWebView? {
        while (idlePool.isNotEmpty()) {
            val pooled = idlePool.pollLast()
            if (pooled != null && !pooled.isInUse && pooled.id !in resettingIds && allWebViews.contains(pooled)) {
                return pooled
            }
        }
        return null
    }

    private fun offerIdle(pooled: PooledWebView) {
        if (destroyed || pooled.isInUse || pooled.id in resettingIds || !allWebViews.contains(pooled)) return
        idlePool.remove(pooled)
        idlePool.offerLast(pooled)
    }

    private fun nextResetToken(pooled: PooledWebView): Int {
        val token = (resetTokens[pooled.id] ?: 0) + 1
        resetTokens[pooled.id] = token
        return token
    }

    private fun invalidateReset(pooled: PooledWebView) {
        resetTokens[pooled.id] = (resetTokens[pooled.id] ?: 0) + 1
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
        private const val MAX_WEB_VIEW_COUNT = 2
        private const val BLANK_HTML = "about:blank"
    }
}