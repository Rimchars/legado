package io.legado.app.ui.main.explore

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.PopupWindow
import android.widget.LinearLayout
import android.widget.TextView
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.FragmentExploreBinding
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.help.WebCacheManager
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.explore.ExploreShowAdapter
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.ui.widget.SourceSelectDialog
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.gone
import io.legado.app.utils.InfoMap
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.setDarkeningAllowed
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现界面
 */
class ExploreFragment() : VMBaseFragment<ExploreViewModel>(R.layout.fragment_explore),
    MainFragmentInterface,
    ExploreAdapter.CallBack,
    ExploreShowAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<ExploreViewModel>()
    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val adapter by lazy { ExploreAdapter(requireContext(), this) }
    private val discoverBookAdapter by lazy { ExploreShowAdapter(requireContext(), this) }
    private val linearLayoutManager by lazy { LinearLayoutManager(context) }
    private val discoverBookLayoutManager by lazy { LinearLayoutManager(requireContext()) }
    private val searchView: SearchView? by lazy {
        binding.titleBar.findViewById<SearchView?>(R.id.search_view)
    }
    private val diffItemCallBack = ExploreDiffItemCallBack()
    private val groups = linkedSetOf<String>()
    private var exploreFlowJob: Job? = null
    private var groupsMenu: SubMenu? = null
    private var oldModeInitialized = false
    private var modernModeInitialized = false
    private var usingModernDiscovery = false
    private var sourceMenuPopup: PopupWindow? = null
    private var tagFilterPopup: PopupWindow? = null
    private var discoverSourceFlowJob: Job? = null
    private var discoverBookshelfFlowJob: Job? = null
    private var discoverWarmupJob: Job? = null
    private var discoverLoadJob: Job? = null
    private var discoverActionJob: Job? = null
    private val discoverSources = mutableListOf<BookSourcePart>()
    private val discoverAllTagItems = mutableListOf<DiscoverTagItem>()
    private val discoverTagItems = mutableListOf<DiscoverTagItem>()
    private val discoverSelectItems = mutableListOf<DiscoverTagItem>()
    private val discoverMajorGroups = mutableListOf<String>()
    private val discoverBookshelf = linkedSetOf<String>()
    private val discoverBooks = linkedSetOf<SearchBook>()
    private val blockedButtonActions = hashMapOf<String, MutableSet<String>>()
    private var selectedDiscoverSourcePart: BookSourcePart? = null
    private var selectedDiscoverSource: BookSource? = null
    private var discoverCurrentUrl: String? = null
    private var discoverPage = 1
    private var discoverHasMore = true
    private var discoverLoading = false
    private var selectedDiscoverMajorGroup: String? = null
    private var selectedDiscoverTagIndex = -1
    private var selectedDiscoverUrlIndex = -1
    private var discoverRequestVersion = 0L
    private var discoverSourceVersion = 0L
    private var discoveryModeLoaded = false
    private var discoverWebView: WebView? = null
    private var discoverPendingPreloadJs: String? = null
    private var discoverJsBridge: SourceLoginJsExtensions? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        usingModernDiscovery = AppConfig.modernDiscoveryPage
        discoveryModeLoaded = false
        binding.swipeRefreshLayout.setColorSchemeColors(accentColor)
        binding.swipeRefreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            currentDiscoverScrollTarget()?.canScrollVertically(-1) == true
        }
        binding.swipeRefreshLayout.setOnRefreshListener {
            if (usingModernDiscovery) {
                loadDiscoverBooks(reset = true)
            } else {
                upExploreData(searchView?.query?.toString())
            }
        }
        binding.llDiscoverSourceRow.applyStatusBarPadding(withInitialPadding = true)
        binding.rvFind.clipToPadding = false
        binding.rvFind.applyMainBottomBarPadding()
        binding.rvDiscoverBooks.clipToPadding = false
        binding.rvDiscoverBooks.applyMainBottomBarPadding(withInitialPadding = true)
        applyDiscoverWebContainerBottomPadding()
        applyDiscoveryMode(loadData = false)
        scheduleDiscoveryWarmup()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        super.onCompatCreateOptionsMenu(menu)
        if (usingModernDiscovery) {
            groupsMenu = null
            return
        }
        menuInflater.inflate(R.menu.main_explore, menu)
        groupsMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupsMenu()
    }

    private fun applyDiscoveryMode(loadData: Boolean = true) {
        val modern = AppConfig.modernDiscoveryPage
        usingModernDiscovery = modern
        binding.titleBar.isGone = modern
        binding.llModernDiscovery.isVisible = modern
        binding.rvFind.isGone = modern
        binding.tvEmptyMsg.isGone = modern
        searchView?.isGone = modern
        if (!loadData) {
            activity?.invalidateOptionsMenu()
            return
        }
        if (modern) {
            exploreFlowJob?.cancel()
            initModernMode()
        } else {
            stopModernMode()
            initClassicMode()
        }
        activity?.invalidateOptionsMenu()
    }

    private fun currentDiscoverScrollTarget(): View? {
        return when {
            usingModernDiscovery && binding.discoverWebContainer.isVisible -> discoverWebView
            usingModernDiscovery -> binding.rvDiscoverBooks
            else -> binding.rvFind
        }
    }

    private fun scheduleDiscoveryWarmup() {
        discoverWarmupJob?.cancel()
        if (!AppConfig.modernDiscoveryPage) return
        discoverWarmupJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(1800)
            if (!isAdded || discoveryModeLoaded || !AppConfig.modernDiscoveryPage) return@launch
            applyDiscoveryMode(loadData = true)
            discoveryModeLoaded = true
        }
    }

    private fun initClassicMode() {
        if (!oldModeInitialized) {
            oldModeInitialized = true
            initSearchView()
            initRecyclerView()
            initGroupData()
        }
        if (exploreFlowJob?.isActive != true) {
            upExploreData(searchView?.query?.toString())
        }
    }

    private fun initModernMode() {
        if (!modernModeInitialized) {
            modernModeInitialized = true
            initDiscoverRecycler()
            bindDiscoverSourceSelector()
            updateDiscoverLoginButtonState()
        }
        observeDiscoverSources()
        observeDiscoverBookshelf()
    }

    private fun stopModernMode() {
        sourceMenuPopup?.dismiss()
        sourceMenuPopup = null
        tagFilterPopup?.dismiss()
        tagFilterPopup = null
        discoverWarmupJob?.cancel()
        discoverWarmupJob = null
        discoverSourceFlowJob?.cancel()
        discoverSourceFlowJob = null
        discoverBookshelfFlowJob?.cancel()
        discoverBookshelfFlowJob = null
        discoverActionJob?.cancel()
        discoverActionJob = null
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverSourceVersion += 1
        discoverRequestVersion += 1
        discoverLoading = false
        binding.pbDiscoverLoading.gone()
        discoverAllTagItems.clear()
        discoverMajorGroups.clear()
        discoverTagItems.clear()
        releaseDiscoverWebView()
        selectedDiscoverMajorGroup = null
        selectedDiscoverTagIndex = -1
        selectedDiscoverUrlIndex = -1
    }

    private fun initSearchView() {
        val view = searchView ?: return
        view.applyTint(primaryTextColor)
        view.isSubmitButtonEnabled = true
        view.queryHint = getString(R.string.screen_find)
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                upExploreData(newText)
                return false
            }
        })
    }

    private fun initDiscoverRecycler() {
        binding.rvDiscoverTags.setOnTagClickListener { index ->
            val item = discoverTagItems.getOrNull(index) ?: return@setOnTagClickListener
            if (item.isButton) {
                handleDiscoverButtonTag(item)
                return@setOnTagClickListener
            }
            selectDiscoverTag(index, item, selectTab = true)
        }
        binding.rvDiscoverSelects.setOnTagClickListener { index ->
            val item = discoverSelectItems.getOrNull(index) ?: return@setOnTagClickListener
            showDiscoverSelectDialog(item)
        }
        binding.rvDiscoverBooks.layoutManager = discoverBookLayoutManager
        binding.rvDiscoverBooks.adapter = discoverBookAdapter
        binding.rvDiscoverBooks.setEdgeEffectColor(primaryColor)
        binding.rvDiscoverBooks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                    loadDiscoverBooks(reset = false)
                }
            }
        })
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun showDiscoverWebPage(url: String, html: String? = null, preloadJs: String? = null) {
        releaseDiscoverWebView()
        discoverPendingPreloadJs = preloadJs?.takeIf { it.isNotBlank() }
        val webView = discoverWebView ?: WebView(requireContext()).also { created ->
            created.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            created.overScrollMode = View.OVER_SCROLL_NEVER
            created.settings.javaScriptEnabled = true
            created.settings.domStorageEnabled = true
            created.settings.cacheMode = WebSettings.LOAD_DEFAULT
            created.settings.loadsImagesAutomatically = true
            created.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            created.settings.loadWithOverviewMode = true
            created.settings.useWideViewPort = true
            created.settings.setDarkeningAllowed(AppConfig.isNightTheme)
            created.setBackgroundColor(Color.TRANSPARENT)
            created.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            created.isVerticalScrollBarEnabled = false
            created.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    val preloadJs = discoverPendingPreloadJs
                    val script = buildString {
                        append("(() => {")
                        append(getInjectionString)
                        if (!preloadJs.isNullOrBlank()) {
                            append('\n')
                            append(preloadJs)
                        }
                        append("})();")
                    }
                    runCatching { view.evaluateJavascript(script, null) }
                }
            }
            created.webChromeClient = WebChromeClient()
            binding.discoverWebContainer.setBackgroundColor(Color.TRANSPARENT)
            binding.discoverWebContainer.addView(created)
            discoverWebView = created
        }
        selectedDiscoverSource?.let { source ->
            val jsBridge = SourceLoginJsExtensions(
                activity as? AppCompatActivity,
                source,
                callback = object : SourceLoginJsExtensions.Callback {
                    override fun upUiData(data: Map<String, Any?>?) = Unit
                    override fun reUiView(deltaUp: Boolean) = Unit
                    override fun showBrowser(
                        url: String,
                        html: String?,
                        preloadJs: String?,
                        config: String?
                    ): Boolean {
                        if (!isAdded) return false
                        binding.root.post { showDiscoverWebPage(url, html, preloadJs) }
                        return true
                    }

                    override fun open(
                        name: String,
                        url: String?,
                        title: String?,
                        origin: String?
                    ): Boolean {
                        if (!isAdded) return false
                        if (name != "explore") return false
                        val targetUrl = url?.takeIf { it.isNotBlank() } ?: return true
                        val targetSourceUrl = origin
                            ?.takeIf { it.isNotBlank() }
                            ?: selectedDiscoverSource?.bookSourceUrl
                            ?: source.bookSourceUrl
                        val targetTitle = title ?: getString(R.string.discovery)
                        binding.root.post { openExplore(targetSourceUrl, targetTitle, targetUrl) }
                        return true
                    }
                }
            )
            discoverJsBridge = jsBridge
            runCatching { webView.removeJavascriptInterface(nameJava) }
            runCatching { webView.removeJavascriptInterface(nameSource) }
            runCatching { webView.removeJavascriptInterface(nameCache) }
            webView.addJavascriptInterface(jsBridge, nameJava)
            webView.addJavascriptInterface(source, nameSource)
            webView.addJavascriptInterface(WebCacheManager, nameCache)
        }
        binding.flDiscoverBooks.gone()
        binding.discoverWebContainer.visible()
        binding.pbDiscoverLoading.gone()
        binding.swipeRefreshLayout.isRefreshing = false
        webView.stopLoading()
        if (html.isNullOrBlank()) {
            webView.loadUrl(url)
        } else {
            webView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
        }
    }

    private fun restoreDiscoverListPage() {
        releaseDiscoverWebView()
        binding.discoverWebContainer.gone()
        binding.flDiscoverBooks.visible()
    }

    private fun releaseDiscoverWebView() {
        val webView = discoverWebView ?: return
        discoverWebView = null
        discoverJsBridge = null
        discoverPendingPreloadJs = null
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.removeJavascriptInterface(nameJava) }
        runCatching { webView.removeJavascriptInterface(nameSource) }
        runCatching { webView.removeJavascriptInterface(nameCache) }
        runCatching { binding.discoverWebContainer.removeView(webView) }
        runCatching {
            webView.webChromeClient = null
            webView.webViewClient = null
        }
        runCatching { webView.clearHistory() }
        runCatching { webView.clearFormData() }
        runCatching { webView.destroy() }
    }

    private fun applyDiscoverWebContainerBottomPadding() {
        val initialPadding = binding.discoverWebContainer.paddingBottom
        val webBottomSpace =
            resources.getDimensionPixelSize(R.dimen.main_bottom_controls_bottom_padding) +
                resources.getDimensionPixelSize(R.dimen.main_bottom_bar_height) +
                5.dpToPx()
        binding.discoverWebContainer.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                initialPadding + windowInsets.navigationBarHeight + webBottomSpace
            )
            windowInsets
        }
    }

    private fun bindDiscoverSourceSelector() {
        val updateSourceNameWidth = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDiscoverSourceNameWidth()
        }
        binding.llDiscoverSourceRow.addOnLayoutChangeListener(updateSourceNameWidth)
        binding.llDiscoverSourceRow.post(::updateDiscoverSourceNameWidth)
        binding.llDiscoverSourceSelect.setOnClickListener {
            showDiscoverSourceMenu()
        }
        binding.btnDiscoverSourceLogin.setOnClickListener {
            openSelectedSourceLogin()
        }
        binding.btnDiscoverSourceSearch.setOnClickListener {
            openDiscoverSearch()
        }
        binding.btnDiscoverTagFilter.setOnClickListener {
            showDiscoverTagFilterMenu()
        }
        updateDiscoverTagFilterButtonState()
        updateDiscoverSearchButtonState()
    }

    private fun updateDiscoverSourceNameWidth() {
        val rowWidth = binding.llDiscoverSourceRow.width
        if (rowWidth <= 0) return
        val actionsWidth = listOf(
            binding.btnDiscoverSourceSearch,
            binding.btnDiscoverTagFilter,
            binding.btnDiscoverSourceLogin
        ).filter { it.isVisible }.sumOf { it.measuredWidth.takeIf { width -> width > 0 } ?: it.layoutParams.width }
        val spacing = 36.dpToPx()
        val maxWidth = (rowWidth - actionsWidth - spacing).coerceIn(96.dpToPx(), 190.dpToPx())
        binding.tvDiscoverSourceSelect.maxWidth = maxWidth
    }

    private fun openSelectedSourceLogin() {
        val source = selectedDiscoverSourcePart ?: return
        if (!source.hasLoginUrl) {
            context?.toastOnUi(R.string.source_no_login)
            return
        }
        startActivity<SourceLoginActivity> {
            putExtra("type", "bookSource")
            putExtra("key", source.bookSourceUrl)
        }
    }

    private fun updateDiscoverLoginButtonState() {
        val canLogin = selectedDiscoverSourcePart?.hasLoginUrl == true
        binding.btnDiscoverSourceLogin.isEnabled = canLogin
        binding.btnDiscoverSourceLogin.alpha = if (canLogin) 1f else 0.45f
    }

    private fun updateDiscoverSearchButtonState() {
        val canSearch = !selectedDiscoverSource?.searchUrl.isNullOrBlank()
        binding.btnDiscoverSourceSearch.isVisible = canSearch
        binding.btnDiscoverSourceSearch.isEnabled = canSearch
        binding.btnDiscoverSourceSearch.alpha = if (canSearch) 1f else 0.45f
        binding.llDiscoverSourceRow.post(::updateDiscoverSourceNameWidth)
    }

    private fun openDiscoverSearch() {
        val source = selectedDiscoverSource ?: return
        if (source.searchUrl.isNullOrBlank()) {
            context?.toastOnUi(R.string.search_book_key)
            return
        }
        startActivity<SearchActivity> {
            putExtra("searchScope", "${source.bookSourceName}::${source.bookSourceUrl}")
        }
    }

    private fun observeDiscoverSources() {
        if (discoverSourceFlowJob?.isActive == true) return
        discoverSourceFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExplore()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.STARTED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect { list ->
                    discoverSources.clear()
                    discoverSources.addAll(list)
                    if (discoverSources.isEmpty()) {
                        selectedDiscoverSourcePart = null
                        selectedDiscoverSource = null
                        AppConfig.modernDiscoverySourceUrl = null
                        discoverCurrentUrl = null
                        discoverAllTagItems.clear()
                        discoverMajorGroups.clear()
                        selectedDiscoverMajorGroup = null
                        clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
                        renderDiscoverTags(emptyList(), -1)
                        binding.tvDiscoverSourceSelect.text = getString(R.string.explore_empty)
                        updateDiscoverLoginButtonState()
                        updateDiscoverSearchButtonState()
                        updateDiscoverTagFilterButtonState()
                        binding.pbDiscoverLoading.gone()
                        return@collect
                    }
                    val keepSource = selectedDiscoverSourcePart?.bookSourceUrl
                        ?: AppConfig.modernDiscoverySourceUrl
                    val selected = discoverSources.firstOrNull { it.bookSourceUrl == keepSource }
                        ?: discoverSources.first()
                    if (selectedDiscoverSourcePart?.bookSourceUrl != selected.bookSourceUrl
                        || discoverTagItems.isEmpty()
                    ) {
                        selectDiscoverSource(selected)
                    } else {
                        updateDiscoverSourceTitle()
                        updateDiscoverLoginButtonState()
                        updateDiscoverSearchButtonState()
                    }
                }
        }
    }

    private fun observeDiscoverBookshelf() {
        if (discoverBookshelfFlowJob?.isActive == true) return
        discoverBookshelfFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowAll()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.STARTED,
                    AppDatabase.BOOK_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect { books ->
                    discoverBookshelf.clear()
                    books.filterNot { it.isNotShelf }
                        .forEach {
                            discoverBookshelf.add("${it.name}-${it.author}")
                            discoverBookshelf.add(it.name)
                            discoverBookshelf.add(it.bookUrl)
                        }
                    if (discoverBookAdapter.itemCount > 0) {
                        discoverBookAdapter.notifyItemRangeChanged(
                            0,
                            discoverBookAdapter.itemCount,
                            bundleOf("isInBookshelf" to null)
                        )
                    }
                }
        }
    }

    private fun showDiscoverSourceMenu() {
        if (discoverSources.isEmpty()) return
        SourceSelectDialog.show(
            context = requireContext(),
            title = getString(R.string.book_source),
            items = discoverSources,
            selectedKey = selectedDiscoverSourcePart?.bookSourceUrl,
            displayName = { it.getDisPlayNameGroup() },
            searchTexts = {
                listOfNotNull(it.bookSourceName, it.bookSourceUrl, it.bookSourceGroup)
            },
            itemKey = { it.bookSourceUrl }
        ) {
            selectDiscoverSource(it)
        }
    }

    private fun selectDiscoverSource(source: BookSourcePart) {
        selectedDiscoverSourcePart = source
        AppConfig.modernDiscoverySourceUrl = source.bookSourceUrl
        updateDiscoverLoginButtonState()
        tagFilterPopup?.dismiss()
        tagFilterPopup = null
        discoverSourceVersion += 1
        val currentSourceVersion = discoverSourceVersion
        discoverRequestVersion += 1
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverLoading = false
        binding.pbDiscoverLoading.gone()
        discoverCurrentUrl = null
        discoverBooks.clear()
        discoverBookAdapter.clearItems()
        binding.tvDiscoverEmpty.gone()
        discoverAllTagItems.clear()
        discoverMajorGroups.clear()
        discoverSelectItems.clear()
        selectedDiscoverMajorGroup = null
        renderDiscoverTags(emptyList(), -1)
        renderDiscoverSelects(emptyList())
        updateDiscoverTagFilterButtonState()
        viewLifecycleOwner.lifecycleScope.launch {
            val fullSource = withContext(IO) {
                appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
            }
            if (currentSourceVersion != discoverSourceVersion || !isAdded) {
                return@launch
            }
            selectedDiscoverSource = fullSource
            updateDiscoverSourceTitle()
            updateDiscoverSearchButtonState()
            loadDiscoverKindsAndDefault()
        }
    }

    private fun updateDiscoverSourceTitle() {
        val name = selectedDiscoverSourcePart?.bookSourceName
            ?: getString(R.string.discovery)
        binding.tvDiscoverSourceSelect.text = name
        binding.llDiscoverSourceRow.post(::updateDiscoverSourceNameWidth)
    }

    private suspend fun loadDiscoverKindsAndDefault() {
        val source = selectedDiscoverSource ?: return
        val kinds = withContext(IO) {
            source.exploreKinds()
        }
        val items = buildDiscoverTagItems(source, kinds)
        discoverAllTagItems.clear()
        discoverAllTagItems.addAll(items)
        if (items.isEmpty()) {
            discoverMajorGroups.clear()
            selectedDiscoverMajorGroup = null
            renderDiscoverTags(emptyList(), -1)
            renderDiscoverSelects(emptyList())
            updateDiscoverTagFilterButtonState()
            clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
            return
        }
        applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
    }

    private fun buildDiscoverTagItems(
        source: BookSource,
        kinds: List<ExploreKind>
    ): List<DiscoverTagItem> {
        val blocked = blockedButtonActions[source.bookSourceUrl]
        var currentGroup: String? = null
        val result = mutableListOf<DiscoverTagItem>()
        kinds.forEach { kind ->
            if (isDiscoverMajorGroupKind(kind)) {
                currentGroup = resolveDiscoverGroupTitle(kind)
                return@forEach
            }

            val action = kind.action?.takeIf { it.isNotBlank() }
            val url = kind.url?.takeIf { it.isNotBlank() }
            val isSelect = kind.type == ExploreKind.Type.select
            val isButton = kind.type == ExploreKind.Type.button && !action.isNullOrBlank()

            if (!url.isNullOrBlank() && !isButton && !isSelect) {
                result += DiscoverTagItem(
                    kind = kind.copy(url = url),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    isButton = false,
                    group = currentGroup
                )
                return@forEach
            }

            if (isSelect) {
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.select),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    isButton = false,
                    group = currentGroup
                )
                return@forEach
            }

            if (!action.isNullOrBlank()) {
                if (blocked?.contains(action) == true) return@forEach
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.button),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    isButton = true,
                    group = currentGroup
                )
            }
        }
        val hasMajorGroup = result.any { !it.group.isNullOrBlank() }
        val normalized = if (hasMajorGroup) {
            result
        } else {
            result.map { it.copy(group = getString(R.string.discover_group_other)) }
        }
        return normalized.distinctBy { "${it.group}|${it.kind.type}|${it.kind.title}|${it.kind.url}|${it.kind.action}" }
    }

    private fun isDiscoverMajorGroupKind(kind: ExploreKind): Boolean {
        if (!kind.action.isNullOrBlank() || !kind.url.isNullOrBlank()) return false
        val style = kind.style()
        if (style.layout_flexBasisPercent >= 0.95f) return true
        if (style.layout_flexGrow >= 1f && style.layout_flexBasisPercent < 0f) return true
        return false
    }

    private fun resolveDiscoverGroupTitle(kind: ExploreKind): String {
        val raw = resolveDiscoverTagText(kind).trim()
        if (raw.isBlank()) return getString(R.string.discovery)
        val normalized = raw
            .replace("🟣", " ")
            .replace("🟪", " ")
            .replace("•", " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return normalized.ifBlank { raw }
    }

    private fun resolveDiscoverTagText(kind: ExploreKind): String {
        val viewName = kind.viewName
        if (!viewName.isNullOrBlank()
            && viewName.length in 3..28
            && viewName.first() == '\''
            && viewName.last() == '\''
        ) {
            return viewName.substring(1, viewName.length - 1)
        }
        return kind.title.ifBlank { kind.type }
    }

    private fun applyDiscoverTagFilterAndSelect(preferredUrl: String?) {
        val groupList = discoverAllTagItems
            .mapNotNull { it.group?.takeIf { name -> name.isNotBlank() } }
            .distinct()
        discoverMajorGroups.clear()
        discoverMajorGroups.addAll(groupList)

        if (discoverMajorGroups.isEmpty()) {
            selectedDiscoverMajorGroup = null
        } else {
            if (selectedDiscoverMajorGroup !in discoverMajorGroups) {
                selectedDiscoverMajorGroup = discoverMajorGroups.first()
            }
        }

        var filtered = if (discoverMajorGroups.isEmpty()) {
            discoverAllTagItems.toList()
        } else {
            discoverAllTagItems.filter { it.group == selectedDiscoverMajorGroup }
        }
        if (filtered.isEmpty() && discoverMajorGroups.isNotEmpty()) {
            val fallbackGroup = discoverMajorGroups.firstOrNull { group ->
                discoverAllTagItems.any { it.group == group }
            }
            selectedDiscoverMajorGroup = fallbackGroup
            filtered = if (fallbackGroup.isNullOrBlank()) {
                discoverAllTagItems.toList()
            } else {
                discoverAllTagItems.filter { it.group == fallbackGroup }
            }
        }

        val selectItems = filtered.filter { it.kind.type == ExploreKind.Type.select }
        val tagItems = filtered.filter { it.kind.type != ExploreKind.Type.select }
        updateDiscoverTagFilterButtonState()
        renderDiscoverSelects(selectItems)
        val targetIndexByUrl = preferredUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { url ->
                tagItems.indexOfFirst { !it.isButton && it.kind.url == url }
                    .takeIf { idx -> idx >= 0 }
            }
        val targetIndex = targetIndexByUrl
            ?: tagItems.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        renderDiscoverTags(tagItems, targetIndex)
        if (targetIndex >= 0) {
            selectDiscoverTag(targetIndex, tagItems[targetIndex], selectTab = true)
        } else {
            clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
        }
    }

    private fun updateDiscoverTagFilterButtonState() {
        val enabled = discoverMajorGroups.size > 1
        binding.btnDiscoverTagFilter.isVisible = enabled
        binding.btnDiscoverTagFilter.isEnabled = enabled
        binding.btnDiscoverTagFilter.alpha = if (enabled) 1f else 0.45f
    }

    private fun showDiscoverTagFilterMenu() {
        if (discoverMajorGroups.size <= 1) return
        val current = selectedDiscoverMajorGroup
        val actions = buildList {
            discoverMajorGroups.forEach { group ->
                add(
                    ModernActionPopup.Action(
                        (if (group == current) "✓ " else "") + group.limitDiscoverText(10)
                    ) {
                        selectedDiscoverMajorGroup = group
                        applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
                    }
                )
            }
        }
        tagFilterPopup = ModernActionPopup.show(
            binding.btnDiscoverTagFilter,
            actions,
            tagFilterPopup
        )
    }

    private fun renderDiscoverTags(items: List<DiscoverTagItem>, selectedIndex: Int) {
        discoverTagItems.clear()
        discoverTagItems.addAll(items)
        selectedDiscoverTagIndex = selectedIndex.coerceIn(-1, items.lastIndex)
        selectedDiscoverUrlIndex = if (selectedDiscoverTagIndex in items.indices && !items[selectedDiscoverTagIndex].isButton) {
            selectedDiscoverTagIndex
        } else {
            items.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        }
        binding.rvDiscoverTags.submitItems(
            items.map { RoundedTagBarView.Item(it.text, if (it.isButton) 0.9f else 1f) },
            selectedDiscoverTagIndex
        )
    }

    private fun renderDiscoverSelects(items: List<DiscoverTagItem>) {
        discoverSelectItems.clear()
        discoverSelectItems.addAll(items)
        if (items.isEmpty()) {
            binding.rvDiscoverSelects.gone()
            binding.rvDiscoverSelects.submitItems(emptyList(), -1)
            return
        }
        binding.rvDiscoverSelects.visible()
        binding.rvDiscoverSelects.submitItems(
            items.map {
                val value = currentDiscoverSelectValue(it)
                RoundedTagBarView.Item("${it.text}:${value}", 1f)
            },
            -1
        )
    }

    private fun currentDiscoverSelectValue(item: DiscoverTagItem): String {
        val source = selectedDiscoverSource ?: return item.kind.default ?: ""
        val key = item.kind.title
        if (key.isBlank()) return item.kind.default ?: ""
        val info = getDiscoverInfoMap(source.bookSourceUrl)
        return info[key]?.toString()?.takeIf { it.isNotBlank() }
            ?: item.kind.default?.takeIf { it.isNotBlank() }
            ?: item.kind.chars?.firstOrNull()?.orEmpty()
            ?: ""
    }

    private fun showDiscoverSelectDialog(item: DiscoverTagItem) {
        val source = selectedDiscoverSource ?: return
        val key = item.kind.title
        if (key.isBlank()) return
        val options = item.kind.chars?.filterNotNull()?.filter { it.isNotBlank() } ?: emptyList()
        if (options.isEmpty()) return
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        context?.selector(item.text, options) { _, value, _ ->
            infoMap[key] = value
            viewLifecycleOwner.lifecycleScope.launch(IO) {
                source.clearExploreKindsCache()
                val action = item.kind.action?.takeIf { it.isNotBlank() }
                if (!action.isNullOrBlank()) {
                    runScriptWithContext {
                        source.evalJS(action) {
                            put("java", SourceLoginJsExtensions(activity as? AppCompatActivity, source))
                            put("infoMap", infoMap)
                        }
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    loadDiscoverKindsAndDefault()
                }
            }
        }
    }

    private fun selectDiscoverTabByCode(index: Int, smooth: Boolean) {
        if (index !in discoverTagItems.indices) return
        binding.rvDiscoverTags.setSelectedIndex(index, smooth)
    }

    private fun selectDiscoverTag(index: Int, item: DiscoverTagItem, selectTab: Boolean) {
        val url = item.kind.url?.takeIf { it.isNotBlank() } ?: return
        restoreDiscoverListPage()
        selectedDiscoverTagIndex = index
        selectedDiscoverUrlIndex = index
        if (selectTab) {
            selectDiscoverTabByCode(index, smooth = true)
        }
        if (discoverCurrentUrl == url && discoverBooks.isNotEmpty()) {
            return
        }
        discoverCurrentUrl = url
        loadDiscoverBooks(reset = true)
    }

    private fun handleDiscoverButtonTag(item: DiscoverTagItem) {
        val source = selectedDiscoverSource ?: return
        val action = item.kind.action?.takeIf { it.isNotBlank() } ?: return
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val actionLower = action.lowercase()
        val isNavigationAction = actionLower.contains("showbrowser(")
            || actionLower.contains("open(\"explore\"")
            || actionLower.contains("open('explore'")
        discoverActionJob?.cancel()
        discoverActionJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.pbDiscoverLoading.visible()
            val result = withContext(IO) {
                kotlin.runCatching {
                    var handledByAction = false
                    val java = SourceLoginJsExtensions(
                        activity as? AppCompatActivity,
                        source,
                        callback = object : SourceLoginJsExtensions.Callback {
                            override fun upUiData(data: Map<String, Any?>?) = Unit
                            override fun reUiView(deltaUp: Boolean) = Unit
                            override fun showBrowser(
                                url: String,
                                html: String?,
                                preloadJs: String?,
                                config: String?
                            ): Boolean {
                                if (!isAdded) return false
                                handledByAction = true
                                binding.root.post {
                                    showDiscoverWebPage(url, html, preloadJs)
                                }
                                return true
                            }

                            override fun open(
                                name: String,
                                url: String?,
                                title: String?,
                                origin: String?
                            ): Boolean {
                                if (!isAdded) return false
                                if (name != "explore") return false
                                handledByAction = true
                                val targetUrl = url?.takeIf { it.isNotBlank() } ?: return true
                                val targetSourceUrl = origin
                                    ?.takeIf { it.isNotBlank() }
                                    ?: selectedDiscoverSource?.bookSourceUrl
                                    ?: source.bookSourceUrl
                                val targetTitle = title ?: item.text
                                binding.root.post {
                                    openExplore(targetSourceUrl, targetTitle, targetUrl)
                                }
                                return true
                            }
                        }
                    )
                    runScriptWithContext {
                        source.evalJS(action) {
                            put("java", java)
                            put("infoMap", infoMap)
                        }
                    }
                    when {
                        handledByAction || isNavigationAction -> null
                        else -> {
                            source.clearExploreKindsCache()
                            source.exploreKinds()
                        }
                    }
                }
            }
            binding.pbDiscoverLoading.gone()
            if (!isAdded) return@launch
            result.onSuccess { kinds ->
                if (kinds == null) {
                    return@onSuccess
                }
                applyDiscoverButtonResult(source, action, kinds)
            }.onFailure {
                AppLog.put("发现标签按钮执行失败", it)
                context?.toastOnUi(it.localizedMessage ?: getString(R.string.unknown_error))
            }
        }
    }

    private fun applyDiscoverButtonResult(
        source: BookSource,
        action: String,
        kinds: List<ExploreKind>
    ) {
        val items = buildDiscoverTagItems(source, kinds)
        val firstUrlIndex = items.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        if (firstUrlIndex >= 0) {
            restoreDiscoverListPage()
            discoverAllTagItems.clear()
            discoverAllTagItems.addAll(items)
            applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
            return
        }
        if (items.isNotEmpty()) {
            discoverAllTagItems.clear()
            discoverAllTagItems.addAll(items)
            applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
        }
        context?.toastOnUi("该按钮未返回可用列表，保留当前标签")
    }

    private fun getDiscoverInfoMap(sourceUrl: String): InfoMap {
        return ExploreAdapter.exploreInfoMapList[sourceUrl] ?: InfoMap(sourceUrl).also {
            ExploreAdapter.exploreInfoMapList.put(sourceUrl, it)
        }
    }

    private fun clearDiscoverBooksToEmpty(message: String) {
        restoreDiscoverListPage()
        discoverRequestVersion += 1
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverLoading = false
        binding.swipeRefreshLayout.isRefreshing = false
        binding.pbDiscoverLoading.gone()
        discoverCurrentUrl = null
        discoverHasMore = false
        discoverPage = 1
        discoverBooks.clear()
        discoverBookAdapter.clearItems()
        binding.tvDiscoverEmpty.text = message
        binding.tvDiscoverEmpty.visible()
    }

    private fun loadDiscoverBooks(reset: Boolean) {
        restoreDiscoverListPage()
        if (!usingModernDiscovery) return
        val source = selectedDiscoverSource ?: return
        val url = discoverCurrentUrl?.takeIf { it.isNotBlank() } ?: return
        if (!reset && !discoverHasMore) return
        if (reset) {
            discoverLoadJob?.cancel()
        } else if (discoverLoading) {
            return
        }
        val requestVersion = if (reset) {
            discoverRequestVersion += 1
            discoverRequestVersion
        } else {
            discoverRequestVersion
        }
        discoverLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            if (reset) {
                discoverPage = 1
                discoverHasMore = true
                discoverBooks.clear()
                discoverBookAdapter.clearItems()
                binding.tvDiscoverEmpty.gone()
            }
            discoverLoading = true
            binding.pbDiscoverLoading.visible()
            try {
                val newBooks = withContext(IO) {
                    WebBook.exploreBookAwait(source, url, discoverPage)
                }
                if (!isAdded || requestVersion != discoverRequestVersion || url != discoverCurrentUrl) {
                    return@launch
                }
                if (newBooks.isEmpty()) {
                    discoverHasMore = false
                    if (discoverBooks.isEmpty()) {
                        binding.tvDiscoverEmpty.text = getString(R.string.explore_empty)
                        binding.tvDiscoverEmpty.visible()
                    }
                } else {
                    withContext(IO) {
                        appDb.searchBookDao.insert(*newBooks.toTypedArray())
                    }
                    discoverPage += 1
                    discoverBooks.addAll(newBooks)
                    discoverBookAdapter.setItems(discoverBooks.toList())
                    binding.tvDiscoverEmpty.gone()
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Throwable) {
                if (!isAdded || requestVersion != discoverRequestVersion || url != discoverCurrentUrl) {
                    return@launch
                }
                AppLog.put("新版发现页加载失败", e)
                if (discoverBooks.isEmpty()) {
                    binding.tvDiscoverEmpty.text = e.localizedMessage ?: getString(R.string.unknown_error)
                    binding.tvDiscoverEmpty.visible()
                }
            } finally {
                if (isAdded && requestVersion == discoverRequestVersion && url == discoverCurrentUrl) {
                    binding.pbDiscoverLoading.gone()
                    binding.swipeRefreshLayout.isRefreshing = false
                    discoverLoading = false
                }
            }
        }
    }

    private fun initRecyclerView() {
        binding.rvFind.setEdgeEffectColor(primaryColor)
        binding.rvFind.layoutManager = linearLayoutManager
        binding.rvFind.adapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.rvFind.scrollToPosition(0)
                }
            }
        })
    }

    private fun initGroupData() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExploreGroups()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups.clear()
                    groups.addAll(it)
                    upGroupsMenu()
                    delay(500)
                }
        }
    }

    private fun upExploreData(searchKey: String? = null) {
        exploreFlowJob?.cancel()
        exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> {
                    appDb.bookSourceDao.flowExplore()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.bookSourceDao.flowGroupExplore(key)
                }

                else -> {
                    appDb.bookSourceDao.flowExplore(searchKey)
                }
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("发现界面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                binding.swipeRefreshLayout.isRefreshing = false
                binding.tvEmptyMsg.isGone = it.isNotEmpty() || (searchView?.query?.isNotEmpty() == true)
                adapter.setItems(it, diffItemCallBack)
                delay(500)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (usingModernDiscovery != AppConfig.modernDiscoveryPage || !discoveryModeLoaded) {
            applyDiscoveryMode(loadData = true)
            discoveryModeLoaded = true
        }
        if (!usingModernDiscovery) {
            adapter.upResumed(true)
        }
    }

    override fun onPause() {
        if (!usingModernDiscovery) {
            adapter.upResumed(false)
            searchView?.clearFocus()
            adapter.onPause()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        stopModernMode()
        oldModeInitialized = false
        modernModeInitialized = false
        groupsMenu = null
        super.onDestroyView()
    }

    private fun upGroupsMenu() = groupsMenu?.transaction { subMenu ->
        subMenu.removeGroup(R.id.menu_group_text)
        groups.forEach {
            subMenu.add(R.id.menu_group_text, Menu.NONE, Menu.NONE, it)
        }
    }

    override val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        if (usingModernDiscovery) return
        if (item.groupId == R.id.menu_group_text) {
            searchView?.setQuery("group:${item.title}", true) ?: upExploreData("group:${item.title}")
        }
    }

    override fun scrollTo(pos: Int) {
        (binding.rvFind.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
    }

    override fun openExplore(sourceUrl: String, title: String, exploreUrl: String?) {
        if (exploreUrl.isNullOrBlank()) return
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("sourceUrl", sourceUrl)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    override fun editSource(sourceUrl: String) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    override fun toTop(source: BookSourcePart) {
        viewModel.topSource(source)
    }

    override fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton {
                viewModel.deleteSource(source)
            }
        }
    }

    override fun searchBook(bookSource: BookSourcePart) {
        SearchActivity.start(requireContext(), bookSource)
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
        return discoverBookshelf.contains(key) || discoverBookshelf.contains(book.bookUrl)
    }

    override fun showBookInfo(book: SearchBook) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(IO) {
                appDb.searchBookDao.insert(book)
            }
            val isVideo = withContext(IO) {
                SearchBookOpenHelper.isVideoResult(
                    book,
                    selectedDiscoverSourcePart?.bookSourceType ?: selectedDiscoverSource?.bookSourceType
                )
            }
            SearchBookOpenHelper.open(requireContext(), book, isVideo)
        }
    }

    fun compressExplore() {
        if (usingModernDiscovery) {
            if (binding.rvDiscoverBooks.canScrollVertically(-1)) {
                if (AppConfig.isEInkMode) {
                    binding.rvDiscoverBooks.scrollToPosition(0)
                } else {
                    binding.rvDiscoverBooks.smoothScrollToPosition(0)
                }
            }
            return
        }
        if (!adapter.compressExplore()) {
            if (AppConfig.isEInkMode) {
                binding.rvFind.scrollToPosition(0)
            } else {
                binding.rvFind.smoothScrollToPosition(0)
            }
        }
    }

}

private fun String.limitDiscoverText(max: Int): String {
    return if (length <= max) this else "${take(max.coerceAtLeast(2) - 1)}…"
}
