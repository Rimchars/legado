package io.legado.app.ui.book.read

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.Animation
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.isGone
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.databinding.ViewMangaMenuBinding
import io.legado.app.constant.PreferKey
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.ui.book.read.config.rememberReaderMenuDialogStyle
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.utils.activity
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.openUrl
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible

class MangaMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val binding = ViewMangaMenuBinding.inflate(LayoutInflater.from(context), this, true)
    internal val callBack: CallBack get() = activity as CallBack
    var canShowMenu: Boolean = false
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
    private var isMenuOutAnimating = false
    private var bgColor = context.bottomBackground

    /**
     * 当前章节进度(Compose章节条读取)
     */
    var seekProgress: Int = 0
    var seekMax: Int = 0

    /**
     * Compose快捷面板刷新信号(供setAutoPage/upMangaIcons等外部更新时触发重组)
     */
    private val refreshState = mutableIntStateOf(0)

    private val menuOutListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            isMenuOutAnimating = true
            binding.vwMenuBg.setOnClickListener(null)
        }

        override fun onAnimationEnd(animation: Animation) {
            this@MangaMenu.invisible()
            binding.mangaTitleBar.invisible()
            binding.bottomMenu.invisible()
            isMenuOutAnimating = false
            canShowMenu = false
            callBack.upSystemUiVisibility(false)
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }
    private val menuInListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            callBack.upSystemUiVisibility(true)
        }

        override fun onAnimationEnd(animation: Animation) {
            binding.vwMenuBg.setOnClickListener { runMenuOut() }
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }

    init {
        binding.root.applyUiBodyTypefaceDeep(context.uiTypeface())
        initView()
        bindEvent()
        initComposeTitleBar()
        initComposeQuickActions()
    }

    private fun initView() = binding.run {
        initAnimation()
        if (AppConfig.isEInkMode) {
            mangaTitleBar.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            bottomMenu.setBackgroundResource(R.drawable.bg_eink_border_top)
        } else {
            bottomMenu.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        /**
         * 确保视图不被导航栏遮挡
         */
        bottomMenu.applyNavigationBarPadding()
    }

    private fun initAnimation() {
        menuTopIn.setAnimationListener(menuInListener)
        menuTopOut.setAnimationListener(menuOutListener)
    }

    private fun initComposeTitleBar() {
        binding.mangaTitleBar.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MangaTitleBar(
                    onBackClick = { callBack.returnToBookshelf() },
                    onBookClick = { callBack.openBookInfoActivity() },
                    onChangeSourceClick = { callBack.changeSource() },
                    onRefreshClick = { callBack.refreshContent() },
                    onMoreClick = { callBack.showMoreSettingMenu() },
                    onAddBookmarkClick = { callBack.addBookmark() },
                    onEditSourceClick = { callBack.openSourceEditActivity() },
                    onDisableSourceClick = { callBack.disableSource() }
                )
            }
        }
    }

    private fun initComposeQuickActions() {
        binding.quickActionsCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val refresh by refreshState
                QuickActionsPanel(
                    menu = this@MangaMenu,
                    refresh = refresh,
                    onAutoPage = { callBack.autoPage() },
                    onOpenColorFilter = { callBack.openColorFilter() },
                    onToggleGray = { callBack.toggleGray() },
                    onToggleEInk = { callBack.toggleEInk() },
                    onToggleNightTheme = { callBack.toggleNightTheme() },
                    onOpenCatalog = { callBack.openCatalog() },
                    onShowInterfaceSetting = { callBack.showInterfaceSetting() },
                    onShowMoreSetting = { callBack.showMoreSettingMenu() }
                )
            }
        }
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode) {
        if (isMenuOutAnimating) {
            return
        }
        if (this.isVisible) {
            if (anim) {
                binding.mangaTitleBar.startAnimation(menuTopOut)
                binding.bottomMenu.startAnimation(menuBottomOut)
            } else {
                menuOutListener.onAnimationStart(menuBottomOut)
                menuOutListener.onAnimationEnd(menuBottomOut)
            }
        }
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        this.visible()
        binding.mangaTitleBar.visible()
        binding.bottomMenu.visible()
        refreshQuickActions()
        if (anim) {
            binding.mangaTitleBar.startAnimation(menuTopIn)
            binding.bottomMenu.startAnimation(menuBottomIn)
        } else {
            menuInListener.onAnimationStart(menuBottomIn)
            menuInListener.onAnimationEnd(menuBottomIn)
        }
    }

    /**
     * 触发Compose快捷面板重组(自动翻页图标/灰度墨水屏两态/亮度状态)
     */
    fun refreshQuickActions() {
        refreshState.value++
    }

    /**
     * 自动翻页图标状态(切换ic_auto_page_stop/ic_auto_page,Compose面板经refreshState感知)
     */
    fun setAutoPage(autoPage: Boolean) {
        refreshState.value++
    }

    /**
     * 灰度/墨水屏/滤镜图标状态刷新(Compose面板经refreshState感知)
     */
    fun upMangaIcons(filterOn: Boolean = AppConfig.mangaColorFilter.orEmpty().isNotBlank()) {
        refreshState.value++
    }

    private fun bindEvent() = binding.run {
        vwMenuBg.setOnClickListener { runMenuOut() }
    }

    fun upSeekBar(value: Int, count: Int) {
        seekProgress = value
        seekMax = count.minus(1)
        refreshQuickActions()
    }

    interface CallBack {
        fun returnToBookshelf()
        fun openBookInfoActivity()
        fun changeSource()
        fun refreshContent()
        fun openSourceEditActivity()
        fun disableSource()
        fun addBookmark()
        fun openMangaConfig()
        fun upSystemUiVisibility(menuIsVisible: Boolean)
        fun skipToPage(index: Int)
        fun autoPage()
        fun isAutoPageActive(): Boolean
        fun openColorFilter()
        fun toggleGray()
        fun toggleEInk()
        fun toggleNightTheme()
        fun openCatalog()
        fun showInterfaceSetting()
        fun showMoreSettingMenu()
    }

}

/**
 * 漫画菜单顶栏(同小说 ReadMenuTitleBar 样式:状态栏衔接+Surface全宽+返回/书名/换源/刷新/三点)
 * 下方章节信息行(同小说 ReadMenuActionBar:章节名+书源胶囊,点击弹编辑源/禁用源)
 */
@Composable
private fun MangaTitleBar(
    onBackClick: () -> Unit,
    onBookClick: () -> Unit,
    onChangeSourceClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onMoreClick: () -> Unit,
    onAddBookmarkClick: () -> Unit,
    onEditSourceClick: () -> Unit,
    onDisableSourceClick: () -> Unit
) {
    val context = LocalContext.current
    val style = rememberReaderMenuDialogStyle(context.bottomBackground)
    var sourcePopupHandle by remember { mutableStateOf<ModernActionPopup.Handle?>(null) }
    var morePopupHandle by remember { mutableStateOf<ModernActionPopup.Handle?>(null) }
    var moreAnchorBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var sourceAnchorBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 状态栏占位(带背景色,衔接顶栏)
            val statusBarHeight = WindowInsets.statusBars
                .asPaddingValues()
                .calculateTopPadding()
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusBarHeight)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBookClick)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回按钮
                MangaTitleIconButton(
                    painterRes = R.drawable.ic_arrow_back,
                    contentDescription = null,
                    tint = style.primaryText,
                    style = style,
                    onClick = onBackClick
                )
                Spacer(modifier = Modifier.width(4.dp))
                // 书名
                Text(
                    text = ReadManga.book?.name ?: "",
                    color = style.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 换源图标(本地书不显示,同小说)
                if (ReadManga.book?.isLocal != true) {
                    MangaTitleIconButton(
                        painterRes = R.drawable.ic_exchange,
                        contentDescription = stringResource(R.string.change_origin),
                        tint = style.primaryText,
                        style = style,
                        onClick = onChangeSourceClick
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // 刷新图标
                MangaTitleIconButton(
                    painterRes = R.drawable.ic_refresh_black_24dp,
                    contentDescription = stringResource(R.string.refresh),
                    tint = style.primaryText,
                    style = style,
                    onClick = onRefreshClick
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 三点菜单(同小说:ModernActionPopup溢出菜单)
                val bookmarkAdd = stringResource(R.string.bookmark_add)
                val preDownload = stringResource(R.string.pre_download)
                val enableAutoPageScroll = stringResource(R.string.enable_auto_page_scroll)
                val enableAutoScroll = stringResource(R.string.enable_auto_scroll)
                val mangaAutoPageSpeedTitle = stringResource(R.string.manga_auto_page_speed)
                val mangaColorFilterTitle = stringResource(R.string.manga_color_filter)
                val mangaFooterConfigTitle = stringResource(R.string.manga_footer_config)
                val mangaEpaperTitle = stringResource(R.string.manga_epaper)
                val mangaEpaperSettingTitle = stringResource(R.string.manga_epaper_stting)
                val enableMangaGrayTitle = stringResource(R.string.enable_manga_gray)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .onGloballyPositioned { coordinates ->
                            val pos = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                            val size = coordinates.size
                            moreAnchorBounds = androidx.compose.ui.geometry.Rect(
                                pos.x, pos.y,
                                pos.x + size.width, pos.y + size.height
                            )
                        }
                        .clickable {
                            val actions = buildList {
                                add(ModernActionPopup.Action(
                                    title = bookmarkAdd,
                                    invoke = onAddBookmarkClick
                                ))
                                add(ModernActionPopup.Action(
                                    title = preDownload,
                                    invoke = onMoreClick
                                ))
                                add(ModernActionPopup.Action(
                                    title = enableAutoPageScroll,
                                    invoke = onMoreClick
                                ))
                                add(ModernActionPopup.Action(
                                    title = enableAutoScroll,
                                    invoke = onMoreClick
                                ))
                                add(ModernActionPopup.Action(
                                    title = mangaAutoPageSpeedTitle,
                                    invoke = onMoreClick
                                ))
                                add(ModernActionPopup.Action(
                                    title = mangaColorFilterTitle,
                                    invoke = onMoreClick
                                ))
                                add(ModernActionPopup.Action(
                                    title = mangaFooterConfigTitle,
                                    invoke = onMoreClick
                                ))
                                add(ModernActionPopup.Action(
                                    title = mangaEpaperTitle,
                                    invoke = onMoreClick
                                ))
                                add(ModernActionPopup.Action(
                                    title = mangaEpaperSettingTitle,
                                    invoke = onMoreClick
                                ))
                                add(ModernActionPopup.Action(
                                    title = enableMangaGrayTitle,
                                    invoke = onMoreClick
                                ))
                            }
                            morePopupHandle = ModernActionPopup.show(
                                context,
                                moreAnchorBounds.left.toInt(),
                                moreAnchorBounds.top.toInt(),
                                moreAnchorBounds.right.toInt(),
                                moreAnchorBounds.bottom.toInt(),
                                actions,
                                morePopupHandle
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more),
                        tint = style.primaryText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            // 章节信息行(同小说 ReadMenuActionBar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 章节名
                ReadManga.curMangaChapter?.let {
                    Text(
                        text = it.chapter.title,
                        color = style.primaryText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                // 书源胶囊(本地书不显示,点击弹编辑源/禁用源;同小说)
                if (ReadManga.book?.isLocal != true) {
                    Spacer(modifier = Modifier.width(12.dp))
                    val sourceName = ReadManga.bookSource?.bookSourceName
                    val editSourceTitle = stringResource(R.string.edit_book_source)
                    val disableSourceTitle = stringResource(R.string.disable_book_source)
                    Box(
                        modifier = Modifier
                            .widthIn(max = 180.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(style.actionRadius))
                            .background(style.fieldSurface)
                            .onGloballyPositioned { coordinates ->
                                val pos = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                                val size = coordinates.size
                                sourceAnchorBounds = androidx.compose.ui.geometry.Rect(
                                    pos.x, pos.y,
                                    pos.x + size.width, pos.y + size.height
                                )
                            }
                            .clickable {
                                val menuActions = buildList {
                                    add(
                                        ModernActionPopup.Action(
                                            title = editSourceTitle,
                                            invoke = onEditSourceClick
                                        )
                                    )
                                    add(
                                        ModernActionPopup.Action(
                                            title = disableSourceTitle,
                                            invoke = onDisableSourceClick
                                        )
                                    )
                                }
                                sourcePopupHandle = ModernActionPopup.show(
                                    context,
                                    sourceAnchorBounds.left.toInt(),
                                    sourceAnchorBounds.top.toInt(),
                                    sourceAnchorBounds.right.toInt(),
                                    sourceAnchorBounds.bottom.toInt(),
                                    menuActions,
                                    sourcePopupHandle
                                )
                            }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = sourceName ?: stringResource(R.string.book_source),
                            color = style.primaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaTitleIconButton(
    painterRes: Int,
    contentDescription: String?,
    tint: Color,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(painterRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 漫画菜单快捷面板(Compose,复用Archive小说阅读菜单样式:章节条+亮度行+图标按钮网格)
 */
@Composable
private fun QuickActionsPanel(
    menu: MangaMenu,
    refresh: Int,
    onAutoPage: () -> Unit,
    onOpenColorFilter: () -> Unit,
    onToggleGray: () -> Unit,
    onToggleEInk: () -> Unit,
    onToggleNightTheme: () -> Unit,
    onOpenCatalog: () -> Unit,
    onShowInterfaceSetting: () -> Unit,
    onShowMoreSetting: () -> Unit
) {
    val context = LocalContext.current
    val style = rememberReaderMenuDialogStyle(context.bottomBackground)
    var brightness by remember(refresh) { mutableIntStateOf(AppConfig.readBrightness) }
    var brightnessAuto by remember(refresh) { mutableStateOf(context.getPrefBoolean("brightnessAuto", true)) }
    var autoPageActive by remember(refresh) { mutableStateOf(menu.callBack.isAutoPageActive()) }
    var grayOn by remember(refresh) { mutableStateOf(AppConfig.enableMangaGray) }
    var eInkOn by remember(refresh) { mutableStateOf(AppConfig.enableMangaEInk) }
    var filterOn by remember(refresh) { mutableStateOf(AppConfig.mangaColorFilter.orEmpty().isNotBlank()) }
    var nightTheme by remember(refresh) { mutableStateOf(AppConfig.isNightTheme) }

    fun setScreenBrightness(value: Float) {
        menu.activity?.run {
            val params = window.attributes
            val b = if (value < 1f) 0.004f else value / 255f
            params.screenBrightness = b
            window.attributes = params
        }
    }

    // 整体面板(同小说阅读菜单底部面板样式)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = style.panelRadius,
            topEnd = style.panelRadius
        ),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 14.dp,
                    top = 10.dp,
                    end = 14.dp,
                    bottom = 10.dp + WindowInsets.navigationBars
                        .asPaddingValues()
                        .calculateBottomPadding()
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 章节条(同小说 ReadMenuSeekBarRow)
            ReadMenuSeekBarRow(
                seekProgress = menu.seekProgress,
                seekMax = menu.seekMax,
                canGoPrev = menu.seekProgress > 0,
                canGoNext = menu.seekProgress < menu.seekMax,
                style = style,
                onPrevClick = { ReadManga.moveToPrevChapter(true) },
                onNextClick = { ReadManga.moveToNextChapter(true) },
                onSeekStart = { },
                onSeekStop = { progress -> menu.callBack.skipToPage(progress) }
            )

            // 亮度行(复用小说 ReadMenuBrightnessRow,showBrightnessView控制显隐)
            ReadMenuBrightnessRow(
                brightness = brightness,
                isAuto = brightnessAuto,
                showBrightnessView = context.getPrefBoolean(PreferKey.showBrightnessView, true),
                style = style,
                onAutoClick = {
                    brightnessAuto = !brightnessAuto
                    context.putPrefBoolean("brightnessAuto", brightnessAuto)
                },
                onBrightnessChange = {
                    brightness = it
                    setScreenBrightness(it.toFloat())
                },
                onBrightnessStop = {
                    brightness = it
                    AppConfig.readBrightness = it
                }
            )

            // 快捷按钮第一行:滤镜/灰度/墨水屏/夜间
            QuickButtonRow {
                QuickActionButton(
                    title = stringResource(R.string.manga_color_filter),
                    iconRes = R.drawable.ic_filter_filled,
                    active = filterOn,
                    style = style,
                    onClick = onOpenColorFilter
                )
                QuickActionButton(
                    title = stringResource(R.string.enable_manga_gray),
                    iconRes = if (grayOn) R.drawable.ic_grayscale_filled else R.drawable.ic_grayscale_outline,
                    active = grayOn,
                    style = style,
                    onClick = {
                        grayOn = !grayOn
                        onToggleGray()
                    }
                )
                QuickActionButton(
                    title = stringResource(R.string.manga_epaper),
                    iconRes = if (eInkOn) R.drawable.ic_book_filled else R.drawable.ic_book_outline,
                    active = eInkOn,
                    style = style,
                    onClick = {
                        eInkOn = !eInkOn
                        onToggleEInk()
                    }
                )
                QuickActionButton(
                    title = stringResource(if (nightTheme) R.string.theme_day else R.string.theme_night),
                    iconRes = if (nightTheme) R.drawable.ic_daytime else R.drawable.ic_brightness,
                    active = nightTheme,
                    style = style,
                    onClick = {
                        nightTheme = !nightTheme
                        onToggleNightTheme()
                    }
                )
            }

            // 快捷按钮第二行:目录/自动翻页/界面/设置
            QuickButtonRow {
                QuickActionButton(
                    title = stringResource(R.string.chapter_list),
                    iconRes = R.drawable.ic_toc,
                    active = false,
                    style = style,
                    onClick = onOpenCatalog
                )
                QuickActionButton(
                    title = stringResource(if (autoPageActive) R.string.auto_next_page_stop else R.string.auto_next_page),
                    iconRes = if (autoPageActive) R.drawable.ic_auto_page_stop else R.drawable.ic_auto_page,
                    active = autoPageActive,
                    style = style,
                    onClick = {
                        autoPageActive = !autoPageActive
                        onAutoPage()
                    }
                )
                QuickActionButton(
                    title = stringResource(R.string.interface_setting),
                    iconRes = R.drawable.ic_interface_setting,
                    active = false,
                    style = style,
                    onClick = onShowInterfaceSetting
                )
                QuickActionButton(
                    title = stringResource(R.string.setting),
                    iconRes = R.drawable.ic_settings,
                    active = false,
                    style = style,
                    onClick = onShowMoreSetting
                )
            }
        }
    }
}

@Composable
private fun QuickButtonRow(
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        content()
    }
}

@Composable
private fun RowScope.QuickActionButton(
    title: String,
    iconRes: Int,
    active: Boolean,
    style: AppDialogStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = if (active) style.accent else style.primaryText
    Column(
        modifier = modifier
            .weight(1f)
            .heightIn(min = 52.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = null
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = title,
            tint = textColor,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = title,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
        )
    }
}
