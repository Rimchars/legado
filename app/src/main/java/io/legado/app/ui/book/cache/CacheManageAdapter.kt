package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.databinding.ItemCacheManageBookBinding
import io.legado.app.lib.theme.UiCorner
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
                return oldItem.groupKey == newItem.groupKey
            }

            override fun areContentsTheSame(oldItem: CacheBookItem, newItem: CacheBookItem): Boolean {
                return oldItem.book.name == newItem.book.name &&
                    oldItem.book.author == newItem.book.author &&
                    oldItem.book.latestChapterTitle == newItem.book.latestChapterTitle &&
                    oldItem.sourceKey == newItem.sourceKey &&
                    oldItem.sourceName == newItem.sourceName &&
                    oldItem.cachedCount == newItem.cachedCount &&
                    oldItem.totalChapterCount == newItem.totalChapterCount &&
                    oldItem.mode == newItem.mode &&
                    oldItem.taskState == newItem.taskState &&
                    oldItem.inBookshelf == newItem.inBookshelf &&
                    oldItem.sourceAvailable == newItem.sourceAvailable &&
                    oldItem.sourceVariants == newItem.sourceVariants
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
        if (payloads.any { it === PAYLOAD_TASK_STATE }) {
            updateTaskViews(this, item)
            return@run
        }
        val book = item.book
        root.background = UiCorner.rounded(
            ContextCompat.getColor(context, R.color.background_card),
            UiCorner.panelRadius(context)
        )
        btnSource.background = UiCorner.actionSelector(
            ContextCompat.getColor(context, R.color.background_menu),
            ContextCompat.getColor(context, R.color.background_card),
            UiCorner.actionRadius(context)
        )
        listOf(btnChapters, btnUpload, btnDelete, btnBookshelf, btnStop).forEach {
            it.background = UiCorner.actionSelector(
                ContextCompat.getColor(context, R.color.background_card),
                ContextCompat.getColor(context, R.color.background_menu),
                UiCorner.actionRadius(context)
            )
        }
        ivCover.load(book, false)
        tvName.text = book.name
        btnSource.text = if (item.sourceAvailable) {
            item.sourceName
        } else {
            context.getString(R.string.cache_manage_source_deleted_chip, item.sourceName)
        }
        btnSource.isEnabled = item.sourceVariants.size > 1
        btnSource.alpha = if (item.sourceVariants.size > 1) 1f else 0.72f
        tvCache.text = context.getString(
            R.string.cache_manage_cached_count,
            item.cachedCount,
            item.totalChapterCount
        )
        btnBookshelf.setText(
            if (item.inBookshelf) R.string.cache_manage_use_cache
            else R.string.cache_manage_add_bookshelf
        )
        if (item.manifest != null) btnBookshelf.visible() else btnBookshelf.gone()
        updateTaskViews(this, item)
    }

    private fun updateTaskViews(binding: ItemCacheManageBookBinding, item: CacheBookItem) = binding.run {
        val taskState = taskStateFor(item)
        val isCaching = taskState?.active == true
        val isPaused = taskState?.status == CacheTaskStatus.PAUSED
        if (isCaching || isPaused) {
            tvTask.visible()
            tvTask.text = taskState?.message
            btnStop.setText(if (isPaused) R.string.resume else R.string.pause)
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
        val taskLocked = isCaching || isPaused
        btnUpload.isEnabled = hasCache && !taskLocked
        btnDelete.isEnabled = hasCache && !taskLocked
        btnUpload.alpha = if (hasCache && !taskLocked) 1f else 0.45f
        btnDelete.alpha = if (hasCache && !taskLocked) 1f else 0.45f
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
        binding.btnBookshelf.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::restoreToBookshelf)
        }
        binding.btnDelete.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::deleteBookCache)
        }
        binding.btnStop.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::stopAudioCache)
        }
        binding.btnSource.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::selectSource)
        }
    }

    fun updateTaskStates(states: Map<String, AudioCacheTaskState>) {
        val changedBookUrls = (taskStates.keys + states.keys)
            .filterTo(hashSetOf<String>()) { taskStates[it] != states[it] }
        taskStates = states
        if (changedBookUrls.isEmpty()) return
        getItems().forEachIndexed { index, item ->
            if (item.containsBookUrl(changedBookUrls)) {
                notifyItemChanged(index, PAYLOAD_TASK_STATE)
            }
        }
    }

    interface Callback {
        fun openChapters(item: CacheBookItem)
        fun upload(item: CacheBookItem)
        fun restoreToBookshelf(item: CacheBookItem)
        fun deleteBookCache(item: CacheBookItem)
        fun stopAudioCache(item: CacheBookItem)
        fun selectSource(item: CacheBookItem)
    }

    private fun CacheBookItem.containsBookUrl(bookUrls: Set<String>): Boolean {
        return book.bookUrl in bookUrls || sourceVariants.any { it.book.bookUrl in bookUrls }
    }

    private fun taskStateFor(item: CacheBookItem): AudioCacheTaskState? {
        taskStates[item.book.bookUrl]?.let { return it }
        item.sourceVariants.forEach { variant ->
            taskStates[variant.book.bookUrl]?.let { return it }
            variant.taskState?.let { return it }
        }
        return item.taskState
    }

    private companion object {
        private val PAYLOAD_TASK_STATE = Any()
    }
}
