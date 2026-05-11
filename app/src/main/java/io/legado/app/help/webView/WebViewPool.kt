package io.legado.app.help.webView

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
import io.legado.app.ui.rss.read.VisibleWebView
import io.legado.app.utils.setDarkeningAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.Stack
import kotlin.math.max
import kotlin.random.Random

object WebViewPool {
    const val BLANK_HTML = "about:blank"
    const val DATA_HTML = "data:text/html;charset=utf-8;base64,"
    // 未使用的、已预初始化的WebView池 (使用栈结构，后进先出，复用缓存)
    private val idlePool = Stack<PooledWebView>()
    // 正在使用的WebView集合
    private val inUsePool = mutableMapOf<String, PooledWebView>()

    private var needInitialize = true
    private val CACHED_WEB_VIEW_MAX_NUM = max(AppConfig.threadCount / 10, 5) // 池子总容量（闲置+使用）
    private const val IDLE_TIME_OUT: Long = 5 * 60 * 1000 // 闲置5分钟后销毁
    private const val IDLE_TIME_OUT_LAST: Long = 30 * 60 * 1000 // 最后一个闲置30分钟后销毁
    private const val RESET_TIME_OUT: Long = 3 * 1000
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val cleanupScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    private var cleanupJob: Job? = null

    // 获取一个WebView
    @Synchronized
    fun acquire(context: Context): PooledWebView {
        val pooledWebView = pollIdleWebView() ?: run {
            if (needInitialize) {
                needInitialize = false
                startCleanupTimer()
            }
            createNewWebView() // 创建新实例
        }
        pooledWebView.upContext(context).apply {
            realWebView.settings.setDarkeningAllowed(AppConfig.isNightTheme) //设置是否夜间
            if (inUsePool.isEmpty()) {
                realWebView.resumeTimers()
            }
            isDestroyed = false
            resetToken++
            isInUse = true
        }
        inUsePool[pooledWebView.id] = pooledWebView
        pooledWebView.realWebView.alpha = 1f
        pooledWebView.realWebView.visibility = android.view.View.VISIBLE
        pooledWebView.realWebView.setBackgroundColor(Color.TRANSPARENT)
        return pooledWebView
    }

    // 释放WebView回池
    @Synchronized
    fun release(pooledWebView: PooledWebView) {
        if (inUsePool.remove(pooledWebView.id) == null) {
            idlePool.remove(pooledWebView)
            destroyWebView(pooledWebView)
            return
        }
        val resetToken = ++pooledWebView.resetToken
        // 重置WebView状态
        pooledWebView.realWebView.run {
            (parent as? ViewGroup)?.removeView(this)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            stopLoading()
            clearFocus() //清除焦点
            setOnLongClickListener(null)
            setOnTouchListener(null)
            setLayerType(android.view.View.LAYER_TYPE_NONE, null)
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
            clearFormData() //清除表单数据
            clearMatches() //清除查找匹配项
            clearDisappearingChildren() //清除消失中的子视图
            clearAnimation() //清除动画
            pooledWebView.upContext(appCtx)
            if (idlePool.size >= CACHED_WEB_VIEW_MAX_NUM - inUsePool.size) {
                // 池子已满，直接销毁
                destroyWebView(pooledWebView)
                return
            }
            webViewClient = object: WebViewClient() {
                @SuppressLint("SetJavaScriptEnabled")
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != BLANK_HTML || pooledWebView.isDestroyed || pooledWebView.resetToken != resetToken) return
                    view?.let{ webview ->
                        webview.settings.apply {
                            javaScriptEnabled = false
                            javaScriptEnabled = true // 禁用再启用来重置js环境，注意需要禁用的订阅源需要再次执行
                            blockNetworkImage = false // 确保允许加载网络图片
                            cacheMode = WebSettings.LOAD_DEFAULT // 重置缓存模式
                            useWideViewPort = false // 恢复默认关闭宽视模式
                            loadWithOverviewMode = false // 恢复默认
                            textZoom = 100
                        }
                        webview.clearHistory()
                        val shouldPauseTimers = synchronized(this@WebViewPool) {
                            inUsePool.isEmpty() && !pooledWebView.isDestroyed && pooledWebView.resetToken == resetToken
                        }
                        if (shouldPauseTimers) {
                            webview.pauseTimers()
                        }
                        webview.onPause()
                    }
                    synchronized(this@WebViewPool) {
                        if (!pooledWebView.isDestroyed && pooledWebView.resetToken == resetToken && !inUsePool.containsKey(pooledWebView.id)) {
                            pooledWebView.isInUse = false
                            pooledWebView.lastUseTime = System.currentTimeMillis()
                            if (!idlePool.contains(pooledWebView)) {
                                idlePool.push(pooledWebView)
                            }
                            ensureCleanupTimer()
                        }
                    }
                }
            }
            loadUrl(BLANK_HTML)
            postDelayed({
                val shouldDestroy = synchronized(this@WebViewPool) {
                    !pooledWebView.isDestroyed
                        && pooledWebView.resetToken == resetToken
                        && !inUsePool.containsKey(pooledWebView.id)
                        && !idlePool.contains(pooledWebView)
                }
                if (shouldDestroy) {
                    destroyWebView(pooledWebView)
                }
            }, RESET_TIME_OUT)
        }
    }


    fun releaseForFastReuse(pooledWebView: PooledWebView) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseForFastReuseOnMain(pooledWebView)
        } else {
            mainHandler.post {
                releaseForFastReuseOnMain(pooledWebView)
            }
        }
    }

    @Synchronized
    private fun releaseForFastReuseOnMain(pooledWebView: PooledWebView) {
        if (inUsePool.remove(pooledWebView.id) == null) {
            idlePool.remove(pooledWebView)
            destroyWebView(pooledWebView)
            return
        }
        pooledWebView.resetToken++
        pooledWebView.realWebView.run {
            (parent as? ViewGroup)?.removeView(this)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            animate().cancel()
            alpha = 1f
            translationY = 0f
            visibility = android.view.View.VISIBLE
            stopLoading()
            clearFocus()
            setOnLongClickListener(null)
            setOnTouchListener(null)
            setLayerType(android.view.View.LAYER_TYPE_NONE, null)
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
            clearFormData()
            clearMatches()
            clearDisappearingChildren()
            clearAnimation()
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                blockNetworkImage = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = false
                loadWithOverviewMode = false
                textZoom = 100
            }
            onPause()
        }
        pooledWebView.upContext(appCtx)
        if (idlePool.size >= CACHED_WEB_VIEW_MAX_NUM - inUsePool.size) {
            destroyWebView(pooledWebView)
            return
        }
        pooledWebView.isInUse = false
        pooledWebView.lastUseTime = System.currentTimeMillis()
        if (!idlePool.contains(pooledWebView)) {
            idlePool.push(pooledWebView)
        }
        ensureCleanupTimer()
    }

    @Synchronized
    fun discard(pooledWebView: PooledWebView) {
        inUsePool.remove(pooledWebView.id)
        idlePool.remove(pooledWebView)
        destroyWebView(pooledWebView)
    }

    private fun pollIdleWebView(): PooledWebView? {
        while (idlePool.isNotEmpty()) {
            val pooledWebView = idlePool.pop()
            if (!pooledWebView.isDestroyed) {
                return pooledWebView
            }
        }
        return null
    }

    private fun ensureCleanupTimer() {
        if (cleanupJob?.isActive != true) {
            needInitialize = false
            startCleanupTimer()
        }
    }

    private fun createNewWebView(): PooledWebView {
        val webView = VisibleWebView(MutableContextWrapper(appCtx))
        preInitWebView(webView)
        return PooledWebView(webView, generateId())
    }

    private fun generateId(): String {
        return "web_${System.currentTimeMillis()}_${Random.nextLong()}"
    }

    // 初始化
    @SuppressLint("SetJavaScriptEnabled")
    private fun preInitWebView(webView: WebView) {
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
    }

    // 定时清理闲置过久的WebView
    private fun startCleanupTimer() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = cleanupScope.launch {
            while (true) {
                delay(30_000) // 每30秒执行一次清理
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<PooledWebView>()
                var shouldCancel = false
                synchronized(this@WebViewPool) {
                    for ((index, pooled) in idlePool.withIndex()) {
                        val timeout = if (index == 0) {
                            IDLE_TIME_OUT_LAST
                        } else {
                            IDLE_TIME_OUT
                        }
                        if (now - pooled.lastUseTime > timeout) {
                            toRemove.add(pooled)
                        }
                    }
                    toRemove.forEach { pooled ->
                        idlePool.remove(pooled)
                        destroyWebView(pooled)
                    }
                    if (idlePool.isEmpty()) {
                        shouldCancel = true
                    }
                }
                if (shouldCancel) {
                    needInitialize = true
                    this@launch.cancel()
                }
            }
        }
    }

    @Synchronized
    private fun destroyWebView(pooledWebView: PooledWebView) {
        inUsePool.remove(pooledWebView.id)
        idlePool.remove(pooledWebView)
        pooledWebView.isInUse = false
        pooledWebView.isDestroyed = true
        pooledWebView.resetToken++
        val action = Runnable {
            try {
                pooledWebView.realWebView.run {
                    (parent as? ViewGroup)?.removeView(this)
                    stopLoading()
                    webChromeClient = null
                    webViewClient = WebViewClient()
                    destroy()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

}
