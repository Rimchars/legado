package io.legado.app.help.config

import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

enum class BookInfoComponentType(
    @StringRes val titleRes: Int,
    @StringRes val hintRes: Int
) {
    HEADER(R.string.book_info_component_header, R.string.book_info_component_header_hint),
    META(R.string.book_info_component_meta, R.string.book_info_component_meta_hint),
    ACTIONS(R.string.reading, R.string.add_to_bookshelf),
    DETAIL(R.string.book_info_component_detail, R.string.book_info_component_detail_hint),
    CATALOG(R.string.book_info_tab_toc, R.string.view_toc);

    companion object {
        fun fromKey(key: String?): BookInfoComponentType? {
            return entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
        }
    }
}

data class BookInfoComponentItem(
    val type: BookInfoComponentType,
    var enabled: Boolean
)

object BookInfoComponentConfig {

    private val defaultOrder = listOf(
        BookInfoComponentType.HEADER,
        BookInfoComponentType.META,
        BookInfoComponentType.DETAIL,
        BookInfoComponentType.CATALOG
    )

    private val manageableTypes = defaultOrder.toSet()
    fun load(): MutableList<BookInfoComponentItem> {
        val raw = appCtx.getPrefString(PreferKey.bookInfoComponents).orEmpty().trim()
        if (raw.isEmpty()) {
            return defaultItems()
        }
        val parsed = raw.split(",")
            .mapNotNull { entry ->
                val parts = entry.split(":")
                val type = BookInfoComponentType.fromKey(parts.getOrNull(0)?.trim())
                    ?.takeIf { it in manageableTypes }
                val enabled = parts.getOrNull(1)?.trim() != "0"
                type?.let { BookInfoComponentItem(it, enabled) }
            }
            .toMutableList()
        defaultOrder.forEach { type ->
            if (parsed.none { it.type == type }) {
                val insertIndex = defaultOrder
                    .takeWhile { it != type }
                    .lastOrNull { previous -> parsed.any { it.type == previous } }
                    ?.let { previous -> parsed.indexOfFirst { it.type == previous } + 1 }
                    ?: parsed.size
                parsed.add(insertIndex.coerceIn(0, parsed.size), BookInfoComponentItem(type, true))
            }
        }
        return parsed.distinctBy { it.type }.toMutableList()
    }

    fun save(items: List<BookInfoComponentItem>) {
        val normalized = items.filter { it.type in manageableTypes }.distinctBy { it.type }.ifEmpty { defaultItems() }
        val safeItems = if (normalized.none { it.enabled }) {
            normalized.mapIndexed { index, item -> item.copy(enabled = index == 0) }
        } else {
            normalized
        }
        val raw = safeItems.joinToString(",") {
            "${it.type.name}:${if (it.enabled) 1 else 0}"
        }
        appCtx.putPrefString(PreferKey.bookInfoComponents, raw)
    }

    fun reset() {
        save(defaultItems())
    }

    private fun defaultItems(): MutableList<BookInfoComponentItem> {
        return defaultOrder.map { BookInfoComponentItem(it, true) }.toMutableList()
    }
}
