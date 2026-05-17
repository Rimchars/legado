package io.legado.app.ui.book.info

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.textclassifier.TextClassifier
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityBookInfoBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.TextViewTagHandler
import io.legado.app.help.WebCacheManager
import io.legado.app.help.book.addType
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BookInfoComponentConfig
import io.legado.app.help.config.BookInfoComponentItem
import io.legado.app.help.config.BookInfoComponentType
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.BookCover
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.edit.BookInfoEditActivity
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.getPrefString
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openFileUri
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setHtml
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookInfoActivity :
    VMBaseActivity<ActivityBookInfoBinding, BookInfoViewModel>(toolBarTheme = Theme.Dark, showOpenMenuIcon = false),
    GroupSelectDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeCoverDialog.CallBack,
    VariableDialog.Callback {

    private enum class DetailPage {
        INTRO, TOC
    }

    private val tocBatchSize = 30
    private var tocPreviewChapters: List<BookChapter> = emptyList()
    private var tocPreviewStart = 0
    private var tocPreviewEnd = 0
    private var isUpdatingTocPreview = false
    private val catalogAdapter by lazy { CatalogAdapter() }
    private val bookInfoPageAdapter by lazy { BookInfoPageAdapter() }
    private lateinit var bookInfoPager: ViewPager2

    private data class BookInfoPageItem(
        val type: BookInfoComponentType,
        val view: View,
        val height: Int,
        val topMargin: Int,
        val fillBefore: Boolean = false
    )

    private enum class BookInfoPageKind {
        NORMAL_COMPONENTS, FULL_CATALOG
    }

    private data class BookInfoPage(
        val kind: BookInfoPageKind,
        val items: List<BookInfoPageItem>,
        val rightItems: List<BookInfoPageItem> = emptyList()
    )

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            viewModel.getBook(false)?.let { book ->
                lifecycleScope.launch {
                    withContext(IO) {
                        val durChapterIndex = it[0] as Int
                        val durChapterPos = it[1] as Int
                        val durVolumeIndex = it[3] as Int
                        val chapterInVolumeIndex = it[4] as Int
                        book.durChapterIndex = durChapterIndex
                        book.durChapterPos = durChapterPos
                        chapterChanged = it[2] as Boolean
                        book.durVolumeIndex = durVolumeIndex
                        book.chapterInVolumeIndex = chapterInVolumeIndex
                        appDb.bookDao.update(book)
                    }
                    startReadActivity(book)
                }
            }
        } ?: let {
            if (!viewModel.inBookshelf) {
                viewModel.delBook() //进目录会保存book，此时退出目录触发的book删除，不通知书源回调
            }
        }
    }
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }
    private val readBookResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.upBook(intent)
        when (it.resultCode) {
            RESULT_OK -> {
                viewModel.inBookshelf = true
                upTvBookshelf()
            }

            RESULT_DELETED -> {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
    private val infoEditResult = registerForActivityResult(
        StartActivityContract(BookInfoEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.upEditBook()
        }
    }
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(BookSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_CANCELED) {
            return@registerForActivityResult
        }
        book?.let { book ->
            viewModel.bookSource = appDb.bookSourceDao.getBookSource(book.origin)?.also { source ->
                viewModel.hasCustomBtn = source.customButton
            }
            viewModel.refreshBook(book)
        }
    }
    private var chapterChanged = false
    private var detailPage = DetailPage.INTRO
    private val waitDialog by lazy { WaitDialog(this) }
    private var editMenuItem: MenuItem? = null
    private var menuCustomBtn: MenuItem? = null
    private val book get() = viewModel.getBook(false)
    private var introRawText: CharSequence = ""
    private var detailIntroOnly = false
    private var bookInfoComponentsReady = false
    private var lastBookInfoBgPath: String? = null

    override val binding by viewBinding(ActivityBookInfoBinding::inflate)
    override val viewModel by viewModels<BookInfoViewModel>()
    private var initIntroView = false
    private val introTextView by lazy {
        initIntroView = true
        createIntroTextView()
    }
    private val introScrollView by lazy {
        NestedScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isNestedScrollingEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener(childScrollTouchListener())
            addView(
                introTextView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private var pooledWebView: PooledWebView? = null

    private val imgAvailableWidth by lazy {
        val textView = introTextView
        textView.width - textView.paddingLeft - textView.paddingRight - 8.dpToPx()  //8是为了文字对齐额外的右边距
    }
    private var initGetter = false
    private val glideImageGetter by lazy {
        initGetter = true
        GlideImageGetter(
            this,
            introTextView,
            lifecycle,
            imgAvailableWidth,
            viewModel.bookSource?.bookSourceUrl
        )
    }

    private val textViewTagHandler by lazy {
        TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
            override fun onButtonClick(name: String, click: String) {
                viewModel.onButtonClick(this@BookInfoActivity, "info button $name" , click)
            }
        })
    }

    private fun createIntroTextView(): TextView {
        return TextView(this).apply {
            id = R.id.tv_intro
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            minHeight = 48.dpToPx()
            setLineSpacing(3.dpToPx().toFloat(), 1f)
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            setTextColor(ContextCompat.getColor(this@BookInfoActivity, R.color.secondaryText))
            textSize = 13.5f
            includeFontPadding = true
            typeface = uiTypeface()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                revealOnFocusHint = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setTextClassifier(TextClassifier.NO_OP)
            }
            setTextIsSelectable(false)
        }
    }

    @SuppressLint("PrivateResource")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.bgBook.setBackgroundColor(backgroundColor)
        binding.vwBg.alpha = 1f
        binding.titleBar.setBackgroundResource(R.color.transparent)
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.arcView.setBgColor(backgroundColor)
        binding.llInfo.setBackgroundResource(R.color.transparent)
        binding.ivCoverC.setCardBackgroundColor(Color.TRANSPARENT)
        applyUiCorners()
        applyBookInfoTypography()
        binding.flAction.setBackgroundResource(R.color.transparent)
        normalizeDetailContentLayout()
        initBookInfoPager()
        applyBookInfoComponents()
        binding.vwBg.applyNavigationBarPadding()
        binding.tvToc.text = getString(R.string.toc_s, getString(R.string.loading))
        initDetailTabs()
        initCatalogPager()
        binding.vwBg.doOnLayout { updateDetailContentPanelHeight() }
        viewModel.bookData.observe(this) { showBook(it) }
        viewModel.chapterListData.observe(this) {
            upLoading(false, it)
            if (detailPage == DetailPage.TOC) {
                renderTocPreview(it)
            }
            renderCatalogPager(it)
        }
        viewModel.waitDialogData.observe(this) { upWaitDialogStatus(it) }
        viewModel.initData(intent)
        initViewEvent()
    }

    private fun applyUiCorners() = binding.run {
        val panelColor = ContextCompat.getColor(this@BookInfoActivity, R.color.background_card)
        val menuColor = ContextCompat.getColor(this@BookInfoActivity, R.color.background_menu)
        val actionColor = ContextCompat.getColor(this@BookInfoActivity, R.color.book_info_frost)
        val strokeColor = ContextCompat.getColor(this@BookInfoActivity, R.color.glass_stroke)
        val transparent = Color.TRANSPARENT
        ivCoverC.radius = UiCorner.panelRadius(this@BookInfoActivity)
        listOfNotNull(llDetailPanel, llInfoPage, llDetailContentPanel, llCatalogPanel).forEach {
            it.background = UiCorner.panelRounded(this@BookInfoActivity, panelColor, UiCorner.panelRadius(this@BookInfoActivity))
        }
        listOfNotNull(tvTabIntro, tvTabToc, tvTabInfo, tvIntroToggle, tvTocFull, etCatalogSearch).forEach {
            it.background = UiCorner.actionSelector(
                transparent,
                menuColor,
                UiCorner.actionRadius(this@BookInfoActivity)
            )
        }
        tvShelf.background = UiCorner.actionStrokeSelector(
            actionColor,
            menuColor,
            UiCorner.actionRadius(this@BookInfoActivity),
            1.dpToPx(),
            strokeColor
        )
    }

    private fun applyBookInfoTypography() = binding.run {
        val uiTf = uiTypeface()
        llInfo.applyUiBodyTypefaceDeep(uiTf)
        flAction.applyUiBodyTypefaceDeep(uiTf)
        etCatalogSearch.typeface = uiTf
        val titleTf = titleTypeface()
        listOfNotNull(
            tvName,
            tvTabIntro,
            tvTabToc,
            tvTabInfo,
            tvToc,
            tvIntroToggle,
            tvTocFull,
            tvCatalogTitle,
            tvCatalogPage
        ).forEach {
            it.applyUiTitleTypeface(this@BookInfoActivity)
            it.typeface = titleTf
        }
    }

    private fun restoreBookInfoComponentBackgrounds() = binding.run {
        val panelColor = ContextCompat.getColor(this@BookInfoActivity, R.color.background_card)
        listOfNotNull(llDetailPanel, llInfoPage, llDetailContentPanel, llCatalogPanel).forEach {
            it.background = UiCorner.panelRounded(this@BookInfoActivity, panelColor, UiCorner.panelRadius(this@BookInfoActivity))
        }
    }
    private fun applyBookInfoComponents(): Unit = binding.run {
        val componentViews = mapOf<BookInfoComponentType, View?>(
            BookInfoComponentType.HEADER to llDetailPanel,
            BookInfoComponentType.META to llInfoPage,
            BookInfoComponentType.DETAIL to llDetailContentPanel,
            BookInfoComponentType.CATALOG to llCatalogPanel
        )
        val orderedComponents: List<BookInfoComponentItem> = BookInfoComponentConfig.load()
            .filter { item -> item.enabled && componentViews.containsKey(item.type) }
        setDetailIntroOnly(orderedComponents.any { it.type == BookInfoComponentType.CATALOG })
        if (!::bookInfoPager.isInitialized || bookInfoPager.width <= 0 || bookInfoPager.height <= 0) {
            if (::bookInfoPager.isInitialized) {
                bookInfoPager.doOnLayout { applyBookInfoComponents() }
            }
            return@run
        }
        restoreBookInfoComponentBackgrounds()
        val pages = if (isBookInfoLandscape()) {
            buildLandscapeBookInfoPages(orderedComponents, componentViews)
        } else {
            buildBookInfoPages(orderedComponents, componentViews)
        }
        val changed = bookInfoPageAdapter.submitPages(pages)
        if (!bookInfoComponentsReady) {
            bookInfoComponentsReady = true
        }
        val lastIndex = (bookInfoPageAdapter.itemCount - 1).coerceAtLeast(0)
        if (bookInfoPager.currentItem > lastIndex) {
            bookInfoPager.setCurrentItem(lastIndex, false)
        }
        updateDetailContentPanelHeight()
    }

    private fun initBookInfoPager(): Unit = binding.run {
        configurePagedRefreshLayout()
        ensureCoverInsideHeader()
        listOf(llDetailPanel, llInfoPage, llDetailContentPanel, llCatalogPanel, flAction).forEach {
            (it.parent as? ViewGroup)?.removeView(it)
        }
        llInfo.removeAllViews()
        llInfo.orientation = LinearLayout.VERTICAL
        llInfo.setPadding(0, 0, 0, 0)
        bookInfoPager = ViewPager2(this@BookInfoActivity).apply {
            adapter = bookInfoPageAdapter
            offscreenPageLimit = 1
            overScrollMode = View.OVER_SCROLL_NEVER
            alpha = 1f
        }
        llInfo.addView(
            bookInfoPager,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        var lastWidth = 0
        var lastHeight = 0
        bookInfoPager.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0 && (width != lastWidth || height != lastHeight)) {
                lastWidth = width
                lastHeight = height
                bookInfoPager.post { applyBookInfoComponents() }
            }
        }
        bookInfoPager.doOnLayout { applyBookInfoComponents() }
    }

    private fun normalizeDetailContentLayout(): Unit = binding.run {
        tvIntroToggle.gone()
        if (tvIntroContainer.parent !== llIntroPage) {
            val oldParent = tvIntroContainer.parent as? ViewGroup
            val oldWrapper = oldParent?.parent as? ViewGroup
            oldParent?.removeView(tvIntroContainer)
            if (oldWrapper?.parent === llIntroPage) {
                llIntroPage.removeView(oldWrapper)
            }
            llIntroPage.addView(
                tvIntroContainer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        tvIntroContainer.updateLayoutParams<LinearLayout.LayoutParams> {
            width = LinearLayout.LayoutParams.MATCH_PARENT
            height = 0
            weight = 1f
            topMargin = 0
            bottomMargin = 0
        }
    }

    private fun setDetailIntroOnly(introOnly: Boolean): Unit = binding.run {
        detailIntroOnly = introOnly
        llDetailTabs.visibility = if (introOnly) View.GONE else View.VISIBLE
        tvTabToc.visibility = if (introOnly) View.GONE else View.VISIBLE
        llTocPage.visibility = View.GONE
        llIntroPage.visibility = View.VISIBLE
        if (introOnly) {
            detailPage = DetailPage.INTRO
        }
    }

    private fun configurePagedRefreshLayout(): Unit = binding.run {
        (refreshLayout.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
            params.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            refreshLayout.layoutParams = params
        }
        refreshLayout.isEnabled = false
        refreshLayout.setOnTouchListener(null)
        refreshLayout.setOnChildScrollUpCallback(null)
        flAction.visibility = View.GONE
    }

    private fun ensureCoverInsideHeader(): Unit = binding.run {
        val isLandscape = isBookInfoLandscape()
        if (ivCoverC.parent !== llDetailPanel) {
            (ivCoverC.parent as? ViewGroup)?.removeView(ivCoverC)
            llDetailPanel.addView(ivCoverC, 0)
        }
        llDetailPanel.orientation = LinearLayout.HORIZONTAL
        llDetailPanel.gravity = Gravity.CENTER_VERTICAL
        llDetailPanel.setPadding(
            if (isLandscape) 12.dpToPx() else 14.dpToPx(),
            if (isLandscape) 10.dpToPx() else 14.dpToPx(),
            if (isLandscape) 12.dpToPx() else 14.dpToPx(),
            if (isLandscape) 10.dpToPx() else 14.dpToPx()
        )
        ivCoverC.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (isLandscape) 92.dpToPx() else 108.dpToPx()
            height = if (isLandscape) 124.dpToPx() else 144.dpToPx()
            gravity = Gravity.CENTER_VERTICAL
            topMargin = 0
            bottomMargin = 0
            marginEnd = if (isLandscape) 12.dpToPx() else 16.dpToPx()
        }
        tvName.maxLines = if (isLandscape) 1 else 2
        tvAuthor.maxLines = 1
        tvLasted.maxLines = 1
        tvReadTime.maxLines = 1
    }

    private fun buildBookInfoPages(
        orderedComponents: List<BookInfoComponentItem>,
        componentViews: Map<BookInfoComponentType, View?>
    ): List<BookInfoPage> {
        val pageHeight = bookInfoPageContentHeight()
        val pageWidth = bookInfoPageContentWidth()
        if (pageHeight <= 0 || pageWidth <= 0) return emptyList()

        val gap = 14.dpToPx()
        val pages = mutableListOf<BookInfoPage>()
        val current = mutableListOf<BookInfoPageItem>()
        var usedHeight = 0
        var actionItem: BookInfoPageItem? = null
        var reservingFirstPageAction = false

        fun flushCurrent() {
            if (current.isNotEmpty()) {
                pages += BookInfoPage(BookInfoPageKind.NORMAL_COMPONENTS, current.toList())
                current.clear()
                reservingFirstPageAction = false
                usedHeight = 0
            }
        }

        fun appendActionToFirstPage() {
            val action = actionItem ?: return
            if (pages.isEmpty()) {
                pages += BookInfoPage(BookInfoPageKind.NORMAL_COMPONENTS, listOf(action.copy(topMargin = 0, fillBefore = true)))
                return
            }
            val first = pages.first()
            if (first.kind != BookInfoPageKind.NORMAL_COMPONENTS) {
                pages.add(0, BookInfoPage(BookInfoPageKind.NORMAL_COMPONENTS, listOf(action.copy(topMargin = 0, fillBefore = true))))
                return
            }
            val items = first.items.toMutableList()
            val used = items.sumOf { it.topMargin + it.height }
            val topMargin = if (items.isEmpty()) 0 else gap
            val remaining = pageHeight - used - topMargin
            val fixedAction = action.copy(height = action.height.coerceAtMost(remaining.coerceAtLeast(0)), topMargin = topMargin, fillBefore = true)
            if (fixedAction.height > 0) {
                items += fixedAction
                pages[0] = first.copy(items = items)
            }
        }

        binding.flAction.visibility = View.VISIBLE
        val actionHeight = measureBookInfoComponent(binding.flAction, pageWidth, 0).coerceAtMost(pageHeight)
        if (actionHeight > 0) {
            val reservedTopMargin = gap
            actionItem = BookInfoPageItem(BookInfoComponentType.ACTIONS, binding.flAction, actionHeight, reservedTopMargin)
            usedHeight = actionHeight + reservedTopMargin
            reservingFirstPageAction = true
        }

        orderedComponents.forEach { item ->
            val view = componentViews[item.type] ?: return@forEach
            view.visibility = View.VISIBLE
            if (item.type == BookInfoComponentType.CATALOG) {
                flushCurrent()
                val height = measureBookInfoComponent(view, pageWidth, pageHeight)
                pages += BookInfoPage(
                    BookInfoPageKind.FULL_CATALOG,
                    listOf(BookInfoPageItem(item.type, view, height, 0))
                )
                return@forEach
            }
            val topMargin = if (current.isEmpty()) 0 else gap
            val naturalHeight = measureBookInfoComponent(view, pageWidth, 0)
            val remaining = pageHeight - usedHeight - topMargin
            val height = naturalHeight.coerceAtMost(remaining.coerceAtLeast(0))

            if (current.isNotEmpty() && (remaining <= 0 || height <= 0 || usedHeight + topMargin + height > pageHeight)) {
                flushCurrent()
            }

            if (current.isEmpty() && reservingFirstPageAction && height <= 0) {
                appendActionToFirstPage()
                actionItem = null
                reservingFirstPageAction = false
                usedHeight = 0
            }
            val nextTopMargin = if (current.isEmpty()) 0 else gap
            val nextRemaining = pageHeight - usedHeight - nextTopMargin
            val nextHeight = naturalHeight.coerceAtMost(nextRemaining.coerceAtLeast(0))
            if (nextHeight <= 0) return@forEach
            current += BookInfoPageItem(item.type, view, nextHeight, nextTopMargin)
            usedHeight += nextTopMargin + nextHeight
        }
        flushCurrent()
        appendActionToFirstPage()
        return pages
    }

    private fun buildLandscapeBookInfoPages(
        orderedComponents: List<BookInfoComponentItem>,
        componentViews: Map<BookInfoComponentType, View?>
    ): List<BookInfoPage> {
        val pageHeight = bookInfoPageContentHeight()
        val fullWidth = bookInfoPageContentWidth()
        if (pageHeight <= 0 || fullWidth <= 0) return emptyList()

        val gap = 14.dpToPx()
        val columnWidth = ((fullWidth - gap).coerceAtLeast(1) / 2).coerceAtLeast(1)
        val pages = mutableListOf<BookInfoPage>()
        var left = mutableListOf<BookInfoPageItem>()
        var right = mutableListOf<BookInfoPageItem>()
        var column = 0
        var usedHeight = 0
        var actionItem: BookInfoPageItem? = null

        fun currentItems(): MutableList<BookInfoPageItem> = if (column == 0) left else right

        fun flushPage() {
            if (left.isNotEmpty() || right.isNotEmpty()) {
                pages += BookInfoPage(BookInfoPageKind.NORMAL_COMPONENTS, left.toList(), right.toList())
            }
            left = mutableListOf()
            right = mutableListOf()
            column = 0
            usedHeight = 0
        }

        fun nextColumn() {
            if (column == 0) {
                column = 1
                usedHeight = right.sumOf { it.topMargin + it.height }
            } else {
                flushPage()
            }
        }

        fun addMeasuredItem(item: BookInfoComponentItem, view: View, forceFullHeight: Boolean = false) {
            val items = currentItems()
            val topMargin = if (items.isEmpty()) 0 else gap
            val naturalHeight = if (forceFullHeight) {
                pageHeight
            } else {
                measureBookInfoComponent(view, columnWidth, 0)
            }
            var remaining = pageHeight - usedHeight - topMargin
            if (items.isNotEmpty() && (remaining <= 0 || naturalHeight > remaining)) {
                nextColumn()
                addMeasuredItem(item, view, forceFullHeight)
                return
            }
            if (items.isEmpty() && naturalHeight > pageHeight && !forceFullHeight) {
                remaining = pageHeight
            }
            val height = naturalHeight.coerceAtMost(remaining.coerceAtLeast(0))
            if (height <= 0) {
                nextColumn()
                addMeasuredItem(item, view, forceFullHeight)
                return
            }
            val targetItems = currentItems()
            val itemTopMargin = if (targetItems.isEmpty()) 0 else gap
            targetItems += BookInfoPageItem(item.type, view, height, itemTopMargin)
            usedHeight += itemTopMargin + height
        }

        fun appendActionToFirstPage() {
            val action = actionItem ?: return
            if (pages.isEmpty()) {
                pages += BookInfoPage(
                    BookInfoPageKind.NORMAL_COMPONENTS,
                    listOf(action.copy(topMargin = 0, fillBefore = true)),
                    emptyList()
                )
                return
            }
            val first = pages.first()
            val leftUsed = first.items.sumOf { it.topMargin + it.height }
            val rightUsed = first.rightItems.sumOf { it.topMargin + it.height }
            val targetRight = rightUsed < leftUsed
            val target = if (targetRight) first.rightItems.toMutableList() else first.items.toMutableList()
            val used = if (targetRight) rightUsed else leftUsed
            val topMargin = if (target.isEmpty()) 0 else gap
            val remaining = pageHeight - used - topMargin
            val fixedAction = action.copy(height = action.height.coerceAtMost(remaining.coerceAtLeast(0)), topMargin = topMargin, fillBefore = true)
            if (fixedAction.height <= 0) return
            target += fixedAction
            pages[0] = if (targetRight) {
                first.copy(rightItems = target)
            } else {
                first.copy(items = target)
            }
        }

        binding.flAction.visibility = View.VISIBLE
        val actionHeight = measureBookInfoComponent(binding.flAction, columnWidth, 0).coerceAtMost(pageHeight)
        if (actionHeight > 0) {
            actionItem = BookInfoPageItem(BookInfoComponentType.ACTIONS, binding.flAction, actionHeight, gap)
        }

        orderedComponents.forEach { item ->
            val view = componentViews[item.type] ?: return@forEach
            view.visibility = View.VISIBLE
            if (item.type == BookInfoComponentType.CATALOG) {
                if (currentItems().isNotEmpty()) {
                    nextColumn()
                }
                addMeasuredItem(item, view, forceFullHeight = true)
                nextColumn()
            } else {
                addMeasuredItem(item, view)
            }
        }
        flushPage()
        appendActionToFirstPage()
        return pages
    }
    private fun measureBookInfoComponent(view: View, width: Int, targetHeight: Int): Int {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = if (targetHeight > 0) {
            View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }
        view.measure(widthSpec, heightSpec)
        return if (targetHeight > 0) targetHeight else view.measuredHeight.coerceAtLeast(1)
    }


    private fun isBookInfoLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun bookInfoPageHorizontalPadding(): Int {
        return if (isBookInfoLandscape()) 18.dpToPx() else 16.dpToPx()
    }

    private fun bookInfoPageTopPadding(): Int {
        return if (isBookInfoLandscape()) 14.dpToPx() else 16.dpToPx()
    }

    private fun bookInfoPageBottomPadding(): Int {
        return if (isBookInfoLandscape()) 14.dpToPx() else 12.dpToPx()
    }

    private fun bookInfoPageContentWidth(): Int {
        val horizontalPadding = bookInfoPageHorizontalPadding() * 2
        return (bookInfoPager.width - horizontalPadding).coerceAtLeast(1)
    }

    private fun bookInfoPageContentHeight(): Int {
        val verticalPadding = bookInfoPageTopPadding() + bookInfoPageBottomPadding()
        return (bookInfoPager.height - verticalPadding).coerceAtLeast(1)
    }

    private fun List<BookInfoPage>.sameBookInfoLayout(other: List<BookInfoPage>): Boolean {
        if (size != other.size) return false
        return indices.all { index ->
            val oldPage = this[index]
            val newPage = other[index]
            oldPage.kind == newPage.kind &&
                oldPage.items.sameBookInfoItems(newPage.items) &&
                oldPage.rightItems.sameBookInfoItems(newPage.rightItems)
        }
    }

    private fun List<BookInfoPageItem>.sameBookInfoItems(other: List<BookInfoPageItem>): Boolean {
        if (size != other.size) return false
        return indices.all { index ->
            val oldItem = this[index]
            val newItem = other[index]
            oldItem.type == newItem.type &&
                oldItem.height == newItem.height &&
                oldItem.topMargin == newItem.topMargin &&
                oldItem.fillBefore == newItem.fillBefore
        }
    }

    private fun childScrollTouchListener(): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var direction = 0
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    direction = 0
                    requestBookInfoPagerDisallowIntercept(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    if (direction == 0) {
                        direction = when {
                            dy > touchSlop && dy >= dx -> 2
                            dx > touchSlop && dx > dy * 1.35f -> 1
                            else -> 0
                        }
                    }
                    requestBookInfoPagerDisallowIntercept(direction != 1)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    direction = 0
                    requestBookInfoPagerDisallowIntercept(false)
                }
            }
            false
        }
    }

    private fun webIntroTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (::bookInfoPager.isInitialized) {
                        bookInfoPager.isUserInputEnabled = false
                    }
                    requestBookInfoPagerDisallowIntercept(true)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    view.postDelayed({
                        requestBookInfoPagerDisallowIntercept(false)
                        if (::bookInfoPager.isInitialized) {
                            bookInfoPager.isUserInputEnabled = true
                        }
                    }, 160L)
                }
            }
            false
        }
    }

    private fun requestBookInfoPagerDisallowIntercept(disallow: Boolean) {
        binding.scrollView.requestDisallowInterceptTouchEvent(disallow)
        if (::bookInfoPager.isInitialized) {
            bookInfoPager.requestDisallowInterceptTouchEvent(disallow)
        }
        binding.refreshLayout.requestDisallowInterceptTouchEvent(disallow)
    }
    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_info, menu)
        editMenuItem = menu.findItem(R.id.menu_edit)
        menuCustomBtn = menu.findItem(R.id.menu_custom_btn).also {
            it.isVisible = viewModel.hasCustomBtn
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_can_update)?.isChecked =
            viewModel.bookData.value?.canUpdate ?: true
        menu.findItem(R.id.menu_split_long_chapter)?.isChecked =
            viewModel.bookData.value?.getSplitLongChapter() ?: true
        menu.findItem(R.id.menu_login)?.isVisible =
            !viewModel.bookSource?.loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_set_source_variable)?.isVisible =
            viewModel.bookSource != null
        menu.findItem(R.id.menu_set_book_variable)?.isVisible =
            viewModel.bookSource != null
        menu.findItem(R.id.menu_can_update)?.isVisible =
            viewModel.bookSource != null
        menu.findItem(R.id.menu_split_long_chapter)?.isVisible =
            viewModel.bookData.value?.isLocalTxt ?: false
        menu.findItem(R.id.menu_upload)?.isVisible =
            viewModel.bookData.value?.isLocal ?: false
        menu.findItem(R.id.menu_delete_alert)?.isChecked =
            LocalConfig.bookInfoDeleteAlert
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_custom_btn -> {
                callSourceCustomButton()
            }

            R.id.menu_edit -> {
                viewModel.getBook()?.let {
                    infoEditResult.launch {
                        putExtra("bookUrl", it.bookUrl)
                    }
                }
            }

            R.id.menu_share_it -> {
                viewModel.getBook()?.let {
                    val bookJson = GSON.toJson(it)
                    val shareStr = "${it.bookUrl}#$bookJson"
                    SourceCallBack.callBackBtn(
                        this,
                        SourceCallBack.CLICK_SHARE_BOOK,
                        viewModel.bookSource,
                        it,
                        null,
                        result = shareStr
                    ) {
                        val intent = Intent(Intent.ACTION_SEND)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.putExtra(Intent.EXTRA_TEXT, shareStr)
                        intent.type = "text/plain"
                        startActivity(Intent.createChooser(intent, it.name))
                    }
                }
            }

            R.id.menu_refresh -> {
                refreshBook()
            }

            R.id.menu_login -> viewModel.bookSource?.let {
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", it.bookSourceUrl)
                    putExtra("bookUrl", book?.bookUrl)
                }
            }

            R.id.menu_top -> viewModel.topBook()
            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_set_book_variable -> setBookVariable()
            R.id.menu_copy_book_url -> viewModel.getBook()?.let {
                SourceCallBack.callBackBtn(
                    this,
                    SourceCallBack.CLICK_COPY_BOOK_URL,
                    viewModel.bookSource,
                    it,
                    null,
                    result = it.bookUrl
                ) {
                    sendToClip(it.bookUrl)
                }
            }

            R.id.menu_copy_toc_url -> viewModel.getBook()?.let {
                SourceCallBack.callBackBtn(
                    this,
                    SourceCallBack.CLICK_COPY_TOC_URL,
                    viewModel.bookSource,
                    it,
                    null,
                    result = it.tocUrl
                ) {
                    sendToClip(it.tocUrl)
                }
            }

            R.id.menu_can_update -> {
                viewModel.getBook()?.let {
                    it.canUpdate = !it.canUpdate
                    if (viewModel.inBookshelf) {
                        if (!it.canUpdate) {
                            it.removeType(BookType.updateError)
                        }
                        viewModel.saveBook(it)
                    }
                }
            }

            R.id.menu_clear_cache -> viewModel.getBook()?.let {
                    SourceCallBack.callBackBtn(this, SourceCallBack.CLICK_CLEAR_CACHE, viewModel.bookSource, it, null) {
                        viewModel.clearCache(it)
                    }
                }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_split_long_chapter -> {
                upLoading(true)
                viewModel.getBook()?.let {
                    it.setSplitLongChapter(!item.isChecked)
                    viewModel.loadBookInfo(it, false)
                }
                item.isChecked = !item.isChecked
                if (!item.isChecked) longToastOnUi(R.string.need_more_time_load_content)
            }

            R.id.menu_delete_alert -> LocalConfig.bookInfoDeleteAlert = !item.isChecked
            R.id.menu_upload -> {
                viewModel.getBook()?.let { book ->
                    book.getRemoteUrl()?.let {
                        alert(R.string.draw, R.string.sure_upload) {
                            okButton {
                                upLoadBook(book)
                            }
                            cancelButton()
                        }
                    } ?: upLoadBook(book)
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun observeLiveBus() {
        viewModel.actionLive.observe(this) {
            when (it) {
                "selectBooksDir" -> localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                }
            }
        }

        observeEvent<Boolean>(EventBus.REFRESH_BOOK_INFO) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshBook()
            }
        }

        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshToc()
            }
        }
    }

    private fun isEventInsideView(view: View, event: MotionEvent): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return event.rawX >= location[0]
                && event.rawX <= location[0] + view.width
                && event.rawY >= location[1]
                && event.rawY <= location[1] + view.height
    }

    private fun refreshBook() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.refreshBook(it)
        }
    }

    private fun refreshToc() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.loadChapter(it, true, isFromBookInfo = true)
        }
    }

    private fun upLoadBook(
        book: Book,
        bookWebDav: RemoteBookWebDav? = AppWebDav.defaultBookWebDav,
    ) {
        lifecycleScope.launch {
            waitDialog.setText(getString(R.string.book_info_uploading))
            waitDialog.show()
            try {
                bookWebDav
                    ?.upload(book)
                    ?: throw NoStackTraceException(getString(R.string.webdav_not_configured))
                //更新书籍最后更新时间,使之比远程书籍的时间新
                book.lastCheckTime = System.currentTimeMillis()
                viewModel.saveBook(book)
            } catch (e: Exception) {
                toastOnUi(e.localizedMessage)
            } finally {
                waitDialog.dismiss()
            }
        }
    }


    private fun showBook(book: Book) = binding.run {
        showCover(book)
        tvName.text = book.name
        tvAuthor.text = getString(R.string.author_show, book.getRealAuthor())
        tvOrigin.text = getString(R.string.origin_show, book.originName)
        tvLasted.text = getString(R.string.lasted_show, book.latestChapterTitle)
        upReadTime(book.name)
        showBookIntro(book)
        if (book.isWebFile) {
            llToc.gone()
            tvLasted.text = getString(R.string.lasted_show, getString(R.string.downloading))
        } else {
            llToc.gone()
        }
        menuCustomBtn?.isVisible = viewModel.hasCustomBtn
        upTvBookshelf()
        upKinds(book)
        upGroup(book.group)
        updateDetailContentPanelHeight()
        renderCatalogPager(viewModel.chapterListData.value)
        root.post { applyBookInfoComponents() }
    }

    inner class CustomWebViewClient : WebViewClient() {
        private val jsStr = getInjectionString
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                val uri = it.url
                return when (uri.scheme) {
                    "http", "https" -> false
                    "legado", "yuedu" -> {
                        startActivity<OnLineImportActivity> {
                            data = uri
                        }
                        true
                    }

                    else -> {
                        binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                            openUrl(uri)
                        }
                        true
                    }
                }
            }
            return true
        }
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view?.evaluateJavascript(jsStr, null)
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            relayoutUseWebIntro(view)
        }
    }

    private fun showBookIntro(book: Book) {
        val intro = book.getDisplayIntro()
        if (intro?.startsWith("<useweb>") == true) {
            binding.tvIntroToggle.gone()
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 8) {
                introTextView.text = intro
                setIntroContent(introTextView.text)
                return
            }
            val html = intro.substring(8, lastIndex)
            val pooledWebView = this.pooledWebView ?: let{
                val pooledWebView = WebViewPool.acquire(this)
                val webView = pooledWebView.realWebView
                webView.onResume()
                webView.webViewClient = CustomWebViewClient()
                webView.addJavascriptInterface(WebCacheManager, nameCache)
                viewModel.bookSource?.let {
                    webView.addJavascriptInterface(it as BaseSource, nameSource)
                    val webJsExtensions = WebJsExtensions(it, null, webView)
                    webView.addJavascriptInterface(webJsExtensions, nameJava)
                }
                pooledWebView
            }
            val webView = pooledWebView.realWebView
            webView.setBackgroundColor(Color.TRANSPARENT)
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webView.setOnTouchListener(webIntroTouchListener())
            if (initIntroView || this.pooledWebView == null) {
                initIntroView = false
                this.pooledWebView = pooledWebView
                binding.tvIntroContainer.removeAllViews()
                binding.tvIntroContainer.addView(
                    webView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                relayoutUseWebIntro(webView)
            }
            val bookUrl = viewModel.getBook()?.bookUrl
                ?.takeIf { it.startsWith("http", true) }
                ?.substringBefore(",")
            val transparentHtml = """
                <html>
                <head>
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <style>
                    html, body { background: transparent !important; }
                  </style>
                </head>
                <body>$html</body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL(bookUrl, transparentHtml, "text/html", "utf-8", bookUrl)
            return
        }
        if (!initIntroView || pooledWebView != null) {
            destroyWeb()
            binding.tvIntroContainer.removeAllViews()
            binding.tvIntroContainer.addView(
                introScrollView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        if (intro.isNullOrBlank()) {
            introTextView.text = ""
            introRawText = ""
            binding.tvIntroToggle.gone()
            return
        }
        val tvIntro = introTextView
        if (intro.startsWith("<usehtml>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 9) {
                tvIntro.text = intro
                setIntroContent(tvIntro.text)
                return
            }
            val html = intro.substring(9, lastIndex)
            tvIntro.setHtml(
                html,
                glideImageGetter,
                textViewTagHandler,
                imgOnLongClickListener = {
                    showDialogFragment(PhotoDialog(it, viewModel.bookSource?.bookSourceUrl))
                },
                imgOnClickListener = {
                    viewModel.onButtonClick(this@BookInfoActivity, "info image" , it)
                }
            )
            setIntroContent(tvIntro.text)
        } else if (intro.startsWith("<md>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 4) {
                tvIntro.text = intro
                setIntroContent(tvIntro.text)
                return
            }
            val mark = intro.substring(4, lastIndex)
            lifecycleScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tvIntro.setTextClassifier(TextClassifier.NO_OP)
                }
                val context = this@BookInfoActivity
                val markwon: Markwon
                val markdown = withContext(IO) {
                    markwon = Markwon.builder(context)
                        .usePlugin(
                            GlideImagesPlugin.create(
                                Glide.with(context)
                                    .applyDefaultRequestOptions(
                                        RequestOptions()
                                            .override(imgAvailableWidth)
                                            .encodeQuality(88)
                                    )
                            )
                        )
                        .usePlugin(HtmlPlugin.create())
                        .usePlugin(TablePlugin.create(context))
                        .build()
                    markwon.toMarkdown(mark)
                }
                tvIntro.setMarkdown(
                    markwon,
                    markdown,
                    imgOnLongClickListener = { source ->
                        showDialogFragment(PhotoDialog(source, viewModel.bookSource?.bookSourceUrl))
                    }
                )
                setIntroContent(tvIntro.text)
            }
        } else {
            tvIntro.text = intro
            setIntroContent(tvIntro.text)
        }
    }

    private fun setIntroContent(content: CharSequence) {
        introRawText = content
        applyIntroContent()
    }

    private fun applyIntroContent() {
        val tvIntro = introTextView
        binding.tvIntroToggle.gone()
        tvIntro.maxLines = Int.MAX_VALUE
        val rawText = introRawText
        if (rawText.isEmpty()) {
            tvIntro.text = ""
            return
        }
        tvIntro.text = rawText
        introScrollView.scrollTo(0, 0)
    }

    private fun upKinds(book: Book) = binding.run {
        lifecycleScope.launch {
            var kinds = book.getKindList()
            if (book.isLocal) {
                withContext(IO) {
                    val size = FileDoc.fromFile(book.bookUrl).size
                    if (size > 0) {
                        kinds = kinds.toMutableList()
                        kinds.add(ConvertUtils.formatFileSize(size))
                    }
                }
            }
            if (kinds.isEmpty()) {
                lbKind.gone()
            } else {
                lbKind.visible()
                val source = viewModel.bookSource
                if (source == null) {
                    lbKind.setLabels(kinds)
                    return@launch
                }
                lbKind.setLabels(
                    kinds,
                    { kind ->
                        SourceCallBack.callBackBtn(
                            this@BookInfoActivity,
                            SourceCallBack.CLICK_BOOK_LABEL,
                            source,
                            book,
                            null,
                            result = kind
                        ) {
                            SearchActivity.start(this@BookInfoActivity, source, kind)
                        }
                    },
                    { kind ->
                        SourceCallBack.callBackBtn(
                            this@BookInfoActivity,
                            SourceCallBack.LONG_CLICK_BOOK_LABEL,
                            source,
                            book,
                            null,
                            result = kind
                        )
                        true
                    }
                )
            }
        }
    }

    private fun showCover(book: Book) {
        binding.ivCover.load(book, false) {
            applyBookInfoBackground()
        }
    }

    private fun applyBookInfoBackground() {
        binding.bgBook.setBackgroundColor(backgroundColor)
        if (AppConfig.isEInkMode) return
        val detailBg = getPrefString(
            if (AppConfig.isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage
        )
        if (detailBg == lastBookInfoBgPath) return
        lastBookInfoBgPath = detailBg
        if (!detailBg.isNullOrBlank()) {
            BookCover.loadBlur(this, detailBg, false, null)
                .into(binding.bgBook)
        } else {
            binding.bgBook.setImageDrawable(null)
        }
    }

    private fun upLoading(isLoading: Boolean, chapterList: List<BookChapter>? = null) {
        when {
            isLoading -> {
                binding.tvToc.text = getString(R.string.toc_s, getString(R.string.loading))
            }

            chapterList.isNullOrEmpty() -> {
                binding.tvToc.text = getString(
                    R.string.toc_s,
                    getString(R.string.error_load_toc)
                )
                binding.tvLasted.text = getString(R.string.lasted_show, book?.latestChapterTitle)
            }

            else -> {
                book?.let {
                    binding.tvToc.text = getString(R.string.toc_s, it.durChapterTitle)
                    binding.tvLasted.text = getString(R.string.lasted_show, it.latestChapterTitle)
                }
            }
        }
    }

    private fun initDetailTabs() = binding.run {
        tvTabIntro.setOnClickListener { showDetailPage(DetailPage.INTRO) }
        tvTabToc.setOnClickListener { showDetailPage(DetailPage.TOC) }
        tvTocFull.setOnClickListener { openChapterListSafely() }
        tocScrollView.setOnScrollChangeListener { view, _, scrollY, _, _ ->
            if (isUpdatingTocPreview) return@setOnScrollChangeListener
            val child = tocScrollView.getChildAt(0) ?: return@setOnScrollChangeListener
            when {
                scrollY <= 48.dpToPx() -> prependTocPreviewBatch()
                scrollY + view.height >= child.height - 48.dpToPx() -> appendTocPreviewBatch()
            }
        }
        tocScrollView.setOnTouchListener(childScrollTouchListener())
        showDetailPage(DetailPage.INTRO)
    }

    private fun initCatalogPager() = binding.run {
        rvCatalog.layoutManager = LinearLayoutManager(this@BookInfoActivity)
        rvCatalog.adapter = catalogAdapter
        rvCatalog.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        rvCatalog.setOnTouchListener(childScrollTouchListener())

        etCatalogSearch.doAfterTextChanged { renderCatalogPager(viewModel.chapterListData.value) }
        rvCatalog.doOnLayout { renderCatalogPager(viewModel.chapterListData.value) }
    }

    private fun showDetailPage(page: DetailPage) = binding.run {
        val targetPage = if (detailIntroOnly) DetailPage.INTRO else page
        detailPage = targetPage
        llIntroPage.visibility = if (targetPage == DetailPage.INTRO) View.VISIBLE else View.GONE
        llTocPage.visibility = if (!detailIntroOnly && targetPage == DetailPage.TOC) View.VISIBLE else View.GONE
        tvTabIntro.isSelected = targetPage == DetailPage.INTRO
        tvTabToc.isSelected = targetPage == DetailPage.TOC
        tvTabIntro.setTextColor(if (targetPage == DetailPage.INTRO) accentColor else secondaryTextColor)
        tvTabToc.setTextColor(if (targetPage == DetailPage.TOC) accentColor else secondaryTextColor)
        if (targetPage == DetailPage.TOC) {
            renderTocPreview(viewModel.chapterListData.value)
        } else if (tvIntroContainer.getChildAt(0) is WebView) {
            relayoutUseWebIntro(tvIntroContainer.getChildAt(0) as WebView)
        }
        updateDetailContentPanelHeight()
    }

    private fun renderTocPreview(chapterList: List<BookChapter>?) = binding.run {
        isUpdatingTocPreview = true
        llTocPreview.removeAllViews()
        val chapters = chapterList.orEmpty().filterNot { it.isVolume }
        val currentBook = book
        if (chapters.isEmpty() || currentBook == null) {
            tocPreviewChapters = emptyList()
            tocPreviewStart = 0
            tocPreviewEnd = 0
            llTocPreview.addView(tocPreviewText(getString(R.string.chapter_list_empty), false))
            isUpdatingTocPreview = false
            return@run
        }
        tocPreviewChapters = chapters
        val currentPosition = chapters.indexOfFirst { it.index == currentBook.durChapterIndex }
            .coerceAtLeast(0)
        var start = (currentPosition - tocBatchSize / 2).coerceAtLeast(0)
        var end = (start + tocBatchSize).coerceAtMost(chapters.size)
        if (end - start < tocBatchSize) {
            start = (end - tocBatchSize).coerceAtLeast(0)
        }
        tocPreviewStart = start
        tocPreviewEnd = end
        addTocPreviewRange(start, end)
        tocScrollView.post {
            isUpdatingTocPreview = false
            centerCurrentTocItem(currentBook.durChapterIndex)
        }
    }

    private fun prependTocPreviewBatch() = binding.run {
        val chapters = tocPreviewChapters
        val oldStart = tocPreviewStart
        val newStart = (oldStart - tocBatchSize).coerceAtLeast(0)
        if (chapters.isEmpty() || newStart == oldStart) return@run
        isUpdatingTocPreview = true
        val oldHeight = llTocPreview.height
        addTocPreviewRange(newStart, oldStart, 0)
        tocPreviewStart = newStart
        llTocPreview.post {
            val addedHeight = (llTocPreview.height - oldHeight).coerceAtLeast(0)
            tocScrollView.scrollBy(0, addedHeight)
            isUpdatingTocPreview = false
        }
    }

    private fun appendTocPreviewBatch() = binding.run {
        val chapters = tocPreviewChapters
        val oldEnd = tocPreviewEnd
        val newEnd = (oldEnd + tocBatchSize).coerceAtMost(chapters.size)
        if (chapters.isEmpty() || newEnd == oldEnd) return@run
        isUpdatingTocPreview = true
        addTocPreviewRange(oldEnd, newEnd)
        tocPreviewEnd = newEnd
        llTocPreview.post { isUpdatingTocPreview = false }
    }

    private fun addTocPreviewRange(start: Int, end: Int, insertAt: Int? = null) = binding.run {
        val chapters = tocPreviewChapters
        if (start !in 0..chapters.size || end !in 0..chapters.size || start >= end) return@run
        val currentIndex = book?.durChapterIndex ?: -1
        chapters.subList(start, end).forEachIndexed { offset, chapter ->
            val itemView = tocPreviewText(chapter.title, chapter.index == currentIndex).apply {
                tag = chapter.index
                setOnClickListener { openChapterDirect(chapter) }
            }
            val targetIndex = insertAt?.let { it + offset }
            if (targetIndex == null) {
                llTocPreview.addView(itemView)
            } else {
                llTocPreview.addView(itemView, targetIndex)
            }
        }
    }

    private fun centerCurrentTocItem(currentIndex: Int) = binding.run {
        tocScrollView.post {
            val targetView = (0 until llTocPreview.childCount)
                .asSequence()
                .map(llTocPreview::getChildAt)
                .firstOrNull { it.tag == currentIndex } ?: return@post
            val targetTop = targetView.top - (tocScrollView.height - targetView.height) / 2
            tocScrollView.smoothScrollTo(0, targetTop.coerceAtLeast(0))
        }
    }

    private fun updateDetailContentPanelHeight() = binding.run {
        tvIntroContainer.post {
            renderCatalogPager(viewModel.chapterListData.value)
        }
    }

    private fun relayoutUseWebIntro(webView: WebView? = binding.tvIntroContainer.getChildAt(0) as? WebView) {
        val target = webView ?: return
        if (target.parent !== binding.tvIntroContainer) return
        if (binding.llDetailContentPanel.height <= 0 || binding.tvIntroContainer.height <= 0) {
            binding.root.post { applyBookInfoComponents() }
        }
    }

    private fun tocPreviewText(text: CharSequence, selected: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = if (selected) 14.5f else 13.5f
            typeface = uiTypeface()
            setTextColor(if (selected) accentColor else primaryTextColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 9.dpToPx(), 0, 9.dpToPx())
        }
    }

    private fun renderCatalogPager(chapterList: List<BookChapter>?) = binding.run {
        val chapters = chapterList.orEmpty().filterNot { it.isVolume }
        val query = etCatalogSearch.text?.toString().orEmpty().trim()
        val filtered = if (query.isBlank()) {
            chapters
        } else {
            chapters.filter { it.title.contains(query, ignoreCase = true) }
        }
        catalogAdapter.submitList(filtered)
        updateCatalogPageIndicator(filtered.size, chapters.size)
        if (query.isBlank()) {
            val currentPosition = book?.durChapterIndex?.let { currentIndex ->
                chapters.indexOfFirst { it.index == currentIndex }
            }?.takeIf { it >= 0 } ?: return@run
            rvCatalog.post {
                (rvCatalog.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentPosition, (rvCatalog.height / 3).coerceAtLeast(0))
            }
        }
    }

    private fun updateCatalogPageIndicator(count: Int, total: Int) {
        binding.tvCatalogPage.text = if (total <= 0) {
            getString(R.string.catalog_page_indicator_empty)
        } else {
            getString(R.string.catalog_page_indicator, count, total)
        }
    }

    private inner class BookInfoPageAdapter : RecyclerView.Adapter<BookInfoPageAdapter.Holder>() {
        private var pages: List<BookInfoPage> = emptyList()

        fun submitPages(newPages: List<BookInfoPage>): Boolean {
            val nextPages = newPages.ifEmpty { listOf(BookInfoPage(BookInfoPageKind.NORMAL_COMPONENTS, emptyList())) }
            if (pages.sameBookInfoLayout(nextPages)) {
                pages = nextPages
                return false
            }
            pages = nextPages
            notifyDataSetChanged()
            return true
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val container = LinearLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            return Holder(container)
        }

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(pages[position])
        }

        inner class Holder(
            private val container: LinearLayout
        ) : RecyclerView.ViewHolder(container) {

            fun bind(page: BookInfoPage) {
                container.removeAllViews()
                container.background = null
                val horizontalPadding = bookInfoPageHorizontalPadding()
                container.setPadding(
                    horizontalPadding,
                    bookInfoPageTopPadding(),
                    horizontalPadding,
                    bookInfoPageBottomPadding()
                )
                if (isBookInfoLandscape()) {
                    bindLandscapePage(page)
                } else {
                    container.orientation = LinearLayout.VERTICAL
                    bindColumn(container, page.items)
                }
            }

            private fun bindLandscapePage(page: BookInfoPage) {
                container.orientation = LinearLayout.HORIZONTAL
                val gap = 14.dpToPx()
                val leftColumn = LinearLayout(container.context).apply {
                    orientation = LinearLayout.VERTICAL
                    clipChildren = false
                    clipToPadding = false
                }
                val rightColumn = LinearLayout(container.context).apply {
                    orientation = LinearLayout.VERTICAL
                    clipChildren = false
                    clipToPadding = false
                }
                container.addView(
                    leftColumn,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                )
                container.addView(
                    rightColumn,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginStart = gap
                    }
                )
                bindColumn(leftColumn, page.items)
                bindColumn(rightColumn, page.rightItems)
            }

            private fun bindColumn(parent: LinearLayout, items: List<BookInfoPageItem>) {
                parent.orientation = LinearLayout.VERTICAL
                items.forEach { item ->
                    if (item.fillBefore) {
                        parent.addView(
                            Space(parent.context),
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                0,
                                1f
                            )
                        )
                    }
                    (item.view.parent as? ViewGroup)?.removeView(item.view)
                    item.view.visibility = View.VISIBLE
                    if (item.type == BookInfoComponentType.ACTIONS) {
                        item.view.setPadding(0, item.view.paddingTop, 0, item.view.paddingBottom)
                    }
                    parent.addView(
                        item.view,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            item.height
                        ).apply {
                            topMargin = item.topMargin
                            weight = 0f
                        }
                    )
                }
            }
        }
    }

    private inner class CatalogAdapter : RecyclerView.Adapter<CatalogAdapter.Holder>() {
        private var chapters: List<BookChapter> = emptyList()

        fun submitList(newChapters: List<BookChapter>) {
            chapters = newChapters
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(tocPreviewText("", false))
        }

        override fun getItemCount(): Int = chapters.size.coerceAtLeast(1)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (chapters.isEmpty()) {
                holder.bind(null)
            } else {
                holder.bind(chapters[position])
            }
        }

        inner class Holder(
            private val textView: TextView
        ) : RecyclerView.ViewHolder(textView) {

            fun bind(chapter: BookChapter?) {
                if (chapter == null) {
                    textView.text = getString(R.string.chapter_list_empty)
                    textView.setTextColor(secondaryTextColor)
                    textView.setOnClickListener(null)
                    return
                }
                val selected = chapter.index == (book?.durChapterIndex ?: -1)
                textView.text = chapter.title
                textView.textSize = if (selected) 14.5f else 13.5f
                textView.setTextColor(if (selected) accentColor else primaryTextColor)
                textView.setOnClickListener { openChapterDirect(chapter) }
            }
        }
    }
    private fun upReadTime(bookName: String) {
        lifecycleScope.launch {
            val readTime = withContext(IO) {
                appDb.readRecordDao.getReadTime(bookName) ?: 0L
            }
            binding.tvReadTime.text = "${getString(R.string.reading_time_tag)} ${formatReadDuration(readTime)}"
        }
    }

    private fun formatReadDuration(millis: Long): String {
        val days = millis / (1000 * 60 * 60 * 24)
        val hours = millis % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
        val minutes = millis % (1000 * 60 * 60) / (1000 * 60)
        val seconds = millis % (1000 * 60) / 1000
        val d = if (days > 0) getString(R.string.duration_day, days) else ""
        val h = if (hours > 0) getString(R.string.duration_hour, hours) else ""
        val m = if (minutes > 0) getString(R.string.duration_minute, minutes) else ""
        val s = if (seconds > 0 && days == 0L && hours == 0L) {
            getString(R.string.duration_second, seconds)
        } else {
            ""
        }
        return "$d$h$m$s".ifBlank { getString(R.string.duration_zero) }
    }

    private fun upTvBookshelf() {
        if (viewModel.inBookshelf) {
            binding.tvShelf.text = getString(R.string.remove_from_bookshelf)
        } else {
            binding.tvShelf.text = getString(R.string.add_to_bookshelf)
        }
        editMenuItem?.isVisible = viewModel.inBookshelf
    }

    private fun upGroup(groupId: Long) {
        viewModel.loadGroup(groupId) {
            if (it.isNullOrEmpty()) {
                binding.tvGroup.text = if (book?.isLocal == true) {
                    getString(R.string.group_s, getString(R.string.local_no_group))
                } else {
                    getString(R.string.group_s, getString(R.string.no_group))
                }
            } else {
                binding.tvGroup.text = getString(R.string.group_s, it)
            }
        }
    }

    private fun initViewEvent() = binding.run {
        ivCover.setOnClickListener {
            viewModel.getBook()?.let {
                showDialogFragment(
                    ChangeCoverDialog(it.name, it.author)
                )
            }
        }
        ivCover.setOnLongClickListener {
            viewModel.getBook()?.getDisplayCover()?.let { path ->
                showDialogFragment(PhotoDialog(path, isBook = true))
            }
            true
        }
        tvRead.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (book.isWebFile) {
                    showWebFileDownloadAlert {
                        readBook(it)
                    }
                } else {
                    readBook(book)
                }
            }
        }
        tvShelf.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (viewModel.inBookshelf) {
                    deleteBook()
                } else {
                    if (book.isWebFile) {
                        showWebFileDownloadAlert()
                    } else {
                        viewModel.addToBookshelf {
                            upTvBookshelf()
                        }
                    }
                }
            }
        }
        tvOrigin.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (book.isLocal) return@let
                if (!appDb.bookSourceDao.has(book.origin)) {
                    toastOnUi(R.string.error_no_source)
                    return@let
                }
                editSourceResult.launch {
                    putExtra("sourceUrl", book.origin)
                }
            }
        }
        tvChangeSource.setOnClickListener {
            viewModel.getBook()?.let { book ->
                showDialogFragment(ChangeBookSourceDialog(book.name, book.author))
            }
        }
        tvIntroToggle.gone()
        tvTocView.setOnClickListener { openChapterListSafely() }
        tvChangeGroup.setOnClickListener {
            viewModel.getBook()?.let {
                showDialogFragment(
                    GroupSelectDialog(it.group)
                )
            }
        }
        tvAuthor.setOnClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.CLICK_AUTHOR,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.author
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.author)
                }
            }
        }
        tvAuthor.setOnLongClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.LONG_CLICK_AUTHOR,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.author
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.author)
                }
            }
            true
        }
        tvName.setOnClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.CLICK_BOOK_NAME,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.name
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.name)
                }
            }
        }
        tvName.setOnLongClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.LONG_CLICK_BOOK_NAME,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.name
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.name)
                }
            }
            true
        }
        refreshLayout.isEnabled = false
        refreshLayout.setOnRefreshListener {
            refreshLayout.isRefreshing = false
        }
    }

    private fun callSourceCustomButton() {
        viewModel.bookSource?.customButton?.let {
            viewModel.getBook()?.let { book ->
                SourceCallBack.callBackBtn(
                    this,
                    SourceCallBack.CLICK_CUSTOM_BUTTON,
                    viewModel.bookSource,
                    book,
                    null
                )
            }
        }
    }

    private fun setSourceVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi(R.string.book_source_not_found)
                return@launch
            }
            val comment =
                source.getDisplayVariableComment(getString(R.string.source_variable_hint))
            val variable = withContext(IO) { source.getVariable() }
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_source_variable),
                    source.getKey(),
                    variable,
                    comment
                )
            )
        }
    }

    private fun setBookVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi(R.string.book_source_not_found)
                return@launch
            }
            val book = viewModel.getBook() ?: return@launch
            val variable = withContext(IO) { book.getCustomVariable() }
            val comment = source.getDisplayVariableComment(
                getString(R.string.book_variable_hint)
            )
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_book_variable),
                    book.bookUrl,
                    variable,
                    comment
                )
            )
        }
    }

    override fun setVariable(key: String, variable: String?) {
        when (key) {
            viewModel.bookSource?.getKey() -> viewModel.bookSource?.setVariable(variable)
            viewModel.bookData.value?.bookUrl -> viewModel.bookData.value?.let {
                it.putCustomVariable(variable)
                if (viewModel.inBookshelf) {
                    viewModel.saveBook(it)
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun deleteBook() {
        viewModel.getBook()?.let { book ->
            if (LocalConfig.bookInfoDeleteAlert) {
                alert(
                    titleResource = R.string.draw,
                    messageResource = R.string.sure_del
                ) {
                    var checkBox: CheckBox? = null
                    if (book.isLocal) {
                        checkBox = CheckBox(this@BookInfoActivity).apply {
                            setText(R.string.delete_book_file)
                            isChecked = LocalConfig.deleteBookOriginal
                        }
                        val view = LinearLayout(this@BookInfoActivity).apply {
                            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                            addView(checkBox)
                        }
                        customView { view }
                    }
                    yesButton {
                        if (checkBox != null) {
                            LocalConfig.deleteBookOriginal = checkBox.isChecked
                        }
                        SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, viewModel.bookSource, book) //确认后删除书架
                        viewModel.delBook(LocalConfig.deleteBookOriginal) {
                            setResult(RESULT_OK)
                            finish()
                        }
                    }
                    noButton()
                }
            } else {
                SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, viewModel.bookSource, book) //点按钮直接删除书架
                viewModel.delBook(LocalConfig.deleteBookOriginal) {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun openChapterList() {
        viewModel.getBook()?.let {
            tocActivityResult.launch(it.bookUrl)
        }
    }

    private fun openChapterListSafely() {
        if (viewModel.chapterListData.value.isNullOrEmpty()) {
            toastOnUi(R.string.chapter_list_empty)
            return
        }
        viewModel.getBook()?.let { book ->
            if (!viewModel.inBookshelf) {
                book.addType(BookType.notShelf)
                viewModel.saveBook(book) {
                    viewModel.saveChapterList {
                        openChapterList()
                    }
                }
            } else {
                viewModel.saveChapterList {
                    openChapterList()
                }
            }
        }
    }

    private fun openChapterDirect(chapter: BookChapter) {
        viewModel.getBook()?.let { book ->
            chapterChanged = true
            viewModel.saveBookAtChapter(book, chapter) {
                startReadActivity(book)
            }
        }
    }

    private fun showWebFileDownloadAlert(
        onClick: ((Book) -> Unit)? = null,
    ) {
        val webFiles = viewModel.webFiles
        if (webFiles.isEmpty()) {
            toastOnUi("Unexpected webFileData")
            return
        }
        selector(
            R.string.download_and_import_file,
            webFiles
        ) { _, webFile, _ ->
            if (webFile.isSupported) {
                //更新书籍最后更新时间,使之比远程书籍的时间新
                viewModel.importOrDownloadWebFile<Book>(webFile) {
                    onClick?.invoke(it)
                }
            } else if (webFile.isSupportDecompress) {
                //更新书籍最后更新时间,使之比远程书籍的时间新
                viewModel.importOrDownloadWebFile<Uri>(webFile) { uri ->
                    viewModel.getArchiveFilesName(uri) { fileNames ->
                        if (fileNames.size == 1) {
                            viewModel.importArchiveBook(uri, fileNames[0]) {
                                onClick?.invoke(it)
                            }
                        } else {
                            showDecompressFileImportAlert(uri, fileNames, onClick)
                        }
                    }
                }
            } else {
                alert(
                    title = getString(R.string.draw),
                    message = getString(R.string.file_not_supported, webFile.name)
                ) {
                    neutralButton(R.string.open_fun) {
                        /* download only */
                        viewModel.importOrDownloadWebFile<Uri>(webFile) {
                            openFileUri(it, "*/*")
                        }
                    }
                    noButton()
                }
            }
        }
    }

    private fun showDecompressFileImportAlert(
        archiveFileUri: Uri,
        fileNames: List<String>,
        success: ((Book) -> Unit)? = null,
    ) {
        if (fileNames.isEmpty()) {
            toastOnUi(R.string.unsupport_archivefile_entry)
            return
        }
        selector(
            R.string.import_select_book,
            fileNames
        ) { _, name, _ ->
            viewModel.importArchiveBook(archiveFileUri, name) {
                success?.invoke(it)
            }
        }
    }

    private fun readBook(book: Book) {
        if (!viewModel.inBookshelf) {
            book.addType(BookType.notShelf)
            viewModel.saveBook(book) {
                viewModel.saveChapterList {
                    startReadActivity(book)
                }
            }
        } else {
            viewModel.saveBook(book) {
                startReadActivity(book)
            }
        }
    }

    private fun startReadActivity(book: Book) {
        when {
            book.isAudio -> readBookResult.launch(
                Intent(this, AudioPlayActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
            )
            book.isVideo -> readBookResult.launch(
                Intent(this, VideoPlayerActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
            )

            else -> readBookResult.launch(
                Intent(
                    this,
                    when {
                        !book.isLocal && book.isImage && AppConfig.showMangaUi -> ReadMangaActivity::class.java
                        else -> ReadBookActivity::class.java
                    }
                )
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
                    .putExtra("chapterChanged", chapterChanged)
            )
        }
    }

    override val oldBook: Book?
        get() = viewModel.bookData.value

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        viewModel.changeTo(source, book, toc)
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.bookData.value?.let { book ->
            book.customCoverUrl = coverUrl
            showCover(book)
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            }
        }
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        upGroup(groupId)
        viewModel.getBook()?.let { book ->
            book.group = groupId
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            } else if (groupId > 0) {
                viewModel.addToBookshelf {
                    upTvBookshelf()
                }
            }
        }
    }

    private fun upWaitDialogStatus(isShow: Boolean) {
        val showText = "Loading....."
        if (isShow) {
            waitDialog.run {
                setText(showText)
                show()
            }
        } else {
            waitDialog.dismiss()
        }
    }

     override fun onStart() {
         super.onStart()
         if (initGetter) {
             glideImageGetter.start()
         }
     }

     override fun onStop() {
         super.onStop()
         if (initGetter) {
             glideImageGetter.stop()
         }
     }

    override fun onDestroy() {
        destroyWeb()
        super.onDestroy()
        if (initGetter) {
            glideImageGetter.clear()
        }
    }

    private fun destroyWeb() {
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }

}
