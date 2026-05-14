package io.legado.app.help.book

import android.webkit.JavascriptInterface
import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ParagraphRuleVar
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.StrResponse
import io.legado.app.model.SharedJsScope

class ParagraphRuleJsExtensions(
    private val rule: ParagraphRule,
    private val browserCallback: ParagraphRuleProcessor.BrowserCallback? = null
) : BaseSource {

    override var concurrentRate: String? = null
    override var loginUrl: String? = rule.loginUrl
    override var loginUi: String? = rule.loginUi
    override var header: String? = null
    override var enabledCookieJar: Boolean? = false
    override var jsLib: String? = rule.jsLib

    override fun getTag(): String = "ParagraphRule:${rule.id}:${rule.displayName()}"

    override fun getKey(): String = "paragraph_rule_${rule.id}"

    @JavascriptInterface
    override fun getVariable(): String {
        return appDb.paragraphRuleDao.vars(rule.id)
            .associate { it.name to it.value }
            .let { io.legado.app.utils.GSON.toJson(it) }
    }

    @JavascriptInterface
    fun getVariable(key: String): String {
        return appDb.paragraphRuleDao.varValue(rule.id, key) ?: ""
    }

    @JavascriptInterface
    override fun putVariable(variable: String?) {
        if (variable.isNullOrBlank()) {
            appDb.paragraphRuleDao.deleteVars(rule.id)
            return
        }
        val map = io.legado.app.utils.GSON.fromJson(
            variable,
            com.google.gson.reflect.TypeToken.getParameterized(
                Map::class.java,
                String::class.java,
                String::class.java
            ).type
        ) as? Map<*, *> ?: return
        map.forEach { (key, value) ->
            if (key != null) {
                appDb.paragraphRuleDao.putVar(ParagraphRuleVar(rule.id, key.toString(), value?.toString() ?: ""))
            }
        }
    }

    @JavascriptInterface
    fun putVariable(key: String, value: String?): Boolean {
        appDb.paragraphRuleDao.putVar(ParagraphRuleVar(rule.id, key, value ?: ""))
        return true
    }

    @JavascriptInterface
    override fun get(key: String): String = CacheManager.get("v_${getKey()}_${key}") ?: ""

    @JavascriptInterface
    override fun put(key: String, value: String): String {
        CacheManager.put("v_${getKey()}_${key}", value)
        return value
    }

    @JavascriptInterface
    fun showBrowser(url: String): Boolean = showBrowser(url, null, null, null)

    @JavascriptInterface
    fun showBrowser(url: String, html: String?): Boolean = showBrowser(url, html, null, null)

    @JavascriptInterface
    fun showBrowser(url: String, html: String?, preloadJs: String?): Boolean = showBrowser(url, html, preloadJs, null)

    @JavascriptInterface
    fun showBrowser(url: String, html: String?, preloadJs: String?, config: String?): Boolean {
        return browserCallback?.showBrowser(url, html, preloadJs, config) == true
    }

    override fun evalJS(jsStr: String, bindingsConfig: ScriptBindings.() -> Unit): Any? {
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = this
            bindings["source"] = this
            bindings["cache"] = CacheManager
            bindings["cookie"] = CookieStore
            bindings["baseUrl"] = getKey()
            bindings.apply(bindingsConfig)
        }
        val sharedScope = SharedJsScope.getScope(jsLib, null)
        val scope = if (sharedScope == null) {
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            bindings.apply { prototype = sharedScope }
        }
        return RhinoScriptEngine.eval(jsStr, scope)
    }
}
