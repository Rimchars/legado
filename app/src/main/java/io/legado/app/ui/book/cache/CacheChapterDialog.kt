package io.legado.app.ui.book.cache

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogCacheChaptersBinding
import io.legado.app.help.book.isAudio
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CacheChapterDialog :
    BaseDialogFragment(R.layout.dialog_cache_chapters),
    CacheChapterAdapter.Callback {

    private val binding by viewBinding(DialogCacheChaptersBinding::bind)
    private val viewModel by activityViewModels<CacheManageViewModel>()
    private val adapter by lazy { CacheChapterAdapter(requireContext(), this) }
    private val searchView: SearchView by lazy {
        binding.toolBar.findViewById(R.id.search_view)
    }
    private val book: Book by lazy {
        requireArguments().getParcelable<Book>("book")!!
    }
    private var chapterLoadJob: Job? = null
    private var filter = CacheChapterFilter.ALL

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        loadChapters()
    }

    private fun initView() = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.title = getString(R.string.cache_manage_chapters)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.cache_manage_search_chapter)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                loadChapters(newText)
                return false
            }
        })
        btnFilterAll.setOnClickListener { switchFilter(CacheChapterFilter.ALL) }
        btnFilterCached.setOnClickListener { switchFilter(CacheChapterFilter.CACHED) }
        btnFilterUncached.setOnClickListener { switchFilter(CacheChapterFilter.UNCACHED) }
        btnSelectAll.setOnClickListener {
            adapter.selectAllVisible()
            updateSelectionBar()
        }
        btnCacheSelected.setOnClickListener { cacheSelectedChapters() }
        btnDeleteSelected.setOnClickListener { deleteSelectedChapters() }
        updateFilterButtons()
        updateSelectionBar()
    }

    override fun onChapterClick(item: CacheChapterItem) {
        if (!adapter.selectionMode) return
        adapter.toggleSelection(item)
        updateSelectionBar()
    }

    override fun onChapterLongClick(item: CacheChapterItem) {
        if (!adapter.selectionMode) {
            adapter.setSelectionMode(true)
        }
        adapter.toggleSelection(item)
        updateSelectionBar()
    }

    private fun switchFilter(newFilter: CacheChapterFilter) {
        if (filter == newFilter) return
        filter = newFilter
        adapter.setSelectionMode(false)
        updateFilterButtons()
        updateSelectionBar()
        loadChapters(searchView.query?.toString())
    }

    private fun updateFilterButtons() = binding.run {
        listOf(
            btnFilterAll to CacheChapterFilter.ALL,
            btnFilterCached to CacheChapterFilter.CACHED,
            btnFilterUncached to CacheChapterFilter.UNCACHED
        ).forEach { (button, itemFilter) ->
            button.setTextColor(if (filter == itemFilter) accentColor else primaryTextColor)
        }
    }

    private fun updateSelectionBar() = binding.run {
        val selectedCount = adapter.getSelectedItems().size
        if (adapter.selectionMode) {
            selectionBar.visible()
            tvHint.gone()
            tvSelectionCount.text = getString(R.string.cache_manage_selected_count, selectedCount)
            btnCacheSelected.isEnabled = selectedCount > 0
            btnDeleteSelected.isEnabled = selectedCount > 0
            btnCacheSelected.alpha = if (selectedCount > 0) 1f else 0.45f
            btnDeleteSelected.alpha = if (selectedCount > 0) 1f else 0.45f
        } else {
            selectionBar.gone()
            tvHint.visible()
        }
    }

    private fun loadChapters(key: String? = null) {
        chapterLoadJob?.cancel()
        binding.rotateLoading.visible()
        lateinit var job: Job
        job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val items = viewModel.getChapterItems(book, key, filter)
                if (chapterLoadJob !== job) return@launch
                adapter.setItems(items)
                binding.tvMsg.run {
                    if (items.isEmpty()) {
                        setText(R.string.chapter_list_empty)
                        visible()
                    } else {
                        gone()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (chapterLoadJob !== job) return@launch
                binding.tvMsg.text = e.localizedMessage
                binding.tvMsg.visible()
            } finally {
                if (chapterLoadJob === job) {
                    binding.rotateLoading.gone()
                }
            }
        }
        chapterLoadJob = job
        job.start()
    }

    private fun cacheSelectedChapters() {
        val items = adapter.getSelectedItems().filterNot { it.cached }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        lifecycleScope.launch {
            kotlin.runCatching {
                if (book.isAudio) {
                    viewModel.cacheAudioChapters(book, items.map { it.chapter })
                } else {
                    viewModel.cacheBookChapters(book, items.map { it.chapter })
                }
            }.onSuccess { count ->
                callback?.onCacheChanged()
                adapter.setSelectionMode(false)
                updateSelectionBar()
                if (book.isAudio && count > 0) {
                    toastOnUi(getString(R.string.cache_manage_audio_cache_started, count))
                    dismissAllowingStateLoss()
                } else {
                    loadChapters(searchView.query?.toString())
                    toastOnUi(getString(R.string.cache_manage_cache_selected_done, count))
                }
            }.onFailure {
                toastOnUi(getString(R.string.cache_manage_cache_failed, it.localizedMessage))
            }
        }
    }

    private fun deleteSelectedChapters() {
        val items = adapter.getSelectedItems().filter { it.cached }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        alert(
            getString(R.string.delete),
            getString(R.string.cache_manage_delete_selected_confirm, items.size)
        ) {
            yesButton {
                binding.rotateLoading.visible()
                lifecycleScope.launch {
                    kotlin.runCatching {
                        items.forEach { viewModel.deleteChapterCache(book, it.chapter) }
                    }.onSuccess {
                        callback?.onCacheChanged()
                        adapter.setSelectionMode(false)
                        updateSelectionBar()
                        loadChapters(searchView.query?.toString())
                        toastOnUi(R.string.delete_success)
                    }.onFailure {
                        binding.rotateLoading.gone()
                        toastOnUi(
                            getString(
                                R.string.cache_manage_delete_chapter_failed,
                                it.localizedMessage
                            )
                        )
                    }
                }
            }
            noButton()
        }
    }

    private val callback: Callback?
        get() = activity as? Callback

    interface Callback {
        fun onCacheChanged()
    }

    companion object {
        fun newInstance(book: Book): CacheChapterDialog {
            return CacheChapterDialog().apply {
                arguments = bundleOf("book" to book)
            }
        }
    }
}
