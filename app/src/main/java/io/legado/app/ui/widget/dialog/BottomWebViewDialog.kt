package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.databinding.DialogWebViewBinding
import io.legado.app.help.WebCacheManager
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.browser.WebViewActivity.Companion.sessionShowWebLog
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class BottomWebViewDialog() : BottomSheetDialogFragment(R.layout.dialog_web_view) {

    constructor(
        sourceKey: String,
        url: String,
        html: String,
        preloadJs: String? = null
    ) : this() {
        arguments = Bundle().apply {
            putString("sourceKey", sourceKey)
            putString("url", url)
            putString("html", html)
            putString("preloadJs", preloadJs)
        }
    }

    private val binding by viewBinding(DialogWebViewBinding::bind)
    private val behavior by lazy {
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            BottomSheetBehavior.from(sheet)
        }
    }
    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView
    private lateinit var currentActivity: AppCompatActivity
    private var source: BaseSource? = null
    private var successInit = false
    private var isFullScreen = false
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var needClearHistory = true
    private fun init() {
        val activity = requireActivity() as? AppCompatActivity
        if (activity == null) {
            dismiss()
            return
        }
        currentActivity = activity
        pooledWebView = WebViewPool.acquire(currentActivity)
        currentWebView = pooledWebView.realWebView
        binding.webViewContainer.addView(currentWebView)
        successInit = true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
        currentActivity.lifecycleScope.launch {
            val args = arguments ?: return@launch
            val sourceKey = args.getString("sourceKey") ?: return@launch
            val url = args.getString("url") ?: return@launch
            var html = args.getString("html") ?: return@launch
            args.getString("preloadJs")?.let { preloadJs ->
                html = if (html.contains("<head>")) {
                    html.replaceFirst("<head>", "<head><script>(() => {$JS_INJECTION\n$preloadJs\n})();</script>")
                } else {
                    "<head><script>(() => {$JS_INJECTION\n$preloadJs\n})();</script></head>$html"
                }
            }
            appDb.bookSourceDao.getBookSource(sourceKey).let {
                if (it == null) {
                    currentActivity.toastOnUi("no find bookSource")
                    dismiss()
                    return@launch
                }
                source = it
            }
            val analyzeUrl = AnalyzeUrl(url, source = source, coroutineContext = coroutineContext)
            initWebView(analyzeUrl.url, html, analyzeUrl.headerMap)
            currentWebView.post {
                currentWebView.clearHistory()
            }
        }
    }

    private fun initWebView(url: String, html: String, headerMap: HashMap<String, String>) {
        currentWebView.webChromeClient = CustomWebChromeClient()
        currentWebView.addJavascriptInterface(JSInterface(currentActivity), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()
        currentWebView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            headerMap[AppConst.UA_NAME]?.let {
                userAgentString = it
            }
        }
        source?.let {
            val webJsExtensions = WebJsExtensions(it, currentActivity, currentWebView)
            currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
            currentWebView.addJavascriptInterface(it, nameSource)
            currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
        }
        currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
    }


    @Suppress("unused")
    private inner class JSInterface(activity: Activity) {
        private val activityRef: WeakReference<Activity> = WeakReference(activity)

        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.requestedOrientation = when (orientation) {
                        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        "landscape", "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    behavior?.state = BottomSheetBehavior.STATE_HIDDEN
                }
            }
        }
    }

    override fun onDestroyView() {
        if (successInit) {
            WebViewPool.release(pooledWebView)
            currentActivity.keepScreenOn(false)
            currentActivity.toggleSystemBar(true)
        }
        super.onDestroyView()
    }

    inner class CustomWebChromeClient : WebChromeClient() {

//        override fun onProgressChanged(view: WebView?, newProgress: Int) {
//            super.onProgressChanged(view, newProgress)
////            binding.progressBar.setDurProgress(newProgress)
////            binding.progressBar.gone(newProgress == 100)
//        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            isFullScreen = true
            binding.bottomSheet.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            currentActivity.keepScreenOn(true)
            currentActivity.toggleSystemBar(false)
        }

        override fun onHideCustomView() {
            isFullScreen = false
            binding.customWebView.removeAllViews()
            binding.bottomSheet.visible()
            currentActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            currentActivity.keepScreenOn(false)
            currentActivity.toggleSystemBar(true)
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            behavior?.state = BottomSheetBehavior.STATE_HIDDEN
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            if (sessionShowWebLog) {
                val source = source ?: return false
                val consoleException =
                    Exception("${consoleMessage.messageLevel().name}: \n${consoleMessage.message()}\n-Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                val message = source.getTag() + ": ${consoleMessage.message()}"
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.LOG -> AppLog.put(message)
                    ConsoleMessage.MessageLevel.DEBUG -> AppLog.put(message, consoleException)
                    ConsoleMessage.MessageLevel.WARNING -> AppLog.put(message, consoleException)
                    ConsoleMessage.MessageLevel.ERROR -> AppLog.put(message, consoleException)
                    ConsoleMessage.MessageLevel.TIP -> AppLog.put(message)
                    else -> AppLog.put(message)
                }
                return true
            }
            return false
        }
    }

    inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?, request: WebResourceRequest?
        ): Boolean {
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url?.let {
                return shouldOverrideUrlLoading(it.toUri())
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }

//        override fun onPageFinished(view: WebView?, url: String?) {
//            super.onPageFinished(view, url)
//        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> {
                        data = url
                    }
                    return true
                }

                else -> {
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        currentActivity.openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?, handler: SslErrorHandler?, error: SslError?
        ) {
            handler?.proceed()
        }

    }


}