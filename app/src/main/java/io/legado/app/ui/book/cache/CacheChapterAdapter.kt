package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.databinding.ItemCacheChapterBinding

class CacheChapterAdapter(
    context: Context,
    private val callback: Callback
) :
    DiffRecyclerAdapter<CacheChapterItem, ItemCacheChapterBinding>(context) {

    private val selectedKeys = linkedSetOf<String>()
    var selectionMode: Boolean = false
        private set

    override val diffItemCallback: DiffUtil.ItemCallback<CacheChapterItem> =
        object : DiffUtil.ItemCallback<CacheChapterItem>() {
            override fun areItemsTheSame(oldItem: CacheChapterItem, newItem: CacheChapterItem): Boolean {
                return oldItem.chapter.bookUrl == newItem.chapter.bookUrl &&
                    oldItem.chapter.url == newItem.chapter.url
            }

            override fun areContentsTheSame(oldItem: CacheChapterItem, newItem: CacheChapterItem): Boolean {
                return oldItem.chapter.title == newItem.chapter.title &&
                    oldItem.chapter.index == newItem.chapter.index &&
                    oldItem.cached == newItem.cached
            }
        }

    override fun getViewBinding(parent: ViewGroup): ItemCacheChapterBinding {
        return ItemCacheChapterBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemCacheChapterBinding,
        item: CacheChapterItem,
        payloads: MutableList<Any>
    ) = binding.run {
        tvTitle.text = "${item.chapter.index + 1}. ${item.chapter.title}"
        tvState.setText(if (item.cached) R.string.cache_manage_cached else R.string.cache_manage_not_cached)
        cbSelect.isVisible = selectionMode
        cbSelect.isChecked = selectedKeys.contains(item.key)
        root.isSelected = selectedKeys.contains(item.key)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemCacheChapterBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::onChapterClick)
        }
        binding.root.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let(callback::onChapterLongClick)
            true
        }
        binding.cbSelect.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::onChapterClick)
        }
    }

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) {
            selectedKeys.clear()
        }
        notifyDataSetChanged()
    }

    fun toggleSelection(item: CacheChapterItem) {
        if (selectedKeys.contains(item.key)) {
            selectedKeys.remove(item.key)
        } else {
            selectedKeys.add(item.key)
        }
        notifyDataSetChanged()
    }

    fun selectAllVisible() {
        selectedKeys.clear()
        getItems().forEach { selectedKeys.add(it.key) }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedKeys.clear()
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<CacheChapterItem> {
        if (selectedKeys.isEmpty()) return emptyList()
        return getItems().filter { selectedKeys.contains(it.key) }
    }

    interface Callback {
        fun onChapterClick(item: CacheChapterItem)
        fun onChapterLongClick(item: CacheChapterItem)
    }
}

private val CacheChapterItem.key: String
    get() = "${chapter.bookUrl}\u0000${chapter.index}\u0000${chapter.url}"
