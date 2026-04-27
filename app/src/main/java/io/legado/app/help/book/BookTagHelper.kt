package io.legado.app.help.book

import java.util.Locale

object BookTagHelper {

    private val splitter = Regex("[,，;；、|/\\s]+")

    fun parse(raw: String?): List<String> {
        return raw.orEmpty()
            .split(splitter)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
    }

    fun join(tags: Collection<String>): String? {
        return tags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .joinToString(",")
            .ifBlank { null }
    }

    fun has(raw: String?, tag: String): Boolean {
        return parse(raw).any { it.equals(tag.trim(), ignoreCase = true) }
    }
}
