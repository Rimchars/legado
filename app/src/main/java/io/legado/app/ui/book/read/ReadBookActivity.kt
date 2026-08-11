package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.TextPaint
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.get
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.core.view.size
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookParagraphRule
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.AppWebDav
import io.legado.app.help.IntentData
import io.legado.app.help.TTS
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.ai.AiReadAloudRoleState
import io.legado.app.help.book.BookCloudEntryMode
import io.legado.app.help.book.BookCloudEntryModeStore
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.help.book.ReadMenuCustomButtonExecutor
import io.legado.app.help.book.library.LibraryChapterManifestV3
import io.legado.app.help.book.library.LibraryChapterPayloadV3
import io.legado.app.help.book.library.LibraryCloudBackend
import io.legado.app.help.book.library.LibraryCloudChapterVersion
import io.legado.app.help.book.library.LibraryCloudCrypto
import io.legado.app.help.book.library.LibraryCloudKeys
import io.legado.app.help.book.library.LibraryCloudPaths
import io.legado.app.help.book.library.LibraryCloudSession
import io.legado.app.help.book.library.LibraryCloudState
import io.legado.app.help.book.library.LibraryCloudSync
import io.legado.app.help.book.library.LibraryContainerManager
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.help.readaloud.ReadAloudProgressState
import io.legado.app.help.source.getSourceType
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.AndroidAlertBuilder
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.ImageProvider
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonObject
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.epubcore.facade.EpubCoreProvider
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.web.EpubWebSelectionAction
import io.legado.app.model.localBook.MobiFile
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.receiver.TimeBatteryReceiver
import io.legado.app.ui.about.ReadRecordWidgetStore
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.changesource.ChangeChapterSourceDialog
import io.legado.app.ui.book.character.BookCharacterManageActivity
import io.legado.app.ui.book.info.BookInfoStartActivityContract
import io.legado.app.ui.book.read.config.AutoReadDialog
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.BG_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_ACCENT_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_COLOR
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.config.ParagraphRuleManageActivity
import io.legado.app.ui.book.read.config.ParagraphRuleQuickDialog
import io.legado.app.ui.book.read.config.ReadMenuCustomButtonEditActivity
import io.legado.app.ui.book.read.config.ReadMenuButtonManageActivity
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TIP_COLOR
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TIP_DIVIDER_COLOR
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.LottieImageBitmapCache
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.read.epub.EpubReadView
import io.legado.app.ui.book.searchContent.SearchContentActivity
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.config.BubbleManageActivity
import io.legado.app.ui.config.ShareNoteTemplateManageActivity
import io.legado.app.ui.dict.DictDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.PopupAction
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.ui.widget.dialog.CommentWebViewSession
import io.legado.app.utils.ACache
import io.legado.app.utils.BookIntroUtils
import io.legado.app.utils.Debounce
import io.legado.app.utils.LogUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.dpToPx
import io.legado.app.utils.dismissDialogFragment
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.iconItemOnLongClick
import io.legado.app.utils.invisible
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isTrue
import io.legado.app.utils.launch
import io.legado.app.utils.navigationBarGravity
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.spToPx
import io.legado.app.utils.sysScreenOffTime
import io.legado.app.utils.throttle
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import com.script.rhino.runScriptWithContext
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.ui.login.SourceLoginJsExtensions
import java.text.DateFormat
import java.util.Date
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher

/**
 * 阅读界面
 */
class ReadBookActivity : BaseReadBookActivity(),
    View.OnTouchListener,
    ReadView.CallBack,
    TextActionMenu.CallBack,
    ContentTextView.CallBack,
    MenuItem.OnMenuItemClickListener,
    ReadMenu.CallBack,
    SearchMenu.CallBack,
    ReadAloudDialog.CallBack,
    ReadAloudPlayerPanel.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeChapterSourceDialog.CallBack,
    ReadBook.CallBack,
    AutoReadDialog.CallBack,
    TxtTocRuleDialog.CallBack,
    ColorPickerDialogListener,
    LayoutProgressListener {

    private var pendingReadAloudPlayerOpen = false
    private var pendingReadAloudPanelIntentOpen = false

    private val tocActivity =
        registerForActivityResult(TocActivityResult()) {
            it?.let {
                val keepReadAloudPanel = BaseReadAloudService.isRun &&
                        binding.readAloudPlayerPanel.isExpanded()
                val chapterIndex = it[0] as Int
                val chapterPos = it[1] as Int
                if (isEpubCoreMode()) {
                    skipToChapter(chapterIndex)
                } else {
                    viewModel.openChapter(chapterIndex, chapterPos)
                }
                if (keepReadAloudPanel) {
                    binding.readAloudPlayerPanel.post {
                        binding.readAloudPlayerPanel.open(force = false)
                        binding.readAloudPlayerPanel.refresh()
                    }
                }
            }
        }
    private val sourceEditActivity =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upBookSource {
                    upMenuView()
                }
            }
        }
    private val replaceActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                viewModel.replaceRuleChanged()
            }
        }
    private val searchContentActivity =
        registerForActivityResult(StartActivityContract(SearchContentActivity::class.java)) {
            val data = it.data ?: return@registerForActivityResult
            val key = data.getLongExtra("key", System.currentTimeMillis())
            val index = data.getIntExtra("index", 0)
            val searchResult = IntentData.get<SearchResult>("searchResult$key")
            val searchResultList = IntentData.get<List<SearchResult>>("searchResultList$key")
            if (searchResult != null && searchResultList != null) {
                viewModel.searchContentQuery = searchResult.query
                binding.searchMenu.upSearchResultList(searchResultList)
                isShowingSearchResult = true
                viewModel.searchResultIndex = index
                binding.searchMenu.updateSearchResultIndex(index)
                binding.searchMenu.selectedSearchResult?.let { currentResult ->
                    ReadBook.saveCurrentBookProgress() //退出全文搜索恢复此时进度
                    skipToSearch(currentResult)
                    showActionMenu()
                }
            }
        }
    private val bookInfoActivity =
        registerForActivityResult(BookInfoStartActivityContract()) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            } else {
                ReadBook.loadOrUpContent()
            }
        }
    private var lastTextMenuAnchor: ReadAiFloatingPanel.Anchor? = null
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(AppConst.imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }
    private var menu: Menu? = null
    private var modernMenuPopup: ModernActionPopup.Handle? = null
    private var backupJob: Job? = null
    private var tts: TTS? = null
    private var commentWebViewSession: CommentWebViewSession? = null
    private var shareNotePreviewOverlay: ShareNotePreviewOverlay? = null
    @Volatile
    private var commentBrowserOpening = false
    @Volatile
    private var commentBrowserShowing = false
    val textActionMenu: TextActionMenu by lazy {
        TextActionMenu(this, this)
    }
    private val popupAction: PopupAction by lazy {
        PopupAction(this)
    }
    override val isInitFinish: Boolean get() = viewModel.isInitFinish
    override val isScroll: Boolean get() = binding.readView.isScroll
    private val isAutoPage get() = binding.readView.isAutoPage
    override var isShowingSearchResult = false
    override var isSelectingSearchResult = false
        set(value) {
            field = value && isShowingSearchResult
        }
    private val timeBatteryReceiver = TimeBatteryReceiver()
    private var screenTimeOut: Long = 0
    private var loadStates: Boolean = false
    override val pageFactory get() = binding.readView.pageFactory
    override val pageDelegate get() = binding.readView.pageDelegate
    override val headerHeight: Int get() = binding.readView.curPage.headerHeight
    override val imgBgPaddingStart: Int get() = binding.readView.curPage.imgBgPaddingStart
    private val nextPageDebounce by lazy { Debounce { keyPage(PageDirection.NEXT) } }
    private val prevPageDebounce by lazy { Debounce { keyPage(PageDirection.PREV) } }
    private var bookChanged = false
    private var pageChanged = false
    private val handler by lazy { buildMainHandler() }
    private val screenOffRunnable by lazy { Runnable { keepScreenOn(false) } }
    private val executor = ReadBook.executor
    private val upSeekBarThrottle = throttle(200) {
        runOnUiThread {
            upSeekBarProgress()
            binding.readMenu.upSeekBar()
        }
    }

    //恢复跳转前进度对话框的交互结果
    private var confirmRestoreProcess: Boolean? = null
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }
    private var justInitData: Boolean = false
    private var syncDialog: AlertDialog? = null
    private data class EpubCoreNavigationRequest(
        val chapterIndex: Int,
        val resetPageOffset: Boolean
    )

    private data class SelectedParagraphForImage(
        val contentIndex: Int,
        val text: String
    )

    private enum class EpubCorePageEdge {
        Start,
        End,
        Keep
    }

    private enum class EpubCoreNavigationMode {
        Sequential,
        ExplicitReplace
    }

    private enum class EpubCoreScheduleMode(
        val key: String,
        val titleRes: Int,
        val pageBudget: Int,
        val workerCount: Int
    ) {
        Light("light", R.string.epub_core_schedule_mode_light, pageBudget = 10, workerCount = 1),
        Normal("normal", R.string.epub_core_schedule_mode_normal, pageBudget = 15, workerCount = 2),
        Performance("performance", R.string.epub_core_schedule_mode_performance, pageBudget = 20, workerCount = 5);

        companion object {
            fun fromKey(key: String?): EpubCoreScheduleMode {
                return values().firstOrNull { it.key == key } ?: Normal
            }
        }
    }

    private var epubCoreActive = false
    private var epubCorePageCount = 0
    private var epubCoreLoading = false
    private var epubCoreLoadingChapterIndex: Int? = null
    private var epubCorePendingNavigation: EpubCoreNavigationRequest? = null
    private var epubCoreRequestSeq = 0L
    private var epubCoreLoadJob: Job? = null
    private val epubCorePrefetchJobs = mutableListOf<Job>()
    private var epubCoreWaitingForLayout = false
    private var epubCoreSuppressProgressSync = false
    private var epubCoreBoundaryTransition = false
    private var epubCoreForegroundTarget: EpubCoreNavigationRequest? = null
    private var epubCoreCommittedChapterIndex: Int? = null
    private val epubCoreEstimatedPages = ConcurrentHashMap<Int, Int>()
    private var epubCoreBoundaryTransactionId = 0L
    private var epubCoreBoundaryDirection = 0
    private var epubCoreBoundaryTargetEdge = EpubCorePageEdge.Start
    private var libraryCloudSession: LibraryCloudSession? = null
    private var libraryCloudState: LibraryCloudState = LibraryCloudState.DISABLED
    private var lastReaderNightMode = AppConfig.isNightTheme

    @SuppressLint("ClickableViewAccessibility")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        binding.cursorLeft.setColorFilter(accentColor)
        binding.cursorRight.setColorFilter(accentColor)
        binding.cursorLeft.setOnTouchListener(this)
        binding.cursorRight.setOnTouchListener(this)
        binding.readAiPanel.attach(this)
        binding.readAiSummaryPanel.attach(this)
        binding.readAloudPlayerPanel.attach(this, this)
        ReadAloudAppCapsuleHost.updateReadBookPanelActive(binding.readAloudPlayerPanel.isFullPanelActive())
        binding.readAloudPlayerPanel.post {
            consumeGlobalReadAloudPanelOpen()
        }
        binding.epubReadView.setListener(object : EpubReadView.Listener {
            override fun onCenterTap(x: Float, y: Float) {
                showActionMenu()
            }

            override fun onTapAction(action: Int, x: Float, y: Float) {
                handleEpubTapAction(action, x, y)
            }

            override fun onPreviousPage() {
                previousEpubPage()
            }

            override fun onNextPage() {
                nextEpubPage()
            }

            override fun onPageChanged(pageIndex: Int, pageCount: Int) {
                if (binding.epubReadView.currentPage()?.chapterHref?.startsWith("loading:") == true) {
                    binding.epubReadView.post {
                        consumePendingEpubCoreNavigation()
                    }
                    return
                }
                syncEpubCoreProgress(pageIndex, pageCount)
                if (epubCoreBoundaryTransition &&
                    epubCoreLoadingChapterIndex == null &&
                    epubCoreForegroundTarget == null &&
                    epubCoreLoading.not()
                ) {
                    epubCoreBoundaryTransition = false
                    val currentChapterIndex = binding.epubReadView.currentPage()?.chapterIndex ?: return
                    commitEpubCoreDisplayedChapter(currentChapterIndex, pageIndex, epubCoreRequestSeq)
                }
            }

            override fun onPageBoundary(direction: Int) {
                requestEpubCoreChapterByDirection(direction, boundaryTransition = true)
            }

            override fun onTextSelected(startX: Float, topY: Float, endX: Float, bottomY: Float, startBottomY: Float, endBottomY: Float) {
                showEpubTextActionMenu(startX, topY, endX, bottomY, startBottomY, endBottomY)
            }

            override fun onSelectionCleared() {
                hideEpubSelectionUi()
            }

            override fun onWebTextSelectionRequested(
                page: EpubCorePage,
                pageIndex: Int,
                action: EpubWebSelectionAction,
                x: Float,
                y: Float,
                generation: Long,
                pageKey: String
            ): Boolean {
                requestEpubWebSelection(page, pageIndex, action, x, y, generation, pageKey)
                return true
            }
        })
        window.setBackgroundDrawable(null)
        upScreenTimeOut()
        ReadBook.register(this)
        Backup.autoBack(this)
        onBackPressedDispatcher.addCallback(this) {
            if (binding.readAloudPlayerPanel.isExpanded()) {
                binding.readAloudPlayerPanel.close()
                return@addCallback
            }
            if (binding.readAiPanel.isVisible) {
                if (!binding.readAiPanel.exitFullscreenIfNeeded()) {
                    binding.readAiPanel.close()
                }
                return@addCallback
            }
            if (isShowingSearchResult) {
                exitSearchMenu()
                restoreLastBookProcess()
                return@addCallback
            }
            //拦截返回供恢复阅读进度
            if (ReadBook.lastBookProgress != null && confirmRestoreProcess != false) {
                restoreLastBookProcess()
                return@addCallback
            }
            if (isAutoPage) {
                autoPageStop()
                return@addCallback
            }
            if (getPrefBoolean("disableReturnKey") && !menuLayoutIsVisible) {
                return@addCallback
            }
            finish()
        }
    }

    private fun handleEpubTapAction(action: Int, x: Float, y: Float) {
        when (action) {
            -1 -> Unit
            0 -> showActionMenu()
            1 -> nextEpubPage()
            2 -> previousEpubPage()
            3 -> requestEpubCoreChapterByDirection(1)
            4 -> requestEpubCoreChapterByDirection(-1)
            5 -> ReadAloud.prevParagraph(this)
            6 -> ReadAloud.nextParagraph(this)
            7 -> addBookmark()
            8 -> showDialogFragment(ContentEditDialog())
            9 -> changeReplaceRuleState()
            10 -> openChapterList()
            11 -> openSearchActivity(null)
            12 -> ReadBook.syncProgress(
                { progress -> sureNewProgress(progress) },
                { toastOnUi(R.string.upload_book_success) },
                { toastOnUi(R.string.sync_book_progress_success) }
            )
            13 -> {
                if (BaseReadAloudService.isPlay()) {
                    ReadAloud.pause(this)
                } else {
                    ReadAloud.resume(this)
                }
            }
            else -> showActionMenu()
        }
    }

    private fun requestEpubWebSelection(
        page: EpubCorePage,
        pageIndex: Int,
        action: EpubWebSelectionAction,
        x: Float,
        y: Float,
        generation: Long,
        pageKey: String
    ) {
        val book = ReadBook.book?.takeIf { it.isEpub } ?: return
        val config = binding.epubReadView.layoutConfig ?: return
        val requestSeq = epubCoreRequestSeq
        val chapterIndex = page.chapterIndex
        lifecycleScope.launch(IO) {
            val payload = runCatching {
                EpubCoreProvider.selectText(
                    book = book,
                    page = page,
                    chapterIndex = chapterIndex,
                    pageIndex = pageIndex,
                    config = config,
                    action = action,
                    x = x,
                    y = y
                )
            }.onFailure {
                AppLog.putDebug("EPUB Web selection failed: ${it.localizedMessage}", it)
            }.getOrNull()
            withContext(Main.immediate) {
                if (requestSeq != epubCoreRequestSeq || !epubCoreActive) return@withContext
                if (!binding.epubReadView.isSelectionRequestCurrent(generation, pageKey, chapterIndex, pageIndex)) {
                    AppLog.putDebug(
                        "EPUB Web selection stale: action=$action chapter=$chapterIndex page=$pageIndex generation=$generation"
                    )
                    return@withContext
                }
                val anchor = payload?.let { binding.epubReadView.applyWebSelectionPayload(it) }
                if (payload != null) {
                    AppLog.putDebug(
                        "EPUB Web selection apply: action=$action chapter=$chapterIndex page=$pageIndex " +
                            "generation=$generation text=${payload.selectedText.length} rects=${payload.rects.size} " +
                            "hit=${payload.hitX},${payload.hitY} applied=${anchor != null}"
                    )
                }
                if (anchor != null) {
                    binding.epubReadView.deferOrShowSelectionMenu(anchor)
                } else if (action == EpubWebSelectionAction.SelectWord) {
                    AppLog.putDebug("EPUB Web selection empty: chapter=$chapterIndex page=$pageIndex")
                    binding.epubReadView.selectTextAtCanvasFallback(x, y)?.let { fallbackAnchor ->
                        binding.epubReadView.deferOrShowSelectionMenu(fallbackAnchor)
                    }
                }
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        viewModel.initReadBookConfig(intent)
        binding.root.post {
            AppLog.put("read-init: schedule, source=postCreate")
            viewModel.initData(intent)
        }
        justInitData = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val openReadAloudPanel = requestOpenReadAloudPanelFromIntent(intent)
        if (openReadAloudPanel &&
            BaseReadAloudService.isRun &&
            intent.getStringExtra("bookUrl") == ReadBook.book?.bookUrl
        ) {
            openPendingReadAloudPanel()
            return
        }
        if (consumeGlobalReadAloudPanelOpen()) {
            return
        }
        viewModel.initData(intent) {
            if (openReadAloudPanel) {
                openPendingReadAloudPanel()
            }
            consumeGlobalReadAloudPanelOpen()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        upSystemUiVisibility()
        if (hasFocus) {
            binding.readMenu.upBrightnessState()
        } else if (!menuLayoutIsVisible) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val readerNightMode = if (AppConfig.themeMode == "0") {
            newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        } else {
            AppConfig.isNightTheme
        }
        val readerNightModeChanged = readerNightMode != lastReaderNightMode
        lastReaderNightMode = readerNightMode
        upSystemUiVisibility()
        binding.readView.upStatusBar()
        if (epubCoreActive) {
            refreshEpubCoreAfterConfigurationChange()
        } else if (readerNightModeChanged) {
            binding.readView.refreshVisualStyle()
        }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        if (!isTopResumedActivity) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        // 后台冻结时可能错过系统明暗模式切换，回到前台若当前明暗模式与上次渲染不同则补刷阅读页
        val readerNightMode = AppConfig.isNightTheme
        if (readerNightMode != lastReaderNightMode) {
            lastReaderNightMode = readerNightMode
            upSystemUiVisibility()
            binding.readView.upStatusBar()
            if (epubCoreActive) {
                refreshEpubCoreAfterConfigurationChange()
            } else {
                binding.readView.refreshVisualStyle()
            }
        }
        ReadBook.readStartTime = System.currentTimeMillis()
        if (bookChanged) {
            bookChanged = false
            ReadBook.callBack = this
            viewModel.initData(intent)
            justInitData = true
        } else {
            //web端阅读时，app处于阅读界面，本地记录会覆盖web保存的进度，在此处恢复
            ReadBook.webBookProgress?.let {
                ReadBook.setProgress(it)
                ReadBook.webBookProgress = null
            }
        }
        upSystemUiVisibility()
        registerReceiver(timeBatteryReceiver, timeBatteryReceiver.filter)
        binding.readView.upTime()
        screenOffTimerStart()
        // 网络监听，当从无网切换到网络环境时同步进度（注意注册的同时就会收到监听，因此界面激活时无需重复执行同步操作）
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData && ReadBook.inBookshelf) {
                ReadBook.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
        binding.readAloudPlayerPanel.setForegroundActive(true)
        if (pendingReadAloudPanelIntentOpen && BaseReadAloudService.isRun) {
            binding.readAloudPlayerPanel.post {
                openPendingReadAloudPanel()
            }
        }
        binding.readAloudPlayerPanel.post {
            consumeGlobalReadAloudPanelOpen()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.readAloudPlayerPanel.setForegroundActive(false)
        autoPageStop()
        backupJob?.cancel()
        ReadBook.upReadTime(forceWidgetUpdate = true)
        if (isFinishing) {
            ImageProvider.clear()
        } else {
            ImageProvider.trimMemory()
        }
        ReadBook.saveRead()
        ReadBook.cancelPreDownloadTask()
        unregisterReceiver(timeBatteryReceiver)
        upSystemUiVisibility()
        if (ReadBook.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.syncProgress()
            } else {
                ReadBook.uploadProgress()
            }
        }
        justInitData = false
        networkChangedListener.unRegister()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_read, menu)
        menu.iconItemOnLongClick(R.id.menu_change_source) {
            modernMenuPopup = ModernActionPopup.showFromMenu(
                it,
                R.menu.book_read_change_source,
                modernMenuPopup,
                onClick = ::onMenuItemClick
            )
        }
        menu.iconItemOnLongClick(R.id.menu_refresh) {
            modernMenuPopup = ModernActionPopup.showFromMenu(
                it,
                R.menu.book_read_refresh,
                modernMenuPopup,
                onClick = ::onMenuItemClick
            )
        }
        binding.readMenu.refreshMenuColorFilter()
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        this.menu = menu
        upMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_same_title_removed)?.isChecked =
            ReadBook.curTextChapter?.sameTitleRemoved == true
        return super.onMenuOpened(featureId, menu)
    }

    /**
     * 更新菜单
     */
    private fun upMenu() {
        val menu = menu ?: return
        val book = ReadBook.book ?: return
        val onLine = !book.isLocal
        for (i in 0 until menu.size) {
            val item = menu[i]
            when (item.groupId) {
                R.id.menu_group_on_line -> item.isVisible = onLine
                R.id.menu_group_local -> item.isVisible = !onLine
                R.id.menu_group_text -> item.isVisible = book.isLocalTxt
                R.id.menu_group_epub -> {
                    item.isVisible = book.isEpub
                    when (item.itemId) {
                        R.id.menu_del_ruby_tag -> item.isChecked = book.getDelTag(Book.rubyTag)
                        R.id.menu_del_h_tag -> item.isChecked = book.getDelTag(Book.hTag)
                        R.id.menu_epub_schedule_mode -> {
                            item.isVisible = isEpubCoreMode()
                            item.title =
                                "${getString(R.string.epub_core_schedule_mode)} (${getString(currentEpubCoreSchedule().titleRes)})"
                        }
                    }
                }
                else -> when (item.itemId) {
                    R.id.menu_enable_replace -> item.isChecked = book.getUseReplaceRule()
                    R.id.menu_re_segment -> item.isChecked = book.getReSegment()
//                    R.id.menu_enable_review -> {
//                        item.isVisible = BuildConfig.DEBUG
//                        item.isChecked = AppConfig.enableReview
//                    }

                    R.id.menu_reverse_content -> item.isVisible = onLine
                    R.id.menu_paragraph_rule_manage -> item.isVisible = !book.isEpub
                }
            }
        }
        lifecycleScope.launch {
            val show = ReadBook.inBookshelf && withContext(IO) {
                AppWebDav.isOk
            }
            menu.findItem(R.id.menu_get_progress)?.isVisible = show
            menu.findItem(R.id.menu_cover_progress)?.isVisible = show
        }
    }

    /**
     * 菜单
     */
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source,
            R.id.menu_book_change_source -> {
                binding.readMenu.runMenuOut()
                ReadBook.book?.let {
                    showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                }
            }

            R.id.menu_chapter_change_source -> lifecycleScope.launch {
                val book = ReadBook.book ?: return@launch
                val chapter =
                    appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                        ?: return@launch
                binding.readMenu.runMenuOut()
                showDialogFragment(
                    ChangeChapterSourceDialog(book.name, book.author, chapter.index, chapter.title)
                )
            }

            R.id.menu_refresh,
            R.id.menu_refresh_dur -> {
                if (ReadBook.bookSource == null) {
                    upContent()
                } else {
                    ReadBook.book?.let {
                        ReadBook.curTextChapter = null
                        binding.readView.upContent()
                        viewModel.refreshContentDur(it)
                    }
                }
            }

            R.id.menu_refresh_after -> {
                if (ReadBook.bookSource == null) {
                    upContent()
                } else {
                    ReadBook.book?.let {
                        ReadBook.clearTextChapter()
                        binding.readView.upContent()
                        viewModel.refreshContentAfter(it)
                    }
                }
            }

            R.id.menu_refresh_all -> {
                if (ReadBook.bookSource == null) {
                    upContent()
                } else {
                    ReadBook.book?.let {
                        refreshContentAll(it)
                    }
                }
            }

            R.id.menu_download -> showDownloadDialog()
            R.id.menu_add_bookmark -> addBookmark()
            R.id.menu_simulated_reading -> showSimulatedReading()
            R.id.menu_edit_content -> showDialogFragment(ContentEditDialog())
            R.id.menu_update_toc -> ReadBook.book?.let {
                if (it.isEpub) {
                    BookHelp.clearCache(it)
                    EpubFile.clear()
                }
                if (it.isMobi) {
                    MobiFile.clear()
                }
                loadChapterList(it)
            }

            R.id.menu_enable_replace -> changeReplaceRuleState()
            R.id.menu_re_segment -> ReadBook.book?.let {
                it.setReSegment(!it.getReSegment())
                item.isChecked = it.getReSegment()
                ReadBook.saveRead(fullUpdate = true)
                ReadBook.reloadCurrentContent("re-segment")
            }

//            R.id.menu_enable_review -> {
//                AppConfig.enableReview = !AppConfig.enableReview
//                item.isChecked = AppConfig.enableReview
//                ReadBook.loadContent(false)
//            }

            R.id.menu_del_ruby_tag -> ReadBook.book?.let {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    it.addDelTag(Book.rubyTag)
                } else {
                    it.removeDelTag(Book.rubyTag)
                }
                ReadBook.saveRead(fullUpdate = true)
                ReadBook.reloadCurrentContent("delete-ruby-tag")
            }

            R.id.menu_del_h_tag -> ReadBook.book?.let {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    it.addDelTag(Book.hTag)
                } else {
                    it.removeDelTag(Book.hTag)
                }
                ReadBook.saveRead(fullUpdate = true)
                ReadBook.reloadCurrentContent("delete-h-tag")
            }

            R.id.menu_epub_schedule_mode -> showEpubCoreScheduleModeDialog()
            R.id.menu_read_menu_edit -> startActivity<ReadMenuButtonManageActivity>()

            R.id.menu_page_anim -> showPageAnimConfig {
                binding.readView.upPageAnim()
                if (epubCoreActive) {
                    ReadBook.book?.takeIf { it.isEpub }?.let { book ->
                        cancelEpubCoreForegroundHard(book)
                        cancelEpubCorePrefetchHard(book)
                    }
                    loadEpubCoreContent(resetPageOffset = false, keepCurrentPageUntilReady = true)
                } else {
                    ReadBook.relayoutCurrentContent("page-anim")
                }
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_toc_regex -> showDialogFragment(
                TxtTocRuleDialog(ReadBook.book?.tocUrl)
            )

            R.id.menu_reverse_content -> ReadBook.book?.let {
                viewModel.reverseContent(it)
            }

            R.id.menu_set_charset -> showCharsetConfig()
            R.id.menu_image_style -> {
                val imgStyles =
                    arrayListOf(
                        Book.imgStyleDefault, Book.imgStyleFull, Book.imgStyleText,
                        Book.imgStyleSingle
                    )
                selector(
                    R.string.image_style,
                    imgStyles
                ) { _, index ->
                    val imageStyle = imgStyles[index]
                    ReadBook.book?.setImageStyle(imageStyle)
                    if (imageStyle == Book.imgStyleSingle) {
                        ReadBook.book?.setPageAnim(0)  // 切换图片样式single后，自动切换为覆盖
                        binding.readView.upPageAnim()
                    }
                    ReadBook.saveRead(fullUpdate = true)
                    ReadBook.reloadCurrentContent("image-style")
                }
            }

            R.id.menu_get_progress -> ReadBook.book?.let {
                viewModel.syncBookProgress(it) { progress ->
                    sureSyncProgress(progress)
                }
            }

            R.id.menu_cover_progress -> ReadBook.book?.let {
                ReadBook.uploadProgress(true) { toastOnUi(R.string.upload_book_success) }
            }

            R.id.menu_same_title_removed -> {
                ReadBook.book?.let {
                    val contentProcessor = ContentProcessor.get(it)
                    val textChapter = ReadBook.curTextChapter
                    if (textChapter != null
                        && !textChapter.sameTitleRemoved
                        && !BookHelp.getChapterCacheFileNames(it, textChapter.chapter, "nr")
                            .any(contentProcessor.removeSameTitleCache::contains)
                    ) {
                        toastOnUi("未找到可移除的重复标题")
                    }
                }
                viewModel.reverseRemoveSameTitle()
            }

            R.id.menu_effective_replaces -> showDialogFragment<EffectiveReplacesDialog>()

            R.id.menu_paragraph_rule_manage -> ReadBook.book?.let {
                startActivity<ParagraphRuleManageActivity> {
                    putExtra("bookUrl", it.bookUrl)
                }
            }

            R.id.menu_help -> showHelp()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun refreshContentAll(book: Book) {
        viewModel.refreshContentAll(book)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return onCompatOptionsItemSelected(item)
    }

    /**
     * 按键拦截,显示菜单
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !binding.readMenu.canShowMenu) {
                binding.readMenu.runMenuIn()
                return true
            }
            if (!isDown && !binding.readMenu.canShowMenu) {
                binding.readMenu.canShowMenu = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 鼠标滚轮事件
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (0 != (event.source and InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                LogUtils.d("onGenericMotionEvent", "axisValue = $axisValue")
                // 获得垂直坐标上的滚动方向
                if (axisValue < 0.0f) { // 滚轮向下滚
                    mouseWheelPage(PageDirection.NEXT, axisValue)
                } else { // 滚轮向上滚
                    mouseWheelPage(PageDirection.PREV, axisValue)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * 按键事件
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuLayoutIsVisible) {
            return super.onKeyDown(keyCode, event)
        }
        val longPress = event.repeatCount > 0
        when {
            isPrevKey(keyCode) -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            isNextKey(keyCode) -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeyPage(PageDirection.PREV, longPress)) {
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeyPage(PageDirection.NEXT, longPress)) {
                return true
            }

            KeyEvent.KEYCODE_PAGE_UP -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }

            KeyEvent.KEYCODE_SPACE -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * 松开按键事件
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeKeyPage(PageDirection.NONE, false)) {
                    return true
                }
            }

        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * view触摸,文字选择
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean = binding.run {
        if (!binding.readView.isTextSelected && !(epubCoreActive && binding.epubReadView.isTextSelected)) {
            return false
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                textActionMenu.dismiss()
                if (epubCoreActive) {
                    binding.epubReadView.beginSelectionHandleDrag()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (v.id) {
                    R.id.cursor_left -> {
                        if (epubCoreActive) {
                            binding.epubReadView.selectStartMoveOnScreen(event.rawX, event.rawY - cursorLeft.height / 2f)
                        } else if (!readView.curPage.getReverseStartCursor()) {
                            readView.curPage.selectStartMove(
                                event.rawX + cursorLeft.width,
                                event.rawY - cursorLeft.height
                            )
                        } else {
                            readView.curPage.selectEndMove(
                                event.rawX - cursorRight.width,
                                event.rawY - cursorRight.height
                            )
                        }
                    }

                    R.id.cursor_right -> {
                        if (epubCoreActive) {
                            binding.epubReadView.selectEndMoveOnScreen(event.rawX, event.rawY - cursorRight.height / 2f)
                        } else if (readView.curPage.getReverseEndCursor()) {
                            readView.curPage.selectStartMove(
                                event.rawX + cursorLeft.width,
                                event.rawY - cursorLeft.height
                            )
                        } else {
                            readView.curPage.selectEndMove(
                                event.rawX - cursorRight.width,
                                event.rawY - cursorRight.height
                            )
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                readView.curPage.resetReverseCursor()
                if (epubCoreActive) {
                    binding.epubReadView.endSelectionHandleDrag()
                } else {
                    showTextActionMenu()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (epubCoreActive) {
                    binding.epubReadView.cancelSelectionHandleDrag()
                }
            }
        }
        return true
    }

    /**
     * 更新文字选择开始位置
     */
    override fun upSelectedStart(x: Float, y: Float, top: Float) = binding.run {
        cursorLeft.x = x - cursorLeft.width
        cursorLeft.y = y
        cursorLeft.visible(true)
        textMenuPosition.x = x
        textMenuPosition.y = top
    }

    /**
     * 更新文字选择结束位置
     */
    override fun upSelectedEnd(x: Float, y: Float) = binding.run {
        cursorRight.x = x
        cursorRight.y = y
        cursorRight.visible(true)
    }

    /**
     * 取消文字选择
     */
    override fun onCancelSelect() = binding.run {
        if (epubCoreActive) {
            clearEpubSelectionUi()
        } else {
            cursorLeft.invisible()
            cursorRight.invisible()
            textActionMenu.dismiss()
        }
    }

    private fun hideEpubSelectionUi() = binding.run {
        cursorLeft.invisible()
        cursorRight.invisible()
        textActionMenu.dismiss()
    }

    private fun clearEpubSelectionUi() = binding.run {
        textActionMenu.dismiss()
        cursorLeft.invisible()
        cursorRight.invisible()
        epubReadView.clearSelection(notify = false)
    }

    override fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean {
        return binding.readView.onTouchEvent(event)
    }

    /**
     * 显示文本操作菜单
     */
    override fun showTextActionMenu() {
        val navigationBarHeight =
            if (!ReadBookConfig.hideNavigationBar && navigationBarGravity == Gravity.BOTTOM)
                binding.navigationBar.height else 0
        val startX = binding.textMenuPosition.x.toInt()
        val topY = binding.textMenuPosition.y.toInt()
        val endX = binding.cursorRight.x.toInt()
        val startBottomY = binding.cursorLeft.y.toInt() + binding.cursorLeft.height
        val endBottomY = binding.cursorRight.y.toInt() + binding.cursorRight.height
        val centerX = ((startX + endX) / 2f).toInt()
        val bottomY = maxOf(startBottomY, endBottomY)
        lastTextMenuAnchor = ReadAiFloatingPanel.Anchor(
            centerX = centerX,
            topY = topY,
            bottomY = bottomY
        )
        textActionMenu.show(
            binding.root,
            binding.root.height + navigationBarHeight,
            startX,
            topY,
            startBottomY,
            endX,
            endBottomY,
            navigationBarHeight
        )
    }

    private fun showEpubTextActionMenu(
        startX: Float,
        topY: Float,
        endX: Float,
        bottomY: Float,
        startBottomY: Float = bottomY,
        endBottomY: Float = bottomY
    ) = binding.run {
        val navigationBarHeight =
            if (!ReadBookConfig.hideNavigationBar && navigationBarGravity == Gravity.BOTTOM)
                binding.navigationBar.height else 0
        val start = startX.toInt()
        val top = topY.toInt()
        val end = endX.toInt()
        val bottom = endBottomY.toInt()
        cursorLeft.x = startX - cursorLeft.width
        cursorLeft.y = startBottomY
        cursorRight.x = endX
        cursorRight.y = endBottomY
        cursorLeft.visible(true)
        cursorRight.visible(true)
        textMenuPosition.x = (startX + endX) / 2f
        textMenuPosition.y = topY
        lastTextMenuAnchor = ReadAiFloatingPanel.Anchor(
            centerX = ((startX + endX) / 2f).toInt(),
            topY = top,
            bottomY = bottom
        )
        textActionMenu.show(
            root,
            root.height + navigationBarHeight,
            start,
            top,
            endBottomY.toInt(),
            end,
            endBottomY.toInt(),
            navigationBarHeight
        )
    }

    /**
     * 当前选择的文本
     */
    override val selectedText: String
        get() = if (epubCoreActive) {
            binding.epubReadView.getSelectedText()
        } else {
            binding.readView.getSelectText()
        }

    /**
     * 文本选择菜单操作
     */
    override fun onMenuItemSelected(itemId: Int): Boolean {
        when (itemId) {
            R.id.menu_web_search -> {
                showDialogFragment(SelectionWebSearchDialog(selectedText))
                return true
            }

            R.id.menu_aloud -> {
                handleSelectedTextReadAloud()
                return true
            }

            R.id.menu_bookmark -> binding.readView.curPage.let {
                val bookmark = it.createBookmark()
                if (bookmark == null) {
                    toastOnUi(R.string.create_bookmark_error)
                } else {
                    showDialogFragment(BookmarkDialog(bookmark))
                }
                return true
            }

            R.id.menu_replace -> {
                val scopes = arrayListOf<String>()
                ReadBook.book?.name?.let {
                    scopes.add(it)
                }
                ReadBook.bookSource?.bookSourceUrl?.let {
                    scopes.add(it)
                }
                val text = selectedText.lineSequence().map { it.trim() }.joinToString("\n")
                replaceActivity.launch(
                    ReplaceEditActivity.startIntent(
                        this,
                        pattern = text,
                        scope = scopes.joinToString(";")
                    )
                )
                return true
            }

            R.id.menu_search_content -> {
                viewModel.searchContentQuery = selectedText
                openSearchActivity(selectedText)
                return true
            }

            R.id.menu_dict -> {
                showDialogFragment(DictDialog(selectedText))
                return true
            }
            R.id.menu_ask_ai -> {
                askAiBySelection()
                return true
            }
            R.id.menu_generate_image -> {
                generateImageBySelection()
                return true
            }
            R.id.menu_share_image -> {
                showShareNotePreviewOverlay(selectedText)
                return true
            }
        }
        return false
    }

    private fun handleSelectedTextReadAloud() {
        val shouldReadContinuously =
            BaseReadAloudService.isRun || AppConfig.contentSelectSpeakMod == 1
        if (epubCoreActive) {
            if (shouldReadContinuously) {
                toastOnUi(R.string.epub_selection_read_aloud_unsupported)
            } else {
                speak(selectedText)
            }
            return
        }
        if (!shouldReadContinuously) {
            speak(selectedText)
            return
        }
        val position = binding.readView.getSelectedReadPosition()
        if (position == null) {
            toastOnUi(R.string.epub_selection_read_aloud_unsupported)
            return
        }
        ReadAloud.playFromPosition(
            context = this,
            bookUrl = position.bookUrl,
            chapterIndex = position.chapterIndex,
            chapterUrl = position.chapterUrl,
            chapterPosition = position.chapterPosition
        )
    }

    private fun askAiBySelection() {
        val prompt = selectedText.trim()
        if (prompt.isEmpty()) return
        openReadAiPanel(prompt)
    }

    private fun generateImageBySelection() {
        if (epubCoreActive) {
            toastOnUi(R.string.ai_image_insert_failed)
            return
        }
        if (AppConfig.aiCurrentImageProvider == null) {
            toastOnUi(R.string.ai_missing_config)
            return
        }
        val paragraph = currentSelectedParagraphForImage()
        if (paragraph == null) {
            toastOnUi(R.string.ai_image_no_selection)
            return
        }
        val prompt = selectedText.trim().ifBlank { paragraph.text.trim() }
        if (prompt.isBlank()) {
            toastOnUi(R.string.ai_image_no_selection)
            return
        }
        showDialogFragment(
            ReadSelectionImageDialog(
                prompt = prompt,
                paragraphIndex = paragraph.contentIndex,
                paragraphText = paragraph.text
            )
        )
    }

    private fun showShareNoteTemplateDialog(selection: String) {
        val text = selection.trim()
        if (text.isBlank()) return
        showDialogFragment(
            ShareNoteTemplateSelectDialog.create(
                onSelected = { entry -> shareSelectionAsNoteImage(text, entry) },
                onManage = { startActivity<ShareNoteTemplateManageActivity>() }
            )
        )
    }

    private fun showShareNotePreviewOverlay(selection: String) {
        val text = selection.trim()
        if (text.isBlank()) return
        val payload = buildShareNotePayload(text)
        lifecycleScope.launch {
            val entries = runCatching {
                withContext(IO) {
                    ShareNoteTemplateManager.loadEntries()
                }
            }.getOrElse {
                AppLog.put("摘录分享模板加载失败\n${it.localizedMessage}", it, true)
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
                return@launch
            }
            val entry = entries.firstOrNull { it.dirName == ShareNoteTemplateManager.lastDirName() }
                ?: entries.firstOrNull()
            if (entry == null) {
                toastOnUi(R.string.share_note_no_template)
                return@launch
            }
            shareNotePreviewOverlay?.dismiss()
            shareNotePreviewOverlay = ShareNotePreviewOverlay.show(
                activity = this@ReadBookActivity,
                parent = binding.root,
                entry = entry,
                payload = payload
            )
        }
    }

    private fun buildShareNotePayload(text: String): ShareNoteImageRenderer.Payload {
        val book = ReadBook.book
        val now = Date()
        val goalConfig = ReadRecordWidgetStore.loadGoalConfig()
        val readTimeMs = book?.let { currentShareNoteReadTime(it, now.time) } ?: 0L
        val readTimeText = readTimeMs.takeIf { it > 0L }?.let(::formatShareNoteDuration).orEmpty()
        val progressText = currentShareNoteProgressText()
        val tags = book?.shareNoteTags().orEmpty()
        return ShareNoteImageRenderer.Payload(
            generatedAt = DateFormat.getDateTimeInstance().format(now),
            profile = ShareNoteImageRenderer.Profile(
                name = goalConfig.userName.orEmpty().ifBlank { "读者" },
                avatar = goalConfig.avatar
            ),
            book = ShareNoteImageRenderer.Book(
                title = book?.name.orEmpty().ifBlank { getString(R.string.book_name) },
                author = book?.getRealAuthor().orEmpty(),
                description = BookIntroUtils.listIntro(book?.getDisplayIntro()).orEmpty(),
                cover = book?.getDisplayCover()?.let(::normalizeShareNoteCoverUrl),
                type = tags,
                kind = tags,
                tags = tags,
                wordCountText = book?.wordCount.orEmpty(),
                readTimeText = readTimeText,
                readStatusText = readTimeText.takeIf { it.isNotBlank() }?.let { "阅读 $it" }.orEmpty(),
                readProgressText = progressText,
                readProgressPercent = parseShareNoteProgressPercent(progressText),
                lastReadTime = book?.durChapterTime?.takeIf { it > 0L }?.let {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                }.orEmpty()
            ),
            note = ShareNoteImageRenderer.Note(
                createAt = DateFormat.getDateInstance().format(now),
                sectionName = currentShareNoteChapterTitle(),
                description = text
            )
        )
    }

    private fun currentShareNoteReadTime(book: Book, now: Long): Long {
        val saved = appDb.readRecordDao.getReadTime(book.name) ?: 0L
        val live = if (AppConfig.enableReadRecord && ReadBook.book?.bookUrl == book.bookUrl) {
            (now - ReadBook.readStartTime).coerceAtLeast(0L)
        } else {
            0L
        }
        return saved + live
    }

    private fun formatShareNoteDuration(mss: Long): String {
        val days = mss / (1000 * 60 * 60 * 24)
        val hours = mss % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
        val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
        val seconds = mss % (1000 * 60) / 1000
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

    private fun currentShareNoteProgressText(): String {
        return runCatching {
            if (epubCoreActive) {
                val page = binding.epubReadView.currentPage() ?: return@runCatching ""
                val chapterSize = currentShareNoteChapterSize()
                val chapterPageIndex = binding.epubReadView.currentChapterPageIndex()
                val chapterPageCount = binding.epubReadView.currentChapterPageCount()
                shareNoteProgressText(
                    chapterIndex = page.chapterIndex,
                    chapterSize = chapterSize,
                    pageIndex = chapterPageIndex,
                    pageSize = chapterPageCount
                )
            } else {
                currentTextShareNoteProgressText()
            }
        }.getOrDefault("")
    }

    private fun currentTextShareNoteProgressText(): String {
        val chapterSize = currentShareNoteChapterSize()
        if (chapterSize <= 0) return ""
        val textChapter = ReadBook.curTextChapter
        val currentPage = runCatching { binding.readView.curPage.textPage }.getOrNull()
        val chapterIndex = firstValidChapterIndex(
            chapterSize,
            textChapter?.chapter?.index,
            ReadBook.durChapterIndex,
            ReadBook.book?.durChapterIndex,
            currentPage?.takeIf { it.chapterSize > 0 }?.chapterIndex
        )
        val pageSize = textChapter?.pageSize?.takeIf { it > 0 }
            ?: currentPage?.pageSize?.takeIf { it > 0 }
            ?: 0
        val pageIndex = textChapter
            ?.getPageIndexByCharIndex(ReadBook.durChapterPos)
            ?.takeIf { it >= 0 }
            ?: currentPage?.index?.takeIf { it >= 0 }
            ?: 0
        return shareNoteProgressText(chapterIndex, chapterSize, pageIndex, pageSize)
    }

    private fun currentShareNoteChapterSize(): Int {
        return ReadBook.simulatedChapterSize.takeIf { it > 0 }
            ?: ReadBook.chapterSize.takeIf { it > 0 }
            ?: ReadBook.book?.simulatedTotalChapterNum()?.takeIf { it > 0 }
            ?: ReadBook.book?.totalChapterNum?.takeIf { it > 0 }
            ?: 0
    }

    private fun firstValidChapterIndex(chapterSize: Int, vararg candidates: Int?): Int {
        return candidates.firstOrNull { it != null && it in 0 until chapterSize } ?: 0
    }

    private fun parseShareNoteProgressPercent(progressText: String): Float? {
        val valueText = shareNoteProgressPercentRegex.find(progressText)
            ?.groupValues
            ?.getOrNull(1)
            ?: progressText.trim()
        return valueText.replace(',', '.')
            .toFloatOrNull()
            ?.div(100f)
            ?.coerceIn(0f, 1f)
    }

    private fun shareNoteProgressText(
        chapterIndex: Int,
        chapterSize: Int,
        pageIndex: Int,
        pageSize: Int
    ): String {
        if (chapterSize <= 0) return ""
        val safeChapterIndex = chapterIndex.coerceIn(0, chapterSize - 1)
        val safePageSize = pageSize.coerceAtLeast(0)
        val safePageIndex = if (safePageSize > 0) {
            pageIndex.coerceIn(0, safePageSize - 1)
        } else {
            0
        }
        val progress = if (safePageSize <= 0) {
            (safeChapterIndex + 1.0) / chapterSize.toDouble()
        } else {
            safeChapterIndex.toDouble() / chapterSize.toDouble() +
                (safePageIndex + 1.0) / safePageSize.toDouble() / chapterSize.toDouble()
        }.coerceIn(0.0, 1.0)
        return java.text.DecimalFormat("0.0%").format(progress)
    }

    private fun Book.shareNoteTags(): String {
        return customTag?.trim()?.takeIf { it.isNotBlank() }
            ?: kind?.trim()?.takeIf { it.isNotBlank() }
            ?: when {
                isAudio -> "音频"
                isEpub -> "EPUB"
                isMobi -> "MOBI"
                isLocalTxt -> "TXT"
                isLocal -> getString(R.string.local)
                else -> ""
            }
    }

    private fun shareSelectionAsNoteImage(
        text: String,
        entry: ShareNoteTemplateManager.Entry
    ) {
        val payload = buildShareNotePayload(text)
        lifecycleScope.launch {
            toastOnUi("正在生成分享图片")
            kotlin.runCatching {
                ShareNoteImageRenderer.renderShareImage(this@ReadBookActivity, entry, payload)
            }.onSuccess { file ->
                kotlin.runCatching {
                    share(file, "image/png")
                }.onFailure {
                    toastOnUi(it.localizedMessage ?: getString(R.string.error))
                }
            }.onFailure {
                AppLog.put("摘录分享图片生成失败\n${it.localizedMessage}", it, true)
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun currentShareNoteChapterTitle(): String {
        if (epubCoreActive) {
            epubCoreChapterTitle()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ReadBook.curTextChapter?.chapter?.title
            ?: ReadBook.book?.durChapterTitle
            ?: ""
    }

    private fun normalizeShareNoteCoverUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (value.startsWith("http", ignoreCase = true) ||
            value.startsWith("file:", ignoreCase = true) ||
            value.startsWith("content:", ignoreCase = true) ||
            value.startsWith("data:", ignoreCase = true)
        ) {
            return value
        }
        return runCatching {
            File(value).takeIf { it.exists() }?.toURI()?.toString()
        }.getOrNull() ?: value
    }

    private fun currentSelectedParagraphForImage(): SelectedParagraphForImage? {
        val textChapter = ReadBook.curTextChapter ?: return null
        val selectStartPos = binding.readView.curPage.selectStartPos
        if (!selectStartPos.isSelected()) return null
        val page = binding.readView.curPage.relativePage(selectStartPos.relativePagePos)
        val line = page.getLine(selectStartPos.lineIndex)
        if (line.paragraphNum <= 0 || line.isTitle) return null
        val paragraphs = textChapter.getParagraphs(pageSplit = false)
        val paragraph = paragraphs.firstOrNull { it.realNum == line.paragraphNum }
            ?: return null
        if (paragraph.firstLine.isTitle) return null
        val contentIndex = paragraph.sourceIndex.takeIf { it >= 0 } ?: return null
        return SelectedParagraphForImage(
            contentIndex = contentIndex,
            text = paragraph.text
        )
    }

    override fun openReadAssistant() {
        openReadAiPanel("")
    }

    override fun openReadAiSummary() {
        val modelConfig = AppConfig.aiSummaryModelConfig
        if (AppConfig.aiProviderForModel(modelConfig)?.baseUrl.isNullOrBlank()) {
            toastOnUi(R.string.ai_missing_config)
            return
        }
        if (!AppConfig.aiAssistantEnabled) {
            toastOnUi(R.string.ai_not_enabled)
            return
        }
        val book = ReadBook.book ?: run {
            toastOnUi(R.string.book_name)
            return
        }
        val textChapter = ReadBook.curTextChapter ?: run {
            toastOnUi(R.string.chapter_list)
            return
        }
        val chapter = textChapter.chapter
        val content = textChapter.getContent().trim()
        if (content.isBlank()) {
            toastOnUi(R.string.content_empty)
            return
        }
        val anchor = lastTextMenuAnchor
            ?: ReadAiFloatingPanel.Anchor(
                centerX = binding.root.width / 2,
                topY = binding.root.height / 4,
                bottomY = binding.root.height / 3
            )
        binding.readAiSummaryPanel.open(book, chapter, content, anchor)
    }

    private fun openReadAiPanel(prompt: String) {
        val modelConfig = AppConfig.aiAskModelConfig
        if (AppConfig.aiProviderForModel(modelConfig)?.baseUrl.isNullOrBlank()) {
            toastOnUi(R.string.ai_missing_config)
            return
        }
        if (!AppConfig.aiAssistantEnabled) {
            toastOnUi(R.string.ai_not_enabled)
            return
        }
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter?.chapter
        val anchor = lastTextMenuAnchor
            ?: ReadAiFloatingPanel.Anchor(
                centerX = binding.root.width / 2,
                topY = binding.root.height / 3,
                bottomY = binding.root.height / 2
            )
        binding.readAiPanel.open(
            ReadAiFloatingPanel.ReadContext(
                bookUrl = book?.bookUrl.orEmpty().ifBlank { book?.name.orEmpty() },
                bookName = book?.name.orEmpty(),
                author = book?.author.orEmpty(),
                sourceName = ReadBook.bookSource?.bookSourceName.orEmpty(),
                chapterTitle = chapter?.title.orEmpty(),
                chapterIndex = chapter?.index ?: ReadBook.durChapterIndex,
                selectedText = prompt
            ),
            anchor = anchor
        )
    }

    /**
     * 文本选择菜单操作完成
     */
    override fun onMenuActionFinally() = binding.run {
        if (epubCoreActive) {
            clearEpubSelectionUi()
        } else {
            textActionMenu.dismiss()
            readView.cancelSelect()
        }
    }

    private fun speak(text: String) {
        if (tts == null) {
            tts = TTS()
        }
        tts?.speak(text)
    }

    /**
     * 鼠标滚轮翻页
     */
    private fun mouseWheelPage(direction: PageDirection, distance: Float) {
        if (menuLayoutIsVisible || !AppConfig.mouseWheelPage) {
            return
        }
        if (epubCoreActive) {
            keyPage(direction)
            return
        }
        if (binding.readView.isScroll) {
            // 滚动视图时滚动,否则翻页
            (binding.readView.pageDelegate as? ScrollPageDelegate)?.curPage?.scroll((distance * 50).toInt())
        } else {
            keyPageDebounce(direction, mouseWheel = true, longPress = false)
        }
    }

    /**
     * 音量键翻页
     */
    private fun volumeKeyPage(direction: PageDirection, longPress: Boolean): Boolean {
        if (binding.readAloudPlayerPanel.isExpanded()) {
            return false
        }
        if (!AppConfig.volumeKeyPage) {
            return false
        }
        if (!AppConfig.volumeKeyPageOnPlay && BaseReadAloudService.isPlay()) {
            return false
        }
        handleKeyPage(direction, longPress)
        return true
    }

    private fun handleKeyPage(direction: PageDirection, longPress: Boolean) {
        if (AppConfig.keyPageOnLongPress || direction == PageDirection.NONE) {
            keyPage(direction)
        } else {
            keyPageDebounce(direction, longPress = longPress)
        }
    }

    private fun keyPageDebounce(
        direction: PageDirection,
        mouseWheel: Boolean = false,
        longPress: Boolean
    ) {
        if (longPress) {
            return
        }
        nextPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        prevPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        when (direction) {
            PageDirection.NEXT -> nextPageDebounce.invoke()
            PageDirection.PREV -> prevPageDebounce.invoke()
            else -> {}
        }
    }

    private fun keyPage(direction: PageDirection) {
        if (epubCoreActive) {
            when (direction) {
                PageDirection.NEXT -> nextEpubPage()
                PageDirection.PREV -> previousEpubPage()
                else -> Unit
            }
            return
        }
        binding.readView.cancelSelect()
        binding.readView.pageDelegate?.isCancel = false
        binding.readView.pageDelegate?.keyTurnPage(direction)
    }

    override fun upMenuView() {
        handler.post {
            upMenu()
            binding.readMenu.upBookView()
            binding.readAloudPlayerPanel.onChapterContentChanged()
            if (BookCloudEntryModeStore.get(ReadBook.book?.bookUrl.orEmpty()) == BookCloudEntryMode.LIBRARY_CHAPTER) {
                refreshLibraryCloudSession(refresh = false, silent = true)
            } else {
                libraryCloudSession = null
                libraryCloudState = LibraryCloudState.DISABLED
                binding.readMenu.updateCloudLibraryState(libraryCloudState)
            }
        }
    }

    override fun loadChapterList(book: Book) {
        ReadBook.upMsg(getString(R.string.toc_updateing))
        viewModel.loadChapterList(book)
    }

    /**
     * 内容加载完成
     */
    override fun contentLoadFinish() {
        if (intent.getBooleanExtra("readAloud", false)) {
            intent.removeExtra("readAloud")
            ReadBook.readAloud()
        }
        consumeOpenReadAloudPanelIntent(intent)
        consumeGlobalReadAloudPanelOpen()
        loadStates = true
    }

    private fun consumeOpenReadAloudPanelIntent(intent: Intent = this.intent): Boolean {
        val requested = requestOpenReadAloudPanelFromIntent(intent) || pendingReadAloudPanelIntentOpen
        if (!requested) return false
        return openPendingReadAloudPanel()
    }

    private fun requestOpenReadAloudPanelFromIntent(intent: Intent): Boolean {
        if (!intent.getBooleanExtra("openReadAloudPanel", false)) return false
        intent.removeExtra("openReadAloudPanel")
        pendingReadAloudPanelIntentOpen = true
        return true
    }

    private fun openPendingReadAloudPanel(): Boolean {
        if (!pendingReadAloudPanelIntentOpen) return false
        pendingReadAloudPanelIntentOpen = false
        return openReadAloudPanelFromExternalRequest()
    }

    private fun consumeGlobalReadAloudPanelOpen(): Boolean {
        if (!BaseReadAloudService.isRun) return false
        val bookUrl = ReadBook.book?.bookUrl ?: return false
        if (!ReadAloudAppCapsuleHost.consumeReadAloudPanelOpen(bookUrl)) return false
        return openReadAloudPanelFromExternalRequest()
    }

    fun openReadAloudPanelFromGlobalCapsule(): Boolean {
        return openReadAloudPanelFromExternalRequest()
    }

    private fun openReadAloudPanelFromExternalRequest(): Boolean {
        if (!BaseReadAloudService.isRun) return false
        pendingReadAloudPlayerOpen = false
        binding.readAloudPlayerPanel.post {
            binding.readAloudPlayerPanel.openFromBottom(force = true)
            binding.readAloudPlayerPanel.refresh()
        }
        return true
    }

    /**
     * 更新内容
     */
    override fun upContent(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) {
        lifecycleScope.launch {
            if (isEpubCoreMode()) {
                if (relativePosition != 0) {
                    success?.invoke()
                    return@launch
                }
                loadEpubCoreContent(
                    relativePosition = relativePosition,
                    resetPageOffset = resetPageOffset,
                    fallback = {
                        binding.readView.upContent(relativePosition, resetPageOffset)
                    },
                    onFinish = {
                        if (relativePosition == 0) {
                            upSeekBarProgress()
                        }
                        loadStates = false
                        success?.invoke()
                    }
                )
                return@launch
            } else {
                switchEpubCore(false)
                binding.readView.upContent(relativePosition, resetPageOffset)
            }
            if (relativePosition == 0) {
                upSeekBarProgress()
            }
            loadStates = false
            success?.invoke()
        }
    }

    override suspend fun upContentAwait(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) = withContext(Main.immediate) {
        if (isEpubCoreMode()) {
            if (relativePosition != 0) {
                success?.invoke()
                return@withContext
            }
            loadEpubCoreContent(
                relativePosition = relativePosition,
                resetPageOffset = resetPageOffset,
                fallback = {
                    binding.readView.upContent(relativePosition, resetPageOffset)
                },
                onFinish = {
                    if (relativePosition == 0) {
                        upSeekBarProgress()
                    }
                    loadStates = false
                    success?.invoke()
                }
            )
            return@withContext
        } else {
            switchEpubCore(false)
            binding.readView.upContent(relativePosition, resetPageOffset)
        }
        if (relativePosition == 0) {
            upSeekBarProgress()
        }
        loadStates = false
    }

    override fun upPageAnim(upRecorder: Boolean) {
        lifecycleScope.launch {
            binding.readView.upPageAnim(upRecorder)
        }
    }

    private fun isEpubCoreMode(): Boolean {
        return ReadBook.book?.isEpub == true && AppConfig.useExperimentalEpubCore
    }

    private fun switchEpubCore(active: Boolean) = binding.run {
        epubCoreActive = active
        epubReadView.isVisible = active
        readView.isVisible = !active
        if (!active) {
            epubCorePageCount = 0
            epubCoreCommittedChapterIndex = null
        }
        if (active) {
            readView.cancelSelect(true)
        }
    }

    private fun loadEpubCoreContent(
        relativePosition: Int = 0,
        targetChapterIndex: Int? = null,
        resetPageOffset: Boolean = true,
        navigationMode: EpubCoreNavigationMode = EpubCoreNavigationMode.ExplicitReplace,
        boundaryTransition: Boolean = false,
        keepCurrentPageUntilReady: Boolean = false,
        fallback: (() -> Unit)? = null,
        onFinish: (() -> Unit)? = null
    ) {
        val book = ReadBook.book
        if (book?.isEpub != true) {
            switchEpubCore(false)
            fallback?.invoke()
            onFinish?.invoke()
            return
        }
        val chapterIndex = (targetChapterIndex ?: (ReadBook.durChapterIndex + relativePosition)).coerceIn(
            0,
            (ReadBook.chapterSize - 1).coerceAtLeast(0)
        )
        if (binding.epubReadView.width <= 0 || binding.epubReadView.height <= 0) {
            prepareEpubCoreLayout {
                loadEpubCoreContent(
                    relativePosition,
                    targetChapterIndex,
                    resetPageOffset,
                    navigationMode,
                    boundaryTransition,
                    keepCurrentPageUntilReady,
                    fallback,
                    onFinish
                )
            }
            return
        }
        epubCoreLoading = true
        epubCoreLoadingChapterIndex = chapterIndex
        epubCoreForegroundTarget = EpubCoreNavigationRequest(chapterIndex, resetPageOffset)
        binding.readMenu.upBookView()
        val requestSeq = ++epubCoreRequestSeq
        val requestBookUrl = book.bookUrl
        val config = buildEpubCoreLayoutConfig()
        if (applyEpubCoreCachedPages(book, chapterIndex, config, resetPageOffset, navigationMode, boundaryTransition)) {
            contentLoadFinish()
            onFinish?.invoke()
            return
        }
        ReadBook.msg = null
        epubCoreLoadJob?.cancel()
        if (keepCurrentPageUntilReady && epubCoreActive) {
            upEpubRendererStyle()
        } else {
            showEpubCoreLoadingPage(chapterIndex, config)
        }
        epubCoreLoadJob = lifecycleScope.launch(IO) {
            val result = try {
                AppLog.putDebug(
                    "EPUB core load start: book=${book.name}, index=$chapterIndex, " +
                            "page=${config.pageWidthPx}x${config.pageHeightPx}, " +
                            "content=${config.contentWidthPx}x${config.contentHeightPx}"
                )
                Result.success(loadEpubCorePagesWithRetry(book, chapterIndex, config))
            } catch (e: CancellationException) {
                throw e
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }
            withContext(Main.immediate) {
                val currentBook = ReadBook.book
                if (requestSeq != epubCoreRequestSeq ||
                    currentBook?.bookUrl != requestBookUrl ||
                    currentBook?.isEpub != true
                ) {
                    if (requestSeq == epubCoreRequestSeq) {
                        epubCoreLoading = false
                        epubCoreLoadingChapterIndex = null
                        epubCoreForegroundTarget = null
                        binding.epubReadView.clearLoading()
                        binding.epubReadView.clearBoundaryLoadingTurn()
                    }
                    return@withContext
                }
                result.onSuccess { pages ->
                    val fragmentCount = pages.map { it.fragments.size }.sum()
                    AppLog.putDebug(
                        "EPUB core load finish: index=$chapterIndex, pages=${pages.size}, " +
                                "fragments=$fragmentCount"
                    )
                    upEpubRendererStyle()
                    binding.epubReadView.layoutConfig = config
                    switchEpubCore(true)
                    val targetEdge = epubCoreTargetEdge(resetPageOffset, boundaryTransition)
                    val initialPage = epubCoreInitialPageIndex(
                        pages = pages,
                        chapterIndex = chapterIndex,
                        targetEdge = targetEdge
                    )
                    if (boundaryTransition && navigationMode == EpubCoreNavigationMode.Sequential) {
                        epubCoreSuppressProgressSync = true
                        epubCoreBoundaryTransition = false
                        epubCorePendingNavigation = null
                        epubCoreLoading = false
                        epubCoreLoadingChapterIndex = null
                        epubCoreForegroundTarget = null
                        binding.epubReadView.clearLoading()
                        val replaced = binding.epubReadView.replaceBoundaryLoadingPage(
                                transactionId = epubCoreBoundaryTransactionId,
                                direction = epubCoreBoundaryDirection,
                                targetChapterIndex = chapterIndex,
                                targetPages = pages,
                                resetPageOffset = targetEdge != EpubCorePageEdge.End
                            )
                        if (!replaced) {
                            binding.epubReadView.clearBoundaryLoadingTurn()
                            epubCoreSuppressProgressSync = true
                            binding.epubReadView.setPages(pages, initialPage)
                        }
                        commitEpubCoreDisplayedChapter(
                            chapterIndex = chapterIndex,
                            chapterPageIndex = initialPage,
                            requestSeq = requestSeq
                        )
                        contentLoadFinish()
                        onFinish?.invoke()
                        return@withContext
                    } else {
                        epubCoreSuppressProgressSync = true
                        binding.epubReadView.setPages(pages, initialPage)
                        commitEpubCoreDisplayedChapter(
                            chapterIndex = chapterIndex,
                            chapterPageIndex = binding.epubReadView.currentChapterPageIndex(),
                            requestSeq = requestSeq
                        )
                    }
                    contentLoadFinish()
                }.onFailure {
                    epubCoreLoading = false
                    epubCoreLoadingChapterIndex = null
                    epubCorePendingNavigation = null
                    epubCoreBoundaryTransition = false
                    epubCoreForegroundTarget = null
                    epubCoreSuppressProgressSync = false
                    binding.epubReadView.clearLoading()
                    binding.epubReadView.clearBoundaryLoadingTurn()
                    AppLog.putDebug("EPUB core render failed: ${it.localizedMessage}", it)
                    if (keepCurrentPageUntilReady && epubCoreActive) {
                        toastOnUi((it.localizedMessage ?: it.toString()).take(120))
                    } else {
                        binding.epubReadView.layoutConfig = config
                        switchEpubCore(true)
                        binding.epubReadView.setError(
                            getString(R.string.error) + "\n" + (it.localizedMessage ?: it.toString())
                        )
                    }
                    contentLoadFinish()
                }
                onFinish?.invoke()
            }
        }
    }

    private suspend fun loadEpubCorePagesWithRetry(
        book: Book,
        chapterIndex: Int,
        config: EpubCoreLayoutConfig
    ): List<EpubCorePage> {
        return try {
            loadEpubCorePagesOnce(book, chapterIndex, config)
        } catch (e: CancellationException) {
            throw e
        } catch (throwable: Throwable) {
            AppLog.putDebug(
                "EPUB core foreground retry: index=$chapterIndex, ${throwable.localizedMessage}",
                throwable
            )
            EpubCoreProvider.cancelForegroundLayout(book)
            loadEpubCorePagesOnce(book, chapterIndex, config)
        }
    }

    private suspend fun loadEpubCorePagesOnce(
        book: Book,
        chapterIndex: Int,
        config: EpubCoreLayoutConfig
    ): List<EpubCorePage> {
        return EpubCoreProvider.paginate(book, chapterIndex, config)
            .takeIf { it.isNotEmpty() }
            ?: error("empty page")
    }

    private fun prepareEpubCoreLayout(onReady: () -> Unit) {
        binding.run {
            if (!epubCoreWaitingForLayout) {
                epubCoreWaitingForLayout = true
                epubReadView.visibility = View.INVISIBLE
                readView.isVisible = !epubCoreActive
                epubReadView.requestLayout()
            }
            epubReadView.visibility = View.INVISIBLE
            epubReadView.doOnLayout {
                epubCoreWaitingForLayout = false
                onReady()
            }
        }
    }

    private fun refreshEpubCoreAfterConfigurationChange() {
        binding.epubReadView.doOnLayout {
            val currentConfig = binding.epubReadView.layoutConfig
            val nextConfig = buildEpubCoreLayoutConfig()
            if (currentConfig == null || !currentConfig.sameEpubLayoutAs(nextConfig)) {
                loadEpubCoreContent(resetPageOffset = false)
            } else {
                upEpubRendererStyle()
                binding.epubReadView.invalidateRendererStyle()
            }
        }
    }

    private fun applyEpubRendererStyleOnly() {
        upEpubRendererStyle()
        binding.epubReadView.invalidateRendererStyle()
    }

    private fun showEpubCoreLoadingPage(chapterIndex: Int, config: EpubCoreLayoutConfig) {
        upEpubRendererStyle()
        binding.epubReadView.layoutConfig = config
        switchEpubCore(true)
        epubCoreSuppressProgressSync = true
        binding.epubReadView.showLoading(chapterIndex, getString(R.string.loading))
        upSeekBarProgress()
    }

    private fun syncEpubCoreProgress(pageIndex: Int, pageCount: Int) {
        val page = binding.epubReadView.currentPage()
        if (page?.chapterHref?.startsWith("loading:") == true) return
        val oldChapterIndex = ReadBook.durChapterIndex
        val chapterIndex = page?.chapterIndex ?: oldChapterIndex
        val chapterPageIndex = page?.pageIndex ?: pageIndex
        epubCorePageCount = page?.totalPagesInChapter ?: pageCount
        if (epubCoreSuppressProgressSync) return
        ReadBook.durChapterIndex = chapterIndex
        ReadBook.durChapterPos = chapterPageIndex.coerceAtLeast(0)
        ReadBook.saveRead(true)
        if (chapterIndex != oldChapterIndex) {
            upMenuView()
            scheduleEpubCorePrefetchWindow(chapterIndex)
        } else {
            maybeAdvanceEpubCorePrefetch(chapterIndex, chapterPageIndex, epubCorePageCount)
        }
        pageChanged()
    }

    private fun epubCoreTargetEdge(
        resetPageOffset: Boolean,
        boundaryTransition: Boolean
    ): EpubCorePageEdge {
        return when {
            boundaryTransition -> epubCoreBoundaryTargetEdge
            resetPageOffset -> EpubCorePageEdge.Start
            else -> EpubCorePageEdge.Keep
        }
    }

    private fun epubCoreInitialPageIndex(
        pages: List<EpubCorePage>,
        chapterIndex: Int,
        targetEdge: EpubCorePageEdge
    ): Int {
        if (pages.isEmpty()) return 0
        return when (targetEdge) {
            EpubCorePageEdge.Start -> 0
            EpubCorePageEdge.End -> pages.lastIndex
            EpubCorePageEdge.Keep -> binding.epubReadView
                .preferredPageIndexForReload(chapterIndex, ReadBook.durChapterPos)
                .coerceIn(0, pages.lastIndex)
        }
    }

    private fun maybeAdvanceEpubCorePrefetch(chapterIndex: Int, chapterPageIndex: Int, chapterPageCount: Int) {
        if (!epubCoreActive || epubCoreLoading || epubCorePendingNavigation != null) return
        if (chapterPageCount <= 0) return
        val remaining = chapterPageCount - 1 - chapterPageIndex
        if (remaining > 1) return
        val nextIndex = chapterIndex + 1
        if (nextIndex !in 0 until ReadBook.chapterSize) return
        scheduleEpubCorePrefetchWindow(nextIndex, epubCoreRequestSeq)
    }

    private fun cancelActiveEpubCoreNavigation(
        clearBoundary: Boolean = true,
        advanceRequestSeq: Boolean = true
    ) {
        if (advanceRequestSeq) {
            epubCoreRequestSeq++
        }
        epubCoreLoadJob?.cancel()
        epubCoreLoadJob = null
        epubCoreLoading = false
        epubCoreLoadingChapterIndex = null
        epubCorePendingNavigation = null
        epubCoreBoundaryTransition = false
        epubCoreForegroundTarget = null
        epubCoreSuppressProgressSync = false
        binding.epubReadView.clearLoading()
        if (clearBoundary) {
            binding.epubReadView.clearBoundaryLoadingTurn()
            epubCoreBoundaryDirection = 0
            epubCoreBoundaryTargetEdge = EpubCorePageEdge.Start
        }
    }

    private fun commitEpubCoreDisplayedChapter(
        chapterIndex: Int,
        chapterPageIndex: Int = binding.epubReadView.currentChapterPageIndex(),
        requestSeq: Long = ++epubCoreRequestSeq,
        schedulePrefetch: Boolean = true
    ) {
        epubCoreSuppressProgressSync = false
        epubCorePendingNavigation = null
        epubCoreLoading = false
        epubCoreLoadingChapterIndex = null
        epubCoreForegroundTarget = null
        binding.epubReadView.clearLoading()
        epubCorePageCount = binding.epubReadView.currentChapterPageCount()
        if (epubCorePageCount > 0) {
            epubCoreEstimatedPages[chapterIndex] = epubCorePageCount
        }
        ReadBook.durChapterIndex = chapterIndex
        ReadBook.durChapterPos = chapterPageIndex.coerceAtLeast(0)
        epubCoreCommittedChapterIndex = chapterIndex
        ReadBook.saveRead(true)
        binding.readMenu.upBookView()
        upSeekBarProgress()
        pageChanged()
        if (schedulePrefetch) {
            scheduleEpubCorePrefetchWindow(chapterIndex, requestSeq)
        }
        AppLog.putDebug(
            "EPUB core commit: chapter=$chapterIndex, page=$chapterPageIndex, " +
                    "pages=$epubCorePageCount, seq=$requestSeq"
        )
    }

    private fun handleEpubCoreConfigUpdate(values: List<Int>) {
        if (values.contains(13)) {
            reloadAfterEpubEngineChanged()
            return
        }
        if (values.contains(0)) {
            upSystemUiVisibility()
        }
        if (values.any { it == 8 || it == 10 }) {
            ChapterProvider.upStyle()
        }
        val needsLayout = values.any { it == 1 || it == 2 || it == 5 || it == 6 || it == 8 || it == 10 }
        if (needsLayout) {
            ReadBook.book?.takeIf { it.isEpub }?.let { book ->
                cancelEpubCoreForegroundHard(book)
                cancelEpubCorePrefetchHard(book)
            }
            loadEpubCoreContent(resetPageOffset = false, keepCurrentPageUntilReady = true)
            return
        }
        if (values.contains(0)) {
            refreshEpubCoreAfterConfigurationChange()
        } else {
            applyEpubRendererStyleOnly()
        }
    }

    private fun handleReadConfigUpdate(values: List<Int>) = binding.run {
        if (values.contains(13)) {
            reloadAfterEpubEngineChanged()
            return@run
        }
        val needSystemUi = values.contains(0)
        val needBackground = values.contains(1)
        val needStyle = values.any { it == 2 || it == 8 || it == 10 }
        val needReload = values.any { it == 5 || it == 6 || it == 8 || it == 10 }
        val needInvalidate = values.contains(9)
        val needSubmitRender = values.contains(11)
        var textRenderInvalidated = false
        if (needSystemUi) {
            upSystemUiVisibility()
        }
        if (values.contains(4)) {
            readView.upPageSlopSquare()
        }
        if (values.contains(12)) {
            readView.upPageTouchClick()
        }
        if (epubCoreActive) {
            handleEpubCoreConfigUpdate(values)
            return@run
        }
        if (needBackground) {
            readView.upBg()
        }
        if (values.contains(3)) {
            readView.upBgAlpha()
        }
        if (needStyle) {
            readView.upStyle()
            if (!needReload) {
                readView.invalidateTextPage()
                readView.submitRenderTask()
                textRenderInvalidated = true
            }
        }
        if (needReload && isInitFinish) {
            ReadBook.relayoutCurrentContent("read-config")
        } else {
            if (needInvalidate && !textRenderInvalidated) {
                readView.invalidateTextPage()
            }
            if (needSubmitRender && !textRenderInvalidated) {
                readView.submitRenderTask()
            }
        }
    }

    private fun reloadAfterEpubEngineChanged() {
        val book = ReadBook.book ?: return
        if (!book.isEpub) return
        cancelEpubCoreForegroundHard(book)
        cancelEpubCorePrefetchHard(book)
        EpubCoreProvider.clearBookCache(book)
        EpubFile.clearBook(book)
        BookHelp.clearCache(book)
        ReadBook.clearTextChapter()
        ReadBook.msg = null
        if (isEpubCoreMode()) {
            switchEpubCore(true)
            loadEpubCoreContent(resetPageOffset = false, keepCurrentPageUntilReady = false)
        } else {
            switchEpubCore(false)
            ChapterProvider.upStyle()
            ReadBook.loadContent(resetPageOffset = false)
        }
        binding.readMenu.upBookView()
        upSeekBarProgress()
    }

    private fun EpubCoreLayoutConfig.sameEpubLayoutAs(other: EpubCoreLayoutConfig): Boolean {
        return pageWidthPx == other.pageWidthPx &&
            pageHeightPx == other.pageHeightPx &&
            paddingLeftPx == other.paddingLeftPx &&
            paddingTopPx == other.paddingTopPx &&
            paddingRightPx == other.paddingRightPx &&
            paddingBottomPx == other.paddingBottomPx &&
            readerPaddingLeftPx == other.readerPaddingLeftPx &&
            readerPaddingTopPx == other.readerPaddingTopPx &&
            readerPaddingRightPx == other.readerPaddingRightPx &&
            readerPaddingBottomPx == other.readerPaddingBottomPx &&
            paragraphSpacingPx == other.paragraphSpacingPx &&
            alignment == other.alignment &&
            textFullJustify == other.textFullJustify &&
            lineSpacingMultiplier == other.lineSpacingMultiplier &&
            lineSpacingExtraPx == other.lineSpacingExtraPx &&
            textPaint.textSize == other.textPaint.textSize &&
            textPaint.letterSpacing == other.textPaint.letterSpacing &&
            textPaint.color == other.textPaint.color &&
            textPaint.typeface?.style == other.textPaint.typeface?.style &&
            readerFontFamily == other.readerFontFamily &&
            readerFontUrl == other.readerFontUrl &&
            readerFontPath == other.readerFontPath &&
            scrollMode == other.scrollMode
    }

    private fun prefetchAdjacentEpubCoreChapters(
        book: Book,
        chapterIndex: Int,
        config: EpubCoreLayoutConfig,
        requestSeq: Long
    ) {
        cancelEpubCorePrefetch()
        val mode = currentEpubCoreSchedule()
        val candidates = epubCorePrefetchCandidatesByPageBudget(book, chapterIndex, config, mode)
        if (candidates.isEmpty()) return
        val workerCount = mode.workerCount.coerceAtLeast(1).coerceAtMost(candidates.size)
        AppLog.putDebug(
            "EPUB core prefetch schedule: mode=${mode.key}, budget=${mode.pageBudget}, center=$chapterIndex, " +
                    "workers=$workerCount, targets=${candidates.joinToString()}"
        )
        repeat(workerCount) { slot ->
            val targets = candidates.filterIndexed { index, _ -> index % workerCount == slot }
            if (targets.isEmpty()) return@repeat
            val job = lifecycleScope.launch(IO) {
                targets.forEach { index ->
                    ensureActive()
                    if (requestSeq != epubCoreRequestSeq || ReadBook.book?.bookUrl != book.bookUrl) return@launch
                    val cachedPages = EpubCoreProvider.peekPages(book, index, config)
                    if (cachedPages != null) {
                        epubCoreEstimatedPages[index] = cachedPages.size.coerceAtLeast(1)
                        AppLog.putDebug("EPUB core prefetch skip cache: index=$index")
                        if (config.scrollMode) {
                            withContext(Main.immediate) {
                                mergeEpubCoreScrollPrefetch(
                                    book = book,
                                    requestSeq = requestSeq,
                                    mode = mode,
                                    chapterIndex = index,
                                    pages = cachedPages
                                )
                            }
                        }
                        return@forEach
                    }
                    runCatching {
                        EpubCoreProvider.paginate(book, index, config, backgroundSlot = slot)
                    }.onFailure {
                        if (it is CancellationException) throw it
                        AppLog.putDebug("EPUB core prefetch failed: index=$index, ${it.localizedMessage}", it)
                    }.onSuccess { pages ->
                        if (pages.isEmpty()) return@onSuccess
                        withContext(Main.immediate) {
                            val currentBook = ReadBook.book
                            if (requestSeq != epubCoreRequestSeq ||
                                currentBook?.bookUrl != book.bookUrl ||
                                currentBook?.isEpub != true ||
                                !epubCoreActive ||
                                epubCoreLoading ||
                                epubCorePendingNavigation != null ||
                                currentEpubCoreSchedule().key != mode.key
                            ) {
                                return@withContext
                            }
                            epubCoreEstimatedPages[index] = pages.size.coerceAtLeast(1)
                            AppLog.putDebug("EPUB core prefetch ready: index=$index, pages=${pages.size}")
                            if (config.scrollMode) {
                                mergeEpubCoreScrollPrefetch(
                                    book = book,
                                    requestSeq = requestSeq,
                                    mode = mode,
                                    chapterIndex = index,
                                    pages = pages
                                )
                            }
                        }
                    }
                }
            }
            epubCorePrefetchJobs += job
        }
    }

    private fun mergeEpubCoreScrollPrefetch(
        book: Book,
        requestSeq: Long,
        mode: EpubCoreScheduleMode,
        chapterIndex: Int,
        pages: List<EpubCorePage>
    ) {
        val currentBook = ReadBook.book
        if (requestSeq != epubCoreRequestSeq ||
            currentBook?.bookUrl != book.bookUrl ||
            currentBook?.isEpub != true ||
            !epubCoreActive ||
            epubCoreLoading ||
            epubCorePendingNavigation != null ||
            currentEpubCoreSchedule().key != mode.key ||
            ReadBook.pageAnim() != PageAnim.scrollPageAnim
        ) {
            return
        }
        if (binding.epubReadView.mergeAdjacentPages(pages)) {
            AppLog.putDebug("EPUB core scroll prefetch merged: index=$chapterIndex, pages=${pages.size}")
        }
    }

    private fun cancelEpubCorePrefetch() {
        epubCorePrefetchJobs.forEach { it.cancel() }
        epubCorePrefetchJobs.clear()
    }

    private fun cancelEpubCorePrefetchHard(book: Book? = ReadBook.book?.takeIf { it.isEpub }) {
        cancelEpubCorePrefetch()
        epubCoreEstimatedPages.clear()
        EpubCoreProvider.cancelBackgroundLayouts(book)
    }

    private fun cancelEpubCoreForegroundHard(book: Book? = ReadBook.book?.takeIf { it.isEpub }) {
        EpubCoreProvider.cancelForegroundLayout(book)
    }

    private fun scheduleEpubCorePrefetchWindow(
        chapterIndex: Int = binding.epubReadView.currentPage()?.chapterIndex ?: ReadBook.durChapterIndex,
        requestSeq: Long = epubCoreRequestSeq
    ) {
        val book = ReadBook.book?.takeIf { it.isEpub } ?: return
        if (!epubCoreActive || epubCoreLoading || epubCorePendingNavigation != null) return
        prefetchAdjacentEpubCoreChapters(
            book = book,
            chapterIndex = chapterIndex,
            config = buildEpubCoreLayoutConfig(),
            requestSeq = requestSeq
        )
    }

    private fun epubCorePrefetchCandidatesByPageBudget(
        book: Book,
        chapterIndex: Int,
        config: EpubCoreLayoutConfig,
        mode: EpubCoreScheduleMode
    ): List<Int> {
        val candidates = arrayListOf<Int>()
        var pages = 0
        fun estimate(index: Int): Int {
            epubCoreEstimatedPages[index]?.let { return it.coerceAtLeast(1) }
            EpubCoreProvider.peekPages(book, index, config)?.let {
                val size = it.size.coerceAtLeast(1)
                epubCoreEstimatedPages[index] = size
                return size
            }
            return epubCoreFallbackEstimatedPages()
        }
        fun add(index: Int): Boolean {
            if (index !in 0 until ReadBook.chapterSize || index in candidates) return false
            val estimated = estimate(index)
            candidates += index
            pages += estimated
            return pages < mode.pageBudget
        }
        val minForwardChapters = when (mode) {
            EpubCoreScheduleMode.Light -> 2
            EpubCoreScheduleMode.Normal -> 4
            EpubCoreScheduleMode.Performance -> 6
        }
        for (distance in 1..minForwardChapters) {
            add(chapterIndex + distance)
        }
        var distance = 1
        while (pages < mode.pageBudget && distance <= ReadBook.chapterSize) {
            val forward = chapterIndex + distance
            val backward = chapterIndex - distance
            val canForward = add(forward)
            if (pages >= mode.pageBudget) break
            val canBackward = add(backward)
            if (!canForward && !canBackward) {
                distance++
                continue
            }
            distance++
        }
        return candidates
    }

    private fun epubCoreFallbackEstimatedPages(): Int {
        val known = epubCoreEstimatedPages.values.filter { it > 0 }
        if (known.isNotEmpty()) {
            return (known.sum() / known.size).coerceIn(1, 5)
        }
        return epubCorePageCount.takeIf { it > 0 }?.coerceIn(1, 5) ?: 1
    }

    private fun currentEpubCoreSchedule(): EpubCoreScheduleMode {
        return EpubCoreScheduleMode.fromKey(AppConfig.epubCoreScheduleMode)
    }

    private fun showEpubCoreScheduleModeDialog() {
        val modes = EpubCoreScheduleMode.values()
        selector(
            R.string.epub_core_schedule_mode,
            modes.map { getString(it.titleRes) }
        ) { _, index ->
            val mode = modes.getOrNull(index) ?: return@selector
            if (AppConfig.epubCoreScheduleMode == mode.key) return@selector
            AppConfig.epubCoreScheduleMode = mode.key
            menu?.findItem(R.id.menu_epub_schedule_mode)?.title =
                "${getString(R.string.epub_core_schedule_mode)} (${getString(mode.titleRes)})"
            val book = ReadBook.book?.takeIf { it.isEpub } ?: return@selector
            cancelEpubCorePrefetchHard(book)
            if (!epubCoreActive || epubCoreLoading) return@selector
            val chapterIndex = binding.epubReadView.currentPage()?.chapterIndex ?: ReadBook.durChapterIndex
            scheduleEpubCorePrefetchWindow(chapterIndex)
        }
    }

    private fun buildEpubCoreLayoutConfig(): EpubCoreLayoutConfig {
        val view = binding.epubReadView
        val scrollMode = ReadBook.pageAnim() == PageAnim.scrollPageAnim
        val textPaint = TextPaint().apply {
            isAntiAlias = true
            color = ReadBookConfig.textColor
            textSize = ReadBookConfig.textSize.toFloat().spToPx()
            letterSpacing = ReadBookConfig.letterSpacing
            typeface = ChapterProvider.contentPaint.typeface
        }
        val readerFontName = epubCoreReaderFontName()
        return EpubCoreLayoutConfig(
            pageWidthPx = view.width,
            pageHeightPx = view.height,
            paddingLeftPx = 0,
            paddingTopPx = 0,
            paddingRightPx = 0,
            paddingBottomPx = 0,
            readerPaddingLeftPx = ReadBookConfig.paddingLeft.dpToPx(),
            readerPaddingTopPx = if (scrollMode) 0 else ReadBookConfig.paddingTop.dpToPx(),
            readerPaddingRightPx = ReadBookConfig.paddingRight.dpToPx(),
            readerPaddingBottomPx = if (scrollMode) 0 else ReadBookConfig.paddingBottom.dpToPx(),
            paragraphSpacingPx = (ReadBookConfig.paragraphSpacing.dpToPx() / 2).coerceAtLeast(8),
            textPaint = textPaint,
            readerFontFamily = readerFontName,
            readerFontUrl = epubCoreReaderFontUrl(),
            readerFontPath = ReadBookConfig.textFont.takeIf { it.isNotBlank() },
            textFullJustify = ReadBookConfig.textFullJustify,
            lineSpacingExtraPx = ReadBookConfig.lineSpacingExtra.toFloat(),
            scrollMode = scrollMode
        )
    }

    private fun upEpubRendererStyle() {
        val config = ReadBookConfig.durConfig
        val bgColor = runCatching {
            if (config.curBgType() == 0) {
                android.graphics.Color.parseColor(config.curBgStr())
            } else {
                android.graphics.Color.rgb(250, 248, 241)
            }
        }.getOrDefault(android.graphics.Color.rgb(250, 248, 241))
        binding.epubReadView.renderer.backgroundColor = bgColor
        binding.epubReadView.renderer.textColor = ReadBookConfig.textColor
        binding.epubReadView.renderer.imageResolver = ReadBook.book
            ?.takeIf { it.isEpub }
            ?.let { EpubCoreProvider.imageResolverOrNull(it) }
        binding.epubReadView.renderer.typefaceResolver = ReadBook.book
            ?.takeIf { it.isEpub }
            ?.let { EpubCoreProvider.typefaceResolverOrNull(it) }
        binding.epubReadView.renderer.textPaint = TextPaint().apply {
            isAntiAlias = true
            color = ReadBookConfig.textColor
            textSize = ReadBookConfig.textSize.toFloat().spToPx()
            letterSpacing = ReadBookConfig.letterSpacing
            typeface = ChapterProvider.contentPaint.typeface
        }
        binding.epubReadView.renderer.lineSpacingExtra = ReadBookConfig.lineSpacingExtra.toFloat()
        binding.epubReadView.invalidate()
    }

    private fun epubCoreReaderFontName(): String? {
        return if (ReadBookConfig.textFont.isBlank()) {
            when (AppConfig.systemTypefaces) {
                1 -> "serif"
                2 -> "monospace"
                else -> "sans-serif"
            }
        } else {
            "legado-reader-font"
        }
    }

    private fun epubCoreReaderFontUrl(): String? {
        return ReadBookConfig.textFont
            .takeIf { it.isNotBlank() }
            ?.let { "https://epub.local/__legado_reader_font__" }
    }

    private fun nextEpubPage() {
        if (!epubCoreActive && !epubCoreLoading) return
        if (epubCoreLoading) {
            requestEpubCoreChapterByDirection(1)
            return
        }
        if (binding.epubReadView.nextPage()) return
        requestEpubCoreChapterByDirection(1)
    }

    private fun previousEpubPage() {
        if (!epubCoreActive && !epubCoreLoading) return
        if (epubCoreLoading) {
            requestEpubCoreChapterByDirection(-1)
            return
        }
        if (binding.epubReadView.previousPage()) return
        requestEpubCoreChapterByDirection(-1)
    }

    private fun openEpubCoreChapter(
        index: Int,
        resetPageOffset: Boolean,
        navigationMode: EpubCoreNavigationMode = EpubCoreNavigationMode.ExplicitReplace,
        boundaryTransition: Boolean = false
    ) {
        val chapterIndex = index.coerceIn(0, (ReadBook.chapterSize - 1).coerceAtLeast(0))
        ReadBook.durChapterPos = if (resetPageOffset) 0 else ReadBook.durChapterPos
        loadEpubCoreContent(
            targetChapterIndex = chapterIndex,
            resetPageOffset = resetPageOffset,
            navigationMode = navigationMode,
            boundaryTransition = boundaryTransition
        )
    }

    override fun openNextEpubCoreChapter() {
        requestEpubCoreChapterByDirection(1)
    }

    override fun openPreviousEpubCoreChapter() {
        requestEpubCoreChapterByDirection(-1)
    }

    private fun requestEpubCoreChapterByDirection(
        direction: Int,
        boundaryTransition: Boolean = false
    ) {
        if (direction == 0 || ReadBook.chapterSize <= 0) return
        val baseIndex = binding.epubReadView.currentPage()
            ?.takeIf { !it.chapterHref.startsWith("loading:") }
            ?.chapterIndex
            ?: epubCoreCommittedChapterIndex
            ?: ReadBook.durChapterIndex
        val targetIndex = baseIndex + direction
        if (targetIndex !in 0 until ReadBook.chapterSize) return
        epubCoreBoundaryTransition = boundaryTransition
        if (boundaryTransition) {
            cancelEpubCorePrefetch()
            epubCoreBoundaryTransactionId++
            epubCoreBoundaryDirection = direction
            epubCoreBoundaryTargetEdge = if (direction > 0) {
                EpubCorePageEdge.Start
            } else {
                EpubCorePageEdge.End
            }
            epubCorePendingNavigation = EpubCoreNavigationRequest(targetIndex, direction > 0)
            val book = ReadBook.book?.takeIf { it.isEpub }
            val config = book?.let { buildEpubCoreLayoutConfig() }
            if (book != null && config != null &&
                startCachedEpubCoreBoundaryTurn(
                    book = book,
                    chapterIndex = targetIndex,
                    config = config,
                    direction = direction,
                    resetPageOffset = direction > 0
                )
            ) {
                return
            }
            val currentPage = binding.epubReadView.currentPage()
                ?.takeIf { !it.chapterHref.startsWith("loading:") }
            if (currentPage != null) {
                val started = binding.epubReadView.startBoundaryLoadingTurn(
                    transactionId = epubCoreBoundaryTransactionId,
                    direction = direction,
                    targetChapterIndex = targetIndex,
                    message = getString(R.string.loading)
                )
                if (started) {
                    AppLog.putDebug(
                        "EPUB core boundary deferred: from=$baseIndex to=$targetIndex, direction=$direction"
                    )
                    return
                }
            }
            epubCorePendingNavigation = null
        }
        requestEpubCoreChapter(
            index = targetIndex,
            resetPageOffset = direction > 0,
            navigationMode = EpubCoreNavigationMode.Sequential,
            boundaryTransition = boundaryTransition
        )
    }

    private fun requestEpubCoreChapter(
        index: Int,
        resetPageOffset: Boolean,
        navigationMode: EpubCoreNavigationMode,
        boundaryTransition: Boolean = false
    ) {
        if (ReadBook.chapterSize <= 0) return
        val chapterIndex = index.coerceIn(0, ReadBook.chapterSize - 1)
        val currentRealPage = binding.epubReadView.currentPage()
            ?.takeIf { !it.chapterHref.startsWith("loading:") }
        if (!boundaryTransition &&
            chapterIndex == currentRealPage?.chapterIndex &&
            !epubCoreLoading
        ) {
            binding.epubReadView.setChapterPageEdge(chapterIndex, toLastPage = !resetPageOffset)
            return
        }
        if (!boundaryTransition &&
            navigationMode == EpubCoreNavigationMode.Sequential &&
            epubCoreActive &&
            currentRealPage != null &&
            binding.epubReadView.setChapterPageEdge(chapterIndex, toLastPage = !resetPageOffset)
        ) {
            finishEpubCoreInstantNavigation(chapterIndex)
            return
        }
        if (epubCoreLoading) {
            val previous = epubCoreForegroundTarget
            AppLog.putDebug(
                "EPUB core navigation preempt: from=${previous?.chapterIndex} to=$chapterIndex reset=$resetPageOffset"
            )
            cancelActiveEpubCoreNavigation(clearBoundary = !boundaryTransition)
        }
        val book = ReadBook.book?.takeIf { it.isEpub }
        val config = buildEpubCoreLayoutConfig()
        if (book != null && applyEpubCoreCachedPages(book, chapterIndex, config, resetPageOffset, navigationMode, boundaryTransition)) {
            return
        }
        openEpubCoreChapter(chapterIndex, resetPageOffset, navigationMode)
    }

    private fun startCachedEpubCoreBoundaryTurn(
        book: Book,
        chapterIndex: Int,
        config: EpubCoreLayoutConfig,
        direction: Int,
        resetPageOffset: Boolean
    ): Boolean {
        val cachedPages = EpubCoreProvider.peekPages(book, chapterIndex, config) ?: return false
        if (cachedPages.isEmpty()) return false
        AppLog.putDebug(
            "EPUB core boundary cache hit: index=$chapterIndex, pages=${cachedPages.size}, direction=$direction"
        )
        upEpubRendererStyle()
        binding.epubReadView.layoutConfig = config
        switchEpubCore(true)
        epubCoreSuppressProgressSync = false
        epubCoreBoundaryTransition = false
        epubCorePendingNavigation = null
        epubCoreLoading = false
        epubCoreLoadingChapterIndex = null
        epubCoreForegroundTarget = null
        binding.epubReadView.clearLoading()
        epubCoreEstimatedPages[chapterIndex] = cachedPages.size.coerceAtLeast(1)
        if (!binding.epubReadView.mergeAdjacentPages(cachedPages)) {
            binding.epubReadView.setPages(cachedPages, if (resetPageOffset) 0 else cachedPages.lastIndex)
            finishEpubCoreInstantNavigation(chapterIndex)
            return true
        }
        val turned = if (direction > 0) {
            binding.epubReadView.nextPage()
        } else {
            binding.epubReadView.previousPage()
        }
        if (!turned) {
            binding.epubReadView.setChapterPageEdge(chapterIndex, toLastPage = !resetPageOffset)
            finishEpubCoreInstantNavigation(chapterIndex)
        }
        return true
    }

    private fun applyEpubCoreCachedPages(
        book: Book,
        chapterIndex: Int,
        config: EpubCoreLayoutConfig,
        resetPageOffset: Boolean,
        navigationMode: EpubCoreNavigationMode,
        boundaryTransition: Boolean = false
    ): Boolean {
        val cachedPages = EpubCoreProvider.peekPages(book, chapterIndex, config) ?: return false
        if (cachedPages.isEmpty()) return false
        AppLog.putDebug("EPUB core navigation cache hit: index=$chapterIndex, pages=${cachedPages.size}")
        upEpubRendererStyle()
        binding.epubReadView.layoutConfig = config
        switchEpubCore(true)
        if (boundaryTransition && navigationMode == EpubCoreNavigationMode.Sequential) {
            epubCoreSuppressProgressSync = true
            epubCoreBoundaryTransition = false
            epubCorePendingNavigation = null
            epubCoreLoading = false
            epubCoreLoadingChapterIndex = null
            epubCoreForegroundTarget = null
            binding.epubReadView.clearLoading()
            val replaced = binding.epubReadView.replaceBoundaryLoadingPage(
                transactionId = epubCoreBoundaryTransactionId,
                direction = epubCoreBoundaryDirection,
                targetChapterIndex = chapterIndex,
                targetPages = cachedPages,
                resetPageOffset = epubCoreBoundaryTargetEdge != EpubCorePageEdge.End
            )
            if (!replaced) {
                binding.epubReadView.clearBoundaryLoadingTurn()
                if (!binding.epubReadView.mergeAdjacentPages(cachedPages)) {
                    binding.epubReadView.setPages(cachedPages, if (resetPageOffset) 0 else cachedPages.lastIndex)
                }
            }
            commitEpubCoreDisplayedChapter(
                chapterIndex = chapterIndex,
                chapterPageIndex = if (epubCoreBoundaryTargetEdge == EpubCorePageEdge.End) {
                    cachedPages.lastIndex
                } else {
                    0
                },
                requestSeq = epubCoreRequestSeq
            )
            return true
        } else {
            epubCoreSuppressProgressSync = true
            if (navigationMode == EpubCoreNavigationMode.ExplicitReplace ||
                !binding.epubReadView.mergeAdjacentPages(cachedPages)
            ) {
                binding.epubReadView.setPages(cachedPages, if (resetPageOffset) 0 else cachedPages.lastIndex)
            }
            binding.epubReadView.setChapterPageEdge(chapterIndex, toLastPage = !resetPageOffset)
            finishEpubCoreInstantNavigation(chapterIndex)
        }
        epubCoreBoundaryTransition = false
        return true
    }

    private fun finishEpubCoreInstantNavigation(chapterIndex: Int) {
        epubCoreSuppressProgressSync = false
        epubCorePendingNavigation = null
        val requestSeq = epubCoreRequestSeq
        epubCoreLoadJob?.cancel()
        epubCoreLoadJob = null
        epubCoreLoading = false
        epubCoreLoadingChapterIndex = null
        epubCoreForegroundTarget = null
        binding.epubReadView.clearLoading()
        epubCorePageCount = binding.epubReadView.currentChapterPageCount()
        if (epubCorePageCount > 0) {
            epubCoreEstimatedPages[chapterIndex] = epubCorePageCount
        }
        ReadBook.durChapterIndex = chapterIndex
        ReadBook.durChapterPos = binding.epubReadView.currentChapterPageIndex().coerceAtLeast(0)
        epubCoreCommittedChapterIndex = chapterIndex
        ReadBook.saveRead(true)
        binding.readMenu.upBookView()
        upSeekBarProgress()
        scheduleEpubCorePrefetchWindow(chapterIndex, requestSeq)
    }

    private fun consumePendingEpubCoreNavigation() {
        if (epubCoreLoading) return
        val pending = epubCorePendingNavigation ?: return
        epubCorePendingNavigation = null
        epubCoreBoundaryTransition = false
        AppLog.putDebug(
            "EPUB core boundary load after animation: index=${pending.chapterIndex}, reset=${pending.resetPageOffset}"
        )
        if (ReadBook.book?.isEpub != true) return
        requestEpubCoreChapter(
            pending.chapterIndex,
            pending.resetPageOffset,
            EpubCoreNavigationMode.ExplicitReplace,
            boundaryTransition = false
        )
    }

    override fun notifyBookChanged() {
        bookChanged = true
        if (!ReadBook.inBookshelf) {
            viewModel.removeFromBookshelf { super.finish() }
        }
    }

    override fun cancelSelect() {
        runOnUiThread {
            binding.readView.cancelSelect()
            binding.epubReadView.cancelSelect()
        }
    }

    /**
     * 页面改变
     */
    override fun pageChanged() {
        pageChanged = true
        if (!epubCoreActive) {
            binding.readView.onPageChange()
        }
        handler.post {
            upSeekBarProgress()
        }
        executor.execute {
            startBackupJob()
        }
    }

    /**
     * 更新进度条位置
     */
    private fun upSeekBarProgress() {
        val progress = when (AppConfig.progressBarBehavior) {
            "page" -> if (epubCoreActive) binding.epubReadView.currentChapterPageIndex() else ReadBook.durPageIndex
            else /* chapter */ -> ReadBook.durChapterIndex
        }
        binding.readMenu.setSeekPage(progress)
    }

    /**
     * 显示菜单
     */
    override fun showMenuBar() {
        binding.readMenu.runMenuIn()
    }

    override val oldBook: Book?
        get() = ReadBook.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (!book.isAudio) {
            viewModel.changeTo(book, toc)
        } else {
            ReadAloud.stop(this)
            lifecycleScope.launch {
                withContext(IO) {
                    ReadBook.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    ReadBook.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun replaceContent(content: String) {
        ReadBook.book?.let {
            viewModel.saveContent(it, content)
        }
    }

    override fun showActionMenu() {
        when {
            isAutoPage -> showDialogFragment<AutoReadDialog>()
            isShowingSearchResult -> binding.searchMenu.runMenuIn()
            else -> binding.readMenu.runMenuIn()
        }
    }

    /**
     * 显示朗读菜单
     */
    override fun showReadAloudDialog() {
        fun openPlayerPanel() {
            binding.readAloudPlayerPanel.openFromBottom(force = true)
        }
        fun startOrOpenPlayerPanel() {
            when {
                !BaseReadAloudService.isRun -> {
                    pendingReadAloudPlayerOpen = true
                    onClickReadAloud()
                }
                BaseReadAloudService.pause -> {
                    ReadAloud.resume(this)
                    openPlayerPanel()
                }
                else -> openPlayerPanel()
            }
        }
        if (binding.readMenu.isVisible) {
            binding.readMenu.runMenuOut {
                startOrOpenPlayerPanel()
            }
        } else {
            startOrOpenPlayerPanel()
        }
    }

    override fun onReadAloudPlayerVisibilityChanged(visible: Boolean) {
        ReadAloudAppCapsuleHost.updateReadBookPanelActive(visible)
        upSystemUiVisibility()
    }

    /**
     * 自动翻页
     */
    override fun autoPage() {
        ReadAloud.stop(this)
        if (isAutoPage) {
            autoPageStop()
        } else {
            binding.readView.autoPager.start()
            binding.readMenu.setAutoPage(true)
            screenTimeOut = -1L
            screenOffTimerStart()
        }
    }

    override fun autoPageStop() {
        if (isAutoPage) {
            binding.readView.autoPager.stop()
            binding.readMenu.setAutoPage(false)
            dismissDialogFragment<AutoReadDialog>()
            upScreenTimeOut()
        }
    }

    override fun openSourceEditActivity() {
        ReadBook.bookSource?.let {
            sourceEditActivity.launch {
                putExtra("sourceUrl", it.bookSourceUrl)
            }
        }
    }

    override fun openBookInfoActivity() {
        ReadBook.book?.let {
            bookInfoActivity.launch {
                putExtra("name", it.name)
                putExtra("author", it.author)
            }
        }
    }

    override fun returnToBookshelf() {
        finish()
    }

    override fun openReplaceRule() {
        replaceActivity.launch(Intent(this, ReplaceRuleActivity::class.java))
    }

    /**
     * 打开目录
     */
    override fun openChapterList() {
        ReadBook.book?.let {
            tocActivity.launch(it.bookUrl)
        }
    }

    /**
     * 打开搜索界面
     */
    override fun openSearchActivity(searchWord: String?) {
        val book = ReadBook.book ?: return
        searchContentActivity.launch {
            putExtra("bookUrl", book.bookUrl)
            putExtra("searchWord", searchWord ?: viewModel.searchContentQuery)
            putExtra("searchResultIndex", viewModel.searchResultIndex)
            viewModel.searchResultList?.first()?.let {
                if (it.query == viewModel.searchContentQuery) {
                    IntentData.put("searchResultList", viewModel.searchResultList)
                }
            }
        }
    }

    /**
     * 禁用书源
     */
    override fun disableSource() {
        viewModel.disableSource()
    }

    /**
     * 显示阅读样式配置
     */
    override fun showReadStyle() {
        showDialogFragment<ReadStyleDialog>()
    }

    /**
     * 显示更多设置
     */
    override fun showMoreSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    override fun showParagraphRuleQuickDialog() {
        val book = ReadBook.book ?: run {
            toastOnUi(R.string.paragraph_rule_no_book_hint)
            return
        }
        lifecycleScope.launch {
            val isEmpty = withContext(IO) {
                appDb.paragraphRuleDao.all().isEmpty()
            }
            if (isEmpty) {
                toastOnUi(R.string.paragraph_rule_empty)
                return@launch
            }
            ParagraphRuleQuickDialog.create(book.bookUrl)
                .show(supportFragmentManager, "paragraphRuleQuick")
        }
    }

    override fun showBubbleQuickSwitch() {
        showDialogFragment(
            BubbleQuickSwitchDialog.create(
                onSelected = { entry -> applyBubblePackageFromReadMenu(entry) },
                onManage = { startActivity<BubbleManageActivity>() }
            )
        )
    }

    private fun applyBubblePackageFromReadMenu(entry: BubblePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(IO) {
                    BubblePackageManager.apply(entry)
                    ImageProvider.clear()
                }
            }.onSuccess {
                postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    override fun runCustomReadMenuButton(id: Long) {
        val book = ReadBook.book ?: run {
            toastOnUi(R.string.paragraph_rule_no_book_hint)
            return
        }
        val chapter = ReadBook.curTextChapter?.chapter ?: run {
            toastOnUi(R.string.read_menu_no_current_chapter)
            return
        }
        lifecycleScope.launch {
            val button = withContext(IO) { appDb.readMenuCustomButtonDao.get(id) }
            if (button == null) {
                toastOnUi(R.string.read_menu_custom_button_missing)
                return@launch
            }
            kotlin.runCatching {
                withContext(IO) {
                    val content = BookHelp.getContent(book, chapter).orEmpty()
                    ReadMenuCustomButtonExecutor.execute(
                        this@ReadBookActivity,
                        button,
                        book,
                        chapter,
                        content,
                        ReadBook.bookSource
                    )
                }
            }.onFailure {
                AppLog.put("阅读菜单自定义按键执行失败: ${button.displayName()}\n${it.localizedMessage}", it, true)
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    override fun editCustomReadMenuButton(id: Long) {
        startActivity<ReadMenuCustomButtonEditActivity> {
            putExtra("id", id)
        }
    }

    override fun loginCustomReadMenuButton(id: Long) {
        lifecycleScope.launch {
            val button = withContext(IO) { appDb.readMenuCustomButtonDao.get(id) }
            if (button == null) {
                toastOnUi(R.string.read_menu_custom_button_missing)
                return@launch
            }
            if (button.loginUrl.isBlank() && button.loginUi.isBlank()) {
                toastOnUi(R.string.source_no_login)
                return@launch
            }
            startActivity<SourceLoginActivity> {
                putExtra("bookType", -1)
                putExtra("type", "readMenuCustomButton")
                putExtra("key", id.toString())
                ReadBook.book?.bookUrl?.let { putExtra("bookUrl", it) }
                putExtra("chapterIndex", ReadBook.durChapterIndex)
            }
        }
    }

    internal fun refreshParagraphRuleLayout() {
        if (ReadBook.book == null) return
        ReadBook.invalidateParagraphRuleLayout()
        ReadBook.callBack?.upContent(resetPageOffset = false)
        ReadBook.loadContent(resetPageOffset = false)
    }

    override fun openBookCharacters() {
        val book = ReadBook.book ?: run {
            toastOnUi("当前书籍不存在")
            return
        }
        startActivity<BookCharacterManageActivity> {
            putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, book.bookUrl)
        }
    }

    override fun showSearchSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    override fun isLibraryCloudEnabled(): Boolean {
        val book = ReadBook.book ?: return false
        if (book.isLocal) return false
        return when (BookCloudEntryModeStore.get(book.bookUrl)) {
            BookCloudEntryMode.CACHE_PACKAGE -> false
            BookCloudEntryMode.LIBRARY_CHAPTER ->
                LibraryContainerManager.readContainer() != null
        }
    }

    override fun libraryCloudState(): LibraryCloudState = libraryCloudState

    override fun showLibraryCloudChapters(refresh: Boolean) {
        val book = ReadBook.book ?: return
        if (BookCloudEntryModeStore.get(book.bookUrl) == BookCloudEntryMode.CACHE_PACKAGE) {
            return
        }
        val chapterIndex = ReadBook.durChapterIndex
        lifecycleScope.launch {
            val session = withContext(IO) {
                if (refresh) {
                    LibraryCloudSync.refreshSession(book)
                } else {
                    currentLibraryCloudSession() ?: LibraryCloudSync.openSession(book)
                }
            }
            libraryCloudSession = session
            libraryCloudState = session.state
            binding.readMenu.updateCloudLibraryState(session.state)
            if (session.state != LibraryCloudState.READY) {
                toastOnUi(libraryCloudStateMessage(session))
                return@launch
            }
            val currentChapter = withContext(IO) {
                appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
            } ?: run {
                toastOnUi("未找到当前章节")
                return@launch
            }
            val versions = withContext(IO) {
                session.listChapterVersions(currentChapter)
            }
            if (versions.isEmpty()) {
                toastOnUi("云端没有匹配当前章节的缓存")
                return@launch
            }
            showLibraryCloudChapterDialog(book, session, currentChapter, versions)
        }
    }

    override fun showLibraryCloudDebug() {
        if (!BuildConfig.DEBUG) return
        val book = ReadBook.book ?: return
        val chapterIndex = ReadBook.durChapterIndex
        lifecycleScope.launch {
            val chapter = withContext(IO) {
                appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
            }
            val container = LibraryContainerManager.readContainer()
            val v3Debug = if (chapter != null && container != null) {
                withContext(IO) {
                    val v3SharedBookKey = LibraryCloudKeys.sharedBookKey(book)
                    val v3ExactBookKey = LibraryCloudKeys.bookKey(book)
                    val v3ChapterKey = LibraryCloudKeys.libraryChapterKey(chapter)
                    val currentPaths = listOf(
                        LibraryCloudPaths.v3CurrentPath(v3SharedBookKey, v3ChapterKey),
                        LibraryCloudPaths.v3CurrentPath(v3ExactBookKey, v3ChapterKey)
                    ).distinct()
                    val manifestPaths = listOf(
                        LibraryCloudPaths.v3ManifestPath(v3ExactBookKey, v3ChapterKey),
                        LibraryCloudPaths.v3ManifestPath(v3SharedBookKey, v3ChapterKey)
                    ).distinct()
                    val backend = LibraryCloudBackend(container)
                    suspend fun currentState(path: String): String {
                        return runCatching {
                            val bytes = backend.downloadOrNull(path)
                                ?: return@runCatching "$path = missing"
                            val json = LibraryCloudCrypto.decodeString(bytes, container.password)
                            val payload = GSON.fromJsonObject<LibraryChapterPayloadV3>(json).getOrThrow()
                            "$path = exists source=${payload.sourceName.ifBlank { payload.sourceUrl }} hash=${payload.contentHash}"
                        }.getOrElse {
                            "$path = error ${it.localizedMessage}"
                        }
                    }
                    suspend fun manifestState(path: String): String {
                        return runCatching {
                            val bytes = backend.downloadOrNull(path)
                                ?: return@runCatching "$path = missing"
                            val json = LibraryCloudCrypto.decodeString(bytes, container.password)
                            val manifest = GSON.fromJsonObject<LibraryChapterManifestV3>(json).getOrThrow()
                            "$path = exists variants=${manifest.variants.size}"
                        }.getOrElse {
                            "$path = error ${it.localizedMessage}"
                        }
                    }
                    runCatching {
                        val currentLines = currentPaths.map { currentState(it) }
                        val manifestLines = manifestPaths.map { manifestState(it) }
                        buildString {
                            appendLine("v3CurrentState:")
                            currentLines.forEach { appendLine(it) }
                            appendLine("v3ManifestState:")
                            manifestLines.forEach { appendLine(it) }
                        }.trimEnd()
                    }.getOrElse {
                        "v3State=error ${it.localizedMessage}"
                    }
                }
            } else {
                "v3State=not_checked"
            }
            val message = buildString {
                appendLine("book.name=${book.name}")
                appendLine("book.author=${book.getRealAuthor()}")
                appendLine("book.bookUrl=${book.bookUrl}")
                appendLine("book.origin=${book.origin}")
                appendLine("book.originName=${book.originName}")
                appendLine()
                appendLine("exactBookKey=${LibraryCloudKeys.bookKey(book)}")
                appendLine("sharedBookKey=${LibraryCloudKeys.sharedBookKey(book)}")
                appendLine("allBookKeys=${LibraryCloudKeys.bookKeys(book).joinToString()}")
                appendLine("normalizedBookName=${LibraryCloudKeys.normalizeBookName(book.name)}")
                appendLine()
                appendLine("container=${LibraryContainerManager.displayLabel(container)}")
                appendLine("containerId=${container?.id.orEmpty()}")
                appendLine("containerPrefix=${container?.container?.prefix.orEmpty()}")
                appendLine()
                if (chapter == null) {
                    appendLine("chapter=null")
                } else {
                    appendLine("chapter.index=${chapter.index}")
                    appendLine("chapter.title=${chapter.title}")
                    appendLine("chapter.url=${chapter.url}")
                    appendLine("chapter.identity=${chapter.contentCacheIdentity()}")
                    appendLine("canonicalTitle=${LibraryCloudKeys.canonicalTextForKey(chapter.title)}")
                    appendLine("titleCodePoints=${LibraryCloudKeys.debugCodePoints(chapter.title)}")
                    appendLine("chapterKey=${LibraryCloudKeys.chapterKey(chapter)}")
                    appendLine("titleKey=${LibraryCloudKeys.titleKey(chapter)}")
                    appendLine("relaxedTitle=${LibraryCloudKeys.relaxedTitle(chapter.title)}")
                    appendLine("relaxedTitleKey=${LibraryCloudKeys.relaxedTitleKey(chapter)}")
                    appendLine("ordinalTitle=${LibraryCloudKeys.chapterOrdinal(chapter.title).orEmpty()}")
                    appendLine("ordinalTitleKey=${LibraryCloudKeys.ordinalTitleKey(chapter).orEmpty()}")
                    appendLine("matchKeys=${LibraryCloudKeys.matchKeys(chapter).joinToString { "${it.kind}:${it.key}" }}")
                    appendLine("sourceKey=${LibraryCloudKeys.sourceKey(book.origin)}")
                    appendLine()
                    val v3SharedBookKey = LibraryCloudKeys.sharedBookKey(book)
                    val v3ExactBookKey = LibraryCloudKeys.bookKey(book)
                    val v3ChapterKey = LibraryCloudKeys.libraryChapterKey(chapter)
                    val sourceKey = LibraryCloudKeys.sourceKey(book.origin)
                    appendLine("v3SharedBookKey=$v3SharedBookKey")
                    appendLine("v3ExactBookKey=$v3ExactBookKey")
                    appendLine("v3ChapterKey=$v3ChapterKey")
                    appendLine("v3SharedCurrentPath=${LibraryCloudPaths.v3CurrentPath(v3SharedBookKey, v3ChapterKey)}")
                    appendLine("v3ExactCurrentPath=${LibraryCloudPaths.v3CurrentPath(v3ExactBookKey, v3ChapterKey)}")
                    appendLine("v3LegacyManifestPath=${LibraryCloudPaths.v3ManifestPath(v3ExactBookKey, v3ChapterKey)}")
                    appendLine("v3LegacyPayloadPath=${LibraryCloudPaths.v3PayloadPath(v3ExactBookKey, v3ChapterKey, sourceKey)}")
                    appendLine(v3Debug)
                    appendLine("v3RequestEstimate.uploadNew=1B+1A")
                    appendLine("v3RequestEstimate.uploadDuplicate=1B")
                    appendLine("v3RequestEstimate.readHit=1B")
                    appendLine("v3RequestEstimate.readLegacyFallback<=4B")
                    appendLine("v3RequestEstimate.listVersions<=4B")
                    appendLine()
                    appendLine("listPrefixes:")
                    LibraryCloudKeys.bookKeys(book).forEach { bookKey ->
                        LibraryCloudKeys.matchKeys(chapter).forEach { matchKey ->
                            appendLine(LibraryCloudPaths.variantsPrefix(bookKey, matchKey))
                        }
                    }
                    appendLine()
                    appendLine("currentPaths:")
                    LibraryCloudKeys.bookKeys(book).forEach { bookKey ->
                        LibraryCloudKeys.matchKeys(chapter).forEach { matchKey ->
                            appendLine(LibraryCloudPaths.currentChapterPath(bookKey, matchKey))
                        }
                    }
                }
            }
            val debugTextView = TextView(this@ReadBookActivity).apply {
                text = message
                setTextIsSelectable(true)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 12.dpToPx())
            }
            val debugScrollView = ScrollView(this@ReadBookActivity).apply {
                addView(
                    debugTextView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            AlertDialog.Builder(this@ReadBookActivity)
                .setTitle("书库调试")
                .setView(debugScrollView)
                .setNegativeButton("复制") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("书库调试", message))
                    toastOnUi("已复制")
                }
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun refreshLibraryCloudSession(refresh: Boolean, silent: Boolean) {
        val book = ReadBook.book
        if (book == null || !isLibraryCloudEnabled()) {
            libraryCloudSession = null
            libraryCloudState = LibraryCloudState.DISABLED
            binding.readMenu.updateCloudLibraryState(libraryCloudState)
            return
        }
        lifecycleScope.launch {
            val session = withContext(IO) {
                if (refresh) {
                    LibraryCloudSync.refreshSession(book)
                } else {
                    currentLibraryCloudSession() ?: LibraryCloudSync.openSession(book)
                }
            }
            if (ReadBook.book?.bookUrl != book.bookUrl) return@launch
            libraryCloudSession = session
            libraryCloudState = session.state
            binding.readMenu.updateCloudLibraryState(session.state)
            if (!silent && session.state != LibraryCloudState.READY) {
                toastOnUi(libraryCloudStateMessage(session))
            }
        }
    }

    private fun currentLibraryCloudSession(): LibraryCloudSession? {
        val currentContainerId = LibraryContainerManager.readContainer()?.id
        return libraryCloudSession?.takeIf { it.config?.id == currentContainerId }
    }

    private fun downloadLibraryCloudChapter(
        book: Book,
        session: LibraryCloudSession,
        localChapter: BookChapter,
        item: LibraryCloudChapterVersion
    ) {
        lifecycleScope.launch {
            ReadBook.upMsg("读取云端章节")
            val result = withContext(IO) {
                runCatching {
                    val content = session.downloadChapter(item)
                        ?: throw NoStackTraceException("云端章节内容不存在")
                    BookHelp.saveText(book, localChapter, content)
                    localChapter
                }
            }
            ReadBook.upMsg(null)
            result.onSuccess { chapter ->
                if (ReadBook.book?.bookUrl != book.bookUrl) return@onSuccess
                LibraryCloudSync.setCloudReadingActive(book, true)
                libraryCloudState = LibraryCloudState.READY
                binding.readMenu.updateCloudLibraryState(libraryCloudState)
                if (isEpubCoreMode()) {
                    skipToChapter(chapter.index)
                } else {
                    viewModel.openChapter(chapter.index)
                }
            }.onFailure {
                toastOnUi("读取云端章节失败\n${it.localizedMessage}")
            }
        }
    }

    private fun showLibraryCloudChapterDialog(
        book: Book,
        session: LibraryCloudSession,
        currentChapter: BookChapter,
        items: List<LibraryCloudChapterVersion>
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
        }
        val summaryView = TextView(this).apply {
            text = "当前章节：${currentChapter.title}"
            textSize = 13f
            setTextColor(secondaryTextColor)
            setPadding(0, 0, 0, 8.dpToPx())
            applyUiBodyTypefaceDeep(this@ReadBookActivity.uiTypeface())
        }
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        var dialog: AlertDialog? = null
        items.groupBy { libraryCloudSourceGroupKey(it) }.forEach { (_, sourceItems) ->
            val sourceLabel = libraryCloudSourceLabel(sourceItems.first())
            listLayout.addView(TextView(this).apply {
                text = sourceLabel
                textSize = 14f
                setTextColor(secondaryTextColor)
                setPadding(2.dpToPx(), 12.dpToPx(), 2.dpToPx(), 6.dpToPx())
                applyUiTitleTypeface(this@ReadBookActivity)
            })
            sourceItems.forEach { item ->
                listLayout.addView(createLibraryCloudChapterRow(book, session, currentChapter, item) {
                    dialog?.dismiss()
                })
            }
        }
        val scrollView = NestedScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                listLayout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55f).toInt()
            )
        }
        content.addView(summaryView)
        content.addView(scrollView)
        dialog = AndroidAlertBuilder(this).apply {
            setTitle("选择书库章节")
            setCustomView(content)
            negativeButton(android.R.string.cancel)
        }.show()
    }

    private fun createLibraryCloudChapterRow(
        book: Book,
        session: LibraryCloudSession,
        currentChapter: BookChapter,
        item: LibraryCloudChapterVersion,
        dismissParent: () -> Unit
    ): View {
        val cardColor = themeCardColorOrDefault()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            background = UiCorner.opaqueRounded(
                cardColor,
                UiCorner.panelRadius(this@ReadBookActivity)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dpToPx()
            }
            addView(TextView(this@ReadBookActivity).apply {
                text = libraryCloudChapterTitle(item)
                setTextColor(primaryTextColor)
                textSize = 15f
                applyUiTitleTypeface(this@ReadBookActivity)
            })
            libraryCloudChapterTime(item)?.let { time ->
                addView(TextView(this@ReadBookActivity).apply {
                    text = time
                    textSize = 13f
                    setTextColor(secondaryTextColor)
                    setPadding(0, 4.dpToPx(), 0, 8.dpToPx())
                    applyUiBodyTypefaceDeep(this@ReadBookActivity.uiTypeface())
                })
            }
            addView(LinearLayout(this@ReadBookActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(libraryCloudActionText("读取", accentColor) {
                    dismissParent()
                    downloadLibraryCloudChapter(book, session, currentChapter, item)
                })
                addView(libraryCloudActionText("删除", 0xFFD32F2F.toInt()) {
                    confirmDeleteLibraryCloudChapter(book, session, currentChapter, item, dismissParent)
                })
            })
            setOnClickListener {
                dismissParent()
                downloadLibraryCloudChapter(book, session, currentChapter, item)
            }
        }
    }

    private fun libraryCloudActionText(
        text: String,
        textColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 14f
            setPadding(0, 4.dpToPx(), 14.dpToPx(), 4.dpToPx())
            applyUiBodyTypefaceDeep(this@ReadBookActivity.uiTypeface())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun confirmDeleteLibraryCloudChapter(
        book: Book,
        session: LibraryCloudSession,
        currentChapter: BookChapter,
        item: LibraryCloudChapterVersion,
        dismissParent: () -> Unit
    ) {
        val input = EditText(this).apply {
            hint = "请输入 删除"
            setSingleLine(true)
            typeface = uiTypeface()
            setPadding(20.dpToPx(), 8.dpToPx(), 20.dpToPx(), 8.dpToPx())
        }
        val confirmDialog = AndroidAlertBuilder(this).apply {
            setTitle("删除云端章节")
            setMessage("将删除云端章节：${item.payload.title.ifBlank { currentChapter.title }}\n请输入“删除”确认。")
            setCustomView(input)
            negativeButton(android.R.string.cancel)
            positiveButton("删除")
        }.show()
        confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.text?.toString()?.trim() != "删除") {
                toastOnUi("请输入 删除 后再确认")
                return@setOnClickListener
            }
            confirmDialog.dismiss()
            dismissParent()
            deleteLibraryCloudChapter(book, session, currentChapter, item)
        }
    }

    private fun deleteLibraryCloudChapter(
        book: Book,
        session: LibraryCloudSession,
        currentChapter: BookChapter,
        item: LibraryCloudChapterVersion
    ) {
        lifecycleScope.launch {
            ReadBook.upMsg("删除云端章节")
            val result = withContext(IO) {
                runCatching {
                    if (!session.deleteChapter(item)) {
                        throw NoStackTraceException("云端章节不存在或删除失败")
                    }
                    LibraryCloudSync.refreshSession(book)
                }
            }
            ReadBook.upMsg(null)
            val refreshedSession = result.getOrNull()
            if (refreshedSession != null) {
                if (ReadBook.book?.bookUrl != book.bookUrl) return@launch
                libraryCloudSession = refreshedSession
                libraryCloudState = refreshedSession.state
                binding.readMenu.updateCloudLibraryState(refreshedSession.state)
                toastOnUi("已删除云端章节")
                showLibraryCloudChapters(refresh = false)
            } else {
                toastOnUi("删除云端章节失败\n${result.exceptionOrNull()?.localizedMessage.orEmpty()}")
            }
        }
    }

    private fun libraryCloudSourceGroupKey(item: LibraryCloudChapterVersion): String {
        val payload = item.payload
        return listOf(payload.sourceUrl, payload.sourceBookUrl, payload.sourceName).joinToString("\u001F")
    }

    private fun libraryCloudSourceLabel(item: LibraryCloudChapterVersion): String {
        return item.payload.sourceName
            .ifBlank { item.payload.sourceUrl }
            .ifBlank { "旧缓存/未知来源" }
    }

    private fun libraryCloudChapterTitle(item: LibraryCloudChapterVersion): String {
        val payload = item.payload
        val title = payload.title.ifBlank { payload.normalizedTitle.ifBlank { payload.chapterKey } }
        val prefix = if (payload.chapterIndex >= 0) "${payload.chapterIndex + 1}. " else ""
        return "$prefix$title"
    }

    private fun libraryCloudChapterTime(item: LibraryCloudChapterVersion): String? {
        return item.payload.updatedAt.takeIf { it > 0L }?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        }
    }

    private fun libraryCloudStateMessage(session: LibraryCloudSession): String {
        return when (session.state) {
            LibraryCloudState.DISABLED -> "未配置书库容器"
            LibraryCloudState.READY -> "云端书库可用"
            LibraryCloudState.ERROR -> session.errorMessage?.let { "云端书库读取失败\n$it" }
                ?: "云端书库读取失败"
        }
    }

    /**
     * 更新状态栏,导航栏
     */
    override fun upSystemUiVisibility() {
        if (binding.readAloudPlayerPanel.isFullPanelActive()) {
            applyReadAloudPlayerSystemBars()
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            upSystemUiVisibility(isInMultiWindow, !menuLayoutIsVisible, bottomDialog > 0)
            upNavigationBarColor()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyReadAloudPlayerSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding.navigationBar.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.getInsetsController(window, binding.root)?.run {
                if (ReadBookConfig.hideNavigationBar) {
                    hide(WindowInsetsCompat.Type.navigationBars())
                } else {
                    show(WindowInsetsCompat.Type.navigationBars())
                }
                if (ReadBookConfig.hideStatusBar) {
                    hide(WindowInsetsCompat.Type.statusBars())
                } else {
                    show(WindowInsetsCompat.Type.statusBars())
                }
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        var flag = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (ReadBookConfig.hideNavigationBar) {
            flag = flag or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
        if (ReadBookConfig.hideStatusBar) {
            flag = flag or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        window.decorView.systemUiVisibility = flag
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setLightStatusBar(false)
    }

    override fun onNightThemeChanged() = binding.run {
        ThemeConfig.applyTheme(this@ReadBookActivity)
        readMenu.reset()
        if (epubCoreActive) {
            if (epubReadView.width > 0 && epubReadView.height > 0) {
                ReadBookConfig.upBg(epubReadView.width, epubReadView.height)
            }
            epubReadView.clearSelection()
            ReadBook.book?.takeIf { it.isEpub }?.let { book ->
                cancelEpubCoreForegroundHard(book)
                cancelEpubCorePrefetchHard(book)
            }
            refreshEpubCoreAfterConfigurationChange()
        } else {
            readView.refreshVisualStyle()
        }
        upSystemUiVisibility()
    }

    // 退出全文搜索
    override fun exitSearchMenu() {
        if (isShowingSearchResult) {
            isShowingSearchResult = false
            binding.searchMenu.invalidate()
            binding.searchMenu.invisible()
            ReadBook.clearSearchResult()
            binding.readView.cancelSelect(true)
        }
    }

    /* 恢复到 全文搜索/进度条跳转前的位置 */
    private fun restoreLastBookProcess() {
        if (confirmRestoreProcess == true) {
            ReadBook.restoreLastBookProgress()
        } else if (confirmRestoreProcess == null) {
            alert(R.string.draw) {
                setMessage(R.string.restore_last_book_process)
                yesButton {
                    confirmRestoreProcess = true
                    ReadBook.restoreLastBookProgress() //恢复启动全文搜索前的进度
                }
                noButton {
                    ReadBook.clearLastBookProgress()
                    confirmRestoreProcess = false
                }
                onCancelled {
                    ReadBook.clearLastBookProgress()
                    confirmRestoreProcess = false
                }
            }
        }
    }

    private fun clearRestoreProcessState() {
        confirmRestoreProcess = null
        ReadBook.clearLastBookProgress()
    }

    override fun showLogin() {
        ReadBook.bookSource?.let {
            startActivity<SourceLoginActivity> {
                putExtra("bookType", BookType.text)
            }
        }
    }

    override fun payAction() {
        val book = ReadBook.book ?: return
        if (book.isLocal) return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
        if (chapter == null) {
            toastOnUi("no chapter")
            return
        }
        alert(R.string.chapter_pay) {
            setMessage(chapter.title)
            yesButton {
                Coroutine.async(lifecycleScope) {
                    val source =
                        ReadBook.bookSource ?: throw NoStackTraceException("no book source")
                    val payAction = source.getContentRule().payAction
                    if (payAction.isNullOrBlank()) {
                        throw NoStackTraceException("no pay action")
                    }
                    val java = SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
                    runScriptWithContext {
                        source.evalJS(payAction) {
                            put("java", java)
                            put("book", book)
                            put("chapter", chapter)
                            put("title", chapter.title)
                            put("baseUrl", chapter.url)
                            put("result", null)
                            put("src", null)
                        }.toString()
                    }
                }.onSuccess(IO) {
                    if (it.isAbsUrl()) {
                        startActivity<WebViewActivity> {
                            val bookSource = ReadBook.bookSource
                            putExtra("title", getString(R.string.chapter_pay))
                            putExtra("url", it)
                            putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                            putExtra("sourceName", bookSource?.bookSourceName)
                            putExtra("sourceType", bookSource?.getSourceType())
                        }
                    } else if (it.isTrue()) {
                        //购买成功后刷新目录
                        ReadBook.book?.let {
                            ReadBook.curTextChapter = null
                            BookHelp.delContent(book, chapter)
                            loadChapterList(book)
                        }
                    }
                }.onError {
                    AppLog.put("执行购买操作出错\n${it.localizedMessage}", it, true)
                }
            }
            noButton()
        }
    }

    /**
     * 点击图片
     */
    private fun evalParagraphRuleClick(click: String?, src: String): Boolean {
        if (!ParagraphRuleProcessor.isParagraphClick(click)) return false
        if (commentBrowserOpening || commentBrowserShowing) return true
        val clickValue = click ?: return false
        commentBrowserOpening = true
        getCommentWebViewSession().prepare(applicationContext)
        Coroutine.async(lifecycleScope, IO) {
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: throw Exception("no find chapter")
            ParagraphRuleProcessor.evalClick(
                book,
                chapter,
                clickValue,
                src,
                paragraphRuleBrowserCallback(),
                coroutineContext
            )
        }.onError {
            AppLog.put("ParagraphRule pclick error: ${it.localizedMessage}", it, true)
        }.onFinally {
            if (!commentBrowserShowing) {
                commentBrowserOpening = false
            }
        }
        return true
    }
    private fun getCommentWebViewSession(): CommentWebViewSession {
        return commentWebViewSession ?: CommentWebViewSession.shared.also { commentWebViewSession = it }
    }

    private fun ensureCurrentChapterCacheForClick(book: Book, chapter: BookChapter) {
        val current = ReadBook.curTextChapter
            ?.takeIf { it.chapter.index == chapter.index }
            ?.getContent()
            ?.takeIf { it.isNotBlank() }
        if (!BookHelp.hasContent(book, chapter) && current != null) {
            BookHelp.saveText(book, chapter, current)
        }
        BookHelp.ensureLegacyContentAlias(book, chapter, current)
    }

    private fun paragraphRuleBrowserCallback(): ParagraphRuleProcessor.BrowserCallback {
        return object : ParagraphRuleProcessor.BrowserCallback {
            override fun showBrowser(
                url: String,
                html: String?,
                preloadJs: String?,
                config: String?,
                sourceKey: String?
            ): Boolean {
                val browserSourceKey = sourceKey ?: ReadBook.bookSource?.getKey() ?: return false
                commentBrowserShowing = true
                runOnUiThread {
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        commentBrowserShowing = false
                        commentBrowserOpening = false
                        return@runOnUiThread
                    }
                    showDialogFragment(
                        BottomWebViewDialog(
                            browserSourceKey,
                            BookType.text,
                            url,
                            html,
                            preloadJs,
                            config,
                            getCommentWebViewSession(),
                            onDismiss = {
                                commentBrowserShowing = false
                                commentBrowserOpening = false
                            }
                        )
                    )
                }
                return true
            }
        }
    }

    override fun oldClickImg(src: String): Boolean {
        val urlMatcher = paramPattern.matcher(src)
        if (urlMatcher.find()) {
            val urlOptionStr = src.substring(urlMatcher.end())
            val urlOptionMap = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()
            val pclick = urlOptionMap?.get("pclick")
            if (!pclick.isNullOrBlank() && evalParagraphRuleClick(pclick, src)) return true
            val click = urlOptionMap?.get("click")
                ?.takeUnless { ParagraphRuleProcessor.isParagraphClick(it) }
            if (!click.isNullOrBlank()) {
                Coroutine.async(lifecycleScope,IO) {
                    val source = ReadBook.bookSource ?: return@async
                    val java = SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
                    val book = ReadBook.book ?: return@async
                    val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
                    ensureCurrentChapterCacheForClick(book, chapter)
                    runScriptWithContext {
                        source.evalJS(click) {
                            put("java", java)
                            put("book", book)
                            put("chapter", chapter)
                            put("result", src)
                        }
                    }
                }.onError {
                    AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
                }
                return true
            }
            val jsStr = urlOptionMap?.get("js") ?: return false
            Coroutine.async(lifecycleScope, IO) {
                val source = ReadBook.bookSource ?: return@async
                val book = ReadBook.book ?: return@async
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
                val urlNoOption = src.take(urlMatcher.start())
                AnalyzeRule(book, source).apply {
                    setCoroutineContext(coroutineContext)
                    setBaseUrl(chapter.url)
                    setChapter(chapter)
                    evalJS(jsStr, urlNoOption)
                }
            }.onError {
                AppLog.put("执行图片链接js键值出错\n${it.localizedMessage}", it, true)
            }
            return true
        }
        return false
    }

    override fun clickImg(click: String, src: String) {
        if (evalParagraphRuleClick(click, src)) return
        Coroutine.async(lifecycleScope,IO) {
            val source = ReadBook.bookSource ?: return@async
            val java = SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
            ensureCurrentChapterCacheForClick(book, chapter)
            runScriptWithContext {
                source.evalJS(click) {
                    put("java", java)
                    put("book", book)
                    put("chapter", chapter)
                    put("result", src)
                }
            }
        }.onError {
            AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
        }
    }


    /**
     * 朗读按钮
     */
    override fun onClickReadAloud() {
        autoPageStop()
        when {
            !BaseReadAloudService.isRun -> {
                ReadAloud.upReadAloudClass()
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim) {
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadBook.readAloud()
                }
            }

            BaseReadAloudService.pause -> {
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    pageChanged = false
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadAloud.resume(this)
                }
            }

            else -> ReadAloud.pause(this)
        }
    }

    override fun showHelp() {
        showHelp("readMenuHelp")
    }

    /**
     * 长按图片
     */
    @SuppressLint("RtlHardcoded")
    override fun onImageLongPress(
        x: Float,
        y: Float,
        src: String,
        paragraphNum: Int,
        imageIndexInParagraph: Int
    ) {
        val aiImageId = AiImageGalleryManager.imageIdFromUri(src)
        val items = mutableListOf(
            SelectItem(getString(R.string.show), "show"),
            SelectItem(getString(R.string.refresh), "refresh"),
            SelectItem(getString(R.string.action_save), "save"),
            SelectItem(getString(R.string.menu), "menu"),
            SelectItem(getString(R.string.select_folder), "selectFolder")
        )
        if (aiImageId != null) {
            items += SelectItem(getString(R.string.ai_image_delete_insert), "deleteAiImage")
        }
        popupAction.setItems(
            items
        )
        popupAction.onActionClick = {
            when (it) {
                "show" -> showDialogFragment(PhotoDialog(src, isBook = true))
                "refresh" -> viewModel.refreshImage(src)
                "save" -> {
                    val path = ACache.get().getAsString(AppConst.imagePathKey)
                    if (path.isNullOrEmpty()) {
                        selectImageDir.launch {
                            value = src
                        }
                    } else {
                        viewModel.saveImage(src, path.toUri())
                    }
                }

                "menu" -> showActionMenu()
                "selectFolder" -> selectImageDir.launch()
                "deleteAiImage" -> confirmDeleteAiInsertedImage(src, paragraphNum, imageIndexInParagraph)
            }
            popupAction.dismiss()
        }
        val navigationBarHeight =
            if (!ReadBookConfig.hideNavigationBar && navigationBarGravity == Gravity.BOTTOM)
                binding.navigationBar.height else 0
        popupAction.showAtLocation(
            binding.readView, Gravity.BOTTOM or Gravity.LEFT, x.toInt(),
            binding.root.height + navigationBarHeight - y.toInt()
        )
    }

    private fun confirmDeleteAiInsertedImage(
        src: String,
        paragraphNum: Int,
        imageIndexInParagraph: Int
    ) {
        alert(titleResource = R.string.delete, messageResource = R.string.ai_image_delete_insert_confirm) {
            okButton {
                deleteAiInsertedImage(src, paragraphNum, imageIndexInParagraph)
            }
            cancelButton()
        }
    }

    private fun deleteAiInsertedImage(
        src: String,
        paragraphNum: Int,
        imageIndexInParagraph: Int
    ) {
        lifecycleScope.launch {
            val deleted = withContext(IO) {
                removeAiInsertedImageFromCurrentChapter(src, paragraphNum, imageIndexInParagraph)
            }
            if (deleted) {
                ReadBook.clearTextChapter()
                postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                toastOnUi(R.string.ai_image_insert_deleted)
            } else {
                toastOnUi(R.string.ai_image_insert_not_found)
            }
        }
    }

    private fun removeAiInsertedImageFromCurrentChapter(
        src: String,
        paragraphNum: Int,
        imageIndexInParagraph: Int
    ): Boolean {
        val imageId = AiImageGalleryManager.imageIdFromUri(src) ?: return false
        val book = ReadBook.book ?: return false
        val chapter = ReadBook.curTextChapter?.chapter ?: return false
        val rawContent = BookHelp.getContent(book, chapter).orEmpty()
        if (rawContent.isBlank()) return false
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val lines = contentProcessor.getContent(book, chapter, rawContent, includeTitle = false)
            .textList
            .toMutableList()
        val targetIndex = currentChapterContentIndex(paragraphNum).takeIf { it in lines.indices }
            ?: return false
        val newLine = removeAiImageTag(lines[targetIndex], imageId, imageIndexInParagraph)
            ?: return false
        lines[targetIndex] = newLine
        BookHelp.saveText(book, chapter, lines.joinToString("\n"))
        return true
    }

    private fun currentChapterContentIndex(paragraphNum: Int): Int {
        val textChapter = ReadBook.curTextChapter ?: return -1
        val paragraphs = textChapter.getParagraphs(pageSplit = false)
        val targetParagraph = paragraphs.firstOrNull { it.realNum == paragraphNum }
            ?: return -1
        return targetParagraph.sourceIndex
    }

    private fun removeAiImageTag(
        line: String,
        imageId: String,
        imageIndexInParagraph: Int
    ): String? {
        val matcher = AppPattern.imgPattern.matcher(line)
        val result = StringBuffer()
        var sameImageIndex = 0
        var removed = false
        while (matcher.find()) {
            val matchedSrc = matcher.group(1)
            if (
                AiImageGalleryManager.imageIdFromUri(matchedSrc) == imageId &&
                sameImageIndex++ == imageIndexInParagraph
            ) {
                matcher.appendReplacement(result, "")
                removed = true
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0).orEmpty()))
            }
        }
        if (!removed) return null
        matcher.appendTail(result)
        return result.toString()
    }

    /**
     * colorSelectDialog
     */
    override fun onColorSelected(dialogId: Int, color: Int) = ReadBookConfig.durConfig.run {
        when (dialogId) {
            TEXT_COLOR -> {
                setCurTextColor(color)
                postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6, 9, 11))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            TEXT_ACCENT_COLOR -> {
                setCurTextAccentColor(color)
                postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6, 9, 11))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            BG_COLOR -> {
                setCurBg(0, "#${color.hexString}")
                postEvent(EventBus.UP_CONFIG, arrayListOf(1))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            TIP_COLOR -> {
                ReadTipConfig.tipColor = color
                postEvent(EventBus.TIP_COLOR, "")
                postEvent(EventBus.UP_CONFIG, arrayListOf(2))
            }

            TIP_DIVIDER_COLOR -> {
                ReadTipConfig.tipDividerColor = color
                postEvent(EventBus.TIP_COLOR, "")
                postEvent(EventBus.UP_CONFIG, arrayListOf(2))
            }
        }
    }

    /**
     * colorSelectDialog
     */
    override fun onDialogDismissed(dialogId: Int) = Unit

    override fun onTocRegexDialogResult(tocRegex: String) {
        ReadBook.book?.let {
            it.tocUrl = tocRegex
            loadChapterList(it)
        }
    }

    private fun sureSyncProgress(progress: BookProgress) {
        alert(R.string.get_book_progress) {
            setMessage(R.string.current_progress_exceeds_cloud)
            okButton {
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    /* 进度条跳转到指定章节 */
    override fun skipToChapter(index: Int) {
        ReadBook.saveCurrentBookProgress() //退出章节跳转恢复此时进度
        if (isEpubCoreMode()) {
            openEpubCoreChapter(index, resetPageOffset = true)
            return
        }
        viewModel.openChapter(index)
    }

    /* 全文搜索跳转 */
    override fun navigateToSearch(searchResult: SearchResult, index: Int) {
        if (isEpubCoreMode()) {
            skipToChapter(searchResult.chapterIndex)
            return
        }
        viewModel.searchResultIndex = index
        skipToSearch(searchResult)
    }

    override fun onMenuShow() {
        binding.readAloudPlayerPanel.setReadMenuVisible(true)
        binding.readMenu.post { syncReadMenuAvoidBounds() }
        binding.readMenu.doOnLayout { syncReadMenuAvoidBounds() }
        if (epubCoreActive) return
        binding.readView.autoPager.pause()
    }

    override fun onMenuHide() {
        binding.readAloudPlayerPanel.setReadMenuAvoidBounds(null)
        if (epubCoreActive) return
        binding.readView.autoPager.resume()
    }

    private fun syncReadMenuAvoidBounds() {
        val bounds = binding.readMenu.bottomMenuBoundsIn(binding.readAloudPlayerPanel)
        binding.readAloudPlayerPanel.setReadMenuAvoidBounds(bounds)
    }

    override fun epubCorePageCount(): Int {
        return if (epubCoreActive) binding.epubReadView.currentChapterPageCount() else 0
    }

    override fun epubCorePageIndex(): Int {
        return if (epubCoreActive) binding.epubReadView.currentChapterPageIndex() else 0
    }

    override fun isEpubCoreBook(): Boolean {
        return isEpubCoreMode()
    }

    override fun epubCoreChapterTitle(): String? {
        val book = ReadBook.book?.takeIf { it.isEpub } ?: return null
        val chapterIndex = epubCoreForegroundTarget?.chapterIndex
            ?: epubCoreLoadingChapterIndex
            ?: binding.epubReadView.currentPage()?.chapterIndex
            ?: ReadBook.durChapterIndex
        return appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)?.title
            ?: book.durChapterTitle?.takeIf { it.isNotBlank() }
    }

    override fun epubCoreChapterUrl(): String? {
        val book = ReadBook.book?.takeIf { it.isEpub } ?: return null
        val chapterIndex = epubCoreForegroundTarget?.chapterIndex
            ?: epubCoreLoadingChapterIndex
            ?: binding.epubReadView.currentPage()?.chapterIndex
            ?: ReadBook.durChapterIndex
        return appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)?.url
    }

    override fun skipToEpubCorePage(index: Int): Boolean {
        if (!epubCoreActive) return false
        if (epubCoreLoading || epubCorePageCount <= 0) return true
        val pageCount = binding.epubReadView.currentChapterPageCount().coerceAtLeast(1)
        val pageIndex = index.coerceIn(0, pageCount - 1)
        val changed = binding.epubReadView.setChapterPageIndex(ReadBook.durChapterIndex, pageIndex)
        if (!changed) {
            ReadBook.durChapterPos = pageIndex
            ReadBook.saveRead(true)
            upSeekBarProgress()
        }
        return true
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upSeekBarThrottle.invoke()
        if (!epubCoreActive) {
            binding.readView.onLayoutPageCompleted(index, page)
        }
    }

    /* 全文搜索跳转 */
    private fun skipToSearch(searchResult: SearchResult) {
        if (searchResult.chapterIndex != ReadBook.durChapterIndex) {
            viewModel.openChapter(searchResult.chapterIndex) {
                jumpToPosition(searchResult)
            }
        } else {
            jumpToPosition(searchResult)
        }
    }

    private fun jumpToPosition(searchResult: SearchResult) {
        val curTextChapter = ReadBook.curTextChapter ?: return
        binding.searchMenu.updateSearchInfo()
        val searchResultPositions =
            viewModel.searchResultPositions(curTextChapter, searchResult)
        val (pageIndex, lineIndex, charIndex, addLine, charIndex2) = searchResultPositions
        ReadBook.skipToPage(pageIndex) {
            isSelectingSearchResult = true
            binding.readView.curPage.selectStartMoveIndex(0, lineIndex, charIndex)
            when (addLine) {
                0 -> binding.readView.curPage.selectEndMoveIndex(
                    0,
                    lineIndex,
                    charIndex + searchResultPositions[5] - 1
                )

                1 -> binding.readView.curPage.selectEndMoveIndex(
                    0, lineIndex + 1, charIndex2
                )
                //consider change page, jump to scroll position
                -1 -> binding.readView.curPage.selectEndMoveIndex(1, 0, charIndex2)
            }
            binding.readView.isTextSelected = true
            isSelectingSearchResult = false
        }
    }

    override fun addBookmark() {
        val book = ReadBook.book
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex)
        if (book != null && page != null) {
            val bookmark = book.createBookMark().apply {
                chapterIndex = ReadBook.durChapterIndex
                chapterPos = ReadBook.durChapterPos
                chapterName = page.title
                bookText = page.text.trim()
            }
            showDialogFragment(BookmarkDialog(bookmark))
        }
    }

    override fun changeReplaceRuleState() {
        ReadBook.book?.let {
            it.setUseReplaceRule(!it.getUseReplaceRule())
            ReadBook.saveRead(fullUpdate = true)
            menu?.findItem(R.id.menu_enable_replace)?.isChecked = it.getUseReplaceRule()
            viewModel.replaceRuleChanged()
        }
    }

    override fun refreshContent() {
        if (ReadBook.bookSource == null) {
            upContent()
        } else {
            ReadBook.book?.let {
                ReadBook.curTextChapter = null
                binding.readView.upContent()
                viewModel.refreshContentDur(it)
            }
        }
    }

    override fun changeSource() {
        binding.readMenu.runMenuOut()
        ReadBook.book?.let {
            showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
        }
    }

    override fun changeSourceSingle() {
        lifecycleScope.launch {
            val book = ReadBook.book ?: return@launch
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: return@launch
            binding.readMenu.runMenuOut()
            showDialogFragment(
                ChangeChapterSourceDialog(book.name, book.author, chapter.index, chapter.title)
            )
        }
    }

    override fun showRefreshOptions() {
        val labels = listOf(
            getString(R.string.menu_refresh_dur),
            getString(R.string.menu_refresh_after),
            getString(R.string.menu_refresh_all)
        )
        binding.readMenu.runMenuOut()
        selector(R.string.refresh, labels) { _, index ->
            if (ReadBook.bookSource == null) {
                upContent()
                return@selector
            }
            ReadBook.book?.let { book ->
                when (index) {
                    0 -> {
                        ReadBook.curTextChapter = null
                        binding.readView.upContent()
                        viewModel.refreshContentDur(book)
                    }
                    1 -> {
                        ReadBook.clearTextChapter()
                        binding.readView.upContent()
                        viewModel.refreshContentAfter(book)
                    }
                    2 -> refreshContentAll(book)
                }
            }
        }
    }

    override fun showCacheDialog() {
        showDownloadDialog()
    }

    override fun editContent() {
        showDialogFragment(ContentEditDialog())
    }

    override fun showPageAnim() {
        showPageAnimConfig {
            binding.readView.upPageAnim()
            ReadBook.relayoutCurrentContent("page-anim")
        }
    }

    override fun editMenu() {
        startActivity<ReadMenuButtonManageActivity>()
    }

    override fun updateToc() {
        ReadBook.book?.let {
            if (it.isEpub) {
                BookHelp.clearCache(it)
                EpubFile.clear()
            }
            if (it.isMobi) {
                MobiFile.clear()
            }
            loadChapterList(it)
        }
    }

    override fun reverseContent() {
        ReadBook.book?.let {
            viewModel.reverseContent(it)
        }
    }

    override fun showReSegment() {
        ReadBook.book?.let {
            it.setReSegment(!it.getReSegment())
            ReadBook.saveRead(fullUpdate = true)
            ReadBook.reloadCurrentContent("re-segment")
        }
    }

    override fun showSameTitleRemoved() {
        ReadBook.book?.let {
            val contentProcessor = ContentProcessor.get(it)
            val textChapter = ReadBook.curTextChapter
            if (textChapter != null
                && !textChapter.sameTitleRemoved
                && !BookHelp.getChapterCacheFileNames(it, textChapter.chapter, "nr")
                    .any(contentProcessor.removeSameTitleCache::contains)
            ) {
                toastOnUi("未找到可移除的重复标题")
            }
        }
        viewModel.reverseRemoveSameTitle()
    }

    override fun showImageStyle() {
        val imgStyles = arrayListOf(
            Book.imgStyleDefault,
            Book.imgStyleFull,
            Book.imgStyleText,
            Book.imgStyleSingle
        )
        selector(R.string.image_style, imgStyles) { _, index ->
            val imageStyle = imgStyles[index]
            ReadBook.book?.setImageStyle(imageStyle)
            if (imageStyle == Book.imgStyleSingle) {
                ReadBook.book?.setPageAnim(0)
                binding.readView.upPageAnim()
            }
            ReadBook.saveRead(fullUpdate = true)
            ReadBook.reloadCurrentContent("image-style")
        }
    }

    override fun showParagraphRuleManage() {
        ReadBook.book?.let {
            startActivity<ParagraphRuleManageActivity> {
                putExtra("bookUrl", it.bookUrl)
            }
        }
    }

    override fun showEffectiveReplaces() {
        showDialogFragment<EffectiveReplacesDialog>()
    }

    override fun showLog() {
        showDialogFragment<AppLogDialog>()
    }

    override fun showGetProgress() {
        ReadBook.book?.let {
            viewModel.syncBookProgress(it) { progress ->
                sureSyncProgress(progress)
            }
        }
    }

    override fun showCoverProgress() {
        ReadBook.book?.let {
            ReadBook.uploadProgress(true) { toastOnUi(R.string.upload_book_success) }
        }
    }

    private fun startBackupJob() {
        backupJob?.cancel()
        backupJob = lifecycleScope.launch(IO) {
            delay(300000)
            ReadBook.book?.let {
                AppCloudStorage.uploadBookProgress(it)
                ensureActive()
                it.update()
            }
        }
    }

    override fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.get_book_progress) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    override fun finish() {
        val book = ReadBook.book ?: return super.finish()
        if (ReadBook.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    ReadBook.book?.removeType(BookType.notShelf)
                    ReadBook.book?.save()
                    SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, ReadBook.bookSource, ReadBook.book)
                    ReadBook.inBookshelf = true
                    setResult(RESULT_OK)
                    callBackBookEnd()
                    super.finish()
                }
                noButton {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(SourceCallBack.END_READ, ReadBook.bookSource, ReadBook.book, ReadBook.curTextChapter?.chapter)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            ReadAloudAppCapsuleHost.updateReadBookPanelActive(false)
        }
        clearRestoreProcessState()
        super.onDestroy()
        epubCoreRequestSeq++
        epubCoreLoadJob?.cancel()
        epubCoreLoadJob = null
        cancelEpubCorePrefetch()
        epubCoreLoading = false
        epubCoreLoadingChapterIndex = null
        epubCorePendingNavigation = null
        epubCoreForegroundTarget = null
        epubCoreCommittedChapterIndex = null
        epubCoreBoundaryTargetEdge = EpubCorePageEdge.Start
        val releasedReadSession = ReadBook.unregister(this)
        if (releasedReadSession) {
            EpubCoreProvider.clear()
        }
        tts?.clearTts()
        textActionMenu.dismiss()
        popupAction.dismiss()
        shareNotePreviewOverlay?.dismiss()
        shareNotePreviewOverlay = null
        binding.readView.onDestroy()
        commentWebViewSession?.destroy()
        commentWebViewSession = null
        commentBrowserOpening = false
        commentBrowserShowing = false
        handler.removeCallbacksAndMessages(null) // 清理Handler消息
        if (!ReadBook.inBookshelf && !isChangingConfigurations) {
            viewModel.removeFromBookshelf(null)
        }
    }


    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        commentWebViewSession?.trimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                ImageProvider.clear()
                LottieImageBitmapCache.clear()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                ImageProvider.trimMemory()
                LottieImageBitmapCache.trimMemory()
            }
        }
    }

    private fun isCurrentReadAloudProgress(progress: ReadAloudProgressState): Boolean {
        val book = ReadBook.book ?: return false
        val chapter = ReadBook.curTextChapter ?: return false
        if (progress.bookUrl.isNotBlank() && progress.bookUrl != book.bookUrl) return false
        if (progress.chapterIndex >= 0 && progress.chapterIndex != chapter.chapter.index) return false
        if (progress.chapterUrl.isNotBlank() &&
            chapter.chapter.url.isNotBlank() &&
            progress.chapterUrl != chapter.chapter.url
        ) {
            return false
        }
        return true
    }

    override fun observeLiveBus() = binding.run {
        observeEvent<String>(EventBus.TIME_CHANGED) { readView.upTime() }
        observeEvent<Int>(EventBus.BATTERY_CHANGED) { readView.upBattery(it) }
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                onClickReadAloud()
            } else {
                ReadBook.readAloud(!BaseReadAloudService.pause)
            }
        }
        observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG) {
            handleReadConfigUpdate(it)
        }
        observeEvent<Boolean>(EventBus.LIBRARY_CONTAINER_CHANGED) {
            val bookUrl = ReadBook.book?.bookUrl.orEmpty()
            if (BookCloudEntryModeStore.get(bookUrl) == BookCloudEntryMode.LIBRARY_CHAPTER) {
                libraryCloudSession = null
                refreshLibraryCloudSession(refresh = true, silent = true)
            } else {
                libraryCloudSession = null
                libraryCloudState = LibraryCloudState.DISABLED
                readMenu.updateCloudLibraryState(libraryCloudState)
            }
        }
        observeEvent<Bundle>(EventBus.READ_ALOUD_CONFIG_CHANGED) {
            readAloudPlayerPanel.onReadAloudConfigChanged(
                it.getString(EventBus.READ_ALOUD_CONFIG_SCOPE)
            )
        }
        observeEvent<Int>(EventBus.ALOUD_STATE) {
            val shouldOpenPendingPanel = pendingReadAloudPlayerOpen && it == Status.PLAY
            readAloudPlayerPanel.onAloudState(it, autoExpand = !shouldOpenPendingPanel)
            if (shouldOpenPendingPanel) {
                pendingReadAloudPlayerOpen = false
                readAloudPlayerPanel.openFromBottom(force = true)
            } else if (it == Status.STOP) {
                pendingReadAloudPlayerOpen = false
            }
            if (it == Status.STOP || it == Status.PAUSE) {
                ReadBook.curTextChapter?.let { textChapter ->
                    val page = textChapter.getPageByReadPos(ReadBook.durChapterPos)
                    if (page != null) {
                        page.removePageAloudSpan()
                        readView.upContent(resetPageOffset = false)
                    }
                }
            }
        }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) {
            readAloudPlayerPanel.onTimerChanged(it)
        }
        observeEvent<ReadAloudPlaybackState>(EventBus.READ_ALOUD_PLAYBACK_STATE) {
            readAloudPlayerPanel.onPlaybackState(it)
        }
        observeEvent<AiReadAloudRoleState>(EventBus.AI_READ_ALOUD_ROLE_STATE) {
            readAloudPlayerPanel.onAiRoleState(it)
            if (pendingReadAloudPlayerOpen &&
                it.stage == AiReadAloudRoleState.STAGE_CURRENT &&
                it.bookUrl == ReadBook.book?.bookUrl
            ) {
                pendingReadAloudPlayerOpen = false
                readAloudPlayerPanel.openFromBottom(force = true)
            }
        }
        observeEvent<ReadAloudProgressState>(EventBus.READ_ALOUD_PROGRESS) { progress ->
            if (!isCurrentReadAloudProgress(progress)) return@observeEvent
            val chapterStart = progress.chapterPosition
            readAloudPlayerPanel.onTtsProgress(chapterStart)
            if (BaseReadAloudService.isPlay() &&
                !ReadBook.isReadAloudUserNavigationActive() &&
                isCurrentReadAloudProgress(progress)
            ) {
                ReadBook.curTextChapter?.let { textChapter ->
                    val previousPageIndex = ReadBook.durPageIndex
                    ReadBook.durChapterPos = chapterStart
                    val pageIndex = ReadBook.durPageIndex
                    val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
                    textChapter.getPage(pageIndex)?.upPageAloudSpan(aloudSpanStart)
                    if (pageIndex != previousPageIndex) {
                        upContent()
                    } else {
                        readView.curPage.invalidateContentView()
                    }
                }
            }
        }
        observeEvent<Boolean>(PreferKey.keepLight) {
            upScreenTimeOut()
        }
        observeEvent<Boolean>(PreferKey.textSelectAble) {
            readView.curPage.upSelectAble(it)
        }
        observeEvent<String>(PreferKey.showBrightnessView) {
            readMenu.upBrightnessState()
        }
        observeEvent<List<SearchResult>>(EventBus.SEARCH_RESULT) {
            viewModel.searchResultList = it
        }
        observeEvent<Boolean>(EventBus.UPDATE_READ_ACTION_BAR) {
            readMenu.reset()
        }
        observeEvent<Boolean>(EventBus.READ_MENU_BUTTON_CHANGED) {
            readMenu.reset()
        }
        observeEvent<Boolean>(EventBus.CONTENT_SELECT_MENU_CONFIG_CHANGED) {
            textActionMenu.upMenu()
        }
        observeEvent<Boolean>(EventBus.UP_SEEK_BAR) {
            readMenu.upSeekBar()
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_CONTENT) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                ReadBook.book?.let {
                    viewModel.refreshContentDur(it)
                }
            }
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                ReadBook.book?.let {
                    loadChapterList(it)
                }
            }
        }
    }

    private fun upScreenTimeOut() {
        val keepLightPrefer = getPrefString(PreferKey.keepLight)?.toInt() ?: 0
        screenTimeOut = keepLightPrefer * 1000L
        screenOffTimerStart()
    }

    /**
     * 重置黑屏时间
     */
    override fun screenOffTimerStart() {
        handler.post {
            if (screenTimeOut < 0) {
                keepScreenOn(true)
                return@post
            }
            val t = screenTimeOut - sysScreenOffTime
            if (t > 0) {
                keepScreenOn(true)
                handler.removeCallbacks(screenOffRunnable)
                handler.postDelayed(screenOffRunnable, screenTimeOut)
            } else {
                keepScreenOn(false)
            }
        }
    }

    companion object {
        const val RESULT_DELETED = 100
        private val shareNoteProgressPercentRegex = Regex("""(\d+(?:[,.]\d+)?)\s*%""")
    }

}
