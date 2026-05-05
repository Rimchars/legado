package io.legado.app.ui.book.cache

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogSourcePickerBinding
import io.legado.app.help.book.isAudio
import io.legado.app.lib.dialogs.alert
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

class CacheChapterDialog : BaseDialogFragment(R.layout.dialog_source_picker) {

    private val binding by viewBinding(DialogSourcePickerBinding::bind)
    private val viewModel by activityViewModels<CacheManageViewModel>()
    private val adapter by lazy { CacheChapterAdapter(requireContext()) }
    private val searchView: SearchView by lazy {
        binding.toolBar.findViewById(R.id.search_view)
    }
    private val cachedOnlyCheckBox by lazy {
        binding.cbCachedOnly
    }
    private val book: Book by lazy {
        requireArguments().getParcelable<Book>("book")!!
    }
    private var chapterLoadJob: Job? = null

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
        cbCachedOnly.text = getString(R.string.cache_manage_cached_only)
        cbCachedOnly.isChecked = false
        cbCachedOnly.isVisible = true
        cbCachedOnly.setOnCheckedChangeListener { _, _ ->
            loadChapters(searchView.query?.toString())
        }
        tvFooterLeft.text = getString(R.string.cache_manage_swipe_delete_hint)
        tvFooterLeft.visible()
        tvCancel.setText(R.string.cache_manage_delete_cached)
        tvCancel.visible()
        tvCancel.setOnClickListener { deleteCachedChapters() }
        if (book.isAudio) {
            tvOk.setText(R.string.cache_manage_cache_uncached)
            tvOk.visible()
            tvOk.setOnClickListener { cacheUncachedAudioChapters() }
        } else {
            tvOk.gone()
        }
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
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val item = adapter.getItem(position)
                if (item == null || !item.cached) {
                    adapter.notifyItemChanged(position)
                    return
                }
                confirmDeleteChapter(position, item)
            }
        }).attachToRecyclerView(recyclerView)
    }

    private fun loadChapters(key: String? = null) {
        chapterLoadJob?.cancel()
        binding.rotateLoading.visible()
        val cachedOnly = cachedOnlyCheckBox.isChecked
        lateinit var job: Job
        job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val items = viewModel.getChapterItems(book, key, cachedOnly)
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

    private fun confirmDeleteChapter(position: Int, item: CacheChapterItem) {
        alert(
            getString(R.string.delete),
            getString(R.string.cache_manage_delete_chapter_confirm, item.chapter.title)
        ) {
            yesButton {
                lifecycleScope.launch {
                    kotlin.runCatching {
                        viewModel.deleteChapterCache(book, item.chapter)
                    }.onSuccess {
                        callback?.onCacheChanged()
                        loadChapters(searchView.query?.toString())
                        toastOnUi(R.string.delete_success)
                    }.onFailure {
                        adapter.notifyItemChanged(position)
                        toastOnUi(
                            getString(
                                R.string.cache_manage_delete_chapter_failed,
                                it.localizedMessage
                            )
                        )
                    }
                }
            }
            noButton {
                adapter.notifyItemChanged(position)
            }
            onCancelled {
                adapter.notifyItemChanged(position)
            }
        }
    }

    private fun cacheUncachedAudioChapters() {
        val items = adapter.getItems().filterNot { it.cached }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        lifecycleScope.launch {
            kotlin.runCatching {
                viewModel.cacheAudioChapters(book, items.map { it.chapter })
            }.onSuccess { count ->
                callback?.onCacheChanged()
                if (count > 0) {
                    toastOnUi(getString(R.string.cache_manage_audio_cache_started, count))
                    dismissAllowingStateLoss()
                } else {
                    loadChapters(searchView.query?.toString())
                    toastOnUi(getString(R.string.cache_manage_audio_cache_done, count))
                }
            }.onFailure {
                toastOnUi(getString(R.string.cache_manage_audio_cache_failed, it.localizedMessage))
            }
        }
    }

    private fun deleteCachedChapters() {
        val items = adapter.getItems().filter { it.cached }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        alert(
            getString(R.string.delete),
            getString(R.string.cache_manage_delete_cached_confirm, items.size)
        ) {
            yesButton {
                binding.rotateLoading.visible()
                lifecycleScope.launch {
                    kotlin.runCatching {
                        items.forEach { viewModel.deleteChapterCache(book, it.chapter) }
                    }.onSuccess {
                        callback?.onCacheChanged()
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
