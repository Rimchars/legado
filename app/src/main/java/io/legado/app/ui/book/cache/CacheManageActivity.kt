package io.legado.app.ui.book.cache

import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityCacheManageBinding
import io.legado.app.help.AppWebDav
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.gone
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

class CacheManageActivity :
    VMBaseActivity<ActivityCacheManageBinding, CacheManageViewModel>(),
    CacheManageAdapter.Callback,
    CacheChapterDialog.Callback {

    override val binding by viewBinding(ActivityCacheManageBinding::inflate)
    override val viewModel by viewModels<CacheManageViewModel>()

    private val adapter by lazy { CacheManageAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        observeData()
        observeTasks()
        viewModel.load(CacheManageMode.BOOK)
    }

    private fun initView() = binding.run {
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
                    val visibleBookUrls = adapter.getItems().map { it.book.bookUrl }.toSet()
                    val taskBookUrls = states.values.filter { it.active }.map { it.bookUrl }.toSet()
                    if (!visibleBookUrls.containsAll(taskBookUrls)) {
                        viewModel.load()
                    } else if (states.values.any { !it.active }) {
                        viewModel.load()
                    }
                }
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
        showDialogFragment(CacheChapterDialog.newInstance(item.book))
    }

    override fun upload(item: CacheBookItem) {
        lifecycleScope.launch {
            toastOnUi(R.string.cache_manage_uploading)
            kotlin.runCatching {
                val zipFile = viewModel.createCachePackage(item.book)
                withContext(Dispatchers.IO) {
                    AppWebDav.uploadCachePackage(zipFile.name, zipFile)
                }
            }.onSuccess {
                toastOnUi(R.string.cache_manage_upload_success)
            }.onFailure {
                toastOnUi(getString(R.string.cache_manage_upload_failed, it.localizedMessage))
            }
        }
    }

    override fun deleteBookCache(item: CacheBookItem) {
        alert(getString(R.string.delete), getString(R.string.cache_manage_delete_book_confirm, item.book.name)) {
            yesButton {
                viewModel.deleteBookCache(item.book) {
                    toastOnUi(R.string.delete_success)
                }
            }
            noButton()
        }
    }

    override fun stopAudioCache(item: CacheBookItem) {
        AudioCacheTaskManager.cancel(item.book.bookUrl)
    }

    private fun uploadAll() {
        val items = adapter.getItems().filter { it.cachedCount > 0 }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        lifecycleScope.launch {
            toastOnUi(R.string.cache_manage_uploading)
            var success = 0
            var failed = 0
            items.forEach { item ->
                kotlin.runCatching {
                    val zipFile = viewModel.createCachePackage(item.book)
                    withContext(Dispatchers.IO) {
                        AppWebDav.uploadCachePackage(zipFile.name, zipFile)
                    }
                }.onSuccess {
                    success++
                }.onFailure {
                    failed++
                }
            }
            toastOnUi(getString(R.string.cache_manage_batch_upload_done, success, failed))
        }
    }

    private fun deleteAll() {
        val items = adapter.getItems().filter { it.cachedCount > 0 }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        alert(
            getString(R.string.delete),
            getString(R.string.cache_manage_delete_all_confirm, items.size)
        ) {
            yesButton {
                viewModel.deleteBookCaches(items.map { it.book }) {
                    toastOnUi(R.string.delete_success)
                }
            }
            noButton()
        }
    }

    override fun onCacheChanged() {
        viewModel.load()
    }
}
