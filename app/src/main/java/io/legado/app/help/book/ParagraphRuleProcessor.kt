package io.legado.app.help.book

import com.google.gson.internal.LinkedTreeMap
import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ParagraphRuleVar
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Scriptable.NOT_FOUND
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object ParagraphRuleProcessor {
    private const val CLICK_PREFIX = "paragraphRule:"
    private const val RULE_PREFIX = "rule:"

    interface BrowserCallback {
        fun showBrowser(url: String, html: String? = null, preloadJs: String? = null, config: String? = null): Boolean
    }

    data class ParagraphItem(
        val index: Int,
        val text: String,
        val start: Int,
        val end: Int,
        val separator: String
    )

    fun isParagraphClick(click: String?): Boolean {
        return click?.startsWith(CLICK_PREFIX) == true || click?.startsWith(RULE_PREFIX) == true
    }

    fun wrapClick(ruleId: Long, js: String): String = "$CLICK_PREFIX$RULE_PREFIX$ruleId\n$js"

    private fun wrapRuleClick(ruleId: Long, js: String): String = "$RULE_PREFIX$ruleId:$js"

    suspend fun process(book: Book, chapter: BookChapter, content: BookContent): BookContent {
        val original = content.textList.joinToString("\n")
        val processed = process(book, chapter, original)
        if (processed == original) return content
        return content.copy(
            textList = processed.split("\n")
        )
    }
    suspend fun process(book: Book, chapter: BookChapter, content: String): String {
        if (book.isEpub) return content
        val rules = appDb.paragraphRuleDao.enabledRulesForBook(book.bookUrl)
        if (rules.isEmpty()) return content
        var result = content
        val context = currentCoroutineContext()
        for (rule in rules) {
            if (rule.script.isBlank()) continue
            result = kotlin.runCatching {
                withTimeout(rule.validTimeout()) {
                    applyRule(rule, book, chapter, result, context)
                }
            }.onFailure {
                if (it !is TimeoutCancellationException) {
                    AppLog.put("ParagraphRule:${rule.id} script error: ${it.localizedMessage ?: it}", it)
                } else {
                    AppLog.put("ParagraphRule:${rule.id} script timeout")
                }
            }.getOrDefault(result)
        }
        return result
    }

    fun evalClick(
        book: Book,
        chapter: BookChapter,
        click: String,
        result: String,
        browserCallback: BrowserCallback,
        coroutineContext: CoroutineContext = EmptyCoroutineContext
    ): Boolean {
        if (!isParagraphClick(click)) return false
        val (ruleId, js) = parseClick(click) ?: return false
        val rule = appDb.paragraphRuleDao.get(ruleId) ?: return false
        val ctx = buildCtx(rule, book, chapter, result, emptyList())
        val scope = buildScope(rule, book, chapter, result, ctx, coroutineContext, browserCallback)
        val script = buildString {
            append(jsCtxPrelude(ctx))
            if (rule.jsLib.isNotBlank()) append(rule.jsLib).append('\n')
            append(js)
        }
        RhinoScriptEngine.eval(script, scope, coroutineContext)
        return true
    }

    private fun applyRule(
        rule: ParagraphRule,
        book: Book,
        chapter: BookChapter,
        content: String,
        coroutineContext: CoroutineContext
    ): String {
        val paragraphs = parseContent(content)
        if (paragraphs.isEmpty()) return content
        val ctx = buildCtx(rule, book, chapter, content, paragraphs)
        val scope = buildScope(rule, book, chapter, content, ctx, coroutineContext, null)
        val script = buildString {
            append(jsCtxPrelude(ctx))
            if (rule.jsLib.isNotBlank()) append(rule.jsLib).append('\n')
            append(rule.script).append('\n')
            append("""
                ;(function(){
                    var __paragraphRuleResult;
                    if (typeof process === 'function') {
                        __paragraphRuleResult = process(ctx);
                    } else if (typeof result !== 'undefined') {
                        __paragraphRuleResult = result;
                    } else {
                        __paragraphRuleResult = ctx.result;
                    }
                    return (typeof __paragraphRuleResult === 'undefined' || __paragraphRuleResult === null) ? ctx.result : __paragraphRuleResult;
                })();
            """.trimIndent())
        }
        val jsResult = RhinoScriptEngine.eval(script, scope, coroutineContext)
        writeVars(rule.id, ctx["vars"])
        return wrapPclicks(rule.id, applyResult(content, paragraphs, jsResult))
    }

    private fun buildCtx(
        rule: ParagraphRule,
        book: Book,
        chapter: BookChapter,
        content: String,
        paragraphs: List<ParagraphItem>
    ): Map<String, Any?> {
        return linkedMapOf(
            "book" to linkedMapOf(
                "name" to book.name,
                "author" to book.author,
                "bookUrl" to book.bookUrl,
                "tocUrl" to book.tocUrl,
                "origin" to book.origin,
                "originName" to book.originName,
                "type" to book.type,
                "durChapterIndex" to book.durChapterIndex,
                "durChapterTitle" to book.durChapterTitle,
                "latestChapterTitle" to book.latestChapterTitle,
                "variable" to book.variable
            ),
            "chapter" to linkedMapOf(
                "bookUrl" to chapter.bookUrl,
                "title" to chapter.title,
                "url" to chapter.url,
                "baseUrl" to chapter.baseUrl,
                "index" to chapter.index,
                "isVolume" to chapter.isVolume,
                "isVip" to chapter.isVip,
                "isPay" to chapter.isPay
            ),
            "content" to content,
            "paragraphs" to paragraphs.map {
                linkedMapOf(
                    "index" to it.index,
                    "text" to it.text,
                    "start" to it.start,
                    "end" to it.end,
                    "separator" to it.separator
                )
            },
            "result" to content,
            "rule" to linkedMapOf(
                "id" to rule.id,
                "name" to rule.name,
                "timeoutMillisecond" to rule.timeoutMillisecond,
                "order" to rule.order,
                "updateTime" to rule.updateTime
            ),
            "vars" to readVars(rule.id)
        )
    }

    private fun jsCtxPrelude(ctx: Map<String, Any?>): String {
        val ctxJsonLiteral = GSON.toJson(GSON.toJson(ctx))
        return """
            var ctx = JSON.parse($ctxJsonLiteral);
            var ruleId = ctx.rule.id;
            var vars = ctx.vars;
            var result = ctx.result;
        """.trimIndent() + '\n'
    }
    private fun buildScope(
        rule: ParagraphRule,
        book: Book,
        chapter: BookChapter,
        content: String,
        ctx: Map<String, Any?>,
        coroutineContext: CoroutineContext,
        browserCallback: BrowserCallback?
    ): Scriptable {
        val java = ParagraphRuleJsExtensions(rule, browserCallback)
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = java
            bindings["source"] = java
            bindings["cache"] = CacheManager
            bindings["cookie"] = CookieStore
            bindings["book"] = book
            bindings["chapter"] = chapter
            bindings["title"] = chapter.title
            bindings["src"] = content
            bindings["result"] = content
            bindings["rule"] = rule
            bindings["ruleId"] = rule.id
            bindings["vars"] = readVars(rule.id)
            bindings["ctx"] = ctx
            bindings["baseUrl"] = chapter.url
        }
        return RhinoScriptEngine.getRuntimeScope(bindings)
    }

    private fun parseContent(content: String): List<ParagraphItem> {
        val list = arrayListOf<ParagraphItem>()
        var start = 0
        var index = 1
        var i = 0
        while (i <= content.length) {
            if (i == content.length || content[i] == '\n') {
                val end = if (i > start && content[i - 1] == '\r') i - 1 else i
                val text = content.substring(start, end)
                if (text.isNotBlank()) {
                    list.add(ParagraphItem(index++, text, start, end, if (i < content.length) "\n" else ""))
                }
                start = i + 1
            }
            i++
        }
        return list
    }

    private fun applyResult(original: String, paragraphs: List<ParagraphItem>, jsResult: Any?): String {
        val result = unwrap(jsResult)
        return when (result) {
            null -> original
            is String -> result
            is NativeArray -> fromList(result.toList())
            is List<*> -> fromList(result)
            is NativeObject -> fromPatch(original, paragraphs, result.toMap())
            is Map<*, *> -> fromPatch(original, paragraphs, result)
            else -> result.toString()
        }
    }

    private fun fromList(list: List<*>): String {
        return buildString {
            var first = true
            list.forEach { raw ->
                val item = unwrap(raw)
                val text = when (item) {
                    is Map<*, *> -> item["text"]?.toString() ?: item["content"]?.toString()
                    else -> item?.toString()
                } ?: return@forEach
                if (text.isBlank()) return@forEach
                if (!first) append('\n')
                append(text)
                first = false
            }
        }
    }

    private fun fromPatch(original: String, paragraphs: List<ParagraphItem>, patch: Map<*, *>): String {
        if (patch.containsKey("content")) return patch["content"]?.toString() ?: original
        if (patch.containsKey("result")) return patch["result"]?.toString() ?: original
        val byIndex = linkedMapOf<Int, String>()
        patch.forEach { (key, value) -> key?.toString()?.toIntOrNull()?.let { byIndex[it] = value?.toString() ?: "" } }
        if (byIndex.isEmpty()) return original
        return buildString {
            paragraphs.forEachIndexed { idx, item ->
                if (idx > 0) append(item.separator.ifEmpty { "\n" })
                append(byIndex[item.index] ?: item.text)
            }
        }
    }

    private fun unwrap(value: Any?): Any? = when (value) {
        is NativeObject -> value.toMap()
        is NativeArray -> value.toList()
        is LinkedTreeMap<*, *> -> value
        else -> value
    }

    private fun NativeObject.toMap(): Map<Any?, Any?> {
        val map = linkedMapOf<Any?, Any?>()
        ids.forEach { key ->
            val value = when (key) {
                is Int -> get(key, this)
                else -> get(key.toString(), this)
            }
            if (value != NOT_FOUND) map[key] = value
        }
        return map
    }

    private fun NativeArray.toList(): List<Any?> = ids.mapNotNull { key ->
        val value = when (key) {
            is Int -> get(key, this)
            else -> get(key.toString(), this)
        }
        value.takeIf { it != NOT_FOUND }
    }

    private fun parseClick(click: String): Pair<Long, String>? {
        val body = click.removePrefix(CLICK_PREFIX)
        if (!body.startsWith(RULE_PREFIX)) return null
        val lineEnd = body.indexOf('\n')
        if (lineEnd > RULE_PREFIX.length) {
            val id = body.substring(RULE_PREFIX.length, lineEnd).toLongOrNull() ?: return null
            return id to body.substring(lineEnd + 1)
        }
        val colon = body.indexOf(':', startIndex = RULE_PREFIX.length)
        if (colon <= RULE_PREFIX.length) return null
        val id = body.substring(RULE_PREFIX.length, colon).toLongOrNull() ?: return null
        return id to body.substring(colon + 1)
    }

    private fun wrapPclicks(ruleId: Long, text: String): String {
        val regex = Regex("""("pclick"\s*:\s*")((?:\\.|[^"\\])*)(")""")
        return regex.replace(text) { match ->
            val raw = match.groupValues[2]
            val decoded = kotlin.runCatching { GSON.fromJson("\"$raw\"", String::class.java) }.getOrNull() ?: raw
            if (isParagraphClick(decoded)) {
                match.value
            } else {
                val encoded = GSON.toJson(wrapRuleClick(ruleId, decoded)).removeSurrounding("\"")
                match.groupValues[1] + encoded + match.groupValues[3]
            }
        }
    }

    private fun readVars(ruleId: Long): MutableMap<String, String> {
        return appDb.paragraphRuleDao.vars(ruleId).associate { it.name to it.value }.toMutableMap()
    }

    private fun writeVars(ruleId: Long, values: Any?) {
        val map = when (val v = unwrap(values)) {
            is Map<*, *> -> v
            else -> return
        }
        map.forEach { (key, value) ->
            if (key != null) appDb.paragraphRuleDao.putVar(ParagraphRuleVar(ruleId, key.toString(), value?.toString() ?: ""))
        }
    }

    fun clickKey(ruleId: Long, js: String): String {
        return "{\"pclick\":${GSON.toJson(wrapClick(ruleId, js))}}"
    }

    fun stableKey(rule: ParagraphRule): String = MD5Utils.md5Encode16("${rule.id}:${rule.updateTime}:${rule.script}")
}
