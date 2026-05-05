package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.databinding.ItemCacheManageBookBinding
import io.legado.app.utils.gone
import io.legado.app.utils.visible

class CacheManageAdapter(
    context: Context,
    private val callback: Callback
) : DiffRecyclerAdapter<CacheBookItem, ItemCacheManageBookBinding>(context) {

    private var taskStates: Map<String, AudioCacheTaskState> = emptyMap()

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
                    oldItem.mode == newItem.mode &&
                    oldItem.taskState == newItem.taskState
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
        val taskState = taskStates[book.bookUrl] ?: item.taskState
        val isCaching = taskState?.active == true
        if (taskState?.active == true) {
            tvTask.visible()
            tvTask.text = taskState.message
            btnStop.visible()
        } else {
            val lastMessage = taskState?.message
            if (!lastMessage.isNullOrBlank() && taskState.status != CacheTaskStatus.COMPLETED) {
                tvTask.visible()
                tvTask.text = lastMessage
            } else {
                tvTask.gone()
            }
            btnStop.gone()
        }
        val hasCache = item.cachedCount > 0
        btnUpload.isEnabled = hasCache && !isCaching
        btnDelete.isEnabled = hasCache && !isCaching
        btnUpload.alpha = if (hasCache && !isCaching) 1f else 0.45f
        btnDelete.alpha = if (hasCache && !isCaching) 1f else 0.45f
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
        binding.btnStop.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::stopAudioCache)
        }
    }

    fun updateTaskStates(states: Map<String, AudioCacheTaskState>) {
        taskStates = states
        notifyDataSetChanged()
    }

    interface Callback {
        fun openChapters(item: CacheBookItem)
        fun upload(item: CacheBookItem)
        fun deleteBookCache(item: CacheBookItem)
        fun stopAudioCache(item: CacheBookItem)
    }
}
