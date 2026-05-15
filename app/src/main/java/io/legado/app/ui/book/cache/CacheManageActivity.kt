package io.legado.app.ui.book.cache

import android.os.Bundle
import android.graphics.Color
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityCacheManageBinding
import io.legado.app.lib.dialogs.AndroidAlertBuilder
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.gone
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CacheManageActivity :
    VMBaseActivity<ActivityCacheManageBinding, CacheManageViewModel>(),
    CacheManageAdapter.Callback,
    CacheChapterDialog.Callback {

    override val binding by viewBinding(ActivityCacheManageBinding::inflate)
    override val viewModel by viewModels<CacheManageViewModel>()

    private val adapter by lazy { CacheManageAdapter(this, this) }
    private var audioTaskReloadJob: Job? = null
    private var lastMissingTaskReloadAt = 0L
    private val handledTerminalTaskReloads = hashSetOf<String>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        observeData()
        observeTasks()
        viewModel.load(CacheManageMode.BOOK)
    }

    private fun initView() = binding.run {
        tabBar.background = UiCorner.opaqueRounded(
            ContextCompat.getColor(this@CacheManageActivity, R.color.background_menu),
            UiCorner.panelRadius(this@CacheManageActivity)
        )
        listOf(btnBooks, btnAudio, btnManga).forEach {
            it.background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                ContextCompat.getColor(this@CacheManageActivity, R.color.background_card),
                UiCorner.actionRadius(this@CacheManageActivity)
            )
        }
        recyclerView.layoutManager = LinearLayoutManager(this@CacheManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        btnBooks.setOnClickListener { switchMode(CacheManageMode.BOOK) }
        btnAudio.setOnClickListener { switchMode(CacheManageMode.AUDIO) }
        btnManga.setOnClickListener { switchMode(CacheManageMode.MANGA) }
        btnUploadAll.setOnClickListener { uploadAll() }
        btnDeleteAll.setOnClickListener { deleteAll() }
        updateTabs(CacheManageMode.BOOK)
    }

    private fun observeData() {
        viewModel.itemsLiveData.observe(this) { items ->
            adapter.setItems(items)
            binding.tvEmpty.run {
                if (items.isEmpty()) {
                    text = getString(R.string.cache_manage_empty, getString(viewModel.mode.titleRes))
                    visible()
                } else {
                    gone()
                }
            }
        }
        viewModel.summaryLiveData.observe(this) { summary ->
            binding.tvSummary.text = getString(
                R.string.cache_manage_summary_state,
                summary.bookCount,
                summary.cachedChapterCount
            )
        }
        viewModel.loadingLiveData.observe(this) { loading ->
            if (loading) binding.rotateLoading.visible() else binding.rotateLoading.gone()
        }
    }

    private fun observeTasks() {
        lifecycleScope.launch {
            AudioCacheTaskManager.states.collectLatest { states ->
                adapter.updateTaskStates(states)
                if (viewModel.mode == CacheManageMode.AUDIO) {
                    reloadAudioItemsWhenNeeded(states)
                }
            }
        }
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                adapter.updateWebDavTaskStates(states)
                reloadItemsWhenWebDavTaskFinished(states)
            }
        }
    }

    private fun reloadItemsWhenWebDavTaskFinished(states: Map<String, WebDavTaskState>) {
        states.values
            .filter { !it.active && it.status.isTerminalForListRefresh() }
            .forEach { state ->
                val key = "webdav:${state.key}:${state.type}:${state.status}"
                if (handledTerminalTaskReloads.add(key)) {
                    viewModel.load()
                }
            }
    }
    private fun reloadAudioItemsWhenNeeded(states: Map<String, AudioCacheTaskState>) {
        val stateValues = states.values
        val activeTaskBookUrls = stateValues
            .asSequence()
            .filter { it.active }
            .mapTo(hashSetOf<String>()) { it.bookUrl }
        if (activeTaskBookUrls.isNotEmpty()) {
            val visibleBookUrls = hashSetOf<String>()
            adapter.getItems().forEach { item ->
                if (item.sourceVariants.isEmpty()) {
                    visibleBookUrls.add(item.book.bookUrl)
                } else {
                    item.sourceVariants.forEach { visibleBookUrls.add(it.book.bookUrl) }
                }
            }
            val missingActiveTasks = activeTaskBookUrls - visibleBookUrls
            if (missingActiveTasks.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastMissingTaskReloadAt > MISSING_TASK_RELOAD_INTERVAL_MS && !viewModel.isLoading()) {
                    lastMissingTaskReloadAt = now
                    scheduleAudioTaskReload(MISSING_TASK_RELOAD_DELAY_MS)
                }
            }
        }
        stateValues
            .filter { !it.active && it.status.isTerminalForListRefresh() }
            .forEach { state ->
                val key = "${state.bookUrl}:${state.status}:${state.completedChapters}:${state.totalChapters}"
                if (handledTerminalTaskReloads.add(key)) {
                    scheduleAudioTaskReload(TERMINAL_TASK_RELOAD_DELAY_MS)
                }
            }
    }

    private fun scheduleAudioTaskReload(delayMs: Long) {
        if (audioTaskReloadJob?.isActive == true) return
        audioTaskReloadJob = lifecycleScope.launch {
            delay(delayMs)
            if (viewModel.mode == CacheManageMode.AUDIO && !viewModel.isLoading()) {
                viewModel.load(CacheManageMode.AUDIO)
            }
        }
    }

    private fun switchMode(mode: CacheManageMode) {
        if (viewModel.mode == mode) return
        updateTabs(mode)
        viewModel.load(mode)
    }

    private fun updateTabs(mode: CacheManageMode) = binding.run {
        btnBooks.isSelected = mode == CacheManageMode.BOOK
        btnAudio.isSelected = mode == CacheManageMode.AUDIO
        btnManga.isSelected = mode == CacheManageMode.MANGA
        btnBooks.setTextColor(if (mode == CacheManageMode.BOOK) accentColor else primaryTextColor)
        btnAudio.setTextColor(if (mode == CacheManageMode.AUDIO) accentColor else primaryTextColor)
        btnManga.setTextColor(if (mode == CacheManageMode.MANGA) accentColor else primaryTextColor)
    }

    override fun openChapters(item: CacheBookItem) {
        if (item.localCachedCount <= 0) {
            toastOnUi(R.string.cache_manage_download_first)
            return
        }
        showDialogFragment(CacheChapterDialog.newInstance(item.book))
    }

    override fun upload(item: CacheBookItem) {
        selectSyncStrategy(R.string.cache_manage_upload_strategy_title) { strategy ->
            val queued = WebDavTaskManager.enqueueCacheUpload(item) {
                viewModel.uploadCacheItem(item, strategy)
            }
            toastOnUi(if (queued) R.string.cache_manage_upload_queued else R.string.cache_manage_webdav_task_duplicate)
        }
    }

    override fun download(item: CacheBookItem) {
        selectSyncStrategy(R.string.cache_manage_download_strategy_title) { strategy ->
            val queued = WebDavTaskManager.enqueueCacheDownload(item) {
                viewModel.downloadRemoteCache(item, strategy)
            }
            toastOnUi(if (queued) R.string.cache_manage_download_queued else R.string.cache_manage_webdav_task_duplicate)
        }
    }

    override fun selectSyncAction(item: CacheBookItem) {
        val actions = listOf(
            R.string.cache_manage_upload to { upload(item) },
            R.string.action_download to { download(item) }
        )
        selector(getString(R.string.cache_manage_sync_action), actions.map { getString(it.first) }) { _, index ->
            actions.getOrNull(index)?.second?.invoke()
        }
    }

    override fun restoreToBookshelf(item: CacheBookItem) {
        lifecycleScope.launch {
            kotlin.runCatching {
                viewModel.restoreCacheToBookshelf(item)
            }.onSuccess { success ->
                if (success) {
                    toastOnUi(
                        if (item.inBookshelf) R.string.cache_manage_use_cache_success
                        else R.string.cache_manage_add_bookshelf_success
                    )
                    viewModel.load()
                } else {
                    toastOnUi(R.string.cache_manage_no_cache)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    override fun deleteBookCache(item: CacheBookItem) {
        selectDeleteTarget(
            item = item,
            localAvailable = item.localCachedCount > 0,
            remoteAvailable = item.hasRemoteCache()
        ) { target ->
            confirmDeleteTarget(target, 1) {
                viewModel.deleteBookCache(item, target) { result ->
                    toastDeleteResult(result)
                }
            }
        }
    }

    override fun stopAudioCache(item: CacheBookItem) {
        AudioCacheTaskManager.togglePause(item.book.bookUrl)
    }

    override fun selectSource(item: CacheBookItem) {
        val variants = item.sourceVariants
        if (variants.size <= 1) return
        val labels: List<CharSequence> = variants.map { variant ->
            buildString {
                append(
                    if (variant.sourceAvailable) {
                        variant.sourceName
                    } else {
                        getString(R.string.cache_manage_source_deleted, variant.sourceName)
                    }
                )
                append(" · ")
                append(variant.cacheCountText(this@CacheManageActivity))
            }
        }
        selector(getString(R.string.cache_manage_select_source), labels) { _, index ->
            val variant = variants.getOrNull(index) ?: return@selector
            viewModel.selectSource(item.groupKey, variant.sourceKey)
        }
    }

    private fun uploadAll() {
        val items = adapter.getItems().filter { it.cachedCount > 0 && !it.hasLockedCacheTask() }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        val queued = items.count { item ->
            WebDavTaskManager.enqueueCacheUpload(item) {
                viewModel.uploadCacheItem(item)
            }
        }
        toastOnUi(getString(R.string.cache_manage_batch_upload_queued, queued))
    }

    private fun deleteAll() {
        val items = adapter.getItems().filter {
            !it.hasLockedCacheTask() && (it.localCachedCount > 0 || it.hasRemoteCache())
        }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        val localAvailable = items.any { it.localCachedCount > 0 }
        val remoteAvailable = items.any { it.hasRemoteCache() }
        selectDeleteTarget(localAvailable = localAvailable, remoteAvailable = remoteAvailable) { target ->
            val targets = items.filter { item -> target.canDelete(item) }
            if (targets.isEmpty()) {
                toastOnUi(R.string.cache_manage_batch_empty)
                return@selectDeleteTarget
            }
            confirmDeleteTarget(target, targets.size) {
                viewModel.deleteBookCaches(targets, target) { result ->
                    toastDeleteResult(result)
                }
            }
        }
    }

    private fun selectDeleteTarget(
        item: CacheBookItem? = null,
        localAvailable: Boolean,
        remoteAvailable: Boolean,
        onSelected: (CacheDeleteTarget) -> Unit
    ) {
        val targets = CacheDeleteTarget.entries.filter { target ->
            when (target) {
                CacheDeleteTarget.LOCAL -> localAvailable
                CacheDeleteTarget.REMOTE -> remoteAvailable
                CacheDeleteTarget.BOTH -> localAvailable && remoteAvailable
            }
        }
        if (targets.isEmpty()) {
            toastOnUi(R.string.cache_manage_no_cache)
            return
        }
        if (targets.size == 1) {
            onSelected(targets.first())
            return
        }
        val title = item?.let { getString(R.string.cache_manage_delete_book_title, it.book.name) }
            ?: getString(R.string.delete)
        selector(title, targets.map { getString(it.labelRes) }) { _, index ->
            targets.getOrNull(index)?.let(onSelected)
        }
    }

    private fun confirmDeleteTarget(
        target: CacheDeleteTarget,
        count: Int,
        onConfirmed: () -> Unit
    ) {
        val message = when (target) {
            CacheDeleteTarget.LOCAL -> getString(R.string.cache_manage_delete_local_confirm, count)
            CacheDeleteTarget.REMOTE -> getString(R.string.cache_manage_delete_remote_confirm, count)
            CacheDeleteTarget.BOTH -> getString(R.string.cache_manage_delete_both_confirm, count)
        }
        AndroidAlertBuilder(this).apply {
            setTitle(R.string.delete)
            setMessage(message)
            yesButton { onConfirmed() }
            noButton()
            show()
        }
    }

    private fun selectSyncStrategy(
        titleRes: Int,
        onSelected: (CacheSyncStrategy) -> Unit
    ) {
        val strategies = CacheSyncStrategy.entries
        selector(getString(titleRes), strategies.map { getString(it.labelRes) }) { _, index ->
            strategies.getOrNull(index)?.let(onSelected)
        }
    }

    private fun toastDeleteResult(result: CacheDeleteResult) {
        result.messageRes?.let {
            toastOnUi(it)
            return
        }
        toastOnUi(result.errorMessage ?: getString(R.string.error))
    }

    override fun onCacheChanged() {
        viewModel.load()
    }

    override fun openCacheChapter(book: Book, chapter: BookChapter) {
        val target = book.apply {
            durChapterIndex = chapter.index
            durChapterTitle = chapter.title
            durChapterPos = 0
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                appDb.bookDao.update(target)
            }
            startActivityForBook(target)
        }
    }
}

private fun CacheTaskStatus.isTerminalForListRefresh(): Boolean {
    return this == CacheTaskStatus.COMPLETED ||
        this == CacheTaskStatus.PAUSED ||
        this == CacheTaskStatus.CANCELLED ||
        this == CacheTaskStatus.FAILED
}

private fun WebDavTaskStatus.isTerminalForListRefresh(): Boolean {
    return this == WebDavTaskStatus.COMPLETED ||
        this == WebDavTaskStatus.CANCELLED ||
        this == WebDavTaskStatus.FAILED
}

private const val MISSING_TASK_RELOAD_INTERVAL_MS = 2500L
private const val MISSING_TASK_RELOAD_DELAY_MS = 250L
private const val TERMINAL_TASK_RELOAD_DELAY_MS = 600L

private fun CacheBookItem.hasLockedCacheTask(): Boolean {
    if (AudioCacheTaskManager.snapshot(book.bookUrl).locksCacheActions()) return true
    if (WebDavTaskManager.snapshot(cacheKey)?.active == true) return true
    return sourceVariants.any {
        AudioCacheTaskManager.snapshot(it.book.bookUrl).locksCacheActions() ||
            WebDavTaskManager.snapshot(it.cacheKey)?.active == true
    }
}

private fun AudioCacheTaskState?.locksCacheActions(): Boolean {
    return this?.active == true || this?.status == CacheTaskStatus.PAUSED
}

private fun CacheDeleteTarget.canDelete(item: CacheBookItem): Boolean {
    return when (this) {
        CacheDeleteTarget.LOCAL -> item.localCachedCount > 0
        CacheDeleteTarget.REMOTE -> item.hasRemoteCache()
        CacheDeleteTarget.BOTH -> item.localCachedCount > 0 || item.hasRemoteCache()
    }
}
