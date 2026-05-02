package io.legado.app.help.config

import android.text.TextUtils
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

object AdvancedTitleConfig {

    const val TITLE_MODE_ADVANCED = 3
    const val SPLIT_DELIMITER = 0
    const val SPLIT_REGEX = 1
    private const val BOOK_RULE_KEY = "advancedTitleRule"

    data class SplitRule(
        val mode: Int = SPLIT_DELIMITER,
        val delimiter: String = " ",
        val regex: String = DEFAULT_REGEX
    )

    data class Template(
        val html: String = DEFAULT_HTML,
        val css: String = DEFAULT_CSS
    )

    data class Parts(
        val title: String,
        val s1: String,
        val s2: String
    )

    var globalRule: SplitRule
        get() = appCtx.getPrefString(PreferKey.advancedTitleConfig)
            ?.let { GSON.fromJsonObject<SplitRule>(it).getOrNull() }
            ?: SplitRule()
        set(value) {
            appCtx.putPrefString(PreferKey.advancedTitleConfig, GSON.toJson(value))
        }

    var template: Template
        get() = appCtx.getPrefString(PreferKey.advancedTitleTemplate)
            ?.let { GSON.fromJsonObject<Template>(it).getOrNull() }
            ?: Template()
        set(value) {
            appCtx.putPrefString(PreferKey.advancedTitleTemplate, GSON.toJson(value))
        }

    fun bookRule(book: Book?): SplitRule? {
        val value = book?.getVariable(BOOK_RULE_KEY)?.takeIf { it.isNotBlank() } ?: return null
        return GSON.fromJsonObject<SplitRule>(value).getOrNull()
    }

    fun setBookRule(book: Book, rule: SplitRule?) {
        book.putVariable(BOOK_RULE_KEY, rule?.let { GSON.toJson(it) })
    }

    fun effectiveRule(book: Book?): SplitRule = bookRule(book) ?: globalRule

    fun split(title: String, book: Book? = null): Parts {
        val cleanTitle = title.trim()
        val rule = effectiveRule(book)
        return split(cleanTitle, rule)
    }

    fun split(title: String, rule: SplitRule): Parts {
        val cleanTitle = title.trim()
        return when (rule.mode) {
            SPLIT_REGEX -> splitByRegex(cleanTitle, rule.regex)
            else -> splitByDelimiter(cleanTitle, rule.delimiter)
        }
    }

    fun renderHtml(book: Book, title: String): String {
        val parts = split(title, book)
        val tpl = template
        val variables = mapOf(
            "title" to parts.title,
            "s1" to parts.s1,
            "s2" to parts.s2,
            "bookName" to book.name,
            "author" to book.author
        )
        val html = variables.entries.fold(tpl.html) { value, entry ->
            value.replace("\${${entry.key}}", TextUtils.htmlEncode(entry.value))
        }.replace(
            "章节名",
            TextUtils.htmlEncode(parts.s2.ifBlank { parts.title })
        ).replace(
            "章节数",
            TextUtils.htmlEncode(parts.s1)
        )
        return """
            <!doctype html>
            <html>
            <head><meta charset="utf-8"><style>html,body{background:transparent;margin:0;padding:0;}${tpl.css}</style></head>
            <body>$html</body>
            </html>
        """.trimIndent()
    }

    fun preview(title: String, book: Book? = null): String {
        val parts = split(title, book)
        return "s1: ${parts.s1.ifBlank { "空" }}\ns2: ${parts.s2.ifBlank { "空" }}"
    }

    private fun splitByDelimiter(title: String, delimiter: String): Parts {
        val mark = delimiter.ifEmpty { " " }
        val index = if (mark.isBlank()) {
            title.indexOfFirst { it.isWhitespace() || it == '　' }
        } else {
            title.indexOf(mark)
        }
        if (index < 0) return splitByRegex(title, DEFAULT_REGEX)
        val end = if (mark.isBlank()) {
            var next = index
            while (next < title.length && (title[next].isWhitespace() || title[next] == '　')) next++
            next
        } else {
            index + mark.length
        }
        val s1 = title.substring(0, index).trim()
        val s2 = title.substring(end.coerceAtMost(title.length)).trim()
        return if (s1.isBlank() || s2.isBlank()) {
            Parts(title, "", title)
        } else {
            Parts(title, s1, s2)
        }
    }

    private fun splitByRegex(title: String, regex: String): Parts {
        val pattern = regex.ifBlank { DEFAULT_REGEX }
        val match = runCatching { Regex(pattern).find(title) }.getOrNull()
        if (match != null) {
            val groups = match.groups
            val namedGroups = groups as? MatchNamedGroupCollection
            val namedS1 = runCatching { namedGroups?.get("s1")?.value }.getOrNull()
            val namedS2 = runCatching { namedGroups?.get("s2")?.value }.getOrNull()
            val s1 = (namedS1 ?: groups.getOrNull(1)?.value).orEmpty().trim()
            val s2 = (namedS2 ?: groups.getOrNull(2)?.value).orEmpty().trim()
            if (s1.isNotBlank() && s2.isNotBlank()) return Parts(title, s1, s2)
        }
        return Parts(title, "", title)
    }

    private fun MatchGroupCollection.getOrNull(index: Int): MatchGroup? {
        return if (index in 0 until size) get(index) else null
    }

    const val DEFAULT_REGEX = "^\\s*(第\\S+[章节回卷部篇集])\\s+(.+?)\\s*$"

    const val DEFAULT_HTML = """
<div class="advanced-title">
  <div class="advanced-title-inner">
    <h3>${'$'}{s2}</h3>
    <h4>${'$'}{s1}</h4>
  </div>
</div>
"""

    const val DEFAULT_CSS = """
.advanced-title {
  text-align: center;
  margin: 2.2em 0 1.6em 0;
}
.advanced-title-inner {
  text-align: center;
}
.advanced-title h3 {
  color: #000000;
  margin: 0 0 0.2em 0;
  font-size: 1.4em;
  font-weight: 600;
}
.advanced-title h4 {
  display: inline-block;
  color: #000000;
  margin: 0.2em 0 0 0;
  padding: 0.45em 2em;
  font-size: 1em;
  font-weight: 200;
}
"""
}
