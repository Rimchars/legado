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
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.dialogs.alert
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
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.gone
import io.legado.app.utils.InfoMap
import io.legado.app.utils.setEdgeEffectColor
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

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        usingModernDiscovery = AppConfig.modernDiscoveryPage
        discoveryModeLoaded = false
        binding.swipeRefreshLayout.setColorSchemeColors(accentColor)
        binding.swipeRefreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
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

    private fun bindDiscoverSourceSelector() {
        binding.llDiscoverSourceSelect.setOnClickListener {
            showDiscoverSourceMenu()
        }
        binding.btnDiscoverSourceLogin.setOnClickListener {
            openSelectedSourceLogin()
        }
        binding.btnDiscoverTagFilter.setOnClickListener {
            showDiscoverTagFilterMenu()
        }
        updateDiscoverTagFilterButtonState()
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
        sourceMenuPopup?.dismiss()
        sourceMenuPopup = null
        val context = requireContext()
        val selectedUrl = selectedDiscoverSourcePart?.bookSourceUrl.orEmpty()
        var dialog: AlertDialog? = null
        var filteredSources = discoverSources.toList()
        val sourceAdapter = object : RecyclerView.Adapter<SourceSelectViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceSelectViewHolder {
                val textView = TextView(parent.context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    minHeight = 48.dpToPx()
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(primaryTextColor)
                    textSize = 15f
                    setPadding(18.dpToPx(), 0, 18.dpToPx(), 0)
                    setBackgroundResource(R.drawable.bg_popup_action_item)
                }
                return SourceSelectViewHolder(textView)
            }

            override fun getItemCount(): Int = filteredSources.size

            override fun onBindViewHolder(holder: SourceSelectViewHolder, position: Int) {
                val source = filteredSources[position]
                val selectedPrefix = if (source.bookSourceUrl == selectedUrl) "✓ " else ""
                holder.textView.text = selectedPrefix + source.getDisPlayNameGroup()
                holder.textView.setOnClickListener {
                    dialog?.dismiss()
                    selectDiscoverSource(source)
                }
            }
        }
        val searchView = SearchView(context).apply {
            queryHint = getString(R.string.screen_find)
            isIconified = false
            isSubmitButtonEnabled = false
            setBackgroundResource(R.drawable.bg_source_picker_search)
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = true

                override fun onQueryTextChange(newText: String?): Boolean {
                    val key = newText.orEmpty().trim()
                    filteredSources = if (key.isBlank()) {
                        discoverSources.toList()
                    } else {
                        discoverSources.filter { source ->
                            source.bookSourceName.contains(key, true)
                                    || source.bookSourceUrl.contains(key, true)
                                    || source.bookSourceGroup?.contains(key, true) == true
                        }
                    }
                    sourceAdapter.notifyDataSetChanged()
                    return true
                }
            })
        }
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = sourceAdapter
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                360.dpToPx()
            ).apply {
                topMargin = 10.dpToPx()
            }
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_source_picker_panel)
            setPadding(14.dpToPx(), 14.dpToPx(), 14.dpToPx(), 12.dpToPx())
            addView(
                TextView(context).apply {
                    text = getString(R.string.book_source)
                    setTextColor(primaryTextColor)
                    textSize = 18f
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(2.dpToPx(), 0, 2.dpToPx(), 12.dpToPx())
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    32.dpToPx()
                )
            )
            addView(
                searchView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    42.dpToPx()
                )
            )
            addView(recyclerView)
        }
        dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
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
        selectedDiscoverMajorGroup = null
        renderDiscoverTags(emptyList(), -1)
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
            loadDiscoverKindsAndDefault()
        }
    }

    private fun updateDiscoverSourceTitle() {
        val name = selectedDiscoverSourcePart?.bookSourceName?.limitDiscoverText(10)
            ?: getString(R.string.discovery)
        binding.tvDiscoverSourceSelect.text = name
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
            val isButton = kind.type == ExploreKind.Type.button && !action.isNullOrBlank()

            if (!url.isNullOrBlank() && !isButton) {
                result += DiscoverTagItem(
                    kind = kind.copy(url = url),
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
        return result.distinctBy { "${it.group}|${it.kind.type}|${it.kind.title}|${it.kind.url}|${it.kind.action}" }
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

        updateDiscoverTagFilterButtonState()
        val targetIndexByUrl = preferredUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { url ->
                filtered.indexOfFirst { !it.isButton && it.kind.url == url }
                    .takeIf { idx -> idx >= 0 }
            }
        val targetIndex = targetIndexByUrl
            ?: filtered.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        renderDiscoverTags(filtered, targetIndex)
        if (targetIndex >= 0) {
            selectDiscoverTag(targetIndex, filtered[targetIndex], selectTab = true)
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

    private fun selectDiscoverTabByCode(index: Int, smooth: Boolean) {
        if (index !in discoverTagItems.indices) return
        binding.rvDiscoverTags.setSelectedIndex(index, smooth)
    }

    private fun selectDiscoverTag(index: Int, item: DiscoverTagItem, selectTab: Boolean) {
        val url = item.kind.url?.takeIf { it.isNotBlank() } ?: return
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
        discoverActionJob?.cancel()
        discoverActionJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.pbDiscoverLoading.visible()
            val result = withContext(IO) {
                kotlin.runCatching {
                    val java = SourceLoginJsExtensions(
                        activity as? AppCompatActivity,
                        source,
                        callback = object : SourceLoginJsExtensions.Callback {
                            override fun upUiData(data: Map<String, Any?>?) = Unit
                            override fun reUiView(deltaUp: Boolean) = Unit
                        }
                    )
                    runScriptWithContext {
                        source.evalJS(action) {
                            put("java", java)
                            put("infoMap", infoMap)
                        }
                    }
                    source.clearExploreKindsCache()
                    source.exploreKinds()
                }
            }
            binding.pbDiscoverLoading.gone()
            if (!isAdded) return@launch
            result.onSuccess { kinds ->
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
            discoverAllTagItems.clear()
            discoverAllTagItems.addAll(items)
            applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
            return
        }
        blockedButtonActions
            .getOrPut(source.bookSourceUrl) { linkedSetOf() }
            .add(action)
        val filteredItems = items.filterNot { it.isButton && it.kind.action == action }
        discoverAllTagItems.clear()
        discoverAllTagItems.addAll(filteredItems)
        applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
        context?.toastOnUi("该按钮未返回可用发现列表，已隐藏")
    }

    private fun getDiscoverInfoMap(sourceUrl: String): InfoMap {
        return ExploreAdapter.exploreInfoMapList[sourceUrl] ?: InfoMap(sourceUrl).also {
            ExploreAdapter.exploreInfoMapList.put(sourceUrl, it)
        }
    }

    private fun clearDiscoverBooksToEmpty(message: String) {
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

private class SourceSelectViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

private fun String.limitDiscoverText(max: Int): String {
    return if (length <= max) this else "${take(max.coerceAtLeast(2) - 1)}…"
}
