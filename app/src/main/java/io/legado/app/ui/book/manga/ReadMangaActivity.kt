package io.legado.app.ui.book.manga

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import io.legado.app.constant.PreferKey
import io.legado.app.utils.putPrefString
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.bumptech.glide.util.FixedPreloadSizeProvider
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityMangaBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.book.isImage
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.ReadManga
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.info.BookInfoStartActivityContract
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaAutoReadDialog
import io.legado.app.ui.book.manga.config.MangaMoreConfigDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaEpaperDialog
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.recyclerview.MangaAdapter
import io.legado.app.ui.book.manga.recyclerview.MangaLayoutManager
import io.legado.app.ui.book.manga.recyclerview.ScrollTimer
import io.legado.app.ui.book.read.MangaMenu
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.canScroll
import io.legado.app.utils.fastBinarySearch
import io.legado.app.utils.findCenterViewPosition
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import kotlin.math.ceil

class ReadMangaActivity : VMBaseActivity<ActivityMangaBinding, ReadMangaViewModel>(),
    ReadManga.Callback, ChangeBookSourceDialog.CallBack, MangaMenu.CallBack,
    MangaColorFilterDialog.Callback, ScrollTimer.ScrollCallback, MangaEpaperDialog.Callback,
    MangaAutoReadDialog.CallBack {

    private val mLayoutManager by lazy {
        MangaLayoutManager(this)
    }
    private val mAdapter: MangaAdapter by lazy {
        MangaAdapter(this)
    }

    private val mSizeProvider by lazy {
        FixedPreloadSizeProvider<Any>(resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
    }

    private val mPagerSnapHelper: PagerSnapHelper by lazy {
        PagerSnapHelper()
    }

    private lateinit var mMangaFooterConfig: MangaFooterConfig
    private val mLabelBuilder by lazy { StringBuilder() }

    private var mMenu: Menu? = null
    private val mangaConfigMenuItems = intArrayOf(
        R.id.menu_pre_manga_number,
        R.id.menu_disable_manga_scale,
        R.id.menu_disable_click_scroll,
        R.id.menu_enable_auto_page,
        R.id.menu_enable_auto_scroll,
        R.id.menu_manga_auto_page_speed,
        R.id.menu_enable_horizontal_scroll,
        R.id.menu_disable_horizontal_page_snap,
        R.id.menu_manga_footer_config,
        R.id.menu_manga_color_filter,
        R.id.menu_hide_manga_title,
        R.id.menu_epaper_manga,
        R.id.menu_epaper_manga_setting,
        R.id.menu_gray_manga
    )

    private var mRecyclerViewPreloader: RecyclerViewPreloader<Any>? = null

    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    private var justInitData: Boolean = false
    private var syncDialog: AlertDialog? = null
    private val mScrollTimer by lazy {
        ScrollTimer(this, binding.recyclerView, lifecycleScope).apply {
            setSpeed(mangaAutoPageSpeed)
        }
    }
    private var enableAutoScrollPage = false
    private var enableAutoScroll = false
    private val mLinearInterpolator by lazy {
        LinearInterpolator()
    }

    private val loadMoreView by lazy {
        LoadMoreView(this).apply {
            setBackgroundColor(getCompatColor(R.color.book_ant_10))
            setLoadingColor(R.color.white)
            setLoadingTextColor(R.color.white)
        }
    }

    //打开目录返回选择章节返回结果
    private val tocActivity = registerForActivityResult(TocActivityResult()) {
        it?.let {
            viewModel.openChapter(it[0] as Int, it[1] as Int)
        }
    }
    private val sourceEditActivity =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upBookSource {
                    mMenu?.let { upMenu(it) }
                }
            }
        }
    private val bookInfoActivity =
        registerForActivityResult(BookInfoStartActivityContract()) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            } else {
                ReadManga.loadOrUpContent()
            }
        }
    override val binding by viewBinding(ActivityMangaBinding::inflate)
    override val viewModel by viewModels<ReadMangaViewModel>()
    private val loadingViewVisible get() = binding.flLoading.isVisible
    private val df by lazy {
        DecimalFormat("0.0%")
    }
    private val mangaPageAnim: Int?
        get() = ReadManga.book?.config?.mangaPageAnim
    private val mangaHorizontalScroll: Boolean
        get() = when (mangaPageAnim) {
            PageAnim.coverPageAnim,
            PageAnim.linkedCoverPageAnim,
            PageAnim.slidePageAnim,
            PageAnim.simulationPageAnim,
            PageAnim.noAnim -> true

            PageAnim.scrollPageAnim -> false
            else -> ReadManga.book?.config?.mangaHorizontalScroll ?: AppConfig.enableMangaHorizontalScroll
        }
    private val mangaDisablePageAnim: Boolean
        get() = when (mangaPageAnim) {
            PageAnim.coverPageAnim,
            PageAnim.linkedCoverPageAnim,
            PageAnim.slidePageAnim,
            PageAnim.simulationPageAnim,
            PageAnim.scrollPageAnim -> false

            PageAnim.noAnim -> true
            else -> ReadManga.book?.config?.mangaDisablePageAnim ?: AppConfig.disableMangaPageAnim
        }
    private val mangaDisableHorizontalPageSnap: Boolean
        get() = when (mangaPageAnim) {
            PageAnim.coverPageAnim,
            PageAnim.linkedCoverPageAnim,
            PageAnim.slidePageAnim,
            PageAnim.simulationPageAnim -> false

            PageAnim.noAnim -> true
            else -> ReadManga.book?.config?.mangaDisableHorizontalPageSnap
                ?: AppConfig.disableHorizontalPageSnap
        }
    private val mangaDisableClickScroll: Boolean
        get() = ReadManga.book?.config?.mangaDisableClickScroll ?: AppConfig.disableClickScroll
    private val mangaDisableScale: Boolean
        get() = ReadManga.book?.config?.mangaDisableScale ?: AppConfig.disableMangaScale
    val mangaAutoPageSpeed: Int
        get() = ReadManga.book?.config?.mangaAutoPageSpeed ?: AppConfig.mangaAutoPageSpeed

    override fun onCreate(savedInstanceState: Bundle?) {
        upLayoutInDisplayCutoutMode()
        super.onCreate(savedInstanceState)
        setOrientation()
    }

    /**
     * 屏幕方向(同小说:0默认/1竖屏/2横屏/3传感器/4反向竖屏/5反向横屏)
     */
    @SuppressLint("SourceLockedOrientationActivity")
    fun setOrientation() {
        when (AppConfig.screenOrientation) {
            "0" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            "1" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "2" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "3" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            "4" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            "5" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        ReadManga.register(this)
        Backup.autoBack(this)
        upSystemUiVisibility(false)
        initRecyclerView()
        binding.tvRetry.setOnClickListener {
            binding.llLoading.isVisible = true
            binding.llRetry.isGone = true
            ReadManga.loadOrUpContent()
        }
        binding.pbLoading.isVisible = !AppConfig.isEInkMode
        mAdapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading && ReadManga.hasNextChapter) {
                loadMoreView.startLoad()
                ReadManga.loadOrUpContent()
            }
        }
        loadMoreView.gone()
        mMangaFooterConfig =
            GSON.fromJsonObject<MangaFooterConfig>(AppConfig.mangaFooterConfig).getOrNull()
                ?: MangaFooterConfig()
    }

    override fun observeLiveBus() {
        observeEvent<MangaFooterConfig>(EventBus.UP_MANGA_CONFIG) {
            mMangaFooterConfig = it
            val item = mAdapter.getItem(binding.recyclerView.findCenterViewPosition())
            upInfoBar(item)
        }
        //漫画设置弹窗(pref变更)应用
        observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG) {
            applyBookMangaReadConfig()
            mAdapter.enableGray(AppConfig.enableMangaGray)
            mAdapter.enableMangaEInk(AppConfig.enableMangaEInk, AppConfig.mangaEInkThreshold)
            binding.mangaMenu.refreshQuickActions()
            if (AppConfig.hideMangaTitle) {
                ReadManga.loadContent()
            }
        }
    }

    private fun initRecyclerView() {
        val mangaColorFilter =
            GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter).getOrNull()
                ?: MangaColorFilterConfig()
        mAdapter.run {
            setMangaImageColorFilter(mangaColorFilter)
            enableMangaEInk(AppConfig.enableMangaEInk, AppConfig.mangaEInkThreshold)
            enableGray(AppConfig.enableMangaGray)
        }
        setHorizontalScroll(mangaHorizontalScroll)
        binding.recyclerView.run {
            adapter = mAdapter
            itemAnimator = null
            layoutManager = mLayoutManager
            setHasFixedSize(true)
            setDisableClickScroll(mangaDisableClickScroll)
            setDisableMangaScale(mangaDisableScale)
            setRecyclerViewPreloader(AppConfig.mangaPreDownloadNum)
            setPreScrollListener { _, _, _, position ->
                if (mAdapter.isNotEmpty()) {
                    val item = mAdapter.getItem(position)
                    if (item is BaseMangaPage) {
                        if (ReadManga.durChapterIndex < item.chapterIndex) {
                            ReadManga.moveToNextChapter()
                        } else if (ReadManga.durChapterIndex > item.chapterIndex) {
                            ReadManga.moveToPrevChapter()
                        } else {
                            ReadManga.durChapterPos = item.index
                            ReadManga.curPageChanged()
                        }
                        if (item is MangaPage) {
                            binding.mangaMenu.upSeekBar(item.index, item.imageCount)
                            upInfoBar(item)
                        }
                    }
                }
            }
        }
        binding.webtoonFrame.run {
            onTouchMiddle {
                if (!binding.mangaMenu.isVisible && !loadingViewVisible) {
                    binding.mangaMenu.runMenuIn()
                }
            }
            onNextPage {
                scrollToNext()
            }
            onPrevPage {
                scrollToPrev()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.initData(intent) {
            applyBookMangaReadConfig()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        viewModel.initData(intent) {
            applyBookMangaReadConfig()
        }
        justInitData = true
    }

    override fun upContent() {
        lifecycleScope.launch {
            setTitle(ReadManga.book?.name)
            val data = withContext(IO) { ReadManga.mangaContents }
            val pos = data.pos
            val list = data.items
            val curFinish = data.curFinish
            val nextFinish = data.nextFinish
            mAdapter.submitList(list) {
                if (loadingViewVisible && curFinish) {
                    binding.infobar.isVisible = true
                    upInfoBar(list[pos])
                    mLayoutManager.scrollToPositionWithOffset(pos, 0)
                    binding.flLoading.isGone = true
                    loadMoreView.visible()
                    binding.mangaMenu.upSeekBar(
                        ReadManga.durChapterPos, ReadManga.curMangaChapter!!.imageCount
                    )
                }

                if (curFinish) {
                    if (!ReadManga.hasNextChapter) {
                        loadMoreView.noMore("暂无章节了！")
                    } else if (nextFinish) {
                        loadMoreView.stopLoad()
                    } else {
                        loadMoreView.startLoad()
                    }
                }
            }
        }
    }

    private fun upInfoBar(page: Any?) {
        if (page !is MangaPage) {
            return
        }
        val chapterIndex = page.chapterIndex
        val chapterSize = page.chapterSize
        val chapterPos = page.index
        val imageCount = page.imageCount
        val chapterName = page.mChapterName
        mMangaFooterConfig.run {
            mLabelBuilder.clear()
            binding.infobar.isGone = hideFooter
            binding.infobar.textInfoAlignment = footerOrientation

            if (!hideChapterName) {
                mLabelBuilder.append(chapterName).append(" ")
            }

            if (!hidePageNumber) {
                if (!hidePageNumberLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_page_number))
                }
                mLabelBuilder.append("${chapterPos + 1}/${imageCount}").append(" ")
            }

            if (!hideChapter) {
                if (!hideChapterLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_chapter))
                }
                mLabelBuilder.append("${chapterIndex + 1}/${chapterSize}").append(" ")
            }

            if (!hideProgressRatio) {
                if (!hideProgressRatioLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_progress))
                }
                val percent = if (chapterSize == 0 || imageCount == 0 && chapterIndex == 0) {
                    "0.0%"
                } else if (imageCount == 0) {
                    df.format((chapterIndex + 1.0f) / chapterSize.toDouble())
                } else {
                    var percent =
                        df.format(
                            chapterIndex * 1.0f / chapterSize + 1.0f /
                                    chapterSize * (chapterPos + 1) / imageCount.toDouble()
                        )
                    if (percent == "100.0%" && (chapterIndex + 1 != chapterSize || chapterPos + 1 != imageCount)) {
                        percent = "99.9%"
                    }
                    percent
                }
                mLabelBuilder.append(percent)
            }
        }
        binding.infobar.update(
            if (mLabelBuilder.isEmpty()) "" else mLabelBuilder.toString()
        )
    }

    override fun onResume() {
        super.onResume()
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData && ReadManga.inBookshelf) {
                ReadManga.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
        if (enableAutoScrollPage) {
            mScrollTimer.isEnabledPage = true
        }
        if (enableAutoScroll) {
            mScrollTimer.isEnabled = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (ReadManga.inBookshelf) {
            ReadManga.saveRead()
            if (AppConfig.syncBookProgressPlus) {
                ReadManga.syncProgress()
            } else {
                ReadManga.uploadProgress()
            }
        }
        ReadManga.cancelPreDownloadTask()
        networkChangedListener.unRegister()
        mScrollTimer.isEnabledPage = false
        mScrollTimer.isEnabled = false
    }

    override fun loadFail(msg: String, retry: Boolean) {
        lifecycleScope.launch {
            if (loadingViewVisible) {
                binding.llLoading.isGone = true
                binding.llRetry.isVisible = true
                binding.tvRetry.isVisible = retry
                binding.tvMsg.text = msg
            } else {
                loadMoreView.error(null, "加载失败，点击重试")
            }
        }
    }

    override fun onDestroy() {
        ReadManga.unregister(this)
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Glide.get(this).clearMemory()
    }

    override fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.get_book_progress) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                ReadManga.setProgress(progress)
            }
            noButton()
        }
    }

    override fun showLoading() {
        lifecycleScope.launch {
            binding.flLoading.isVisible = true
        }
    }

    override fun startLoad() {
        lifecycleScope.launch {
            loadMoreView.startLoad()
        }
    }

    override fun scrollBy(distance: Int) {
        if (!binding.recyclerView.canScroll(1)) {
            return
        }
        val time = ceil(16f / distance * 10000).toInt()
        binding.recyclerView.smoothScrollBy(10000, 10000, mLinearInterpolator, time)
    }

    override fun scrollPage() {
        scrollToNext()
    }

    override val oldBook: Book?
        get() = ReadManga.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (book.isImage) {
            binding.flLoading.isVisible = true
            viewModel.changeTo(book, toc)
        } else {
            toastOnUi("所选择的源不是漫画源")
        }
    }

    override fun updateColorFilter(config: MangaColorFilterConfig) {
        mAdapter.setMangaImageColorFilter(config)
    }

    @SuppressLint("StringFormatMatches")
    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_manga, menu)
        upMenu(menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    /**
     * 菜单
     */
    @SuppressLint("StringFormatMatches", "NotifyDataSetChanged")
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source -> {
                binding.mangaMenu.runMenuOut()
                ReadManga.book?.let {
                    showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                }
            }

            R.id.menu_catalog -> {
                ReadManga.book?.let {
                    tocActivity.launch(it.bookUrl)
                }
            }

            R.id.menu_refresh -> {
                binding.flLoading.isVisible = true
                ReadManga.book?.let {
                    viewModel.refreshContentDur(it)
                }
            }

            R.id.menu_manga_config -> {
                showMangaConfigMenu()
            }

            R.id.menu_pre_manga_number -> {
                showNumberPickerDialog(
                    0,
                    getString(R.string.pre_download),
                    AppConfig.mangaPreDownloadNum
                ) {
                    AppConfig.mangaPreDownloadNum = it
                    item.title = getString(R.string.pre_download_m, it)
                    setRecyclerViewPreloader(it)
                }
            }

            R.id.menu_disable_manga_scale -> {
                item.isChecked = !item.isChecked
                updateBookMangaReadConfig {
                    mangaPageAnim = null
                    mangaDisableScale = item.isChecked
                }
                setDisableMangaScale(item.isChecked)
            }

            R.id.menu_disable_click_scroll -> {
                item.isChecked = !item.isChecked
                updateBookMangaReadConfig {
                    mangaPageAnim = null
                    mangaDisableClickScroll = item.isChecked
                }
                setDisableClickScroll(item.isChecked)
            }

            R.id.menu_enable_auto_page -> {
                item.isChecked = !item.isChecked
                val menuMangaAutoPageSpeed = mMenu?.findItem(R.id.menu_manga_auto_page_speed)
                mScrollTimer.isEnabledPage = item.isChecked
                menuMangaAutoPageSpeed?.isVisible = item.isChecked
                enableAutoScrollPage = item.isChecked
                enableAutoScroll = false
                mScrollTimer.isEnabled = false
                mMenu?.findItem(R.id.menu_enable_auto_scroll)?.isChecked = false
            }

            R.id.menu_manga_auto_page_speed -> {
                showNumberPickerDialog(
                    1, getString(R.string.setting_manga_auto_page_speed),
                    mangaAutoPageSpeed
                ) {
                    updateBookMangaReadConfig {
                        mangaAutoPageSpeed = it
                    }
                    item.title = getString(R.string.manga_auto_page_speed, it)
                    mScrollTimer.setSpeed(it)
                    if (enableAutoScrollPage) {
                        mScrollTimer.isEnabledPage = true
                    }
                }
            }

            R.id.menu_manga_footer_config -> {
                showDialogFragment(MangaFooterSettingDialog())
            }

            R.id.menu_enable_horizontal_scroll -> {
                item.isChecked = !item.isChecked
                updateBookMangaReadConfig {
                    mangaPageAnim = null
                    mangaHorizontalScroll = item.isChecked
                }
                mMenu?.findItem(R.id.menu_disable_horizontal_page_snap)?.isVisible =
                    item.isChecked && !mangaDisablePageAnim
                setHorizontalScroll(item.isChecked)
                mAdapter.notifyDataSetChanged()
            }

            R.id.menu_manga_color_filter -> {
                binding.mangaMenu.runMenuOut()
                showDialogFragment(MangaColorFilterDialog())
            }

            R.id.menu_enable_auto_scroll -> {
                item.isChecked = !item.isChecked
                mScrollTimer.isEnabled = item.isChecked
                mMenu?.findItem(R.id.menu_enable_auto_page)?.isChecked = false
                enableAutoScroll = item.isChecked
                enableAutoScrollPage = false
                mScrollTimer.isEnabledPage = false
                mMenu?.findItem(R.id.menu_manga_auto_page_speed)?.isVisible = item.isChecked
                if (enableAutoScroll) {
                    mPagerSnapHelper.attachToRecyclerView(null)
                } else if (mangaHorizontalScroll) {
                    mPagerSnapHelper.attachToRecyclerView(binding.recyclerView)
                }
            }

            R.id.menu_hide_manga_title -> {
                item.isChecked = !item.isChecked
                AppConfig.hideMangaTitle = item.isChecked
                ReadManga.loadContent()
            }

            R.id.menu_epaper_manga -> {
                item.isChecked = !item.isChecked
                AppConfig.enableMangaEInk = item.isChecked
                mMenu?.findItem(R.id.menu_gray_manga)?.isChecked = false
                AppConfig.enableMangaGray = false
                mMenu?.findItem(R.id.menu_epaper_manga_setting)?.isVisible = item.isChecked
                mAdapter.enableMangaEInk(item.isChecked, AppConfig.mangaEInkThreshold)
            }

            R.id.menu_epaper_manga_setting -> {
                showDialogFragment(MangaEpaperDialog())
            }

            R.id.menu_disable_horizontal_page_snap -> {
                item.isChecked = !item.isChecked
                updateBookMangaReadConfig {
                    mangaPageAnim = null
                    mangaDisableHorizontalPageSnap = item.isChecked
                }
                if (item.isChecked) {
                    mPagerSnapHelper.attachToRecyclerView(null)
                } else {
                    mPagerSnapHelper.attachToRecyclerView(binding.recyclerView)
                }
            }

            R.id.menu_gray_manga -> {
                item.isChecked = !item.isChecked
                AppConfig.enableMangaGray = item.isChecked
                mMenu?.findItem(R.id.menu_epaper_manga)?.isChecked = false
                AppConfig.enableMangaEInk = false
                mMenu?.findItem(R.id.menu_epaper_manga_setting)?.isVisible = false
                mAdapter.enableGray(item.isChecked)
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun openBookInfoActivity() {
        ReadManga.book?.let {
            bookInfoActivity.launch {
                putExtra("name", it.name)
                putExtra("author", it.author)
            }
        }
    }

    override fun returnToBookshelf() {
        finish()
    }

    override fun changeSource() {
        binding.mangaMenu.runMenuOut()
        ReadManga.book?.let {
            showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
        }
    }

    override fun refreshContent() {
        binding.flLoading.isVisible = true
        ReadManga.book?.let {
            viewModel.refreshContentDur(it)
        }
    }

    override fun openSourceEditActivity() {
        ReadManga.bookSource?.let {
            sourceEditActivity.launch {
                putExtra("sourceUrl", it.bookSourceUrl)
            }
        }
    }

    override fun disableSource() {
        viewModel.disableSource()
    }

    override fun addBookmark() {
        val book = ReadManga.book
        val chapter = ReadManga.curMangaChapter?.chapter
        if (book != null && chapter != null) {
            val bookmark = book.createBookMark().apply {
                chapterIndex = ReadManga.durChapterIndex
                chapterPos = ReadManga.durChapterPos
                chapterName = chapter.title
                bookText = chapter.title
            }
            showDialogFragment(BookmarkDialog(bookmark, editPos = 0, contentOnly = true))
        }
    }

    override fun openMangaConfig() {
        showMangaConfigMenu()
    }

    override fun upSystemUiVisibility(menuIsVisible: Boolean) {
        toggleSystemBar(menuIsVisible)
        if (enableAutoScroll) {
            mScrollTimer.isEnabled = !menuIsVisible
        }
        if (enableAutoScrollPage) {
            mScrollTimer.isEnabledPage = !menuIsVisible
        }
    }

    override fun autoPage() {
        if (enableAutoScrollPage || enableAutoScroll) {
            stopMangaAutoPage()
        } else {
            mScrollTimer.setSpeed(mangaAutoPageSpeed)
            setAutoPageEnabled(true)
            binding.mangaMenu.setAutoPage(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.infobar.update(getString(R.string.auto_page_running, mangaAutoPageSpeed))
            showDialogFragment(MangaAutoReadDialog())
        }
    }

    override fun isAutoPageActive(): Boolean {
        return enableAutoScrollPage || enableAutoScroll
    }

    /**
     * 自动翻页控制条:停止自动翻页
     */
    fun stopMangaAutoPage() {
        setAutoPageEnabled(false)
        setAutoScrollEnabled(false)
        binding.mangaMenu.setAutoPage(false)
        binding.mangaMenu.refreshQuickActions()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.infobar.update("")
    }

    /**
     * 自动翻页控制条:应用翻页模式(逐页/连续滚动)
     */
    fun applyMangaAutoMode(mode: String?) {
        if (enableAutoScrollPage || enableAutoScroll) {
            mScrollTimer.setSpeed(mangaAutoPageSpeed)
            if (mode == "scroll") {
                setAutoScrollEnabled(true)
            } else {
                setAutoPageEnabled(true)
            }
        }
    }

    /**
     * 自动翻页控制条:速度变化
     */
    fun applyMangaAutoSpeed(speed: Int) {
        mScrollTimer.setSpeed(speed)
        binding.infobar.update(getString(R.string.auto_page_running, speed))
    }

    /**
     * 自动翻页控制条:当前是否连续滚动模式
     */
    fun isAutoScrollEnabled(): Boolean {
        return enableAutoScroll
    }

    /**
     * 自动翻页控制条:显示主菜单(底部面板)
     */
    override fun showMenuBar() {
        if (!binding.mangaMenu.isVisible) {
            binding.mangaMenu.runMenuIn()
        }
    }

    override fun autoPageStop() {
        stopMangaAutoPage()
    }

    override fun openChapterList() {
        ReadManga.book?.let {
            tocActivity.launch(it.bookUrl)
        }
    }

    private fun setAutoPageEnabled(enable: Boolean) {
        enableAutoScrollPage = enable
        enableAutoScroll = false
        mScrollTimer.isEnabledPage = enable
        mScrollTimer.isEnabled = false
        mMenu?.let { upMenu(it) }
    }

    private fun setAutoScrollEnabled(enable: Boolean) {
        enableAutoScroll = enable
        enableAutoScrollPage = false
        mScrollTimer.isEnabled = enable
        mScrollTimer.isEnabledPage = false
        if (enable) {
            mPagerSnapHelper.attachToRecyclerView(null)
        } else if (mangaHorizontalScroll) {
            mPagerSnapHelper.attachToRecyclerView(binding.recyclerView)
        }
        mMenu?.let { upMenu(it) }
    }

    override fun openColorFilter() {
        binding.mangaMenu.runMenuOut()
        showDialogFragment(MangaColorFilterDialog())
    }

    override fun toggleGray() {
        triggerMangaMenuItem(R.id.menu_gray_manga)
    }

    override fun toggleEInk() {
        triggerMangaMenuItem(R.id.menu_epaper_manga)
    }

    override fun toggleNightTheme() {
        AppConfig.isNightTheme = !AppConfig.isNightTheme
        ThemeConfig.applyDayNight(this)
    }

    override fun openCatalog() {
        ReadManga.book?.let {
            tocActivity.launch(it.bookUrl)
        }
    }

    override fun showInterfaceSetting() {
        showMangaConfigMenu()
    }

    override fun showMoreSettingMenu() {
        showMangaConfigMenu()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !binding.mangaMenu.canShowMenu) {
                binding.mangaMenu.runMenuIn()
                return true
            }
            if (!isDown && !binding.mangaMenu.canShowMenu) {
                binding.mangaMenu.canShowMenu = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun setRecyclerViewPreloader(maxPreload: Int) {
        if (mRecyclerViewPreloader != null) {
            binding.recyclerView.removeOnScrollListener(mRecyclerViewPreloader!!)
        }
        mRecyclerViewPreloader = RecyclerViewPreloader(
            Glide.with(this), mAdapter, mSizeProvider, maxPreload
        )
        binding.recyclerView.addOnScrollListener(mRecyclerViewPreloader!!)
    }

    private fun setHorizontalScroll(enable: Boolean) {
        mAdapter.isHorizontal = enable
        if (enable) {
            if (!enableAutoScroll) {
                if (mangaDisableHorizontalPageSnap || mangaDisablePageAnim) {
                    mPagerSnapHelper.attachToRecyclerView(null)
                } else {
                    mPagerSnapHelper.attachToRecyclerView(binding.recyclerView)
                }
            }
            mLayoutManager.orientation = LinearLayoutManager.HORIZONTAL
        } else {
            mPagerSnapHelper.attachToRecyclerView(null)
            mLayoutManager.orientation = LinearLayoutManager.VERTICAL
        }
    }

    @SuppressLint("StringFormatMatches")
    private fun upMenu(menu: Menu) {
        this.mMenu = menu
        menu.findItem(R.id.menu_pre_manga_number).title =
            getString(R.string.pre_download_m, AppConfig.mangaPreDownloadNum)
        menu.findItem(R.id.menu_disable_manga_scale).isChecked = mangaDisableScale
        menu.findItem(R.id.menu_disable_click_scroll).isChecked = mangaDisableClickScroll
        menu.findItem(R.id.menu_manga_auto_page_speed).title =
            getString(R.string.manga_auto_page_speed, mangaAutoPageSpeed)
        menu.findItem(R.id.menu_enable_horizontal_scroll).isChecked =
            mangaHorizontalScroll
        menu.findItem(R.id.menu_epaper_manga).isChecked = AppConfig.enableMangaEInk
        menu.findItem(R.id.menu_epaper_manga_setting).isVisible = AppConfig.enableMangaEInk
        menu.findItem(R.id.menu_disable_horizontal_page_snap).run {
            isVisible = mangaHorizontalScroll && !mangaDisablePageAnim
            isChecked = mangaDisableHorizontalPageSnap || mangaDisablePageAnim
        }
        menu.findItem(R.id.menu_gray_manga).isChecked = AppConfig.enableMangaGray
        menu.findItem(R.id.menu_enable_auto_page).isChecked = enableAutoScrollPage
        menu.findItem(R.id.menu_enable_auto_scroll).isChecked = enableAutoScroll
        menu.findItem(R.id.menu_manga_auto_page_speed).isVisible =
            enableAutoScrollPage || enableAutoScroll
        mangaConfigMenuItems.forEach { id ->
            menu.findItem(id)?.isVisible = false
        }
    }

    private fun applyBookMangaReadConfig() {
        setHorizontalScroll(mangaHorizontalScroll)
        setDisableClickScroll(mangaDisableClickScroll)
        setDisableMangaScale(mangaDisableScale)
        mScrollTimer.setSpeed(mangaAutoPageSpeed)
        mMenu?.let { upMenu(it) }
    }

    fun showMangaConfigMenu() {
        //漫画设置弹窗(同小说MoreConfigDialog:底部sheet+preference列表)
        showDialogFragment(MangaMoreConfigDialog())
    }

    /**
     * 屏幕方向选择(同小说:0默认/1竖屏/2横屏/3传感器/4反向竖屏/5反向横屏)
     */
    private fun showScreenDirectionDialog() {
        val titles = resources.getStringArray(R.array.screen_direction_title)
        val values = resources.getStringArray(R.array.screen_direction_value)
        selector(getString(R.string.screen_direction), titles.toList()) { _, index ->
            val value = values.getOrElse(index) { "0" }
            this@ReadMangaActivity.putPrefString(PreferKey.screenOrientation, value)
            setOrientation()
        }
    }

    fun triggerMangaMenuItem(id: Int) {
        val item = mMenu?.findItem(id)
        if (item != null) {
            onCompatOptionsItemSelected(item)
            mMenu?.let { upMenu(it) }
            return
        }
        //mMenu为null(漫画页无toolbar,系统不调onCreateOptionsMenu)时直接执行
        when (id) {
            R.id.menu_pre_manga_number -> {
                showNumberPickerDialog(
                    0,
                    getString(R.string.pre_download),
                    AppConfig.mangaPreDownloadNum
                ) {
                    AppConfig.mangaPreDownloadNum = it
                    setRecyclerViewPreloader(it)
                }
            }

            R.id.menu_disable_manga_scale -> {
                updateBookMangaReadConfig {
                    mangaPageAnim = null
                    mangaDisableScale = !(mangaDisableScale ?: false)
                }
                setDisableMangaScale(mangaDisableScale)
            }

            R.id.menu_disable_click_scroll -> {
                updateBookMangaReadConfig {
                    mangaPageAnim = null
                    mangaDisableClickScroll = !(mangaDisableClickScroll ?: false)
                }
                setDisableClickScroll(mangaDisableClickScroll)
            }

            R.id.menu_enable_auto_page -> {
                enableAutoScrollPage = !enableAutoScrollPage
                enableAutoScroll = false
                mScrollTimer.isEnabledPage = enableAutoScrollPage
                mScrollTimer.isEnabled = false
            }

            R.id.menu_enable_auto_scroll -> {
                enableAutoScroll = !enableAutoScroll
                enableAutoScrollPage = false
                mScrollTimer.isEnabled = enableAutoScroll
                mScrollTimer.isEnabledPage = false
                if (enableAutoScroll) {
                    mPagerSnapHelper.attachToRecyclerView(null)
                } else if (mangaHorizontalScroll) {
                    mPagerSnapHelper.attachToRecyclerView(binding.recyclerView)
                }
            }

            R.id.menu_manga_auto_page_speed -> {
                showNumberPickerDialog(
                    1, getString(R.string.setting_manga_auto_page_speed),
                    mangaAutoPageSpeed
                ) {
                    updateBookMangaReadConfig {
                        mangaAutoPageSpeed = it
                    }
                    mScrollTimer.setSpeed(it)
                    if (enableAutoScrollPage) {
                        mScrollTimer.isEnabledPage = true
                    }
                }
            }

            R.id.menu_enable_horizontal_scroll -> {
                updateBookMangaReadConfig {
                    mangaPageAnim = null
                    mangaHorizontalScroll = !(mangaHorizontalScroll ?: false)
                }
                setHorizontalScroll(mangaHorizontalScroll)
                mAdapter.notifyDataSetChanged()
            }

            R.id.menu_disable_horizontal_page_snap -> {
                updateBookMangaReadConfig {
                    mangaPageAnim = null
                    mangaDisableHorizontalPageSnap = !(mangaDisableHorizontalPageSnap ?: false)
                }
                if (mangaDisableHorizontalPageSnap || mangaDisablePageAnim) {
                    mPagerSnapHelper.attachToRecyclerView(null)
                } else {
                    mPagerSnapHelper.attachToRecyclerView(binding.recyclerView)
                }
            }

            R.id.menu_manga_footer_config -> {
                showDialogFragment(MangaFooterSettingDialog())
            }

            R.id.menu_manga_color_filter -> {
                binding.mangaMenu.runMenuOut()
                showDialogFragment(MangaColorFilterDialog())
            }

            R.id.menu_hide_manga_title -> {
                AppConfig.hideMangaTitle = !AppConfig.hideMangaTitle
                ReadManga.loadContent()
            }

            R.id.menu_epaper_manga -> {
                AppConfig.enableMangaEInk = !AppConfig.enableMangaEInk
                AppConfig.enableMangaGray = false
                mAdapter.enableMangaEInk(AppConfig.enableMangaEInk, AppConfig.mangaEInkThreshold)
            }

            R.id.menu_epaper_manga_setting -> {
                showDialogFragment(MangaEpaperDialog())
            }

            R.id.menu_gray_manga -> {
                AppConfig.enableMangaGray = !AppConfig.enableMangaGray
                AppConfig.enableMangaEInk = false
                mAdapter.enableGray(AppConfig.enableMangaGray)
            }
        }
        binding.mangaMenu.refreshQuickActions()
    }

    private fun updateBookMangaReadConfig(block: Book.ReadConfig.() -> Unit) {
        val book = ReadManga.book ?: return
        book.config.block()
        lifecycleScope.launch(IO) {
            book.save()
        }
    }

    private fun setDisableMangaScale(disable: Boolean) {
        binding.webtoonFrame.disableMangaScale = disable
        binding.recyclerView.disableMangaScale = disable
        if (disable) {
            binding.recyclerView.resetZoom()
        }
    }

    private fun setDisableClickScroll(disable: Boolean) {
        binding.webtoonFrame.disabledClickScroll = disable
    }

    private fun upLayoutInDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun scrollToNext() {
        scrollPageTo(1)
    }

    private fun scrollToPrev() {
        scrollPageTo(-1)
    }

    private fun scrollPageTo(direction: Int) {
        if (!binding.recyclerView.canScroll(direction)) {
            return
        }
        var dx = 0
        var dy = 0
        if (mangaHorizontalScroll) {
            dx = binding.recyclerView.run {
                width - paddingStart - paddingEnd
            }
        } else {
            dy = binding.recyclerView.run {
                height - paddingTop - paddingBottom
            }
        }
        dx *= direction
        dy *= direction
        if (mangaDisablePageAnim) {
            binding.recyclerView.scrollBy(dx, dy)
        } else {
            binding.recyclerView.smoothScrollBy(dx, dy)
        }
    }

    fun showNumberPickerDialog(
        min: Int,
        title: String,
        initValue: Int,
        callback: (Int) -> Unit,
    ) {
        NumberPickerDialog(this)
            .setTitle(title)
            .setMaxValue(9999)
            .setMinValue(min)
            .setValue(initValue)
            .show {
                callback.invoke(it)
            }
    }

    override fun finish() {
        val book = ReadManga.book ?: return super.finish()

        if (ReadManga.inBookshelf) {
            return super.finish()
        }

        if (!AppConfig.showAddToShelfAlert) {
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    ReadManga.book?.removeType(BookType.notShelf)
                    ReadManga.book?.save()
                    ReadManga.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton { viewModel.removeFromBookshelf { super.finish() } }
            }
        }
    }

    override fun skipToPage(index: Int) {
        val durChapterIndex = ReadManga.durChapterIndex
        val itemPos = mAdapter.getItems().fastBinarySearch {
            val chapterIndex: Int
            val pageIndex: Int
            if (it is BaseMangaPage) {
                chapterIndex = it.chapterIndex
                pageIndex = it.index
            } else {
                error("unknown item type")
            }
            val delta = chapterIndex - durChapterIndex
            if (delta != 0) {
                delta
            } else {
                pageIndex - index
            }
        }
        if (itemPos > -1) {
            mLayoutManager.scrollToPositionWithOffset(itemPos, 0)
            upInfoBar(mAdapter.getItem(itemPos))
            ReadManga.durChapterPos = index
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                scrollToPrev()
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                scrollToNext()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun updateEepaper(value: Int) {
        mAdapter.updateThreshold(value)
    }
}
