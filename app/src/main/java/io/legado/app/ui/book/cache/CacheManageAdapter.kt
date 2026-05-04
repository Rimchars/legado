package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.databinding.ItemCacheManageBookBinding

class CacheManageAdapter(
    context: Context,
    private val callback: Callback
) : DiffRecyclerAdapter<CacheBookItem, ItemCacheManageBookBinding>(context) {

    override val diffItemCallback: DiffUtil.ItemCallback<CacheBookItem> =
        object : DiffUtil.ItemCallback<CacheBookItem>() {
            override fun areItemsTheSame(oldItem: CacheBookItem, newItem: CacheBookItem): Boolean {
                return oldItem.book.bookUrl == newItem.book.bookUrl
            }

            override fun areContentsTheSame(oldItem: CacheBookItem, newItem: CacheBookItem): Boolean {
                return oldItem.book.name == newItem.book.name &&
                    oldItem.book.author == newItem.book.author &&
                    oldItem.book.durChapterTitle == newItem.book.durChapterTitle &&
                    oldItem.book.latestChapterTitle == newItem.book.latestChapterTitle &&
                    oldItem.cachedCount == newItem.cachedCount &&
                    oldItem.totalChapterCount == newItem.totalChapterCount &&
                    oldItem.mode == newItem.mode
            }
        }

    override fun getViewBinding(parent: ViewGroup): ItemCacheManageBookBinding {
        return ItemCacheManageBookBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemCacheManageBookBinding,
        item: CacheBookItem,
        payloads: MutableList<Any>
    ) = binding.run {
        val book = item.book
        ivCover.load(book, false)
        tvName.text = book.name
        tvType.setText(item.mode.titleRes)
        tvAuthor.text = context.getString(R.string.author_show, book.getRealAuthor())
        tvRead.text = book.durChapterTitle?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.last_read)
        tvLatest.text = book.latestChapterTitle?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.lasted_show, context.getString(R.string.unknown))
        tvCache.text = context.getString(
            R.string.cache_manage_cached_count,
            item.cachedCount,
            item.totalChapterCount
        )
        val hasCache = item.cachedCount > 0
        btnUpload.isEnabled = hasCache
        btnDelete.isEnabled = hasCache
        btnUpload.alpha = if (hasCache) 1f else 0.45f
        btnDelete.alpha = if (hasCache) 1f else 0.45f
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemCacheManageBookBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::openChapters)
        }
        binding.btnChapters.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::openChapters)
        }
        binding.btnUpload.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::upload)
        }
        binding.btnDelete.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::deleteBookCache)
        }
    }

    interface Callback {
        fun openChapters(item: CacheBookItem)
        fun upload(item: CacheBookItem)
        fun deleteBookCache(item: CacheBookItem)
    }
}
