package io.legado.app.help.book

import java.util.Locale

object BookTagManagement {

    fun mergeTags(configured: List<String>, existing: List<String>): List<String> {
        val merged = linkedMapOf<String, String>()
        (configured + existing).forEach { rawTag ->
            val tag = rawTag.trim()
            if (tag.isNotEmpty()) {
                merged.putIfAbsent(tag.lowercase(Locale.ROOT), tag)
            }
        }
        return merged.values.toList()
    }

    fun reusableTags(current: List<String>, all: List<String>): List<String> {
        val currentKeys = current.asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()
        return mergeTags(emptyList(), all).filterNot {
            it.lowercase(Locale.ROOT) in currentKeys
        }
    }

    /**
     * Result of a tag mutation.
     * - null: no database write needed
     * - non-null: write [customTag] (which may be null to clear all tags)
     */
    data class TagWrite(val customTag: String?)

    /**
     * @return null when the stored value does not need an update; otherwise a [TagWrite]
     * whose [TagWrite.customTag] may be null after removing the last tag.
     */
    fun updateTag(customTag: String?, tag: String, selected: Boolean): TagWrite? {
        val tags = BookTagHelper.parse(customTag).toMutableList()
        val hasTag = tags.any { it.equals(tag, ignoreCase = true) }
        if (hasTag == selected) return null
        if (selected) {
            tags.add(tag)
        } else {
            tags.removeAll { it.equals(tag, ignoreCase = true) }
        }
        return TagWrite(BookTagHelper.join(tags))
    }
}
