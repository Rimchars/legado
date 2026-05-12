package io.legado.app.ui.book.info

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.legado.app.ui.widget.text.ScrollTextView
import android.view.textclassifier.TextClassifier
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
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

    companion object {
        private const val COLLAPSED_INTRO_LINES = 8
        private const val COLLAPSED_INTRO_CHARS = 220
    }

    private enum class DetailPage {
        INTRO, TOC
    }

    private val tocBatchSize = 30
    private var tocPreviewChapters: List<BookChapter> = emptyList()
    private var tocPreviewStart = 0
    private var tocPreviewEnd = 0
    private var isUpdatingTocPreview = false
    private val catalogAdapter by lazy { CatalogPagerAdapter() }
    private var detailPanelLastHeight = -1

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
    private var introExpanded = false
    private var introRawText: CharSequence = ""

    override val binding by viewBinding(ActivityBookInfoBinding::inflate)
    override val viewModel by viewModels<BookInfoViewModel>()
    private var initIntroView = false
    private val introTextView by lazy {
        initIntroView = true
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_book_intro, binding.tvIntroContainer, false) as ScrollTextView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            view.revealOnFocusHint = false
        }
        view.typeface = uiTypeface()
        view
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

    @SuppressLint("PrivateResource")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundResource(R.color.transparent)
        binding.refreshLayout?.setColorSchemeColors(accentColor)
        binding.arcView?.setBgColor(backgroundColor)
        binding.llInfo.setBackgroundResource(R.color.transparent)
        binding.ivCoverC.setCardBackgroundColor(Color.TRANSPARENT)
        applyUiCorners()
        applyBookInfoTypography()
        applyBookInfoComponents()
        binding.flAction.setBackgroundResource(R.color.transparent)
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
        listOfNotNull(tvTabIntro, tvTabToc, tvTabInfo, tvIntroToggle, tvTocFull).forEach {
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

    private fun applyBookInfoComponents() = binding.run {
        val componentViews = mapOf<BookInfoComponentType, View?>(
            BookInfoComponentType.HEADER to llDetailPanel,
            BookInfoComponentType.META to llInfoPage,
            BookInfoComponentType.DETAIL to llDetailContentPanel,
            BookInfoComponentType.CATALOG to llCatalogPanel
        )
        val orderedComponents = BookInfoComponentConfig.load()
        if (isLandscapeComponentLayout()) {
            applyLandscapeBookInfoComponents(orderedComponents, componentViews)
            updateDetailContentPanelHeight()
            return@run
        }
        orderedComponents.forEach { item ->
            val componentView = componentViews[item.type] ?: return@forEach
            componentView.visibility = if (item.enabled) View.VISIBLE else View.GONE
            if (componentView.parent === llInfo) {
                llInfo.removeView(componentView)
            }
            llInfo.addView(componentView)
        }
        updateDetailContentPanelHeight()
    }

    private fun isLandscapeComponentLayout(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                findViewById<View>(R.id.ll_info_left) != null &&
                findViewById<View>(R.id.ll_info_right) != null
    }

    private fun applyLandscapeBookInfoComponents(
        orderedComponents: List<BookInfoComponentItem>,
        componentViews: Map<BookInfoComponentType, View?>
    ) {
        val leftColumn = findViewById<LinearLayout>(R.id.ll_info_left) ?: return
        val rightColumn = findViewById<LinearLayout>(R.id.ll_info_right) ?: return
        val leftScroll = findViewById<View>(R.id.scroll_view)
        val divider = findViewById<View>(R.id.book_info_land_divider)
        val actionView = findViewById<View>(R.id.fl_action)
        val leftItems = orderedComponents.filterNot { isRightBookInfoComponent(it.type) }
        val rightItems = orderedComponents.filter { isRightBookInfoComponent(it.type) }
        val headerVisible = leftItems.any { it.type == BookInfoComponentType.HEADER && it.enabled } &&
                componentViews[BookInfoComponentType.HEADER] != null
        findViewById<View>(R.id.iv_cover_c)?.visibility = if (headerVisible) View.VISIBLE else View.GONE
        leftItems.forEach { item ->
            val componentView = componentViews[item.type] ?: return@forEach
            componentView.visibility = if (item.enabled) View.VISIBLE else View.GONE
            (componentView.parent as? ViewGroup)?.removeView(componentView)
            leftColumn.addView(componentView)
        }
        resetLandscapeComponentMargins(leftColumn)
        rightItems.forEach { item ->
            val componentView = componentViews[item.type] ?: return@forEach
            componentView.visibility = if (item.enabled) View.VISIBLE else View.GONE
            (componentView.parent as? ViewGroup)?.removeView(componentView)
            val actionIndex = rightColumn.indexOfChild(actionView).takeIf { it >= 0 } ?: rightColumn.childCount
            rightColumn.addView(componentView, actionIndex)
        }
        resetLandscapeRightComponentMargins(rightColumn, actionView)
        val hasLeftContent = leftItems.any { it.enabled && componentViews[it.type] != null }
        leftScroll?.visibility = if (hasLeftContent) View.VISIBLE else View.GONE
        divider?.visibility = if (hasLeftContent) View.VISIBLE else View.GONE
        rightColumn.visibility = View.VISIBLE
    }

    private fun isRightBookInfoComponent(type: BookInfoComponentType): Boolean {
        return type == BookInfoComponentType.DETAIL || type == BookInfoComponentType.CATALOG
    }

    private fun resetLandscapeComponentMargins(leftColumn: LinearLayout) {
        var visibleIndex = 0
        for (index in 0 until leftColumn.childCount) {
            val child = leftColumn.getChildAt(index)
            child.updateLayoutParams<LinearLayout.LayoutParams> {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                height = LinearLayout.LayoutParams.WRAP_CONTENT
                weight = 0f
                topMargin = if (child.visibility == View.VISIBLE && visibleIndex++ > 0) 12.dpToPx() else 0
            }
        }
    }

    private fun resetLandscapeRightComponentMargins(rightColumn: LinearLayout, actionView: View?) {
        var visibleIndex = 0
        for (index in 0 until rightColumn.childCount) {
            val child = rightColumn.getChildAt(index)
            if (child === actionView) continue
            if (child.id != R.id.ll_detail_content_panel && child.id != R.id.ll_catalog_panel) continue
            child.updateLayoutParams<LinearLayout.LayoutParams> {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                height = 0
                weight = if (child.visibility == View.VISIBLE) 1f else 0f
                topMargin = if (child.visibility == View.VISIBLE && visibleIndex++ > 0) 12.dpToPx() else 0
            }
        }
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (initIntroView && ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it === introTextView && introTextView.hasSelection()) {
                    it.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
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
            view?.post {
                binding.tvIntroContainer.requestLayout()
                updateDetailContentPanelHeight()
            }
        }
    }

    private fun showBookIntro(book: Book) {
        introExpanded = false
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
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            webView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE -> binding.scrollView?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> binding.scrollView?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            if (initIntroView || this.pooledWebView == null) {
                initIntroView = false
                this.pooledWebView = pooledWebView
                binding.tvIntroContainer.removeAllViews()
                binding.tvIntroContainer.addView(webView)
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
            binding.tvIntroContainer.addView(introTextView)
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
        applyIntroCollapseState()
    }

    private fun applyIntroCollapseState() {
        val tvIntro = introTextView
        binding.tvIntroToggle.gone()
        tvIntro.maxLines = Int.MAX_VALUE
        val rawText = introRawText
        if (rawText.isEmpty()) {
            tvIntro.text = ""
            return
        }
        val lineBreakCount = rawText.count { it == '\n' }
        val shouldShowToggle = introExpanded ||
                lineBreakCount >= COLLAPSED_INTRO_LINES ||
                rawText.length > COLLAPSED_INTRO_CHARS
        if (!shouldShowToggle) {
            tvIntro.text = rawText
            tvIntro.movementMethod = null
            return
        }
        val displayText = SpannableStringBuilder()
        if (introExpanded) {
            displayText.append(rawText)
        } else {
            val end = minOf(rawText.length, COLLAPSED_INTRO_CHARS)
            displayText.append(rawText.subSequence(0, end).trimEnd())
            if (end < rawText.length) {
                displayText.append("...")
            }
        }
        val linkStart = displayText.length
        displayText.append(if (introExpanded) "  ${getString(R.string.collapse)}" else "  ${getString(R.string.expand)}")
        displayText.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                introExpanded = !introExpanded
                applyIntroCollapseState()
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.color = accentColor
                ds.isUnderlineText = false
            }
        }, linkStart, displayText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvIntro.text = displayText
        tvIntro.movementMethod = LinkMovementMethod.getInstance()
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
        binding.bgBook.setImageDrawable(null)
        binding.bgBook.setBackgroundColor(backgroundColor)
        if (AppConfig.isEInkMode) return
        val detailBg = getPrefString(
            if (AppConfig.isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage
        )
        if (!detailBg.isNullOrBlank()) {
            BookCover.loadBlur(this, detailBg, false, null)
                .into(binding.bgBook)
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
        tocScrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> scrollView?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> scrollView?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        scrollView?.setOnTouchListener { _, event ->
            detailPage == DetailPage.TOC && event.actionMasked == MotionEvent.ACTION_MOVE
        }
        showDetailPage(DetailPage.INTRO)
    }

    private fun initCatalogPager() = binding.run {
        vpCatalog.adapter = catalogAdapter
        vpCatalog.offscreenPageLimit = 1
        vpCatalog.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateCatalogPageIndicator(position, catalogAdapter.itemCount)
            }
        })
        vpCatalog.doOnLayout { renderCatalogPager(viewModel.chapterListData.value) }
    }

    private fun showDetailPage(page: DetailPage) = binding.run {
        detailPage = page
        llIntroPage.visibility = if (page == DetailPage.INTRO) android.view.View.VISIBLE else android.view.View.GONE
        llTocPage.visibility = if (page == DetailPage.TOC) android.view.View.VISIBLE else android.view.View.GONE
        tvTabIntro.isSelected = page == DetailPage.INTRO
        tvTabToc.isSelected = page == DetailPage.TOC
        tvTabIntro.setTextColor(if (page == DetailPage.INTRO) accentColor else secondaryTextColor)
        tvTabToc.setTextColor(if (page == DetailPage.TOC) accentColor else secondaryTextColor)
        if (page == DetailPage.TOC) {
            renderTocPreview(viewModel.chapterListData.value)
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
        if (isLandscapeComponentLayout()) {
            detailPanelLastHeight = -1
            tvIntroContainer.post {
                (tvIntroContainer.getChildAt(0) as? ScrollTextView)?.refreshScrollBounds()
                renderCatalogPager(viewModel.chapterListData.value)
            }
            return@run
        }
        val panel = llDetailContentPanel
        val action = flAction
        val panelLoc = IntArray(2)
        val actionLoc = IntArray(2)
        panel.getLocationOnScreen(panelLoc)
        action.getLocationOnScreen(actionLoc)
        val bottomGap = 8.dpToPx()
        val minHeight = 220.dpToPx()
        val targetHeight = (actionLoc[1] - panelLoc[1] - bottomGap).coerceAtLeast(minHeight)
        if (targetHeight == detailPanelLastHeight) return@run
        detailPanelLastHeight = targetHeight
        panel.updateLayoutParams<LinearLayout.LayoutParams> {
            height = targetHeight
            weight = 0f
        }
        llCatalogPanel.updateLayoutParams<LinearLayout.LayoutParams> {
            height = targetHeight
            weight = 0f
        }
        tvIntroContainer.post {
            (tvIntroContainer.getChildAt(0) as? ScrollTextView)?.refreshScrollBounds()
            renderCatalogPager(viewModel.chapterListData.value)
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
        if (chapters.isEmpty()) {
            catalogAdapter.submitList(listOf(emptyList()))
            updateCatalogPageIndicator(0, 0)
            return@run
        }
        val pagerHeight = vpCatalog.height
        if (pagerHeight <= 0) {
            updateCatalogPageIndicator(0, 0)
            return@run
        }
        val rowsPerPage = (pagerHeight / 42.dpToPx()).coerceAtLeast(1)
        val pages = chapters.chunked(rowsPerPage)
        catalogAdapter.submitList(pages)
        val currentPosition = book?.durChapterIndex?.let { currentIndex ->
            chapters.indexOfFirst { it.index == currentIndex }
        }?.takeIf { it >= 0 } ?: 0
        val targetPage = (currentPosition / rowsPerPage).coerceIn(0, pages.lastIndex)
        vpCatalog.setCurrentItem(targetPage, false)
        updateCatalogPageIndicator(targetPage, pages.size)
    }

    private fun updateCatalogPageIndicator(position: Int, count: Int) {
        binding.tvCatalogPage.text = if (count <= 0) "0/0" else "${position + 1}/$count"
    }

    private inner class CatalogPagerAdapter : RecyclerView.Adapter<CatalogPagerAdapter.Holder>() {
        private var pages: List<List<BookChapter>> = emptyList()

        fun submitList(newPages: List<List<BookChapter>>) {
            pages = newPages
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val container = LinearLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.VERTICAL
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

            fun bind(chapters: List<BookChapter>) {
                container.removeAllViews()
                if (chapters.isEmpty()) {
                    container.addView(tocPreviewText(getString(R.string.chapter_list_empty), false))
                    return
                }
                val currentIndex = book?.durChapterIndex ?: -1
                chapters.forEach { chapter ->
                    container.addView(tocPreviewText(chapter.title, chapter.index == currentIndex).apply {
                        tag = chapter.index
                        setOnClickListener { openChapterDirect(chapter) }
                    })
                }
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
        tvIntroToggle.setOnClickListener {
            introExpanded = !introExpanded
            applyIntroCollapseState()
        }
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
        refreshLayout?.setOnRefreshListener {
            refreshLayout.isRefreshing = false
            refreshBook()
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
                /* import */
                viewModel.importOrDownloadWebFile<Book>(webFile) {
                    onClick?.invoke(it)
                }
            } else if (webFile.isSupportDecompress) {
                /* 解压筛选后再选择导入项 */
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
