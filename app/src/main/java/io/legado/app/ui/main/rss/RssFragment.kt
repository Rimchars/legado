package io.legado.app.ui.main.rss

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.FragmentRssBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.sortUrls
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.article.RssArticlesFragment
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.article.RssSortViewModel
import io.legado.app.ui.rss.favorites.RssFavoritesActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.gone
import io.legado.app.utils.openUrl
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.ui.widget.SourceSelectDialog
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 订阅界面
 */
class RssFragment() : VMBaseFragment<RssViewModel>(R.layout.fragment_rss), MainFragmentInterface,
    RssAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentRssBinding::bind)
    override val viewModel by viewModels<RssViewModel>()
    private val sortHostViewModel by viewModels<RssSortViewModel>()
    private val adapter by lazy {
        RssAdapter(requireContext(), this, this, viewLifecycleOwner.lifecycle)
    }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }

    private var groupsFlowJob: Job? = null
    private var rssFlowJob: Job? = null
    private val groups = linkedSetOf<String>()
    private var groupsMenu: SubMenu? = null
    private var rssWebView: WebView? = null
    private var selectedRssSource: RssSource? = null
    private val rssSources = mutableListOf<RssSource>()
    private val currentSorts = mutableListOf<Pair<String, String>>()
    private var selectedTagIndex = 0
    private var currentSearchKey: String? = null
    private var usingModernRss = false
    private var webSourceVersion = 0L
    private var lastRenderedWebSourceUrl: String? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        binding.titleBar.applyStatusBarPadding(withInitialPadding = true)
        applyWebContainerBottomPadding()
        initSearchView()
        initGroupData()
        applyRssMode()
    }

    override fun onResume() {
        super.onResume()
        if (usingModernRss != AppConfig.modernRssPage) {
            applyRssMode()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_rss, menu)
        groupsMenu = menu.findItem(R.id.menu_group)?.subMenu
        menu.findItem(R.id.menu_rss_star)?.isVisible = !usingModernRss
        menu.findItem(R.id.menu_rss_config)?.isVisible = !usingModernRss
        upGroupsMenu()
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_read_record -> showDialogFragment<ReadRecordDialog>()
            R.id.menu_rss_config -> startActivity<RssSourceActivity>()
            R.id.menu_rss_star -> startActivity<RssFavoritesActivity>()
            else -> if (!usingModernRss && item.groupId == R.id.menu_group_text) {
                searchView.setQuery("group:${item.title}", true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        searchView.clearFocus()
    }

    override fun onDestroyView() {
        rssWebView?.let { webView ->
            binding.rssWebContainer.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        rssWebView = null
        super.onDestroyView()
    }

    private fun applyRssMode() {
        usingModernRss = AppConfig.modernRssPage
        binding.titleBar.isGone = usingModernRss
        binding.llRssSourceRow.isVisible = usingModernRss
        binding.rvRssTags.isVisible = false
        binding.rssFragmentContainer.isGone = true
        binding.rssWebContainer.isGone = true
        binding.recyclerView.isGone = usingModernRss
        binding.pbRssLoading.gone()
        binding.tvEmptyMsg.gone()
        if (usingModernRss) {
            initModernRssView()
            observeRssSources()
        } else {
            initClassicRecycler()
            observeClassicRssSources()
        }
        activity?.invalidateOptionsMenu()
    }

    private fun upGroupsMenu() = groupsMenu?.transaction { subMenu ->
        subMenu.removeGroup(R.id.menu_group_text)
        groups.forEach {
            subMenu.add(R.id.menu_group_text, Menu.NONE, Menu.NONE, it)
        }
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.rss)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                if (usingModernRss) {
                    observeRssSources(newText)
                } else {
                    observeClassicRssSources(newText)
                }
                return false
            }
        })
    }

    private fun initClassicRecycler() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.clipToPadding = false
        binding.recyclerView.applyMainBottomBarPadding(withInitialPadding = true)
        if (binding.recyclerView.adapter !== adapter) {
            binding.recyclerView.adapter = adapter
        }
        binding.swipeRefreshLayout.setColorSchemeColors(accentColor)
        binding.swipeRefreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        binding.swipeRefreshLayout.setOnRefreshListener {
            observeClassicRssSources(searchView.query?.toString())
        }
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            currentRssScrollTarget()?.canScrollVertically(-1) == true
        }
    }

    private fun initModernRssView() {
        binding.tvRssSourceSelect.applyUiTitleTypeface(requireContext())
        binding.llRssSourceRow.applyStatusBarPadding(withInitialPadding = true)
        binding.swipeRefreshLayout.setColorSchemeColors(accentColor)
        binding.swipeRefreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            currentRssScrollTarget()?.canScrollVertically(-1) == true
        }
        val updateSourceNameWidth = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateRssSourceNameWidth()
        }
        binding.llRssSourceRow.addOnLayoutChangeListener(updateSourceNameWidth)
        binding.llRssSourceRow.post(::updateRssSourceNameWidth)
        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshCurrentRssContent()
        }
        binding.llRssSourceSelect.setOnClickListener {
            showSourceSelector()
        }
        binding.btnRssSourceLogin.setOnClickListener {
            selectedRssSource?.let(::openRssLogin)
        }
        binding.btnRssSourceStar.setOnClickListener {
            startActivity<RssFavoritesActivity>()
        }
        binding.btnRssSourceRefresh.setOnClickListener {
            refreshCurrentRssContent(forceWebRefresh = true)
        }
        binding.btnRssSourceSearch.setOnClickListener {
            openRssSearch()
        }
        binding.rvRssTags.setOnTagClickListener { index ->
            if (index == selectedTagIndex) return@setOnTagClickListener
            selectedTagIndex = index
            binding.rvRssTags.setSelectedIndex(index)
            renderCurrentSort()
        }
    }

    private fun updateRssSourceNameWidth() {
        val rowWidth = binding.llRssSourceRow.width
        if (rowWidth <= 0) return
        val actionsWidth = listOf(
            binding.btnRssSourceSearch,
            binding.btnRssSourceStar,
            binding.btnRssSourceRefresh,
            binding.btnRssSourceLogin
        ).filter { it.isVisible }.sumOf { it.measuredWidth.takeIf { width -> width > 0 } ?: it.layoutParams.width }
        val spacing = 36.dpToPx()
        val maxWidth = (rowWidth - actionsWidth - spacing).coerceIn(96.dpToPx(), 190.dpToPx())
        binding.tvRssSourceSelect.maxWidth = maxWidth
    }

    private fun currentRssScrollTarget(): View? {
        return when {
            usingModernRss && binding.rssWebContainer.isVisible -> rssWebView
            usingModernRss && binding.rssFragmentContainer.isVisible ->
                childFragmentManager.findFragmentById(R.id.rss_fragment_container)
                    ?.view
                    ?.findViewById<View>(R.id.recycler_view)
            else -> binding.recyclerView
        }
    }

    private fun initGroupData() {
        groupsFlowJob?.cancel()
        groupsFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.rssSourceDao.flowEnabledGroups().catch {
                AppLog.put("订阅界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).conflate().collect {
                groups.clear()
                groups.addAll(it)
                upGroupsMenu()
            }
        }
    }

    private fun observeClassicRssSources(searchKey: String? = currentSearchKey) {
        currentSearchKey = searchKey
        rssFlowJob?.cancel()
        rssFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.rssSourceDao.flowEnabled()
                searchKey.startsWith("group:") -> appDb.rssSourceDao.flowEnabledByGroup(searchKey.substringAfter("group:"))
                else -> appDb.rssSourceDao.flowEnabled(searchKey)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("订阅界面更新数据出错", it)
            }.flowOn(IO).collect {
                binding.swipeRefreshLayout.isRefreshing = false
                adapter.setItems(it)
                binding.tvEmptyMsg.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun observeRssSources(searchKey: String? = currentSearchKey) {
        currentSearchKey = searchKey
        rssFlowJob?.cancel()
        rssFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.rssSourceDao.flowEnabled()
                searchKey.startsWith("group:") -> appDb.rssSourceDao.flowEnabledByGroup(searchKey.substringAfter("group:"))
                else -> appDb.rssSourceDao.flowEnabled(searchKey)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("订阅界面更新数据出错", it)
            }.flowOn(IO).collect { sources ->
                binding.swipeRefreshLayout.isRefreshing = false
                rssSources.clear()
                rssSources.addAll(sources)
                val keep = selectedRssSource?.sourceUrl?.let { key ->
                    sources.firstOrNull { it.sourceUrl == key && it.canRenderInModernPage() }
                }
                val remembered = if (keep == null && searchKey.isNullOrEmpty()) {
                    AppConfig.modernRssSourceUrl?.let { key ->
                        sources.firstOrNull { it.sourceUrl == key && it.canRenderInModernPage() }
                    }
                } else {
                    null
                }
                when {
                    keep != null -> selectSource(keep, reload = false)
                    remembered != null -> selectSource(remembered, reload = true)
                    sources.any { it.canRenderInModernPage() } ->
                        selectSource(sources.first { it.canRenderInModernPage() }, reload = true)
                    else -> renderEmptyState()
                }
            }
        }
    }

    private fun selectSource(source: RssSource, reload: Boolean) {
        val changed = selectedRssSource?.sourceUrl != source.sourceUrl
        selectedRssSource = source
        AppConfig.modernRssSourceUrl = source.sourceUrl
        binding.tvRssSourceSelect.text = source.sourceName
        binding.btnRssSourceLogin.isVisible = !source.loginUrl.isNullOrBlank()
        binding.btnRssSourceSearch.isVisible = !source.searchUrl.isNullOrBlank()
        binding.btnRssSourceRefresh.isVisible = source.ruleArticles.isNullOrBlank()
        binding.llRssSourceRow.post(::updateRssSourceNameWidth)
        if (changed) {
            selectedTagIndex = 0
        }
        if (changed || reload) {
            viewLifecycleOwner.lifecycleScope.launch {
                presentSource(source)
            }
        }
    }

    private suspend fun presentSource(source: RssSource) {
        if (binding.swipeRefreshLayout.isRefreshing) {
            binding.pbRssLoading.gone()
        } else {
            binding.pbRssLoading.visible()
        }
        if (!source.canRenderInModernPage()) {
            binding.pbRssLoading.gone()
            renderEmptyState()
            return
        }
        binding.tvEmptyMsg.gone()
        sortHostViewModel.url = source.sourceUrl
        sortHostViewModel.rssSource = source
        sortHostViewModel.sourceName = source.sourceName
        sortHostViewModel.searchKey = null

        if (source.ruleArticles.isNullOrBlank()) {
            currentSorts.clear()
            binding.rvRssTags.gone()
            renderWebSource(source)
            return
        }

        val sorts = kotlin.runCatching { source.sortUrls() }
            .getOrElse {
                AppLog.put("订阅界面加载分类失败\n${it.localizedMessage}", it)
                listOf(Pair("", source.sourceUrl))
            }.ifEmpty {
                listOf(Pair("", source.sourceUrl))
            }
        currentSorts.clear()
        currentSorts.addAll(sorts)
        val visibleTags = currentSorts.filter { it.first.isNotBlank() }
        if (visibleTags.size > 1 || (currentSorts.size == 1 && currentSorts.first().first.isNotBlank())) {
            binding.rvRssTags.visible()
            binding.rvRssTags.submitItems(
                currentSorts.map { io.legado.app.ui.widget.RoundedTagBarView.Item(it.first) },
                selectedTagIndex.coerceIn(0, currentSorts.lastIndex)
            )
        } else {
            binding.rvRssTags.gone()
        }
        renderCurrentSort()
    }

    private fun renderCurrentSort() {
        val source = selectedRssSource ?: return
        if (currentSorts.isEmpty()) {
            binding.pbRssLoading.gone()
            renderEmptyState()
            return
        }
        binding.swipeRefreshLayout.isEnabled = true
        selectedTagIndex = selectedTagIndex.coerceIn(0, currentSorts.lastIndex)
        binding.rvRssTags.setSelectedIndex(selectedTagIndex, smooth = false)
        val sort = currentSorts[selectedTagIndex]
        binding.recyclerView.gone()
        binding.rssWebContainer.gone()
        binding.rssFragmentContainer.visible()
        binding.pbRssLoading.gone()
        childFragmentManager.commit {
            replace(
                R.id.rss_fragment_container,
                RssArticlesFragment(sort.first, sort.second, null),
                "rss_articles_${source.sourceUrl}_${selectedTagIndex}"
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun renderWebSource(source: RssSource) {
        if (!source.canRenderInModernPage()) {
            renderEmptyState()
            return
        }
        webSourceVersion += 1
        val currentVersion = webSourceVersion
        binding.swipeRefreshLayout.isRefreshing = false
        binding.swipeRefreshLayout.isEnabled = false
        binding.recyclerView.gone()
        binding.rssFragmentContainer.gone()
        binding.rssWebContainer.visible()
        val webView = rssWebView ?: WebView(requireContext()).also { created ->
            created.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            created.overScrollMode = View.OVER_SCROLL_NEVER
            created.settings.javaScriptEnabled = true
            created.settings.domStorageEnabled = true
            created.settings.cacheMode = WebSettings.LOAD_DEFAULT
            created.settings.loadsImagesAutomatically = true
            created.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            created.webViewClient = WebViewClient()
            created.webChromeClient = WebChromeClient()
            binding.rssWebContainer.addView(created)
            rssWebView = created
        }
        webView.settings.javaScriptEnabled = source.enableJs
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.stopLoading()
        if (lastRenderedWebSourceUrl != source.sourceUrl) {
            webView.clearHistory()
            webView.loadUrl("about:blank")
        }
        viewModel.launchRssWithHtml(source, {
            if (currentVersion != webSourceVersion || selectedRssSource?.sourceUrl != source.sourceUrl) {
                return@launchRssWithHtml
            }
            binding.pbRssLoading.gone()
            binding.swipeRefreshLayout.isRefreshing = false
            lastRenderedWebSourceUrl = source.sourceUrl
            webView.loadUrl(source.sourceUrl)
        }) { html ->
            if (currentVersion != webSourceVersion || selectedRssSource?.sourceUrl != source.sourceUrl) {
                return@launchRssWithHtml
            }
            binding.pbRssLoading.gone()
            binding.swipeRefreshLayout.isRefreshing = false
            lastRenderedWebSourceUrl = source.sourceUrl
            webView.loadDataWithBaseURL(
                source.sourceUrl,
                html,
                "text/html",
                "utf-8",
                source.sourceUrl
            )
        }
    }

    private fun refreshCurrentRssContent(forceWebRefresh: Boolean = false) {
        if (!usingModernRss) {
            observeClassicRssSources(searchView.query?.toString())
            return
        }
        selectedRssSource?.let { source ->
            if (source.ruleArticles.isNullOrBlank()) {
                if (forceWebRefresh) {
                    lastRenderedWebSourceUrl = null
                }
                renderWebSource(source)
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    presentSource(source)
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        } ?: run {
            binding.swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun renderEmptyState() {
        selectedRssSource = null
        currentSorts.clear()
        binding.tvRssSourceSelect.text = getString(R.string.rss)
        binding.btnRssSourceLogin.gone()
        binding.btnRssSourceSearch.gone()
        binding.btnRssSourceRefresh.gone()
        binding.swipeRefreshLayout.isEnabled = true
        binding.rvRssTags.gone()
        binding.recyclerView.gone()
        binding.rssFragmentContainer.gone()
        binding.rssWebContainer.gone()
        binding.pbRssLoading.gone()
        binding.tvEmptyMsg.visible()
    }

    private fun applyWebContainerBottomPadding() {
        val initialPadding = binding.rssWebContainer.paddingBottom
        val webBottomSpace =
            resources.getDimensionPixelSize(R.dimen.main_bottom_controls_bottom_padding) +
                resources.getDimensionPixelSize(R.dimen.main_bottom_bar_height) +
                5.dpToPx()
        binding.rssWebContainer.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                initialPadding + windowInsets.navigationBarHeight + webBottomSpace
            )
            windowInsets
        }
    }

    private fun showSourceSelector() {
        if (rssSources.isEmpty()) return
        SourceSelectDialog.show(
            context = requireContext(),
            title = getString(R.string.rss),
            items = rssSources,
            selectedKey = selectedRssSource?.sourceUrl,
            displayName = { it.getDisplayNameGroup() },
            searchTexts = {
                listOfNotNull(it.sourceName, it.sourceUrl, it.sourceGroup)
            },
            itemKey = { it.sourceUrl }
        ) {
            if (it.canRenderInModernPage()) {
                selectSource(it, reload = true)
            } else {
                openRssLegacy(it)
            }
        }
    }

    private fun openRssSearch() {
        val source = selectedRssSource ?: return
        if (source.searchUrl.isNullOrBlank()) return
        RssSortActivity.start(requireContext(), null, source.sourceUrl, focusSearch = true)
    }

    private fun openRssLogin(rssSource: RssSource) {
        startActivity<SourceLoginActivity> {
            putExtra("type", "rssSource")
            putExtra("key", rssSource.sourceUrl)
        }
    }

    override fun openRss(rssSource: RssSource) {
        openRssLegacy(rssSource)
    }

    private fun openRssLegacy(rssSource: RssSource) {
        if (rssSource.singleUrl) {
            viewModel.getSingleUrl(rssSource) { url ->
                if (url.startsWith("http", true)) {
                    ReadRssActivity.start(
                        requireContext(),
                        true,
                        rssSource.sourceUrl,
                        rssSource.sourceName,
                        url
                    )
                } else {
                    context?.openUrl(url)
                }
            }
        } else {
            viewModel.launchRssWithHtml(rssSource, {
                startActivity<io.legado.app.ui.rss.article.RssSortActivity> {
                    putExtra("sourceUrl", rssSource.sourceUrl)
                }
            }) { html ->
                ReadRssActivity.start(
                    requireContext(),
                    true,
                    rssSource.sourceUrl,
                    rssSource.sourceName,
                    startHtml = html
                )
            }
        }
    }

    private fun RssSource.canRenderInModernPage(): Boolean {
        return !singleUrl
    }

    override fun toTop(rssSource: RssSource) {
        viewModel.topSource(rssSource)
    }

    override fun login(rssSource: RssSource) {
        openRssLogin(rssSource)
    }

    override fun edit(rssSource: RssSource) {
        startActivity<RssSourceEditActivity> {
            putExtra("sourceUrl", rssSource.sourceUrl)
        }
    }

    override fun del(rssSource: RssSource) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rssSource.sourceName)
            noButton()
            yesButton {
                viewModel.del(rssSource)
            }
        }
    }

    override fun disable(rssSource: RssSource) {
        viewModel.disable(rssSource)
    }

    fun gotoTop() {
        val target = when {
            binding.rssWebContainer.isVisible -> rssWebView
            else -> childFragmentManager.findFragmentById(R.id.rss_fragment_container)?.view?.findViewById<View>(R.id.recycler_view)
        }
        when (target) {
            is WebView -> target.scrollTo(0, 0)
            is androidx.recyclerview.widget.RecyclerView -> target.scrollToPosition(0)
        }
    }
}
