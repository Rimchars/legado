package io.legado.app.ui.book.search

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.databinding.ActivityBookSearchBinding
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class SearchActivity : VMBaseActivity<ActivityBookSearchBinding, SearchViewModel>(),
    BookAdapter.CallBack,
    HistoryKeyAdapter.CallBack,
    SearchScopeDialog.Callback,
    SearchAdapter.CallBack {

    override val binding by viewBinding(ActivityBookSearchBinding::inflate)
    override val viewModel by viewModels<SearchViewModel>()

    private val adapter by lazy { SearchAdapter(this, this) }
    private val bookAdapter by lazy {
        BookAdapter(this, this).apply {
            setHasStableIds(true)
        }
    }
    private val historyKeyAdapter by lazy {
        HistoryKeyAdapter(this, this).apply {
            setHasStableIds(true)
        }
    }
    private val searchView: SearchView by lazy { binding.searchView }
    private var groups: List<String>? = null
    private var historyFlowJob: Job? = null
    private var booksFlowJob: Job? = null
    private var modernMenuPopup: PopupWindow? = null
    private var isManualStopSearch = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
        initRecyclerView()
        initSearchView()
        initOtherView()
        initData()
        receiptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiptIntent(intent)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_precision_search -> {
                putPrefBoolean(
                    PreferKey.precisionSearch,
                    !getPrefBoolean(PreferKey.precisionSearch)
                )
                searchView.query?.toString()?.trim()?.let {
                    searchView.setQuery(it, true)
                }
            }

            R.id.menu_search_scope -> alertSearchScope()
            R.id.menu_source_manage -> startActivity<BookSourceActivity>()
            R.id.menu_log -> showDialogFragment(AppLogDialog())
            R.id.menu_1 -> viewModel.searchScope.update("")
            else -> {
                if (item.groupId == R.id.menu_group_1) {
                    viewModel.searchScope.remove(item.title.toString())
                } else if (item.groupId == R.id.menu_group_2) {
                    viewModel.searchScope.update(item.title.toString())
                }
            }
        }
        return true
    }

    private fun initTopBar() {
        binding.root.applyStatusBarPadding()
        binding.btnMenu.setColorFilter(secondaryTextColor)
        val isNight = AppConfig.isNightTheme
        val searchSurfaceColor = if (isNight) {
            ColorUtils.adjustAlpha(Color.rgb(52, 52, 56), 0.42f)
        } else {
            ColorUtils.adjustAlpha(Color.rgb(120, 120, 128), 0.18f)
        }
        val cardColor = if (isNight) {
            ColorUtils.adjustAlpha(Color.rgb(44, 44, 46), 0.45f)
        } else {
            ContextCompat.getColor(this, R.color.background_menu)
        }
        val chipColor = if (isNight) {
            ColorUtils.adjustAlpha(Color.rgb(58, 58, 62), 0.32f)
        } else {
            ContextCompat.getColor(this, R.color.background_card)
        }
        val chipPressedColor = if (isNight) {
            ColorUtils.adjustAlpha(Color.rgb(82, 82, 86), 0.45f)
        } else {
            ContextCompat.getColor(this, R.color.background_menu)
        }
        val strokeColor = ColorUtils.adjustAlpha(primaryTextColor, if (isNight) 0.10f else 0.08f)
        binding.searchView.background = GradientDrawable().apply {
            cornerRadius = UiCorner.searchRadius(18f)
            setColor(searchSurfaceColor)
            setStroke(1.dpToPx(), strokeColor)
        }
        binding.llBookshelfHintCard.background = UiCorner.roundedStroke(
            cardColor,
            UiCorner.searchRadius(20f),
            1.dpToPx(),
            strokeColor
        )
        binding.llHistoryCard.background = UiCorner.roundedStroke(
            cardColor,
            UiCorner.searchRadius(20f),
            1.dpToPx(),
            strokeColor
        )
        binding.tvClearHistory.background = UiCorner.actionSelector(
            chipColor,
            chipPressedColor,
            UiCorner.searchRadius(14f)
        )
        binding.btnMenu.setOnClickListener {
            showSearchMenu(it)
        }
    }

    private fun showSearchMenu(anchor: View) {
        modernMenuPopup = ModernActionPopup.showFromMenu(
            anchor = anchor,
            menuRes = R.menu.book_search,
            previousPopup = modernMenuPopup,
            prepare = {
                prepareSearchMenu(this)
            }
        ) {
            onCompatOptionsItemSelected(it)
        }
    }

    private fun prepareSearchMenu(menu: Menu) {
        menu.findItem(R.id.menu_precision_search)?.isChecked = getPrefBoolean(PreferKey.precisionSearch)
        menu.removeGroup(R.id.menu_group_1)
        menu.removeGroup(R.id.menu_group_2)
        var hasChecked = false
        val searchScopeNames = viewModel.searchScope.displayNames
        if (viewModel.searchScope.isSource()) {
            menu.add(R.id.menu_group_1, Menu.NONE, Menu.NONE, searchScopeNames.first()).apply {
                isChecked = true
                hasChecked = true
            }
        }
        val allSourceMenu =
            menu.add(R.id.menu_group_2, R.id.menu_1, Menu.NONE, getString(R.string.all_source))
                .apply {
                    if (searchScopeNames.isEmpty()) {
                        isChecked = true
                        hasChecked = true
                    }
                }
        groups?.forEach {
            if (searchScopeNames.contains(it)) {
                menu.add(R.id.menu_group_1, Menu.NONE, Menu.NONE, it).apply {
                    isChecked = true
                    hasChecked = true
                }
            } else {
                menu.add(R.id.menu_group_2, Menu.NONE, Menu.NONE, it)
            }
        }
        if (!hasChecked) {
            viewModel.searchScope.update("")
            allSourceMenu.isChecked = true
        }
        menu.setGroupCheckable(R.id.menu_group_1, true, false)
        menu.setGroupCheckable(R.id.menu_group_2, true, true)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)
            ?.apply {
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(primaryTextColor)
                setHintTextColor(secondaryTextColor)
            }
        searchView.findViewById<android.view.View>(androidx.appcompat.R.id.search_plate)
            ?.setBackgroundColor(Color.TRANSPARENT)
        searchView.findViewById<android.view.View>(androidx.appcompat.R.id.search_edit_frame)
            ?.setBackgroundColor(Color.TRANSPARENT)
        searchView.findViewById<android.view.View>(androidx.appcompat.R.id.submit_area)
            ?.setBackgroundColor(Color.TRANSPARENT)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search_book_key)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                query.trim().let { searchKey ->
                    isManualStopSearch = false
                    viewModel.saveSearchKey(searchKey)
                    viewModel.searchKey = ""
                    viewModel.search(searchKey)
                }
                visibleInputHelp(false)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                viewModel.stop()
                binding.fbStartStop.invisible()
                searchView.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.apply {
                    setTextColor(primaryTextColor)
                    setHintTextColor(secondaryTextColor)
                }
                upHistory(newText.trim())
                return false
            }
        })
        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (binding.refreshProgressBar.isAutoLoading || (!hasFocus && adapter.isNotEmpty() && searchView.query.isNotBlank())) {
                visibleInputHelp(false)
            } else {
                visibleInputHelp(true)
            }
        }
        visibleInputHelp(true)
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.rvBookshelfSearch.setEdgeEffectColor(primaryColor)
        binding.rvHistoryKey.setEdgeEffectColor(primaryColor)
        binding.rvBookshelfSearch.layoutManager = FlexboxLayoutManager(this)
        binding.rvBookshelfSearch.adapter = bookAdapter
        binding.rvHistoryKey.layoutManager = FlexboxLayoutManager(this)
        binding.rvHistoryKey.adapter = historyKeyAdapter
        binding.llHistoryCard.applyNavigationBarMargin()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.applyNavigationBarPadding()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.recyclerView.scrollToPosition(0)
                }
            }

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                super.onItemRangeMoved(fromPosition, toPosition, itemCount)
                if (toPosition == 0) {
                    binding.recyclerView.scrollToPosition(0)
                }
            }
        })
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy < 0) {
                    return
                }
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastPosition = layoutManager.findLastVisibleItemPosition()
                if (lastPosition == RecyclerView.NO_POSITION) {
                    return
                }
                if (adapter.itemCount - lastPosition <= 3 || !recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                }
            }
        })
    }

    private fun initOtherView() {
        binding.fbStartStop.backgroundTintList =
            Selector.colorBuild()
                .setDefaultColor(accentColor)
                .setPressedColor(ColorUtils.darkenColor(accentColor))
                .create()
        binding.fbStartStop.setOnClickListener {
            if (viewModel.isSearchLiveData.value == true) {
                isManualStopSearch = true
                viewModel.stop()
                binding.refreshProgressBar.isAutoLoading = false
            } else {
                viewModel.search("")
            }
        }
        binding.fbStartStop.applyNavigationBarMargin(true)
        binding.tvClearHistory.setOnClickListener { alertClearHistory() }
    }

    private fun initData() {
        viewModel.searchScope.stateLiveData.observe(this) {
            if (!binding.llInputHelp.isVisible) {
                searchView.query?.toString()?.trim()?.let {
                    searchView.setQuery(it, true)
                }
            }
        }
        viewModel.isSearchLiveData.observe(this) {
            if (it) {
                startSearch()
            } else {
                searchFinally()
            }
        }
        viewModel.searchBookLiveData.observe(this) {
            adapter.setItems(it)
        }
        lifecycleScope.launch {
            appDb.bookSourceDao.flowEnabledGroups().collect {
                groups = it
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.resume()
                try {
                    awaitCancellation()
                } finally {
                    viewModel.pause()
                }
            }
        }
    }

    /**
     * 处理传入数据
     */
    private fun receiptIntent(intent: Intent? = null) {
        val searchScope = intent?.getStringExtra("searchScope")
        searchScope?.let {
            viewModel.searchScope.update(searchScope, postValue = false, save = false)
        }
        val key = intent?.getStringExtra("key")
        if (key.isNullOrBlank()) {
            searchView.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)
                .requestFocus()
        } else {
            searchView.setQuery(key, true)
        }
    }

    /**
     * 滚动到底部事件
     */
    private fun scrollToBottom() {
        if (isManualStopSearch) {
            return
        }
        if (viewModel.isSearchLiveData.value == false
            && viewModel.searchKey.isNotEmpty()
            && viewModel.hasMore
        ) {
            viewModel.search("")
        }
    }

    /**
     * 打开关闭输入帮助
     */
    private fun visibleInputHelp(visible: Boolean) {
        if (visible) {
            upHistory(searchView.query.toString())
            binding.llInputHelp.visibility = VISIBLE
        } else {
            binding.llInputHelp.visibility = GONE
        }
    }

    /**
     * 更新搜索历史
     */
    private fun upHistory(key: String? = null) {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            if (key.isNullOrBlank()) {
                binding.llBookshelfHintCard.gone()
                binding.tvBookShow.gone()
                binding.rvBookshelfSearch.gone()
            } else {
                appDb.bookDao.flowSearch(key).conflate().collect {
                    if (it.isEmpty()) {
                        binding.llBookshelfHintCard.gone()
                        binding.tvBookShow.gone()
                        binding.rvBookshelfSearch.gone()
                    } else {
                        binding.llBookshelfHintCard.visible()
                        binding.tvBookShow.visible()
                        binding.rvBookshelfSearch.visible()
                    }
                    bookAdapter.setItems(it)
                }
            }
        }
        historyFlowJob?.cancel()
        historyFlowJob = lifecycleScope.launch {
            when {
                key.isNullOrBlank() -> appDb.searchKeywordDao.flowByTime()
                else -> appDb.searchKeywordDao.flowSearch(key)
            }.catch {
                AppLog.put("搜索界面获取搜索历史数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                historyKeyAdapter.setItems(it)
                if (it.isEmpty()) {
                    binding.tvClearHistory.invisible()
                    binding.tvHistoryEmpty.visible()
                    binding.rvHistoryKey.gone()
                } else {
                    binding.tvClearHistory.visible()
                    binding.tvHistoryEmpty.gone()
                    binding.rvHistoryKey.visible()
                }
            }
        }
    }

    /**
     * 开始搜索
     */
    private fun startSearch() {
        binding.refreshProgressBar.visible()
        binding.refreshProgressBar.isAutoLoading = true
        binding.fbStartStop.setImageResource(R.drawable.ic_stop_black_24dp)
        binding.fbStartStop.visible()
    }

    /**
     * 搜索结束
     */
    private fun searchFinally() {
        binding.refreshProgressBar.isAutoLoading = false
        binding.refreshProgressBar.gone()
        if (!isManualStopSearch && viewModel.hasMore) {
            binding.fbStartStop.setImageResource(R.drawable.ic_play_24dp)
        } else {
            binding.fbStartStop.invisible()
        }
    }

    override fun observeLiveBus() {
        viewModel.upAdapterLiveData.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundleOf(it to null))
        }
        viewModel.searchFinishLiveData.observe(this) { isEmpty ->
            if (!isEmpty || viewModel.searchScope.isAll()) return@observe
            alert("搜索结果为空") {
                val precisionSearch = appCtx.getPrefBoolean(PreferKey.precisionSearch)
                val displayScope = viewModel.searchScope.display
                if (precisionSearch) {
                    setMessage("${displayScope}分组搜索结果为空，是否关闭精准搜索？")
                    yesButton {
                        appCtx.putPrefBoolean(PreferKey.precisionSearch, false)
                        viewModel.searchKey = ""
                        viewModel.search(searchView.query.toString())
                    }
                } else {
                    setMessage("${displayScope}分组搜索结果为空，是否切换到全部分组？")
                    yesButton {
                        viewModel.searchScope.update("")
                    }
                }
                noButton()
            }
        }
    }

    /**
     * 显示书籍详情
     */
    override fun showBookInfo(book: SearchBook) {
        lifecycleScope.launch {
            val isVideo = withContext(IO) {
                SearchBookOpenHelper.isVideoResult(
                    book,
                    viewModel.searchScope.getSingleBookSourcePart()?.bookSourceType
                )
            }
            if (isVideo) {
                SearchBookOpenHelper.open(this@SearchActivity, book, true)
            } else {
                SearchBookOpenHelper.open(this@SearchActivity, book, false)
            }
        }
    }

    /**
     * 是否已经加入书架
     */
    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    /**
     * 显示书籍详情
     */
    override fun showBookInfo(book: Book) {
        if (book.isVideo) {
            startActivityForBook(book)
            return
        }
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    /**
     * 点击历史关键字
     */
    override fun searchHistory(key: String) {
        lifecycleScope.launch {
            when {
                searchView.query.toString() == key -> {
                    searchView.setQuery(key, true)
                }

                withContext(IO) { appDb.bookDao.findByName(key).isEmpty() } -> {
                    searchView.setQuery(key, true)
                }

                else -> {
                    searchView.setQuery(key, false)
                }
            }
        }
    }

    /**
     * 删除搜索记录
     */
    override fun deleteHistory(searchKeyword: SearchKeyword) {
        viewModel.deleteHistory(searchKeyword)
    }


    override fun onSearchScopeOk(searchScope: SearchScope) {
        viewModel.searchScope.update(searchScope.toString())
    }

    private fun alertSearchScope() {
        showDialogFragment<SearchScopeDialog>()
    }

    private fun alertClearHistory() {
        alert(R.string.draw) {
            setMessage(R.string.sure_clear_search_history)
            yesButton {
                viewModel.clearHistory()
            }
            noButton()
        }
    }

    override fun finish() {
        if (searchView.hasFocus()) {
            searchView.clearFocus()
            return
        }
        super.finish()
    }

    companion object {

        fun start(context: Context, key: String?, searchScope: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", searchScope)
            }
        }

        fun start(context: Context, source: BookSource, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", SearchScope(source).toString())
            }
        }

        fun start(context: Context, source: BookSourcePart, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", SearchScope(source).toString())
            }
        }

    }
}
