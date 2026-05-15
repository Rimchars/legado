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
    private var webDavTaskStates: Map<String, WebDavTaskState> = emptyMap()

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
                    oldItem.remoteAvailable == newItem.remoteAvailable &&
                    oldItem.remoteCachedCount == newItem.remoteCachedCount &&
                    oldItem.remoteUpdatedAt == newItem.remoteUpdatedAt &&
                    oldItem.remoteZipFileName == newItem.remoteZipFileName &&
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
        root.background = UiCorner.panelRounded(
            context,
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
        btnUpload.setText(
            when {
                item.hasLocalCache() && item.hasRemoteCache() -> R.string.cache_manage_sync_action
                item.hasRemoteCache() && !item.hasLocalCache() -> R.string.action_download
                else -> R.string.cache_manage_upload
            }
        )
        btnSource.isEnabled = item.sourceVariants.size > 1
        btnSource.alpha = if (item.sourceVariants.size > 1) 1f else 0.72f
        tvCache.text = item.cacheCountText(context)
        tvCacheState.text = context.getString(item.cacheStateLabelRes())
        btnBookshelf.setText(
            if (item.inBookshelf) R.string.cache_manage_use_cache
            else R.string.cache_manage_add_bookshelf
        )
        if (item.manifest != null && item.hasLocalCache()) btnBookshelf.visible() else btnBookshelf.gone()
        btnChapters.isEnabled = item.hasLocalCache()
        btnChapters.alpha = if (item.hasLocalCache()) 1f else 0.45f
        updateTaskViews(this, item)
    }

    private fun updateTaskViews(binding: ItemCacheManageBookBinding, item: CacheBookItem) = binding.run {
        val audioState = taskStateFor(item)
        val webDavState = webDavTaskStateFor(item)
        val isCaching = audioState?.active == true
        val isPaused = audioState?.status == CacheTaskStatus.PAUSED
        val webDavActive = webDavState?.active == true
        if (isCaching || isPaused) {
            tvTask.visible()
            tvTask.text = audioState.message
            btnStop.setText(if (isPaused) R.string.resume else R.string.pause)
            btnStop.visible()
        } else if (webDavActive) {
            tvTask.visible()
            tvTask.text = webDavState.message
            btnStop.gone()
        } else {
            val lastMessage = webDavState?.takeIf { it.status != WebDavTaskStatus.COMPLETED }?.message
                ?: audioState?.takeIf { it.status != CacheTaskStatus.COMPLETED }?.message
            if (!lastMessage.isNullOrBlank()) {
                tvTask.visible()
                tvTask.text = lastMessage
            } else {
                tvTask.gone()
            }
            btnStop.gone()
        }
        val hasCache = item.hasLocalCache()
        val canSync = hasCache || item.hasRemoteCache()
        val taskLocked = isCaching || isPaused || webDavActive
        btnUpload.isEnabled = canSync && !taskLocked
        val canDelete = (hasCache || item.hasRemoteCache()) && !taskLocked
        btnDelete.isEnabled = canDelete
        btnUpload.alpha = if (canSync && !taskLocked) 1f else 0.45f
        btnDelete.alpha = if (canDelete) 1f else 0.45f
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemCacheManageBookBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::openChapters)
        }
        binding.btnChapters.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::openChapters)
        }
        binding.btnUpload.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                when {
                    it.hasLocalCache() && it.hasRemoteCache() -> callback.selectSyncAction(it)
                    it.hasRemoteCache() && !it.hasLocalCache() -> callback.download(it)
                    else -> callback.upload(it)
                }
            }
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

    fun updateWebDavTaskStates(states: Map<String, WebDavTaskState>) {
        val changedCacheKeys = (webDavTaskStates.keys + states.keys)
            .filterTo(hashSetOf<String>()) { webDavTaskStates[it] != states[it] }
        webDavTaskStates = states
        if (changedCacheKeys.isEmpty()) return
        getItems().forEachIndexed { index, item ->
            if (item.containsCacheKey(changedCacheKeys)) {
                notifyItemChanged(index, PAYLOAD_TASK_STATE)
            }
        }
    }

    interface Callback {
        fun openChapters(item: CacheBookItem)
        fun upload(item: CacheBookItem)
        fun download(item: CacheBookItem)
        fun selectSyncAction(item: CacheBookItem)
        fun restoreToBookshelf(item: CacheBookItem)
        fun deleteBookCache(item: CacheBookItem)
        fun stopAudioCache(item: CacheBookItem)
        fun selectSource(item: CacheBookItem)
    }

    private fun CacheBookItem.containsBookUrl(bookUrls: Set<String>): Boolean {
        return book.bookUrl in bookUrls || sourceVariants.any { it.book.bookUrl in bookUrls }
    }

    private fun CacheBookItem.containsCacheKey(cacheKeys: Set<String>): Boolean {
        return cacheKey in cacheKeys || sourceVariants.any { it.cacheKey in cacheKeys }
    }

    private fun taskStateFor(item: CacheBookItem): AudioCacheTaskState? {
        taskStates[item.book.bookUrl]?.let { return it }
        item.sourceVariants.forEach { variant ->
            taskStates[variant.book.bookUrl]?.let { return it }
            variant.taskState?.let { return it }
        }
        return item.taskState
    }

    private fun webDavTaskStateFor(item: CacheBookItem): WebDavTaskState? {
        webDavTaskStates[item.cacheKey]?.let { return it }
        item.sourceVariants.forEach { variant ->
            webDavTaskStates[variant.cacheKey]?.let { return it }
        }
        return null
    }

    private companion object {
        private val PAYLOAD_TASK_STATE = Any()
    }
}

private fun CacheBookItem.cacheCountText(context: Context): String {
    return buildCacheCountText(context, localCachedCount, remoteCachedCount, totalChapterCount, remoteAvailable)
}

fun CacheBookSourceVariant.cacheCountText(context: Context): String {
    return buildCacheCountText(context, localCachedCount, remoteCachedCount, totalChapterCount, remoteAvailable)
}

private fun buildCacheCountText(
    context: Context,
    localCount: Int,
    remoteCount: Int,
    totalCount: Int,
    remoteAvailable: Boolean
): String {
    val parts = arrayListOf<String>()
    if (localCount > 0) {
        parts += context.getString(R.string.cache_manage_local_cached_count, localCount)
    }
    if (remoteAvailable && remoteCount > 0) {
        parts += context.getString(R.string.cache_manage_remote_cached_count, remoteCount)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: context.getString(R.string.cache_manage_cached_count, localCount)
}


private fun CacheBookItem.hasLocalCache(): Boolean = localCachedCount > 0

private fun CacheBookItem.cacheStateLabelRes(): Int {
    val local = hasLocalCache()
    val remote = hasRemoteCache()
    return when {
        local && remote -> R.string.cache_manage_state_both
        local -> R.string.cache_manage_state_local
        remote -> R.string.cache_manage_state_remote
        else -> R.string.cache_manage_state_none
    }
}
