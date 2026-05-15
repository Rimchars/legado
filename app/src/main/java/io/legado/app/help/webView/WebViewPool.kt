package io.legado.app.help.webView

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.rss.read.VisibleWebView
import io.legado.app.utils.setDarkeningAllowed
import io.legado.app.utils.runOnUI
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

    enum class Scope {
        GLOBAL,
        DISCOVERY,
        RSS
    }

    private class ScopePool(
        val scope: Scope,
        val maxCached: Int,
        val idleTimeout: Long,
        val lastIdleTimeout: Long
    ) {
        val idlePool = Stack<PooledWebView>()
        val inUsePool = mutableMapOf<String, PooledWebView>()
        val resettingPool = mutableMapOf<String, PooledWebView>()
        var needInitialize = true
        var cleanupJob: Job? = null
        var destroyJob: Job? = null
    }

    private val globalMaxCached = max(AppConfig.threadCount / 10, 5)
    private const val IDLE_TIME_OUT: Long = 5 * 60 * 1000 // 闲置5分钟后销毁
    private const val IDLE_TIME_OUT_LAST: Long = 30 * 60 * 1000 // 最后一个闲置30分钟后销毁
    private const val SCOPED_WEB_VIEW_MAX_NUM = 2
    private const val SCOPED_IDLE_TIME_OUT: Long = 30 * 1000
    private val cleanupScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    private val pools = mutableMapOf<Scope, ScopePool>()

    private fun pool(scope: Scope): ScopePool {
        return pools.getOrPut(scope) {
            when (scope) {
                Scope.GLOBAL -> ScopePool(scope, globalMaxCached, IDLE_TIME_OUT, IDLE_TIME_OUT_LAST)
                Scope.DISCOVERY, Scope.RSS -> ScopePool(
                    scope,
                    SCOPED_WEB_VIEW_MAX_NUM,
                    SCOPED_IDLE_TIME_OUT,
                    SCOPED_IDLE_TIME_OUT
                )
            }
        }
    }

    // 获取一个WebView
    @Synchronized
    fun acquire(context: Context, scope: Scope = Scope.GLOBAL): PooledWebView {
        val scopePool = pool(scope)
        scopePool.destroyJob?.cancel()
        scopePool.destroyJob = null
        val pooledWebView = if (scopePool.idlePool.isNotEmpty()) {
            scopePool.idlePool.pop() // 复用闲置实例
        } else {
            if (scopePool.needInitialize) {
                scopePool.needInitialize = false
                startCleanupTimer(scopePool)
            }
            createNewWebView(scope) // 创建新实例
        }
        pooledWebView.upContext(context).apply {
            realWebView.settings.setDarkeningAllowed(AppConfig.isNightTheme) //设置是否夜间
            if (scopePool.inUsePool.isEmpty()) {
                realWebView.resumeTimers()
            }
            isDestroyed = false
            isInUse = true
        }
        scopePool.inUsePool[pooledWebView.id] = pooledWebView
        pooledWebView.realWebView.setBackgroundColor(Color.TRANSPARENT)
        return pooledWebView
    }

    // 释放WebView回池
    @Synchronized
    fun release(pooledWebView: PooledWebView) {
        if (pooledWebView.isDestroyed) return
        val scopePool = pool(pooledWebView.scope)
        if (scopePool.inUsePool.remove(pooledWebView.id) == null) {
            scopePool.resettingPool.remove(pooledWebView.id)
            pooledWebView.isDestroyed = true
            pooledWebView.realWebView.destroy()
            return
        }
        scopePool.resettingPool[pooledWebView.id] = pooledWebView
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
            if (scopePool.idlePool.size >= scopePool.maxCached - scopePool.inUsePool.size) {
                // 池子已满，直接销毁
                scopePool.resettingPool.remove(pooledWebView.id)
                pooledWebView.isDestroyed = true
                pooledWebView.realWebView.destroy()
                return
            }
            webViewClient = object: WebViewClient() {
                @SuppressLint("SetJavaScriptEnabled")
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != BLANK_HTML) return
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
                        if (scopePool.inUsePool.isEmpty()) {
                            webview.pauseTimers()
                        }
                        webview.onPause()
                    }
                    pooledWebView.isInUse = false
                    pooledWebView.lastUseTime = System.currentTimeMillis()
                    synchronized(this@WebViewPool) {
                        scopePool.resettingPool.remove(pooledWebView.id)
                        if (!pooledWebView.isDestroyed) {
                            scopePool.idlePool.push(pooledWebView)
                            startCleanupTimer(scopePool)
                        }
                    }
                }
            }
            loadUrl(BLANK_HTML)
        }
    }

    fun scheduleDestroyScope(scope: Scope, delayMillis: Long = SCOPED_IDLE_TIME_OUT) {
        if (scope == Scope.GLOBAL) return
        val scopePool = synchronized(this) { pool(scope) }
        scopePool.destroyJob?.cancel()
        scopePool.destroyJob = cleanupScope.launch {
            delay(delayMillis)
            destroyScope(scope)
        }
    }

    fun destroyScope(scope: Scope) {
        if (scope == Scope.GLOBAL) return
        val toDestroy = synchronized(this) {
            val scopePool = pool(scope)
            scopePool.destroyJob?.cancel()
            scopePool.destroyJob = null
            scopePool.cleanupJob?.cancel()
            scopePool.cleanupJob = null
            scopePool.needInitialize = true
            val list = scopePool.idlePool.toMutableList() +
                scopePool.inUsePool.values +
                scopePool.resettingPool.values
            scopePool.idlePool.clear()
            scopePool.inUsePool.clear()
            scopePool.resettingPool.clear()
            list
        }
        toDestroy.forEach { destroyNow(it) }
    }

    private fun destroyNow(pooledWebView: PooledWebView) {
        pooledWebView.isDestroyed = true
        runOnUI {
            try {
                pooledWebView.realWebView.run {
                    (parent as? ViewGroup)?.removeView(this)
                    stopLoading()
                    loadUrl(BLANK_HTML)
                    destroy()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNewWebView(scope: Scope): PooledWebView {
        val webView = VisibleWebView(MutableContextWrapper(appCtx))
        preInitWebView(webView)
        return PooledWebView(webView, generateId(scope), scope)
    }

    private fun generateId(scope: Scope): String {
        return "web_${scope.name.lowercase()}_${System.currentTimeMillis()}_${Random.nextLong()}"
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
    private fun startCleanupTimer(scopePool: ScopePool) {
        if (scopePool.cleanupJob?.isActive == true) return
        scopePool.cleanupJob = cleanupScope.launch {
            while (true) {
                delay(30_000) // 每30秒执行一次清理
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<PooledWebView>()
                var shouldCancel = false
                synchronized(this@WebViewPool) {
                    for ((index, pooled) in scopePool.idlePool.withIndex()) {
                        val timeout = if (index == 0) {
                            scopePool.lastIdleTimeout
                        } else {
                            scopePool.idleTimeout
                        }
                        if (now - pooled.lastUseTime > timeout) {
                            toRemove.add(pooled)
                        }
                    }
                    toRemove.forEach { pooled ->
                        scopePool.idlePool.remove(pooled)
                        destroyNow(pooled)
                    }
                    if (scopePool.idlePool.isEmpty()) {
                        shouldCancel = true
                    }
                }
                if (shouldCancel) {
                    scopePool.needInitialize = true
                    this@launch.cancel()
                }
            }
        }
    }

}
