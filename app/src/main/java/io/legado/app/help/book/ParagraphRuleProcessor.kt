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
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.coroutine.ActivelyCancelException
import io.legado.app.help.http.CookieStore
import io.legado.app.model.Debug
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
import java.util.LinkedHashMap

object ParagraphRuleProcessor {
    private const val CLICK_PREFIX = "paragraphRule:"
    private const val RULE_PREFIX = "rule:"
    private const val PROCESS_CACHE_MAX_SIZE = 32
    private val processCache = object : LinkedHashMap<String, BookContent>(PROCESS_CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookContent>?): Boolean {
            return size > PROCESS_CACHE_MAX_SIZE
        }
    }

    interface BrowserCallback {
        fun showBrowser(
            url: String,
            html: String? = null,
            preloadJs: String? = null,
            config: String? = null,
            sourceKey: String? = null
        ): Boolean
    }

    data class ParagraphItem(
        val index: Int,
        val text: String,
        val start: Int,
        val end: Int,
        val separator: String,
        val sourcePosition: Int = index - 1
    )

    data class DebugResult(
        val content: String,
        val logs: List<String>
    )

    fun isParagraphClick(click: String?): Boolean {
        return click?.startsWith(CLICK_PREFIX) == true || click?.startsWith(RULE_PREFIX) == true
    }

    fun wrapClick(ruleId: Long, js: String): String = "$CLICK_PREFIX$RULE_PREFIX$ruleId\n$js"

    private fun wrapRuleClick(ruleId: Long, js: String): String = "$RULE_PREFIX$ruleId:$js"

    suspend fun process(book: Book, chapter: BookChapter, content: BookContent): BookContent {
        if (book.isEpub || content.textList.isEmpty()) return content
        val rules = appDb.paragraphRuleDao.enabledRulesForBook(book.bookUrl)
        if (rules.isEmpty()) return content
        val cacheKey = processCacheKey(book, chapter, content, rules)
        synchronized(processCache) {
            processCache[cacheKey]?.let { return it }
        }
        val original = content.textList.joinToString("\n")
        var result = ChapterResult(original, content.textList)
        val context = currentCoroutineContext()
        var hasFailure = false
        for (rule in rules) {
            if (rule.script.isBlank()) continue
            result = kotlin.runCatching {
                withTimeout(rule.validTimeout()) {
                    applyRule(rule, book, chapter, result, context)
                }
            }.onFailure {
                hasFailure = true
                when (it) {
                    is ActivelyCancelException -> throw it
                    is TimeoutCancellationException -> AppLog.put("ParagraphRule:${rule.id} script timeout")
                    else -> AppLog.put("ParagraphRule:${rule.id} script error: ${it.localizedMessage ?: it}", it)
                }
            }.getOrDefault(result)
        }
        val processed = if (result.content == original) content else content.copy(textList = result.paragraphs)
        if (!hasFailure) {
            synchronized(processCache) {
                processCache[cacheKey] = processed
            }
        }
        return processed
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
                when (it) {
                    is ActivelyCancelException -> throw it
                    is TimeoutCancellationException -> AppLog.put("ParagraphRule:${rule.id} script timeout")
                    else -> AppLog.put("ParagraphRule:${rule.id} script error: ${it.localizedMessage ?: it}", it)
                }
            }.getOrDefault(result)
        }
        return result
    }

    suspend fun debug(rule: ParagraphRule, book: Book, chapter: BookChapter, content: String): DebugResult {
        if (book.isEpub) {
            throw NoStackTraceException("Paragraph rules do not run on EPUB books.")
        }
        if (rule.script.isBlank()) {
            throw NoStackTraceException("Paragraph rule script is empty.")
        }
        val normalizedContent = ContentProcessor.get(book.name, book.origin)
            .getContent(book, chapter, content, includeTitle = false)
            .textList
            .joinToString("\n")
        val logs = arrayListOf<String>()
        val debugSource = "paragraph_rule_${rule.id}"
        val callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                logs.add(msg)
            }
        }
        val processed = Debug.withDebugSource(debugSource, callback) {
            Debug.log(debugSource, "paragraph debug start: ${book.name} / ${chapter.title}")
            withTimeout(rule.validTimeout()) {
                applyRule(rule, book, chapter, normalizedContent, currentCoroutineContext())
            }.also {
                Debug.log(debugSource, "paragraph debug finish")
            }
        }
        return DebugResult(processed, logs)
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
            append(jsCompatPrelude())
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
        current: ChapterResult,
        coroutineContext: CoroutineContext
    ): ChapterResult {
        val paragraphs = parseParagraphs(current.paragraphs)
        if (paragraphs.isEmpty()) return current
        val ctx = buildCtx(rule, book, chapter, current.content, paragraphs)
        val scope = buildScope(rule, book, chapter, current.content, ctx, coroutineContext, null)
        val script = buildRuleScript(rule, ctx)
        val jsResult = RhinoScriptEngine.eval(script, scope, coroutineContext)
        writeVars(rule.id, ctx["vars"])
        return applyChapterResult(current, paragraphs, jsResult).wrapPclicks(rule.id)
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
        val script = buildRuleScript(rule, ctx)
        val jsResult = RhinoScriptEngine.eval(script, scope, coroutineContext)
        writeVars(rule.id, ctx["vars"])
        return wrapPclicks(rule.id, applyResult(content, paragraphs, jsResult))
    }

    private fun buildRuleScript(rule: ParagraphRule, ctx: Map<String, Any?>): String {
        return buildString {
            append(jsCompatPrelude())
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
    }

    private fun jsCompatPrelude(): String {
        return """
            ;(function(){
                if (!Array.prototype.forEach) {
                    Array.prototype.forEach = function(callback, thisArg) {
                        if (this == null) throw new TypeError('Array.prototype.forEach called on null or undefined');
                        var object = Object(this);
                        var length = object.length >>> 0;
                        for (var i = 0; i < length; i++) {
                            if (i in object) callback.call(thisArg, object[i], i, object);
                        }
                    };
                }
                if (!Array.prototype.map) {
                    Array.prototype.map = function(callback, thisArg) {
                        if (this == null) throw new TypeError('Array.prototype.map called on null or undefined');
                        var object = Object(this);
                        var length = object.length >>> 0;
                        var result = new Array(length);
                        for (var i = 0; i < length; i++) {
                            if (i in object) result[i] = callback.call(thisArg, object[i], i, object);
                        }
                        return result;
                    };
                }
                if (!Array.prototype.filter) {
                    Array.prototype.filter = function(callback, thisArg) {
                        if (this == null) throw new TypeError('Array.prototype.filter called on null or undefined');
                        var object = Object(this);
                        var length = object.length >>> 0;
                        var result = [];
                        for (var i = 0; i < length; i++) {
                            if (i in object && callback.call(thisArg, object[i], i, object)) result.push(object[i]);
                        }
                        return result;
                    };
                }
                if (!Array.isArray) {
                    Array.isArray = function(value) {
                        return Object.prototype.toString.call(value) === '[object Array]';
                    };
                }
            })();
        """.trimIndent() + '\n'
    }

    private fun buildCtx(
        rule: ParagraphRule,
        book: Book,
        chapter: BookChapter,
        content: String,
        paragraphs: List<ParagraphItem>
    ): Map<String, Any?> {
        val chapterRequestUrl = chapterRequestUrl(chapter)
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
                "url" to chapterRequestUrl,
                "rawUrl" to chapter.url,
                "requestUrl" to chapterRequestUrl,
                "absoluteUrl" to chapterRequestUrl,
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
            bindings["baseUrl"] = chapterRequestUrl(chapter)
        }
        return RhinoScriptEngine.getRuntimeScope(bindings)
    }

    private fun chapterRequestUrl(chapter: BookChapter): String {
        val absoluteUrl = runCatching { chapter.getAbsoluteURL() }.getOrNull().orEmpty()
        return when {
            absoluteUrl.startsWith("http://", true) || absoluteUrl.startsWith("https://", true) -> absoluteUrl
            chapter.url.startsWith("http://", true) || chapter.url.startsWith("https://", true) -> chapter.url
            chapter.baseUrl.startsWith("http://", true) || chapter.baseUrl.startsWith("https://", true) -> chapter.baseUrl
            else -> ""
        }
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

    private fun parseParagraphs(paragraphs: List<String>): List<ParagraphItem> {
        var start = 0
        var index = 1
        return paragraphs.mapIndexedNotNull { idx, text ->
            val separator = if (idx < paragraphs.lastIndex) "\n" else ""
            val item = if (text.isNotBlank()) {
                ParagraphItem(
                    index = index++,
                    text = text,
                    start = start,
                    end = start + text.length,
                    separator = separator,
                    sourcePosition = idx
                )
            } else {
                null
            }
            start += text.length + separator.length
            item
        }
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

    private fun applyChapterResult(
        current: ChapterResult,
        paragraphs: List<ParagraphItem>,
        jsResult: Any?
    ): ChapterResult {
        val result = unwrap(jsResult) ?: return current
        return when (result) {
            is String -> chapterResultOf(result)
            is NativeArray -> fromListResult(result.toList())
            is List<*> -> fromListResult(result)
            is NativeObject -> fromPatchResult(current, paragraphs, result.toMap())
            is Map<*, *> -> fromPatchResult(current, paragraphs, result)
            else -> chapterResultOf(result.toString())
        }
    }

    private fun chapterResultOf(text: String): ChapterResult {
        val paragraphs = text.split('\n').filter { it.isNotBlank() }
        return ChapterResult(paragraphs.joinToString("\n"), paragraphs)
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

    private fun fromListResult(list: List<*>): ChapterResult {
        val paragraphs = arrayListOf<String>()
        list.forEach { raw ->
            val item = unwrap(raw)
            val text = when (item) {
                is Map<*, *> -> item["text"]?.toString() ?: item["content"]?.toString()
                else -> item?.toString()
            } ?: return@forEach
            if (text.isNotBlank()) paragraphs.add(text)
        }
        return ChapterResult(paragraphs.joinToString("\n"), paragraphs)
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

    private fun fromPatchResult(
        current: ChapterResult,
        paragraphs: List<ParagraphItem>,
        patch: Map<*, *>
    ): ChapterResult {
        if (patch.containsKey("content")) {
            val text = patch["content"]?.toString() ?: current.content
            return chapterResultOf(text)
        }
        if (patch.containsKey("result")) {
            val text = patch["result"]?.toString() ?: current.content
            return chapterResultOf(text)
        }
        val byIndex = linkedMapOf<Int, String>()
        patch.forEach { (key, value) ->
            key?.toString()?.toIntOrNull()?.let { byIndex[it] = value?.toString() ?: "" }
        }
        if (byIndex.isEmpty()) return current
        val paragraphIndexes = paragraphs.map { it.index }.toHashSet()
        val out = current.paragraphs.toMutableList()
        paragraphs.forEach { item ->
            val replace = byIndex[item.index] ?: return@forEach
            val position = item.sourcePosition
            if (position in out.indices) out[position] = replace
        }
        byIndex
            .filterKeys { it !in paragraphIndexes }
            .toSortedMap()
            .values
            .filterTo(out) { it.isNotBlank() }
        return ChapterResult(out.joinToString("\n"), out)
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
        return dedupeParagraphRuleImages(regex.replace(text) { match ->
            val raw = match.groupValues[2]
            val decoded = kotlin.runCatching { GSON.fromJson("\"$raw\"", String::class.java) }.getOrNull() ?: raw
            if (isParagraphClick(decoded)) {
                match.value
            } else {
                val encoded = GSON.toJson(wrapRuleClick(ruleId, decoded)).removeSurrounding("\"")
                match.groupValues[1] + encoded + match.groupValues[3]
            }
        })
    }

    private fun ChapterResult.wrapPclicks(ruleId: Long): ChapterResult {
        val wrapped = paragraphs.map { wrapPclicks(ruleId, it) }
        return ChapterResult(wrapped.joinToString("\n"), wrapped)
    }

    private fun dedupeParagraphRuleImages(text: String): String {
        if (!text.contains("pclick") && !text.contains("data-legado-pclick")) return text
        val regex = Regex("""<img\b[^>]*(?:"pclick"\s*:\s*"((?:\\.|[^"\\])*)"|data-legado-pclick\s*=\s*"([^"]*)")[^>]*>""")
        val seen = hashSetOf<String>()
        return regex.replace(text) { match ->
            val key = match.groupValues.getOrNull(1)?.ifBlank { match.groupValues.getOrNull(2).orEmpty() }.orEmpty()
            if (key.isNotBlank() && !seen.add(key)) "" else match.value
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

    fun stableKey(rule: ParagraphRule): String {
        return MD5Utils.md5Encode16("${rule.id}:${rule.updateTime}:${rule.timeoutMillisecond}:${rule.script}:${rule.jsLib}")
    }

    private fun processCacheKey(
        book: Book,
        chapter: BookChapter,
        content: BookContent,
        rules: List<ParagraphRule>
    ): String {
        val rulesKey = rules.joinToString("|") { stableKey(it) }
        val varsKey = rules.joinToString("|") { rule ->
            appDb.paragraphRuleDao.vars(rule.id).joinToString("&") { "${it.name}=${it.value}" }
        }
        val contentKey = MD5Utils.md5Encode16(content.textList.joinToString("\u0001"))
        return "${book.bookUrl}|${chapter.index}|${chapter.url}|$rulesKey|$varsKey|$contentKey"
    }

    private data class ChapterResult(
        val content: String,
        val paragraphs: List<String>
    )
}
