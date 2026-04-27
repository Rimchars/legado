package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import androidx.core.view.isGone
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.databinding.ViewReadMenuBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.buttonDisabledColor
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible
import splitties.views.onClick
import splitties.views.onLongClick
import androidx.core.graphics.toColorInt
import io.legado.app.constant.BookType
import io.legado.app.utils.buildMainHandler

/**
 * 阅读界面菜单
 */
class ReadMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var canShowMenu: Boolean = false
    private val callBack: CallBack get() = activity as CallBack
    private val binding = ViewReadMenuBinding.inflate(LayoutInflater.from(context), this, true)
    private var confirmSkipToChapter: Boolean = false
    private var isMenuOutAnimating = false
    private val menuTopIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_in)
    }
    private val menuTopOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_out)
    }
    private val menuBottomIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_in)
    }
    private val menuBottomOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_out)
    }
    private val immersiveMenu: Boolean
        get() = AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0
    private var bgColor: Int = if (immersiveMenu) {
        kotlin.runCatching {
            ReadBookConfig.durConfig.curBgStr().toColorInt()
        }.getOrDefault(context.bottomBackground)
    } else {
        context.bottomBackground
    }
    private var textColor: Int = if (immersiveMenu) {
        ReadBookConfig.durConfig.curTextColor()
    } else {
        context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
    }

    private var onMenuOutEnd: (() -> Unit)? = null
    private val showBrightnessView
        get() = context.getPrefBoolean(
            PreferKey.showBrightnessView,
            true
        )
    private var modernMenuPopup: PopupWindow? = null
    private var currentChapterUrl: String? = null
    private val menuInListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            binding.tvSourceAction.text =
                ReadBook.bookSource?.bookSourceName ?: context.getString(R.string.book_source)
            binding.tvSourceAction.isGone = ReadBook.isLocalBook
            ReadBook.bookSource?.let {
                if (it.customButton) {
                    binding.tvCustomBtn.visibility = VISIBLE
                }
            }
            callBack.upSystemUiVisibility()
            updateBrightnessSectionVisibility()
        }

        @SuppressLint("RtlHardcoded")
        override fun onAnimationEnd(animation: Animation) {
            binding.vwMenuBg.setOnClickListener { runMenuOut() }
            callBack.upSystemUiVisibility()
            if (!LocalConfig.readMenuHelpVersionIsLast) {
                callBack.showHelp()
            }
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }
    private val menuOutListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            isMenuOutAnimating = true
            binding.vwMenuBg.setOnClickListener(null)
        }

        override fun onAnimationEnd(animation: Animation) {
            this@ReadMenu.invisible()
            binding.titleBar.invisible()
            binding.bottomMenu.invisible()
            canShowMenu = false
            isMenuOutAnimating = false
            onMenuOutEnd?.invoke()
            callBack.upSystemUiVisibility()
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }

    init {
        binding.titleBar.applyStatusBarPadding(withInitialPadding = true)
        initView()
        upBrightnessState()
        bindEvent()
    }

    private fun createPanelDrawable(
        radiusDp: Float,
        color: Int,
        strokeColor: Int,
        topOnly: Boolean = false
    ) = GradientDrawable().apply {
        val radius = radiusDp.dpToPx()
        if (topOnly) {
            cornerRadii = floatArrayOf(
                radius, radius,
                radius, radius,
                0f, 0f,
                0f, 0f
            )
        } else {
            cornerRadius = radius
        }
        setColor(color)
        setStroke(1.dpToPx(), strokeColor)
    }

    private fun createFillDrawable(color: Int) = GradientDrawable().apply {
        setColor(color)
    }

    private fun initView(reset: Boolean = false) = binding.run {
        if (AppConfig.isNightTheme) {
            fabNightTheme.setImageResource(R.drawable.ic_daytime)
        } else {
            fabNightTheme.setImageResource(R.drawable.ic_brightness)
        }
        initAnimation()
        val paletteBaseColor = if (immersiveMenu) bgColor else context.bottomBackground
        val palette = ReaderSheetStyle.resolve(context, paletteBaseColor)
        tvCustomBtn.setColorFilter(palette.accentColor)
        val primaryTextColor = palette.textColor
        titleBar.setTextColor(primaryTextColor)
        titleBar.setColorFilter(primaryTextColor)
        tvChapterName.setTextColor(primaryTextColor)
        tvChapterUrl.setTextColor(
            ColorUtils.withAlpha(primaryTextColor, 0.72f)
        )
        val menuOpacity = 1f
        val isBgLight = ColorUtils.isColorLight(bgColor)
        val headerBaseColor = ColorUtils.blendColors(
            palette.surface,
            palette.primaryColor,
            if (isBgLight) 0.14f else 0.24f
        )
        val sheetBaseColor = ColorUtils.blendColors(
            palette.surface,
            palette.panel,
            if (isBgLight) 0.72f else 0.82f
        )
        val actionBaseColor = ColorUtils.blendColors(
            palette.panelStrong,
            palette.primaryColor,
            if (isBgLight) 0.18f else 0.28f
        )
        val sheetColor = ColorUtils.withAlpha(sheetBaseColor, menuOpacity.coerceAtLeast(0.92f))
        val headerColor = ColorUtils.withAlpha(headerBaseColor, menuOpacity.coerceAtLeast(0.9f))
        val actionColor = ColorUtils.withAlpha(actionBaseColor, menuOpacity.coerceAtLeast(0.96f))
        val panelStrokeColor = palette.stroke
        vwMenuBg.setBackgroundColor(0x00000000)
        if (AppConfig.isEInkMode) {
            titleBar.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            titleBar.toolbar.background = null
            titleBarAddition.background = null
            llTitleInfo.background = null
            tvSourceAction.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            bottomMenu.background = null
            llBottomBg.setBackgroundResource(R.drawable.bg_eink_border_top)
        } else {
            titleBar.background = createFillDrawable(headerColor)
            titleBar.toolbar.background = null
            titleBarAddition.background = null
            llTitleInfo.background = null
            bottomMenu.background = null
            llBottomBg.background = createPanelDrawable(28F, sheetColor, panelStrokeColor, topOnly = true)
            quickActionBarContainer.background = null
            llFloatingButton.background = null
            llBrightness.background = null
            llChapterPanel.background = null
            llActionPanel.background = null
            tvSourceAction.background = createPanelDrawable(12F, actionColor, panelStrokeColor)
            tvPre.background = createPanelDrawable(12F, actionColor, panelStrokeColor)
            tvNext.background = createPanelDrawable(12F, actionColor, panelStrokeColor)
        }
        tvSourceAction.setTextColor(primaryTextColor)
        fabSearch.backgroundTintList = null
        fabSearch.setColorFilter(primaryTextColor)
        fabAutoPage.backgroundTintList = null
        fabAutoPage.setColorFilter(primaryTextColor)
        fabReplaceRule.backgroundTintList = null
        fabReplaceRule.setColorFilter(primaryTextColor)
        fabNightTheme.backgroundTintList = null
        fabNightTheme.setColorFilter(primaryTextColor)
        tvPre.setTextColor(primaryTextColor)
        tvNext.setTextColor(primaryTextColor)
        ivCatalog.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        tvCatalog.setTextColor(primaryTextColor)
        ivReadAloud.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        tvReadAloud.setTextColor(primaryTextColor)
        ivFont.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        tvFont.setTextColor(primaryTextColor)
        ivSetting.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        tvSetting.setTextColor(primaryTextColor)
        tvQuickSearchLabel.setTextColor(palette.secondaryTextColor)
        tvQuickAutoPageLabel.setTextColor(palette.secondaryTextColor)
        tvQuickReplaceRuleLabel.setTextColor(palette.secondaryTextColor)
        tvQuickNightThemeLabel.setTextColor(palette.secondaryTextColor)
        tvBrightnessLabel.setTextColor(primaryTextColor)
        ivBrightnessAuto.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        vwBrightnessPosAdjust.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        llSetting.visible()
        llBrightness.setOnClickListener(null)
        updateBrightnessSectionVisibility()
        seekBrightness.post {
            seekBrightness.progress = AppConfig.readBrightness
        }
        if (AppConfig.showReadTitleBarAddition) {
            titleBarAddition.visible()
        } else {
            titleBarAddition.gone()
        }
        upBrightnessVwPos()
        /**
         * 确保视图不被导航栏遮挡
         */
        bottomMenu.applyNavigationBarPadding()
    }

    fun reset() {
        upColorConfig()
        initView(true)
    }

    fun refreshMenuColorFilter() {
        if (immersiveMenu) {
            binding.titleBar.setColorFilter(textColor)
        }
    }

    private fun upColorConfig() {
        bgColor = if (immersiveMenu) {
            kotlin.runCatching {
                ReadBookConfig.durConfig.curBgStr().toColorInt()
            }.getOrDefault(context.bottomBackground)
        } else {
            context.bottomBackground
        }
        textColor = if (immersiveMenu) {
            ReadBookConfig.durConfig.curTextColor()
        } else {
            context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
        }
    }

    fun upBrightnessState() {
        updateBrightnessSectionVisibility()
        if (brightnessAuto()) {
            binding.ivBrightnessAuto.setColorFilter(ReaderSheetStyle.resolve(context).accentColor)
            binding.seekBrightness.isEnabled = false
        } else {
            binding.ivBrightnessAuto.setColorFilter(context.buttonDisabledColor)
            binding.seekBrightness.isEnabled = true
        }
        setScreenBrightness(AppConfig.readBrightness.toFloat())
    }

    private fun updateBrightnessSectionVisibility() = binding.run {
        val visible = showBrightnessView
        llBrightness.isVisible = visible
        dividerBrightnessTop.isVisible = visible
        dividerBrightnessBottom.isVisible = visible
    }

    /**
     * 系统亮度监听，在高阳光亮度时启用
     */
    private var contentObserver: ContentObserver? = null
    /**
     * 设置屏幕亮度
     */
    fun setScreenBrightness(value: Float) {
        activity?.run {
            fun setBrightness(value: Float) {
                val params = window.attributes
                params.screenBrightness = value
                window.attributes = params
            }
            val autoBrightness = BRIGHTNESS_OVERRIDE_NONE
            if (brightnessAuto() || value == autoBrightness) {
                setBrightness(autoBrightness)
                return
            }
            val brightness = if (value < 1f) 0.004f else value / 255f
            var isSunMax = false
            if (brightness == 1f) {
                val sysBrightness = getCurrentBrightness(context)
                if (sysBrightness == 255) {
                    isSunMax = true
                }
            }
            if (isSunMax) {
                contentObserver = object : ContentObserver(buildMainHandler()) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        if (contentObserver == null) return
                        if (uri == Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)) {
                            val sysBrightness = getCurrentBrightness(context)
                            if (sysBrightness < 200) {
                                setBrightness(brightness)
                                contentObserver?.let {
                                    context.contentResolver.unregisterContentObserver(it)
                                }
                                contentObserver = null
                            } else if (sysBrightness < 255) {
                                setBrightness(brightness)
                            } else {
                                setBrightness(autoBrightness)
                            }
                        }
                    }
                }
                val brightnessUri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                context.contentResolver.registerContentObserver(
                    brightnessUri,
                    false,
                    contentObserver!!
                )
                setBrightness(autoBrightness)
            } else {
                setBrightness(brightness)
            }
        }
    }

    /**
     * 获取系统亮度值
     */
    private fun getCurrentBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (_: Settings.SettingNotFoundException) {
            -1
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        callBack.onMenuShow()
        this.visible()
        binding.titleBar.visible()
        binding.bottomMenu.visible()
        if (anim) {
            binding.titleBar.startAnimation(menuTopIn)
            binding.bottomMenu.startAnimation(menuBottomIn)
        } else {
            menuInListener.onAnimationStart(menuBottomIn)
            menuInListener.onAnimationEnd(menuBottomIn)
        }
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode, onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating) {
            return
        }
        callBack.onMenuHide()
        this.onMenuOutEnd = onMenuOutEnd
        if (this.isVisible) {
            if (anim) {
                binding.titleBar.startAnimation(menuTopOut)
                binding.bottomMenu.startAnimation(menuBottomOut)
            } else {
                menuOutListener.onAnimationStart(menuBottomOut)
                menuOutListener.onAnimationEnd(menuBottomOut)
            }
        }
    }

    private fun brightnessAuto(): Boolean {
        return context.getPrefBoolean("brightnessAuto", true) || !showBrightnessView
    }

    private fun bindEvent() = binding.run {
        vwMenuBg.setOnClickListener { runMenuOut() }
        titleBar.toolbar.setOnClickListener {
            callBack.openBookInfoActivity()
        }
        val chapterViewClickListener = OnClickListener {
            if (ReadBook.isLocalBook) {
                return@OnClickListener
            }
            val chapterUrl = getChapterUrlForOpen() ?: return@OnClickListener
            if (AppConfig.readUrlInBrowser) {
                context.openUrl(chapterUrl)
            } else {
                Coroutine.async {
                    context.startActivity<WebViewActivity> {
                        val bookSource = ReadBook.bookSource
                        putExtra("title", tvChapterName.text)
                        putExtra("url", chapterUrl)
                        putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                        putExtra("sourceName", bookSource?.bookSourceName)
                        putExtra("sourceType", bookSource?.getSourceType())
                    }
                }
            }
        }
        val chapterViewLongClickListener = OnLongClickListener {
            if (ReadBook.isLocalBook) {
                return@OnLongClickListener true
            }
            context.alert(R.string.open_fun) {
                setMessage(R.string.use_browser_open)
                okButton {
                    AppConfig.readUrlInBrowser = true
                }
                noButton {
                    AppConfig.readUrlInBrowser = false
                }
            }
            true
        }
        tvChapterName.setOnClickListener(chapterViewClickListener)
        tvChapterName.setOnLongClickListener(chapterViewLongClickListener)
        tvChapterUrl.setOnClickListener(chapterViewClickListener)
        tvChapterUrl.setOnLongClickListener(chapterViewLongClickListener)
        tvCustomBtn.setOnClickListener {
            val book = ReadBook.book ?: return@setOnClickListener
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
        }
        tvCustomBtn.setOnLongClickListener {
            val book = ReadBook.book ?: return@setOnLongClickListener true
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.LONG_CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
            true
        }
        //书源操作
        tvSourceAction.onClick {
            modernMenuPopup = ModernActionPopup.showFromMenu(
                tvSourceAction,
                R.menu.book_read_source,
                modernMenuPopup,
                prepare = {
                    findItem(R.id.menu_login).isVisible =
                        !ReadBook.bookSource?.loginUrl.isNullOrEmpty()
                    findItem(R.id.menu_chapter_pay).isVisible =
                        !ReadBook.bookSource?.loginUrl.isNullOrEmpty()
                                && ReadBook.curTextChapter?.isVip == true
                                && ReadBook.curTextChapter?.isPay != true
                }
            ) {
                when (it.itemId) {
                    R.id.menu_login -> callBack.showLogin()
                    R.id.menu_chapter_pay -> callBack.payAction()
                    R.id.menu_edit_source -> callBack.openSourceEditActivity()
                    R.id.menu_disable_source -> callBack.disableSource()
                }
                true
            }
        }
        //亮度跟随
        ivBrightnessAuto.setOnClickListener {
            context.putPrefBoolean("brightnessAuto", !brightnessAuto())
            upBrightnessState()
        }
        //亮度调节
        seekBrightness.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setScreenBrightness(progress.toFloat())
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                AppConfig.readBrightness = seekBar.progress
            }

        })
        vwBrightnessPosAdjust.setOnClickListener {
            AppConfig.brightnessVwPos = !AppConfig.brightnessVwPos
            upBrightnessVwPos()
        }
        //阅读进度
        seekReadPage.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener { runMenuOut() }
                when (AppConfig.progressBarBehavior) {
                    "page" -> ReadBook.skipToPage(seekBar.progress)
                    "chapter" -> {
                        if (confirmSkipToChapter) {
                            callBack.skipToChapter(seekBar.progress)
                        } else {
                            context.alert("章节跳转确认", "确定要跳转章节吗？") {
                                yesButton {
                                    confirmSkipToChapter = true
                                    callBack.skipToChapter(seekBar.progress)
                                }
                                noButton {
                                    upSeekBar()
                                }
                                onCancelled {
                                    upSeekBar()
                                }
                            }
                        }
                    }
                }
            }

        })

        //搜索
        fabSearch.setOnClickListener {
            runMenuOut {
                callBack.openSearchActivity(null)
            }
        }

        //自动翻页
        fabAutoPage.setOnClickListener {
            runMenuOut {
                callBack.autoPage()
            }
        }

        //替换
        fabReplaceRule.setOnClickListener { callBack.openReplaceRule() }

        //夜间模式
        fabNightTheme.setOnClickListener {
            AppConfig.isNightTheme = !AppConfig.isNightTheme
            ThemeConfig.applyDayNight(context)
        }

        //上一章
        tvPre.setOnClickListener { ReadBook.moveToPrevChapter(upContent = true, toLast = false) }

        //下一章
        tvNext.setOnClickListener { ReadBook.moveToNextChapter(true) }

        //目录
        llCatalog.setOnClickListener {
            runMenuOut {
                callBack.openChapterList()
            }
        }

        //朗读
        llReadAloud.setOnClickListener {
            runMenuOut {
                callBack.onClickReadAloud()
            }
        }
        llReadAloud.onLongClick {
            runMenuOut {
                callBack.showReadAloudDialog()
            }
        }
        //界面
        llFont.setOnClickListener {
            runMenuOut {
                callBack.showReadStyle()
            }
        }

        //设置
        llSetting.setOnClickListener {
            runMenuOut {
                callBack.showMoreSetting()
            }
        }
    }

    private fun initAnimation() {
        menuTopIn.setAnimationListener(menuInListener)
        menuTopOut.setAnimationListener(menuOutListener)
    }

    fun upBookView() {
        binding.titleBar.title = ReadBook.book?.name
        ReadBook.curTextChapter?.let {
            binding.tvChapterName.text = it.title
            binding.tvChapterName.visible()
            if (!ReadBook.isLocalBook) {
                currentChapterUrl = it.chapter.getAbsoluteURL()
                val chapterMeta = formatChapterMeta(currentChapterUrl)
                if (chapterMeta.isNullOrBlank()) {
                    binding.tvChapterUrl.gone()
                } else {
                    binding.tvChapterUrl.text = chapterMeta
                    binding.tvChapterUrl.visible()
                }
            } else {
                currentChapterUrl = null
                binding.tvChapterUrl.gone()
            }
            upSeekBar()
            binding.tvPre.isEnabled = ReadBook.durChapterIndex != 0
            binding.tvNext.isEnabled = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
        } ?: let {
            currentChapterUrl = null
            binding.tvChapterName.gone()
            binding.tvChapterUrl.gone()
        }
    }

    private fun getChapterUrlForOpen(): String? {
        val url = currentChapterUrl?.substringBefore(",{")?.trim().orEmpty()
        return url.takeIf { it.isNotBlank() && !it.startsWith("data:", true) }
    }

    private fun formatChapterMeta(url: String?): String? {
        val rawUrl = url?.substringBefore(",{")?.trim().orEmpty()
        val sourceName = ReadBook.bookSource?.bookSourceName?.takeIf { it.isNotBlank() }
        if (rawUrl.isBlank() || rawUrl.startsWith("data:", true)) {
            return sourceName
        }
        val host = runCatching { Uri.parse(rawUrl).host }
            .getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        return listOfNotNull(sourceName, host)
            .distinct()
            .joinToString(" · ")
            .ifBlank { sourceName ?: host ?: rawUrl.take(32) }
    }

    fun upSeekBar() {
        binding.seekReadPage.apply {
            when (AppConfig.progressBarBehavior) {
                "page" -> {
                    ReadBook.curTextChapter?.let {
                        max = it.pageSize.minus(1)
                        progress = ReadBook.durPageIndex
                    }
                }

                "chapter" -> {
                    max = ReadBook.simulatedChapterSize - 1
                    progress = ReadBook.durChapterIndex
                }
            }
        }
    }

    fun setSeekPage(seek: Int) {
        binding.seekReadPage.progress = seek
    }

    fun setAutoPage(autoPage: Boolean) = binding.run {
        if (autoPage) {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page_stop)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page_stop)
        } else {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page)
        }
        fabAutoPage.setColorFilter(textColor)
    }

    private fun upBrightnessVwPos() {
        binding.vwBrightnessPosAdjust.gone()
    }

    interface CallBack {
        fun autoPage()
        fun openReplaceRule()
        fun openChapterList()
        fun openSearchActivity(searchWord: String?)
        fun openSourceEditActivity()
        fun openBookInfoActivity()
        fun showReadStyle()
        fun showMoreSetting()
        fun showReadAloudDialog()
        fun upSystemUiVisibility()
        fun onClickReadAloud()
        fun showHelp()
        fun showLogin()
        fun payAction()
        fun disableSource()
        fun skipToChapter(index: Int)
        fun onMenuShow()
        fun onMenuHide()
    }

}
