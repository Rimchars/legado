package io.legado.app.help.config

import com.airbnb.lottie.LottieCompositionFactory
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

object AdvancedTitleConfig {

    const val TITLE_MODE_ADVANCED = 3
    const val SPLIT_DELIMITER = 0
    const val SPLIT_REGEX = 1
    const val LOTTIE_BLOCK_ROLE = "advanced_title_lottie"
    const val DEFAULT_HEIGHT_FACTOR = 55
    private const val BOOK_RULE_KEY = "advancedTitleRule"

    data class SplitRule(
        val mode: Int = SPLIT_DELIMITER,
        val delimiter: String = " ",
        val regex: String = DEFAULT_REGEX
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

    var lottieJson: String?
        get() = appCtx.getPrefString(PreferKey.advancedTitleLottieJson)
        set(value) {
            appCtx.putPrefString(PreferKey.advancedTitleLottieJson, value?.takeIf { it.isNotBlank() })
        }

    var lottiePath: String?
        get() = appCtx.getPrefString(PreferKey.advancedTitleLottiePath)
        set(value) {
            appCtx.putPrefString(PreferKey.advancedTitleLottiePath, value?.takeIf { it.isNotBlank() })
        }

    var heightFactor: Int
        get() = appCtx.getPrefInt(PreferKey.advancedTitleHeightFactor, DEFAULT_HEIGHT_FACTOR)
            .coerceIn(30, 120)
        set(value) {
            appCtx.putPrefInt(PreferKey.advancedTitleHeightFactor, value.coerceIn(30, 120))
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

    fun renderLottieJson(book: Book, title: String): String? {
        val raw = lottieJson?.takeIf { it.isNotBlank() }
            ?: lottiePath?.takeIf { it.isNotBlank() }?.let { path ->
                runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
            }
        return raw?.let { replaceVariables(it, book, title) }
    }

    fun renderValidLottieJson(book: Book, title: String): String? {
        val json = renderLottieJson(book, title)?.takeIf { it.isNotBlank() } ?: return null
        return json.takeIf { isValidLottieJson(it) }
    }

    fun isValidLottieJson(json: String): Boolean {
        return runCatching {
            val obj = JSONObject(json)
            obj.has("layers") &&
                obj.optJSONArray("layers") != null &&
                LottieCompositionFactory.fromJsonStringSync(
                    json,
                    "advanced-title-check:${json.hashCode()}"
                ).value != null
        }.getOrDefault(false)
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

    private fun replaceVariables(
        source: String,
        book: Book,
        title: String
    ): String {
        val parts = split(title, book)
        val variables = variables(book, parts)
        return variables.entries.fold(source) { value, entry ->
            val replacement = entry.value
            value
                .replace("\${${entry.key}}", replacement)
                .replace("{{${entry.key}}}", replacement)
        }.replace(
            "章节名",
            parts.s2.ifBlank { parts.title }
        ).replace(
            "章节数",
            parts.s1
        )
    }

    private fun variables(book: Book, parts: Parts): Map<String, String> {
        return mapOf(
            "title" to parts.title,
            "s1" to parts.s1,
            "s2" to parts.s2,
            "bookName" to book.name,
            "author" to book.author
        )
    }

    const val DEFAULT_REGEX = "^\\s*(第\\S+[章节回卷部篇集])\\s+(.+?)\\s*$"

}
