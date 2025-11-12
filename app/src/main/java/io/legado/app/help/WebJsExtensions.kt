package io.legado.app.help

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.utils.GSON
import io.legado.app.utils.escapeForJs
import io.legado.app.utils.fromJsonObject

class WebJsExtensions(private val source: BaseSource, private val activity: AppCompatActivity, private val webView: WebView): JsExtensions {
    override fun getSource(): BaseSource {
        return source
    }
    @JavascriptInterface
    fun put(key: String, value: String): String {
        getSource().put(key, value)
        return value
    }
    @JavascriptInterface
    fun get(key: String): String {
        return getSource().get(key)
    }
    @JavascriptInterface
    fun request(jsCode: String, id: String) {
        Coroutine.async(activity.lifecycleScope) {
            AnalyzeRule(null, source).run {
                setCoroutineContext(coroutineContext)
                evalJS(jsCode).toString()
            }
        }.onSuccess { data ->
            webView.evaluateJavascript("window.JSBridgeResult('$id', '${data.escapeForJs()}', null);", null)
        }.onError {
            webView.evaluateJavascript("window.JSBridgeResult('$id', null, '${it.localizedMessage?.escapeForJs()}');", null)
        }
    }
    @JavascriptInterface
    fun toast(msg: String?) {
        super.toast(msg)
    }
    @JavascriptInterface
    fun longToast(msg: String?) {
        super.longToast(msg)
    }
    @JavascriptInterface
    fun log(msg: String?) {
        super.log(msg)
    }
    @JavascriptInterface
    fun ajax(url: String): String? {
        return super.ajax(url)
    }
    @JavascriptInterface
    fun connect(urlStr: String, header: String): String {
        return super.connect(urlStr, header).toString()
    }
    @JavascriptInterface
    fun get(urlStr: String, headers: String): String {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(headers).getOrNull() ?: emptyMap()
        return super.get(urlStr, headerMap).body()
    }
    @JavascriptInterface
    fun post(urlStr: String, body: String, headers: String): String {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(headers).getOrNull() ?: emptyMap()
        return super.post(urlStr, body, headerMap).body()
    }
    @JavascriptInterface
    fun head(urlStr: String, headers: String): String {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(headers).getOrNull() ?: emptyMap()
        return GSON.toJson(super.head(urlStr, headerMap).headers())
    }

    companion object{
        const val JS_INJECTION = """
            window.run = function(jsCode) {
                return new Promise((resolve, reject) => {
                    const requestId = 'req_' + Date.now() + '_' + Math.random().toString(36).substring(2, 5);
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[requestId] = { resolve, reject };
                    window.java?.request(String(jsCode), requestId);
                });
            };
            window.JSBridgeResult = function(requestId, result, error) {
                if (window.JSBridgeCallbacks?.[requestId]) {
                    if (error) {
                        window.JSBridgeCallbacks[requestId].reject(error);
                    } else {
                        window.JSBridgeCallbacks[requestId].resolve(result);
                    }
                    delete window.JSBridgeCallbacks[requestId];
                }
            };"""
    }
}