package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.airbnb.lottie.ImageAssetDelegate
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.airbnb.lottie.LottieImageAsset
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.TextDelegate
import com.airbnb.lottie.LottieOnCompositionLoadedListener
import com.airbnb.lottie.model.LottieCompositionCache
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import io.legado.app.R
import io.legado.app.constant.AppConst.timeFormat
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.ViewBookPageBinding
import io.legado.app.help.book.isEpub
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.AdvancedTipConfig
import io.legado.app.help.config.AdvancedTipSlot
import io.legado.app.help.config.AdvancedTitleFontAssetDelegate
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.ReadSelectionPosition
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.BatteryView
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.decodeBase64DataUrlBytes
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.setTextIfNotEqual
import splitties.views.backgroundColor
import java.io.ByteArrayInputStream
import java.util.Date
import org.json.JSONObject

/**
 * 页面视图
 */
class PageView(context: Context) : FrameLayout(context) {

    private val binding = ViewBookPageBinding.inflate(LayoutInflater.from(context), this, true)
    private val readBookActivity get() = activity as? ReadBookActivity
    private var battery = 100
    private var tvTitle: BatteryView? = null
    private var tvTime: BatteryView? = null
    private var tvBattery: BatteryView? = null
    private var tvBatteryP: BatteryView? = null
    private var tvPage: BatteryView? = null
    private var tvTotalProgress: BatteryView? = null
    private var tvTotalProgress1: BatteryView? = null
    private var tvPageAndTotal: BatteryView? = null
    private var tvBookName: BatteryView? = null
    private var tvTimeBattery: BatteryView? = null
    private var tvTimeBatteryP: BatteryView? = null
    private var isMainView = false
    /** Bumped when page-turn screenshot pixels may change (content / tip / lottie). */
    var snapRevision: Long = 1L
        private set
    private var overlayBindToken: Long = 0L
    private var overlayBindScheduledToken: Long = 0L
    private val overlayBindRunnable = Runnable {
        if (overlayBindScheduledToken != overlayBindToken) return@Runnable
        bindAdvancedOverlaysIdle()
    }
    private var currentTextPage: TextPage? = null
    private var pairedTextPage: TextPage? = null
    private var advancedTitleLottieKey: String? = null
    private var advancedTitlePairLottieKey: String? = null
    private var scrollTitleLinger: List<ScrollAdvTitle> = emptyList()
    private var lastScrollPageOffsetForTitle: Int = Int.MIN_VALUE
    private var boundScrollTitleIds: Array<String?> = arrayOf(null, null)
    private var advancedHeaderLottieKey: String? = null
    private var advancedFooterLottieKey: String? = null
    private var headerTipTextDelegate: TipFieldTextDelegate? = null
    private var footerTipTextDelegate: TipFieldTextDelegate? = null
    private var lastTipContext: AdvancedTipConfig.TipContext = AdvancedTipConfig.TipContext()
    private val styledLottieJsonCache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_STYLED_LOTTIE_CACHE_SIZE
        }
    }
    var isScroll = false

    val headerHeight: Int
        get() {
            val h1 = if (binding.vwStatusBar.isGone) 0 else binding.vwStatusBar.height
            val h2 = if (binding.llHeader.isGone) 0 else binding.llHeader.height
            return h1 + h2 + binding.vwRoot.paddingTop
        }
    val imgBgPaddingStart: Int
        get() {
            return binding.vwRoot.paddingStart
        }

    init {
        if (!isInEditMode) {
            // Hard-clip title to content band so it cannot paint through the (transparent) header.
            binding.advancedTitleOverlay.clipChildren = true
            binding.advancedTitleOverlay.clipToPadding = true
            binding.advancedTitleOverlay.clipToOutline = true
            binding.advancedTitleOverlay.outlineProvider = ViewOutlineProvider.BOUNDS
            binding.advancedTitleOverlay.elevation = 0f
            binding.advancedTitleLottie.setRenderMode(com.airbnb.lottie.RenderMode.SOFTWARE)
            binding.advancedTitleLottiePair.setRenderMode(com.airbnb.lottie.RenderMode.SOFTWARE)
            upStyle()
            binding.vwStatusBar.applyStatusBarPadding()
            binding.vwNavigationBar.applyNavigationBarPadding()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        upBg()
    }

    override fun onDetachedFromWindow() {
        clearAdvancedTitleLoadingState(binding.advancedTitleLottie)
        clearAdvancedTitleLoadingState(binding.advancedTitleLottiePair)
        advancedTitleLottieKey = null
        advancedTitlePairLottieKey = null
        binding.advancedTitleLottie.cancelAnimation()
        binding.advancedTitleLottiePair.cancelAnimation()
        binding.contentTextView.setScrollFollowBackground(null, 255)
        binding.vwRoot.background = null
        synchronized(styledLottieJsonCache) {
            styledLottieJsonCache.clear()
        }
        super.onDetachedFromWindow()
    }

    fun upStyle() = binding.run {
        upTipStyle()
        warmAdvancedTipCompositions()
        ReadBookConfig.let {
            val textColor = it.textColor
            val tipColor = with(ReadTipConfig) {
                if (tipColor == 0) textColor else tipColor
            }
            val tipDividerColor = with(ReadTipConfig) {
                when (tipDividerColor) {
                    -1 -> ContextCompat.getColor(context, R.color.divider)
                    0 -> textColor
                    else -> tipDividerColor
                }
            }
            tvHeaderLeft.setColor(tipColor)
            tvHeaderMiddle.setColor(tipColor)
            tvHeaderRight.setColor(tipColor)
            tvFooterLeft.setColor(tipColor)
            tvFooterMiddle.setColor(tipColor)
            tvFooterRight.setColor(tipColor)
            advancedTitleFallback.setTextColor(textColor)
            advancedTitleFallbackPair.setTextColor(textColor)
            advancedTitleFallback.textSize = advancedTitleTextSizeSp()
            advancedTitleFallbackPair.textSize = advancedTitleTextSizeSp()
            val titleTypeface = ChapterProvider.titlePaint.typeface ?: ChapterProvider.typeface
            advancedTitleFallback.typeface = titleTypeface
            advancedTitleFallbackPair.typeface = titleTypeface
            vwTopDivider.backgroundColor = tipDividerColor
            vwBottomDivider.backgroundColor = tipDividerColor
            upStatusBar()
            upNavigationBar()
            upPaddingDisplayCutouts()
            llHeader.setPadding(
                it.headerPaddingLeft.dpToPx(),
                it.headerPaddingTop.dpToPx(),
                it.headerPaddingRight.dpToPx(),
                it.headerPaddingBottom.dpToPx()
            )
            llFooter.setPadding(
                it.footerPaddingLeft.dpToPx(),
                it.footerPaddingTop.dpToPx(),
                it.footerPaddingRight.dpToPx(),
                it.footerPaddingBottom.dpToPx()
            )
            vwTopDivider.gone(llHeader.isGone || !it.showHeaderLine)
            vwBottomDivider.gone(llFooter.isGone || !it.showFooterLine)
        }
        upTime()
        upBattery(battery)
        invalidateTextRenderCache()
    }

    fun invalidateTextRenderCache() {
        currentTextPage?.invalidateAll()
        pairedTextPage?.invalidateAll()
        ViewCompat.postInvalidateOnAnimation(binding.contentTextView)
    }

    /**
     * 显示状态栏时隐藏header
     */
    fun upStatusBar() = with(binding.vwStatusBar) {
//        setPadding(paddingLeft, context.statusBarHeight, paddingRight, paddingBottom)
        isGone = ReadBook.book?.isEpub == true ||
            ReadBookConfig.hideStatusBar ||
            readBookActivity?.isInMultiWindow == true
    }

    fun upNavigationBar() {
        binding.vwNavigationBar.isGone = ReadBook.book?.isEpub == true || ReadBookConfig.hideNavigationBar
    }

    fun upPaddingDisplayCutouts() {
        if (ReadBookConfig.isNineBgImg) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.vwRoot, null)
            return
        }
        if (AppConfig.paddingDisplayCutouts) {
            binding.vwRoot.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                binding.vwRoot.setPadding(
                    insets.left,
                    if (binding.vwStatusBar.isGone) insets.top else 0,
                    insets.right,
                    insets.bottom
                )
                windowInsets
            }
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(binding.vwRoot, null)
            binding.vwRoot.setPadding(0, 0, 0, 0)
        }
    }

    /**
     * 更新阅读信息
     */
    private fun upTipStyle(textPage: TextPage? = currentTextPage) = binding.run {
        val isEpub = ReadBook.book?.isEpub == true
        tvHeaderLeft.tag = null
        tvHeaderMiddle.tag = null
        tvHeaderRight.tag = null
        tvFooterLeft.tag = null
        tvFooterMiddle.tag = null
        tvFooterRight.tag = null
        llHeader.isGone = if (isEpub) {
            true
        } else {
            when (ReadTipConfig.headerMode) {
                ReadTipConfig.HEADER_MODE_SHOW,
                ReadTipConfig.HEADER_MODE_ADVANCED -> false
                ReadTipConfig.HEADER_MODE_HIDE -> true
                else -> !ReadBookConfig.hideStatusBar
            }
        }
        llFooter.isGone = if (isEpub) {
            true
        } else {
            when (ReadTipConfig.footerMode) {
                ReadTipConfig.FOOTER_MODE_HIDE -> true
                else -> false
            }
        }
        ReadTipConfig.apply {
            tvHeaderLeft.isGone = tipHeaderLeft == none
            tvHeaderRight.isGone = tipHeaderRight == none
            tvHeaderMiddle.isGone = tipHeaderMiddle == none
            tvFooterLeft.isInvisible = tipFooterLeft == none
            tvFooterRight.isGone = tipFooterRight == none
            tvFooterMiddle.isGone = tipFooterMiddle == none
        }
        tvTitle = getTipView(ReadTipConfig.chapterTitle)?.apply {
            tag = ReadTipConfig.chapterTitle
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvTime = getTipView(ReadTipConfig.time)?.apply {
            tag = ReadTipConfig.time
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvBattery = getTipView(ReadTipConfig.battery)?.apply {
            tag = ReadTipConfig.battery
            isBattery = true
            textSize = 11f
        }
        tvPage = getTipView(ReadTipConfig.page)?.apply {
            tag = ReadTipConfig.page
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvTotalProgress = getTipView(ReadTipConfig.totalProgress)?.apply {
            tag = ReadTipConfig.totalProgress
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvTotalProgress1 = getTipView(ReadTipConfig.totalProgress1)?.apply {
            tag = ReadTipConfig.totalProgress1
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvPageAndTotal = getTipView(ReadTipConfig.pageAndTotal)?.apply {
            tag = ReadTipConfig.pageAndTotal
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvBookName = getTipView(ReadTipConfig.bookName)?.apply {
            tag = ReadTipConfig.bookName
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvTimeBattery = getTipView(ReadTipConfig.timeBattery)?.apply {
            tag = ReadTipConfig.timeBattery
            isBattery = true
            typeface = ChapterProvider.typeface
            textSize = 11f
        }
        tvBatteryP = getTipView(ReadTipConfig.batteryPercentage)?.apply {
            tag = ReadTipConfig.batteryPercentage
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvTimeBatteryP = getTipView(ReadTipConfig.timeBatteryPercentage)?.apply {
            tag = ReadTipConfig.timeBatteryPercentage
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        applyAdvancedTipChromeVisibility()
    }

    /**
     * 获取信息视图
     * @param tip 信息类型
     */
    private fun getTipView(tip: Int): BatteryView? = binding.run {
        return when (tip) {
            ReadTipConfig.tipHeaderLeft -> tvHeaderLeft
            ReadTipConfig.tipHeaderMiddle -> tvHeaderMiddle
            ReadTipConfig.tipHeaderRight -> tvHeaderRight
            ReadTipConfig.tipFooterLeft -> tvFooterLeft
            ReadTipConfig.tipFooterMiddle -> tvFooterMiddle
            ReadTipConfig.tipFooterRight -> tvFooterRight
            else -> null
        }
    }

    /**
     * 更新背景
     */
    fun upBg() {
        val bgDrawable = ReadBookConfig.bg?.safePageBackgroundDrawable()
        val followScrollBackground =
            AppConfig.readScrollFollowBackground &&
                isScroll &&
                !ReadBookConfig.isNineBgImg &&
                bgDrawable is BitmapDrawable &&
                !bgDrawable.bitmap.isRecycled
        val bgAlpha = (ReadBookConfig.bgAlpha / 100f * 255).toInt()
        if (followScrollBackground) {
            // Draw scrolling wallpaper on the whole page so header/footer are not solid mean-color bars.
            // Content no longer paints its own copy (avoids double-darkening).
            binding.contentTextView.setScrollFollowBackground(null, bgAlpha)
            val follow = ScrollFollowBackgroundDrawable(
                bitmap = bgDrawable.bitmap,
                offsetProvider = { binding.contentTextView.getBackgroundOffset() },
                yBiasProvider = { binding.contentTextView.top.toFloat() }
            ).apply { alpha = bgAlpha }
            binding.vwRoot.background = LayerDrawable(
                arrayOf(
                    ReadBookConfig.bgMeanColor.toDrawable(),
                    follow
                )
            )
            binding.llHeader.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.llFooter.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            binding.contentTextView.setScrollFollowBackground(null, bgAlpha)
            binding.vwRoot.background = bgDrawable?.let {
                LayerDrawable(
                    arrayOf(
                        ReadBookConfig.bgMeanColor.toDrawable(),
                        it
                    )
                )
            } ?: ReadBookConfig.bgMeanColor.toDrawable()
            binding.llHeader.background = null
            binding.llFooter.background = null
        }
        upBgAlpha()
    }

    /**
     * 更新背景透明度
     */
    fun upBgAlpha() {
        val bgAlpha = (ReadBookConfig.bgAlpha / 100f * 255).toInt()
        binding.contentTextView.setScrollFollowBackgroundAlpha(bgAlpha)
        val background = binding.vwRoot.background
        if (background is LayerDrawable && background.numberOfLayers > 1) {
            background.getDrawable(1).alpha = bgAlpha
        } else {
            background?.alpha = bgAlpha
        }
        binding.vwRoot.invalidate()
    }

    private fun Drawable.safePageBackgroundDrawable(): Drawable? {
        if (this is BitmapDrawable) {
            val source = bitmap ?: return null
            if (source.isRecycled) return null
            return BitmapDrawable(resources, source).apply {
                alpha = this@safePageBackgroundDrawable.alpha
            }
        }
        return constantState?.newDrawable(resources)?.mutate() ?: mutate()
    }

    /**
     * 更新时间信息
     */
    fun upTime() {
        tvTime?.text = timeFormat.format(Date(System.currentTimeMillis()))
        upTimeBattery()
        if (ReadTipConfig.isHeaderAdvanced() || ReadTipConfig.isFooterAdvanced()) {
            lastTipContext = lastTipContext.copy(time = AdvancedTipConfig.currentTimeText())
            refreshAdvancedTipFieldsIfBound()
            markSnapDirty()
            // Debounced: time ticks must not force multi full-page screenshots every minute.
            schedulePageTurnPrewarm()
        }
    }

    /**
     * 更新电池信息
     */
    @SuppressLint("SetTextI18n")
    fun upBattery(battery: Int) {
        this.battery = battery
        tvBattery?.setBattery(battery)
        tvBatteryP?.text = "$battery%"
        upTimeBattery()
        if (ReadTipConfig.isHeaderAdvanced() || ReadTipConfig.isFooterAdvanced()) {
            lastTipContext = lastTipContext.copy(battery = battery.toString())
            refreshAdvancedTipFieldsIfBound()
            markSnapDirty()
            schedulePageTurnPrewarm()
        }
    }

    /**
     * 更新电池信息
     */
    @SuppressLint("SetTextI18n")
    private fun upTimeBattery() {
        val time = timeFormat.format(Date(System.currentTimeMillis()))
        tvTimeBattery?.setBattery(battery, time)
        tvTimeBatteryP?.text = "$time $battery%"
    }

    /**
     * 设置内容
     */
    private fun markSnapDirty() {
        snapRevision++
    }

    private fun schedulePageTurnPrewarm() {
        (parent as? ReadView)?.schedulePageTurnPrewarm()
    }

    /**
     * Critical path: body text + classic tip labels only.
     * Advanced title/header/footer Lottie always binds on the next frame (idle),
     * so first open / chapter switch never stalls on setComposition or Lottie draw.
     */
    fun setContent(
        textPage: TextPage,
        pairedTextPage: TextPage? = null,
        resetPageOffset: Boolean = true
    ) {
        currentTextPage = textPage
        this.pairedTextPage = pairedTextPage
        markSnapDirty()
        upTipStyle(textPage)
        if (resetPageOffset) {
            resetPageOffset()
        }
        // Body text first and only on the hot path.
        binding.contentTextView.setContent(textPage, pairedTextPage, resetPageOffset)
        // Classic tip TextViews are cheap; keep them in sync for non-advanced chrome.
        applyProgressTexts(textPage)
        // If tip Lottie already composed, only refresh TextDelegate variables (no re-parse).
        refreshAdvancedTipFieldsIfBound()
        // Kick async composition parse early without setComposition on this frame.
        warmAdvancedTipCompositions()
        prewarmAdvancedTitleFromPage(textPage)
        prewarmAdvancedTitleFromPage(pairedTextPage)
        scheduleOverlayBind()
    }

    private fun scheduleOverlayBind() {
        overlayBindToken++
        overlayBindScheduledToken = overlayBindToken
        removeCallbacks(overlayBindRunnable)
        // One frame later: never compete with body text layout/draw of this frame.
        post(overlayBindRunnable)
    }

    private fun bindAdvancedOverlaysIdle() {
        val textPage = currentTextPage ?: return
        // Titles first (visible in content band), then chrome tips.
        upAdvancedTitleLotties(textPage, pairedTextPage)
        if (ReadTipConfig.isHeaderAdvanced() || ReadTipConfig.isFooterAdvanced()) {
            upAdvancedTipLotties(textPage)
        }
        // Snapshots after overlays start binding; multi-pass covers async composition.
        markSnapDirty()
        schedulePageTurnPrewarm()
    }

    /** Update classic tip labels without touching Lottie. */
    @SuppressLint("SetTextI18n")
    private fun applyProgressTexts(textPage: TextPage) = textPage.apply {
        tvBookName?.setTextIfNotEqual(ReadBook.book?.name)
        tvTitle?.setTextIfNotEqual(textPage.title)
        val readProgress = readProgress
        tvTotalProgress?.setTextIfNotEqual(readProgress)
        tvTotalProgress1?.setTextIfNotEqual("${chapterIndex.plus(1)}/${chapterSize}")
        if (textChapter.isCompleted) {
            tvPageAndTotal?.setTextIfNotEqual("${index.plus(1)}/$pageSize  $readProgress")
            tvPage?.setTextIfNotEqual("${index.plus(1)}/$pageSize")
        } else {
            val pageSizeInt = pageSize
            val pageSizeText = if (pageSizeInt <= 0) "-" else "~$pageSizeInt"
            tvPageAndTotal?.setTextIfNotEqual("${index.plus(1)}/$pageSizeText  $readProgress")
            tvPage?.setTextIfNotEqual("${index.plus(1)}/$pageSizeText")
        }
        lastTipContext = AdvancedTipConfig.TipContext(
            book = ReadBook.book?.name.orEmpty(),
            title = textPage.title,
            page = (index + 1).toString(),
            pages = if (textChapter.isCompleted) pageSize.toString() else if (pageSize <= 0) "-" else "~${pageSize}",
            progress = readProgress,
            time = AdvancedTipConfig.currentTimeText(),
            battery = battery.toString(),
            author = ReadBook.book?.author.orEmpty()
        )
    }

    /**
     * Cheap path: tip Lottie already on-screen for this package — only swap text fields.
     * Avoids setComposition / parse on page turns and minute ticks.
     */
    private fun refreshAdvancedTipFieldsIfBound() {
        if (!(ReadTipConfig.isHeaderAdvanced() || ReadTipConfig.isFooterAdvanced())) return
        val vars = AdvancedTipConfig.variables(lastTipContext)
        if (ReadTipConfig.isHeaderAdvanced()) {
            val key = AdvancedTipConfig.compositionCacheKey(AdvancedTipSlot.HEADER)
            if (advancedHeaderLottieKey == key && binding.advancedHeaderLottie.composition != null) {
                headerTipTextDelegate?.let {
                    it.variables = vars
                    binding.advancedHeaderLottie.invalidate()
                }
            }
        }
        if (ReadTipConfig.isFooterAdvanced()) {
            val key = AdvancedTipConfig.compositionCacheKey(AdvancedTipSlot.FOOTER)
            if (advancedFooterLottieKey == key && binding.advancedFooterLottie.composition != null) {
                footerTipTextDelegate?.let {
                    it.variables = vars
                    binding.advancedFooterLottie.invalidate()
                }
            }
        }
    }

    /** Best-effort: parse title payload of an adjacent page into Lottie cache off the hot path. */
    private fun prewarmAdvancedTitleFromPage(textPage: TextPage?) {
        if (ReadBookConfig.titleMode != AdvancedTitleConfig.TITLE_MODE_ADVANCED) return
        val block = textPage?.epubEmbeddedBlocks?.firstOrNull {
            it.role == AdvancedTitleConfig.LOTTIE_BLOCK_ROLE
        } ?: return
        val json = block.payload?.takeIf { it.isNotBlank() } ?: return
        val pageWidth = binding.contentTextView.width.toFloat().coerceAtLeast(1f)
        val styled = applyLottieTextFallbackStyle(json, advancedTitleTextLayerScale(block, pageWidth))
        val tw = block.width.toInt().coerceAtLeast(1)
        val th = block.height.toInt().coerceAtLeast(1)
        val key = "advanced_title:" + styled.hashCode() + ":" + tw + ":" + th
        if (LottieCompositionCache.getInstance().get(key) != null) return
        LottieCompositionFactory.fromJsonString(styled, key)
    }

    fun invalidateContentView() {
        binding.contentTextView.invalidate()
    }

    /**
     * 设置无障碍文本
     */
    fun setContentDescription(content: String) {
        binding.contentTextView.contentDescription = content
    }

    /**
     * 重置滚动位置
     */
    fun resetPageOffset() {
        binding.contentTextView.resetPageOffset()
    }

    /**
     * 设置进度
     */
    @SuppressLint("SetTextI18n")
    fun setProgress(textPage: TextPage) {
        applyProgressTexts(textPage)
        // Prefer field-only refresh; full tip bind stays on idle overlay path.
        refreshAdvancedTipFieldsIfBound()
        if ((ReadTipConfig.isHeaderAdvanced() || ReadTipConfig.isFooterAdvanced()) &&
            !advancedTipViewsBound()
        ) {
            scheduleOverlayBind()
        }
    }

    private fun advancedTipViewsBound(): Boolean {
        if (ReadTipConfig.isHeaderAdvanced()) {
            val key = AdvancedTipConfig.compositionCacheKey(AdvancedTipSlot.HEADER)
            if (advancedHeaderLottieKey != key || binding.advancedHeaderLottie.composition == null) {
                return false
            }
        }
        if (ReadTipConfig.isFooterAdvanced()) {
            val key = AdvancedTipConfig.compositionCacheKey(AdvancedTipSlot.FOOTER)
            if (advancedFooterLottieKey != key || binding.advancedFooterLottie.composition == null) {
                return false
            }
        }
        return true
    }

    fun setAutoPager(autoPager: AutoPager?) {
        binding.contentTextView.setAutoPager(autoPager)
    }

    fun submitRenderTask() {
        binding.contentTextView.submitRenderTask()
    }

    fun setIsScroll(value: Boolean) {
        val changed = isScroll != value
        isScroll = value
        if (changed && !value) {
            scrollTitleLinger = emptyList()
            lastScrollPageOffsetForTitle = Int.MIN_VALUE
            boundScrollTitleIds[0] = null
            boundScrollTitleIds[1] = null
        }
        binding.contentTextView.setIsScroll(value)
        if (value) {
            binding.advancedTitleLottie.pauseAnimation()
        } else if (binding.advancedTitleLottie.visibility == VISIBLE) {
            binding.advancedTitleLottie.playAnimation()
        }
        if (changed && AppConfig.readScrollFollowBackground) {
            upBg()
        }
    }

    /**
     * 滚动事件
     */
    fun scroll(offset: Int) {
        binding.contentTextView.scroll(offset)
        if (isScroll) {
            // Position-only sync: never reparse/reload Lottie during finger scroll (avoids jitter).
            syncScrollAdvancedTitlePositions()
            // Root wallpaper is driven by backgroundScrollOffset; invalidate for follow-bg.
            if (AppConfig.readScrollFollowBackground) {
                binding.vwRoot.invalidate()
            }
        }
    }

    /**
     * 更新是否开启选择功能
     */
    fun upSelectAble(selectAble: Boolean) {
        binding.contentTextView.selectAble = selectAble
    }

    /**
     * 优先处理页面内单击
     * @return true:已处理, false:未处理
     */
    fun onClick(x: Float, y: Float): Boolean {
        return binding.contentTextView.click(x - imgBgPaddingStart, y - headerHeight)
    }

    /**
     * 长按事件
     */
    fun longPress(
        x: Float, y: Float,
        select: (textPos: TextPos) -> Unit,
    ): Boolean =
        binding.contentTextView.longPress(x - imgBgPaddingStart, y - headerHeight, select)

    /**
     * 选择文本
     */
    fun selectText(
        x: Float, y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        return binding.contentTextView.selectText(x - imgBgPaddingStart, y - headerHeight, select)
    }

    fun getCurVisiblePage(): TextPage {
        return binding.contentTextView.getCurVisiblePage()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        return binding.contentTextView.getReadAloudPos()
    }

    fun markAsMainView() {
        isMainView = true
        binding.contentTextView.isMainView = true
    }

    fun selectStartMove(x: Float, y: Float) {
        binding.contentTextView.selectStartMove(x - imgBgPaddingStart, y - headerHeight)
    }

    fun selectStartMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int
    ) {
        binding.contentTextView.selectStartMoveIndex(relativePagePos, lineIndex, charIndex)
    }

    fun selectStartMoveIndex(textPos: TextPos) {
        binding.contentTextView.selectStartMoveIndex(textPos)
    }

    fun selectEndMove(x: Float, y: Float) {
        binding.contentTextView.selectEndMove(x - imgBgPaddingStart, y - headerHeight)
    }

    fun selectEndMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int
    ) {
        binding.contentTextView.selectEndMoveIndex(relativePagePos, lineIndex, charIndex)
    }

    fun selectEndMoveIndex(textPos: TextPos) {
        binding.contentTextView.selectEndMoveIndex(textPos)
    }

    fun getReverseStartCursor(): Boolean {
        return binding.contentTextView.reverseStartCursor
    }

    fun getReverseEndCursor(): Boolean {
        return binding.contentTextView.reverseEndCursor
    }

    fun isLongScreenShot(): Boolean {
        return binding.contentTextView.longScreenshot
    }

    fun resetReverseCursor() {
        binding.contentTextView.resetReverseCursor()
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        binding.contentTextView.cancelSelect(clearSearchResult)
    }

    fun createBookmark(): Bookmark? {
        return binding.contentTextView.createBookmark()
    }

    fun relativePage(relativePagePos: Int): TextPage {
        return binding.contentTextView.relativePage(relativePagePos)
    }

    private fun upAdvancedTitleLotties(textPage: TextPage, pairedTextPage: TextPage?) {
        val contentWidth = binding.contentTextView.width
        if (isScroll) {
            val pageWidth = contentWidth.toFloat().coerceAtLeast(1f)
            val titles = collectScrollAdvancedTitles(pageWidth)
            advancedTitleLottieKey = bindScrollAdvancedTitle(
                title = titles.getOrNull(0),
                lottieView = binding.advancedTitleLottie,
                fallbackView = binding.advancedTitleFallback,
                currentKey = advancedTitleLottieKey,
                pageWidth = pageWidth,
                slotIndex = 0
            )
            advancedTitlePairLottieKey = bindScrollAdvancedTitle(
                title = titles.getOrNull(1),
                lottieView = binding.advancedTitleLottiePair,
                fallbackView = binding.advancedTitleFallbackPair,
                currentKey = advancedTitlePairLottieKey,
                pageWidth = pageWidth,
                slotIndex = 1
            )
            boundScrollTitleIds[0] = titles.getOrNull(0)?.id
            boundScrollTitleIds[1] = titles.getOrNull(1)?.id
            return
        }
        val useDoublePage = ChapterProvider.doublePage
        val pairOffsetX = if (useDoublePage) contentWidth / 2f else 0f
        val pageWidth = if (useDoublePage) {
            contentWidth / 2f
        } else {
            contentWidth.toFloat()
        }.coerceAtLeast(1f)
        advancedTitleLottieKey = upAdvancedTitleLottie(
            textPage = textPage,
            lottieView = binding.advancedTitleLottie,
            fallbackView = binding.advancedTitleFallback,
            currentKey = advancedTitleLottieKey,
            pageOffsetX = 0f,
            pageWidth = pageWidth,
            scrollBaseY = 0f
        )
        advancedTitlePairLottieKey = upAdvancedTitleLottie(
            textPage = pairedTextPage,
            lottieView = binding.advancedTitleLottiePair,
            fallbackView = binding.advancedTitleFallbackPair,
            currentKey = advancedTitlePairLottieKey,
            pageOffsetX = pairOffsetX,
            pageWidth = pageWidth,
            scrollBaseY = 0f
        )
    }
    private data class ScrollAdvTitle(
        val id: String,
        val textPage: TextPage,
        val block: TextPage.EpubEmbeddedBlock,
        val x: Float,
        val y: Float,
        val width: Int,
        val height: Int,
        val json: String?
    )

    /**
     * Gather advanced titles still intersecting the reading viewport.
     * Titles stay mounted until fully outside (top or bottom), like normal painted titles.
     */
    private fun collectScrollAdvancedTitles(pageWidth: Float): List<ScrollAdvTitle> {
        val content = binding.contentTextView
        // Overlay is content-sized: same coordinate space as painted text pages.
        val contentHeight = content.height.toFloat().coerceAtLeast(1f)
        val contentWidth = content.width.toFloat().coerceAtLeast(1f)
        val live = ArrayList<ScrollAdvTitle>(3)
        for (pos in 0..2) {
            if (!content.hasScrollRelativePage(pos)) continue
            val page = content.scrollRelativePage(pos)
            val block = page.epubEmbeddedBlocks.firstOrNull {
                it.role == AdvancedTitleConfig.LOTTIE_BLOCK_ROLE
            } ?: continue
            val width = block.width.toInt().coerceAtLeast(1)
            val height = block.height.toInt().coerceAtLeast(1)
            // Identical to text: relativeOffset(pos) + in-page offsetY.
            val y = content.scrollRelativeOffset(pos) + block.offsetY
            val x = block.offsetX - (contentWidth - width) / 2f
            // Keep while any pixel still intersects the content viewport (like normal text).
            if (y + height <= 0f || y >= contentHeight) continue
            val json = block.payload?.takeIf { it.isNotBlank() }?.let {
                applyLottieTextFallbackStyle(it, advancedTitleTextLayerScale(block, pageWidth))
            }
            val id = "${page.chapterIndex}:${page.index}:${page.title}"
            live.add(ScrollAdvTitle(id, page, block, x, y, width, height, json))
        }
        live.sortBy { it.y }
        scrollTitleLinger = live.take(2)
        return scrollTitleLinger
    }

    /**
     * Finger-scroll path: only move overlays. Reload happens in setContent via full bind.
     */
    private fun syncScrollAdvancedTitlePositions() {
        if (!isScroll) return
        if (ReadBookConfig.titleMode != AdvancedTitleConfig.TITLE_MODE_ADVANCED) return
        val pageWidth = binding.contentTextView.width.toFloat().coerceAtLeast(1f)
        val titles = collectScrollAdvancedTitles(pageWidth)
        val id0 = titles.getOrNull(0)?.id
        val id1 = titles.getOrNull(1)?.id
        val sameBinding =
            boundScrollTitleIds[0] == id0 &&
                boundScrollTitleIds[1] == id1 &&
                (id0 == null || binding.advancedTitleLottie.composition != null || binding.advancedTitleFallback.visibility == VISIBLE) &&
                (id1 == null || binding.advancedTitleLottiePair.composition != null || binding.advancedTitleFallbackPair.visibility == VISIBLE)
        if (!sameBinding) {
            // Chapter/page set changed: full bind (may load composition once).
            currentTextPage?.let { upAdvancedTitleLotties(it, pairedTextPage) }
            return
        }
        applyScrollTitlePosition(binding.advancedTitleLottie, binding.advancedTitleFallback, titles.getOrNull(0))
        applyScrollTitlePosition(binding.advancedTitleLottiePair, binding.advancedTitleFallbackPair, titles.getOrNull(1))
    }

    private fun applyTitleContentClip(view: android.view.View, y: Float, width: Int, height: Int) {
        if (y >= 0f) {
            view.clipBounds = null
            return
        }
        val top = (-y).toInt().coerceIn(0, height)
        view.clipBounds = if (top >= height) {
            android.graphics.Rect(0, 0, 0, 0)
        } else {
            android.graphics.Rect(0, top, width.coerceAtLeast(1), height.coerceAtLeast(1))
        }
    }

    private fun applyScrollTitlePosition(
        lottieView: LottieAnimationView,
        fallbackView: TextView,
        title: ScrollAdvTitle?
    ) {
        if (title == null) {
            lottieView.visibility = GONE
            fallbackView.visibility = GONE
            return
        }
        val params = lottieView.layoutParams
        if (params.width != title.width || params.height != title.height) {
            params.width = title.width
            params.height = title.height
            lottieView.layoutParams = params
        }
        lottieView.translationX = title.x
        lottieView.translationY = title.y
        applyTitleContentClip(lottieView, title.y, title.width, title.height)
        if (lottieView.composition != null) {
            lottieView.visibility = VISIBLE
            lottieView.pauseAnimation()
            lottieView.progress = 0f
            fallbackView.visibility = GONE
        } else if (fallbackView.visibility == VISIBLE) {
            val fp = fallbackView.layoutParams
            if (fp.width != title.width || fp.height != title.height) {
                fp.width = title.width
                fp.height = title.height
                fallbackView.layoutParams = fp
            }
            fallbackView.translationX = title.x
            fallbackView.translationY = title.y
        }
        val fparams = fallbackView.layoutParams
        if (fallbackView.visibility == VISIBLE) {
            if (fparams.width != title.width || fparams.height != title.height) {
                fparams.width = title.width
                fparams.height = title.height
                fallbackView.layoutParams = fparams
            }
            fallbackView.translationX = title.x
            fallbackView.translationY = title.y
        }
    }
    private fun bindScrollAdvancedTitle(
        title: ScrollAdvTitle?,
        lottieView: LottieAnimationView,
        fallbackView: TextView,
        currentKey: String?,
        pageWidth: Float,
        slotIndex: Int
    ): String? {
        if (title == null) {
            boundScrollTitleIds[slotIndex] = null
            clearAdvancedTitleLoadingState(lottieView)
            lottieView.cancelAnimation()
            lottieView.visibility = GONE
            fallbackView.visibility = GONE
            return null
        }
        boundScrollTitleIds[slotIndex] = title.id
        // Absolute content coordinates: scrollBaseY carries full Y (contentOrigin=0 for content overlay).
        val key = upAdvancedTitleLottie(
            textPage = title.textPage,
            lottieView = lottieView,
            fallbackView = fallbackView,
            currentKey = currentKey,
            pageOffsetX = 0f,
            pageWidth = pageWidth,
            scrollBaseY = title.y - title.block.offsetY,
            forceVisibleInScroll = true
        )
        // Force exact position after bind (avoid recomputation drift).
        lottieView.translationX = title.x
        lottieView.translationY = title.y
        applyTitleContentClip(lottieView, title.y, title.width, title.height)
        if (fallbackView.visibility == VISIBLE) {
            fallbackView.translationX = title.x
            fallbackView.translationY = title.y
            applyTitleContentClip(fallbackView, title.y, title.width, title.height)
        }
        return key
    }
    private fun upAdvancedTitleLottie(
        textPage: TextPage?,
        lottieView: LottieAnimationView,
        fallbackView: TextView,
        currentKey: String?,
        pageOffsetX: Float,
        pageWidth: Float,
        scrollBaseY: Float = 0f,
        forceVisibleInScroll: Boolean = false
    ): String? {
        fun clear(): String? {
            clearAdvancedTitleLoadingState(lottieView)
            lottieView.cancelAnimation()
            lottieView.visibility = GONE
            fallbackView.visibility = GONE
            return null
        }

        fun hideLoadedComposition(): String? {
            fallbackView.visibility = GONE
            if (currentKey == null || lottieView.tag != currentKey || lottieView.composition == null) {
                return clear()
            }
            lottieView.removeAllLottieOnCompositionLoadedListener()
            lottieView.setFailureListener(null)
            lottieView.pauseAnimation()
            lottieView.progress = 0f
            lottieView.alpha = 1f
            lottieView.visibility = GONE
            return currentKey
        }

        fun resolveTitleViewSize(block: TextPage.EpubEmbeddedBlock): Pair<Int, Int> {
            return block.width.toInt().coerceAtLeast(1) to block.height.toInt().coerceAtLeast(1)
        }

        fun resolveTitleTranslationX(block: TextPage.EpubEmbeddedBlock, targetWidth: Int): Float {
            val contentWidth = binding.contentTextView.width
            if (contentWidth <= 0) return pageOffsetX + block.offsetX
            val centeredX = (contentWidth - targetWidth) / 2f
            return pageOffsetX + block.offsetX - centeredX
        }

        fun resolveTitleTranslationY(block: TextPage.EpubEmbeddedBlock, targetHeight: Int): Float {
            if (isScroll) {
                // Overlay matches ContentTextView; same Y space as painted text.
                return scrollBaseY + block.offsetY
            }
            val contentHeight = binding.contentTextView.height
            if (contentHeight <= 0) return block.offsetY
            val maxTranslation = (contentHeight - targetHeight).toFloat().coerceAtLeast(0f)
            return block.offsetY.coerceIn(0f, maxTranslation)
        }

        fun showFallback(block: TextPage.EpubEmbeddedBlock): String? {
            clearAdvancedTitleLoadingState(lottieView)
            lottieView.cancelAnimation()
            lottieView.visibility = GONE
            val (targetWidth, targetHeight) = resolveTitleViewSize(block)
            val params = fallbackView.layoutParams as ViewGroup.LayoutParams
            if (params.width != targetWidth || params.height != targetHeight) {
                params.width = targetWidth
                params.height = targetHeight
                fallbackView.layoutParams = params
            }
            fallbackView.translationX = resolveTitleTranslationX(block, targetWidth)
            fallbackView.translationY = resolveTitleTranslationY(block, targetHeight)
            fallbackView.gravity = Gravity.CENTER
            fallbackView.text = textPage?.title.orEmpty()
            fallbackView.visibility = VISIBLE
            return null
        }

        if (ReadBookConfig.titleMode != AdvancedTitleConfig.TITLE_MODE_ADVANCED) {
            return clear()
        }
        val block = textPage?.epubEmbeddedBlocks?.firstOrNull {
            it.role == AdvancedTitleConfig.LOTTIE_BLOCK_ROLE
        } ?: return hideLoadedComposition()
        // Scroll: still show Lottie, but freeze on keyframe (progress=0) and follow pageOffset.
        val (targetWidth, targetHeight) = resolveTitleViewSize(block)
        val params = lottieView.layoutParams as ViewGroup.LayoutParams
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth
            params.height = targetHeight
            lottieView.layoutParams = params
        }
        lottieView.scaleType = ImageView.ScaleType.FIT_CENTER
        lottieView.translationX = resolveTitleTranslationX(block, targetWidth)
        lottieView.translationY = resolveTitleTranslationY(block, targetHeight)
        if (isScroll && !forceVisibleInScroll) {
            val overlayHeight = binding.advancedTitleOverlay.height.toFloat()
                .takeIf { it > 0f }
                ?: (binding.llHeader.height + binding.contentTextView.height).toFloat().coerceAtLeast(1f)
            val y = lottieView.translationY
            // Fully outside the reading overlay (top or bottom edge) — same as normal painted titles.
            val offScreen = y + targetHeight <= 0f || y >= overlayHeight
            if (offScreen) {
                lottieView.pauseAnimation()
                lottieView.visibility = GONE
                fallbackView.visibility = GONE
                return currentKey
            }
        }
        // Non-scroll may loop; scroll freezes on keyframe (progress 0).
        lottieView.repeatCount = if (isScroll) 0 else LottieDrawable.INFINITE
        lottieView.setFontAssetDelegate(defaultFontAssetDelegate)
        val json = block.payload?.takeIf { it.isNotBlank() }
        val resolvedJson = json?.let { applyLottieTextFallbackStyle(it, advancedTitleTextLayerScale(block, pageWidth)) }
        val compositionSize = resolvedJson?.let(::lottieCompositionSize)
        lottieView.setMaintainOriginalImageBounds(true)
        lottieView.setImageAssetDelegate(
            dataUriImageAssetDelegate(
                viewWidth = targetWidth,
                viewHeight = targetHeight,
                compositionWidth = compositionSize?.first ?: targetWidth,
                compositionHeight = compositionSize?.second ?: targetHeight
            )
        )
        lottieView.setCacheComposition(resolvedJson == null)
        val nextKey = resolvedJson?.let {
            "advanced_title:${it.hashCode()}:$targetWidth:$targetHeight"
        } ?: "advanced_title:raw:$targetWidth:$targetHeight"

        fun showComposition() {
            if (lottieView.tag != nextKey || lottieView.composition == null) return
            fallbackView.visibility = GONE
            lottieView.progress = 0f
            lottieView.alpha = 1f
            lottieView.visibility = VISIBLE
            if (isMainView && !isScroll) {
                lottieView.playAnimation()
            } else {
                lottieView.pauseAnimation()
            }
            markSnapDirty()
            schedulePageTurnPrewarm()
        }

        if (currentKey != nextKey) {
            // Do not clear the current composition first; swap only when the next one is ready.
            lottieView.animate().cancel()
            lottieView.removeAllLottieOnCompositionLoadedListener()
            lottieView.setFailureListener(null)
            lottieView.tag = nextKey
            lottieView.alpha = 1f
            val hasOld = lottieView.composition != null && lottieView.visibility == VISIBLE
            if (!hasOld) {
                lottieView.visibility = INVISIBLE
                fallbackView.visibility = GONE
            }
            fun applyLoaded(composition: com.airbnb.lottie.LottieComposition) {
                if (lottieView.tag != nextKey) return
                lottieView.setComposition(composition)
                showComposition()
            }
            if (resolvedJson != null) {
                LottieCompositionCache.getInstance().get(nextKey)?.let { composition ->
                    applyLoaded(composition)
                    return nextKey
                }
                LottieCompositionFactory.fromJsonString(resolvedJson, nextKey)
                    .addListener { composition ->
                        composition?.let(::applyLoaded)
                    }
                    .addFailureListener {
                        if (lottieView.tag == nextKey) showFallback(block)
                    }
            } else {
                lottieView.setFailureListener {
                    if (lottieView.tag == nextKey) showFallback(block)
                }
                lottieView.addLottieOnCompositionLoadedListener(
                    LottieOnCompositionLoadedListener {
                        if (lottieView.tag == nextKey) showComposition()
                    }
                )
                runCatching {
                    lottieView.setAnimation(R.raw.advanced_title_lottie)
                }.onFailure {
                    return showFallback(block)
                }
            }
            return nextKey
        }
        if (lottieView.tag != nextKey || lottieView.composition == null) {
            lottieView.alpha = 1f
            lottieView.visibility = INVISIBLE
            return nextKey
        }
        fallbackView.visibility = GONE
        lottieView.alpha = 1f
        lottieView.visibility = VISIBLE
        runCatching {
            if (isMainView && !isScroll && !lottieView.isAnimating) {
                lottieView.playAnimation()
            } else if (!isMainView || isScroll) {
                lottieView.pauseAnimation()
                lottieView.progress = 0f
            }
        }.onFailure {
            return showFallback(block)
        }
        return nextKey
    }


    private fun applyAdvancedTipChromeVisibility() = binding.run {
        val headerAdvanced = !isEpubBook() && ReadTipConfig.isHeaderAdvanced()
        val footerAdvanced = !isEpubBook() && ReadTipConfig.isFooterAdvanced()
        if (headerAdvanced) {
            tvHeaderLeft.isGone = true
            tvHeaderMiddle.isGone = true
            tvHeaderRight.isGone = true
        }
        if (footerAdvanced) {
            tvFooterLeft.isInvisible = true
            tvFooterMiddle.isGone = true
            tvFooterRight.isGone = true
        }
        if (!headerAdvanced) {
            advancedHeaderLottie.visibility = GONE
            advancedHeaderLottieKey = null
        }
        if (!footerAdvanced) {
            advancedFooterLottie.visibility = GONE
            advancedFooterLottieKey = null
        }
    }

    private fun isEpubBook(): Boolean = ReadBook.book?.isEpub == true

    private fun upAdvancedTipLotties(textPage: TextPage?) {
        advancedHeaderLottieKey = upAdvancedTipLottie(
            slot = AdvancedTipSlot.HEADER,
            textPage = textPage,
            lottieView = binding.advancedHeaderLottie,
            currentKey = advancedHeaderLottieKey
        )
        advancedFooterLottieKey = upAdvancedTipLottie(
            slot = AdvancedTipSlot.FOOTER,
            textPage = textPage,
            lottieView = binding.advancedFooterLottie,
            currentKey = advancedFooterLottieKey
        )
    }

    private fun upAdvancedTipLottie(
        slot: AdvancedTipSlot,
        textPage: TextPage?,
        lottieView: LottieAnimationView,
        currentKey: String?
    ): String? {
        val enabled = when (slot) {
            AdvancedTipSlot.HEADER -> !isEpubBook() && ReadTipConfig.isHeaderAdvanced()
            AdvancedTipSlot.FOOTER -> !isEpubBook() && ReadTipConfig.isFooterAdvanced()
        }
        if (!enabled) {
            clearAdvancedTitleLoadingState(lottieView)
            lottieView.cancelAnimation()
            lottieView.setTextDelegate(null)
            when (slot) {
                AdvancedTipSlot.HEADER -> headerTipTextDelegate = null
                AdvancedTipSlot.FOOTER -> footerTipTextDelegate = null
            }
            lottieView.visibility = GONE
            return null
        }
        val page = textPage ?: currentTextPage
        val context = lastTipContext.copy(
            book = lastTipContext.book.ifBlank { ReadBook.book?.name.orEmpty() },
            title = page?.title?.takeIf { it.isNotBlank() } ?: lastTipContext.title,
            time = AdvancedTipConfig.currentTimeText(),
            battery = battery.toString(),
            author = ReadBook.book?.author.orEmpty()
        )
        lastTipContext = context
        val vars = AdvancedTipConfig.variables(context)
        val raw = AdvancedTipConfig.rawTemplate(slot)
        if (raw.isNullOrBlank() || !AdvancedTitleConfig.hasRenderableLayers(raw)) {
            clearAdvancedTitleLoadingState(lottieView)
            lottieView.cancelAnimation()
            lottieView.visibility = GONE
            return null
        }
        // Key by package identity only. Page/title/time updates use TextDelegate (no re-parse).
        val nextKey = AdvancedTipConfig.compositionCacheKey(slot)
        lottieView.scaleType = ImageView.ScaleType.FIT_CENTER
        lottieView.repeatCount = LottieDrawable.INFINITE
        lottieView.setFontAssetDelegate(defaultFontAssetDelegate)

        fun ensureDelegate() {
            val existing = when (slot) {
                AdvancedTipSlot.HEADER -> headerTipTextDelegate
                AdvancedTipSlot.FOOTER -> footerTipTextDelegate
            }
            if (existing != null) {
                existing.variables = vars
                lottieView.invalidate()
                return
            }
            val created = TipFieldTextDelegate(lottieView).also { it.variables = vars }
            lottieView.setTextDelegate(created)
            when (slot) {
                AdvancedTipSlot.HEADER -> headerTipTextDelegate = created
                AdvancedTipSlot.FOOTER -> footerTipTextDelegate = created
            }
        }

        fun showLoaded(composition: com.airbnb.lottie.LottieComposition) {
            if (lottieView.tag != nextKey) return
            lottieView.setComposition(composition)
            ensureDelegate()
            lottieView.alpha = 1f
            lottieView.visibility = VISIBLE
            if (isMainView) {
                if (!lottieView.isAnimating) lottieView.playAnimation()
            } else {
                lottieView.pauseAnimation()
            }
            // Composition just became drawable — pre-capture page-turn bitmaps while idle.
            markSnapDirty()
            schedulePageTurnPrewarm()
        }

        if (currentKey == nextKey && lottieView.composition != null) {
            ensureDelegate()
            lottieView.visibility = VISIBLE
            if (isMainView && !lottieView.isAnimating) lottieView.playAnimation()
            return nextKey
        }

        lottieView.animate().cancel()
        lottieView.removeAllLottieOnCompositionLoadedListener()
        lottieView.setFailureListener(null)
        lottieView.tag = nextKey
        lottieView.alpha = 1f
        if (!(lottieView.composition != null && lottieView.visibility == VISIBLE)) {
            lottieView.visibility = INVISIBLE
        }

        LottieCompositionCache.getInstance().get(nextKey)?.let { composition ->
            showLoaded(composition)
            return nextKey
        }
        if (!isMainView) {
            // Do not compete with main page on first parse; retry when cache is ready.
            lottieView.visibility = INVISIBLE
            lottieView.post {
                LottieCompositionCache.getInstance().get(nextKey)?.let { composition ->
                    if (lottieView.tag == nextKey) showLoaded(composition)
                }
            }
            LottieCompositionFactory.fromJsonString(raw, nextKey)
            return nextKey
        }
        // Main page: parse once per package; later flips only refresh TextDelegate.
        LottieCompositionFactory.fromJsonString(raw, nextKey)
            .addListener { composition -> composition?.let(::showLoaded) }
            .addFailureListener {
                if (lottieView.tag == nextKey) lottieView.visibility = GONE
            }
        return nextKey
    }

    private class TipFieldTextDelegate(
        animationView: LottieAnimationView
    ) : TextDelegate(animationView) {
        @Volatile
        var variables: Map<String, String> = emptyMap()

        init {
            setCacheText(false)
        }

        override fun getText(input: String): String {
            return AdvancedTipConfig.substituteText(input, variables)
        }
    }


    private fun warmAdvancedTipCompositions() {
        if (isEpubBook()) return
        // Always ensure raw package compositions are parsing/cached before the first flip.
        if (ReadTipConfig.isHeaderAdvanced()) {
            val raw = AdvancedTipConfig.rawTemplate(AdvancedTipSlot.HEADER)
            if (!raw.isNullOrBlank()) {
                val key = AdvancedTipConfig.compositionCacheKey(AdvancedTipSlot.HEADER)
                LottieCompositionFactory.fromJsonString(raw, key)
            }
        }
        if (ReadTipConfig.isFooterAdvanced()) {
            val raw = AdvancedTipConfig.rawTemplate(AdvancedTipSlot.FOOTER)
            if (!raw.isNullOrBlank()) {
                val key = AdvancedTipConfig.compositionCacheKey(AdvancedTipSlot.FOOTER)
                LottieCompositionFactory.fromJsonString(raw, key)
            }
        }
    }

    private fun clearAdvancedTitleLoadingState(view: LottieAnimationView) {
        view.animate().cancel()
        view.removeAllLottieOnCompositionLoadedListener()
        view.setFailureListener(null)
        view.tag = null
        view.alpha = 1f
    }

    private fun advancedTitleTextSizeSp(): Float {
        return with(ReadBookConfig) {
            (textSize + titleSize * ADVANCED_TITLE_SIZE_FACTOR).coerceAtLeast(1f)
        }
    }

    private fun advancedTitleScale(): Float {
        return with(ReadBookConfig) {
            (advancedTitleTextSizeSp() / textSize.coerceAtLeast(1)).coerceIn(0.6f, 2.5f)
        }
    }
    private fun advancedTitleTextLayerScale(block: TextPage.EpubEmbeddedBlock, pageWidth: Float): Float {
        val contentWidth = pageWidth.takeIf { it > 0f } ?: block.width
        if (contentWidth <= 0f) return 1f
        val actualWidthRatio = block.width / contentWidth
        if (actualWidthRatio < 0.98f) return 1f
        val requestedWidthRatio = ADVANCED_TITLE_WIDTH_FACTOR * advancedTitleScale() *
            (AdvancedTitleConfig.heightFactor / AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR.toFloat())
        return (requestedWidthRatio / actualWidthRatio).coerceIn(1f, 2.5f)
    }

    private fun applyLottieTextFallbackStyle(rawJson: String, textScale: Float): String {
        val fallbackColor = ReadBookConfig.textColor
        val fallbackHex = String.format("#%06X", 0xFFFFFF and fallbackColor)
        val fallbackFont = "legado_default_font"
        val normalizedTextScale = textScale.coerceIn(1f, 2.5f)
        val cacheKey = "${rawJson.hashCode()}:$fallbackHex:${"%.3f".format(normalizedTextScale)}"
        synchronized(styledLottieJsonCache) {
            styledLottieJsonCache[cacheKey]?.let { return it }
        }
        return runCatching {
            val root = JSONObject(rawJson)
            normalizeFullWidthImageLayers(root)
            val layers = root.optJSONArray("layers") ?: return rawJson
            for (i in 0 until layers.length()) {
                val layer = layers.optJSONObject(i) ?: continue
                if (layer.optInt("ty") != 5) continue
                val text = layer.optJSONObject("t") ?: continue
                val d = text.optJSONObject("d") ?: continue
                val kArr = d.optJSONArray("k") ?: continue
                for (j in 0 until kArr.length()) {
                    val keyFrame = kArr.optJSONObject(j) ?: continue
                    val style = keyFrame.optJSONObject("s") ?: continue
                    if (!style.has("f") || style.optString("f").isBlank()) {
                        style.put("f", fallbackFont)
                    }
                    if (style.optString("f") == fallbackFont ||
                        !style.has("fc") || style.optJSONArray("fc") == null
                    ) {
                        style.put("fc", parseColorArray(fallbackHex))
                    }
                    scaleLottieTextStyle(style, normalizedTextScale)
                }
            }
            val fonts = root.optJSONObject("fonts") ?: JSONObject().also { root.put("fonts", it) }
            val list = fonts.optJSONArray("list") ?: org.json.JSONArray().also { fonts.put("list", it) }
            var hasFont = false
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                if (item.optString("fName") == fallbackFont) {
                    hasFont = true
                    break
                }
            }
            if (!hasFont) {
                list.put(JSONObject().apply {
                    put("fName", fallbackFont)
                    put("fFamily", fallbackFont)
                    put("fStyle", "Regular")
                    put("ascent", 75)
                })
            }
            root.toString()
        }.getOrDefault(rawJson).also { styledJson ->
            synchronized(styledLottieJsonCache) {
                styledLottieJsonCache[cacheKey] = styledJson
            }
        }
    }

    private fun normalizeFullWidthImageLayers(root: JSONObject) {
        val rootWidth = root.optDouble("w", 0.0)
        if (rootWidth <= 0.0) return
        val assets = root.optJSONArray("assets") ?: return
        val assetWidthMap = mutableMapOf<String, Double>()
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val id = asset.optString("id").takeIf { it.isNotBlank() } ?: continue
            assetWidthMap[id] = asset.optDouble("w", 0.0)
        }
        val layers = root.optJSONArray("layers") ?: return
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            if (layer.optInt("ty") != 2) continue
            val assetWidth = assetWidthMap[layer.optString("refId")] ?: continue
            if (kotlin.math.abs(assetWidth - rootWidth) > 1.0) continue
            val scaleArray = layer.optJSONObject("ks")
                ?.optJSONObject("s")
                ?.optJSONArray("k") ?: continue
            val scaleX = scaleArray.optDouble(0, 100.0)
            val scaleY = scaleArray.optDouble(1, scaleX)
            if (scaleX <= 0.0 || scaleX >= 99.9) continue
            val fillScale = (100.0 / scaleX).coerceIn(1.0, 2.0)
            scaleArray.put(0, scaleX * fillScale)
            scaleArray.put(1, scaleY * fillScale)
        }
    }

    private fun scaleLottieTextStyle(style: JSONObject, scale: Float) {
        if (scale <= 1.001f) return
        val fontSize = style.optDouble("s", 0.0)
        if (fontSize > 0.0) {
            style.put("s", fontSize * scale)
        }
        val lineHeight = style.optDouble("lh", 0.0)
        if (lineHeight > 0.0) {
            style.put("lh", lineHeight * scale)
        }
        val size = style.optJSONArray("sz")
        val oldHeight = size?.optDouble(1, 0.0) ?: 0.0
        if (size != null && oldHeight > 0.0) {
            val newHeight = oldHeight * scale
            size.put(1, newHeight)
            val position = style.optJSONArray("ps")
            if (position != null && position.length() > 1) {
                val oldY = position.optDouble(1, 0.0)
                if (kotlin.math.abs(oldY + oldHeight / 2.0) < 1.0) {
                    position.put(1, -newHeight / 2.0)
                }
            }
        }
    }

    private fun parseColorArray(hex: String): org.json.JSONArray {
        val color = Color.parseColor(hex)
        return org.json.JSONArray().apply {
            put(Color.red(color) / 255.0)
            put(Color.green(color) / 255.0)
            put(Color.blue(color) / 255.0)
        }
    }

    private fun lottieCompositionSize(json: String): Pair<Int, Int>? {
        return runCatching {
            val root = JSONObject(json)
            val width = root.optInt("w")
            val height = root.optInt("h")
            if (width > 0 && height > 0) width to height else null
        }.getOrNull()
    }

    private fun dataUriImageAssetDelegate(
        viewWidth: Int,
        viewHeight: Int,
        compositionWidth: Int,
        compositionHeight: Int
    ) = ImageAssetDelegate { asset: LottieImageAsset ->
        val source = resolveLottieAssetSource(asset) ?: return@ImageAssetDelegate null
        val decodeSize = LottieImageMemoryPolicy.decodeSize(
            assetWidth = asset.width.takeIf { it > 0 } ?: compositionWidth.coerceAtLeast(viewWidth),
            assetHeight = asset.height.takeIf { it > 0 } ?: compositionHeight.coerceAtLeast(viewHeight),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            compositionWidth = compositionWidth,
            compositionHeight = compositionHeight
        ) ?: return@ImageAssetDelegate null
        val cacheKey = LottieImageCacheKey(
            sourceSha256 = LottieImageMemoryPolicy.sourceSha256(source),
            width = decodeSize.width,
            height = decodeSize.height
        )
        LottieImageBitmapCache.get(cacheKey)?.let { return@ImageAssetDelegate it }
        loadLottieAssetBitmap(source, decodeSize)?.also { bitmap ->
            LottieImageBitmapCache.put(cacheKey, bitmap)
        }
    }

    private fun resolveLottieAssetSource(asset: LottieImageAsset): String? {
        val candidates = arrayListOf<String>()
        asset.fileName?.let { candidates.add(it) }
        if (!asset.dirName.isNullOrBlank() && !asset.fileName.isNullOrBlank()) {
            candidates.add(asset.dirName + asset.fileName)
        }
        return candidates.firstOrNull { candidate ->
            candidate.startsWith("data:image", ignoreCase = true)
        }
    }

    private fun loadLottieAssetBitmap(source: String, decodeSize: LottieDecodeSize): android.graphics.Bitmap? {
        return runCatching {
            val bytes = source.decodeBase64DataUrlBytes() ?: return@runCatching null
            decodeBitmapByType(source, bytes, decodeSize)
        }.getOrNull()
    }

    private fun decodeBitmapByType(
        source: String,
        bytes: ByteArray,
        decodeSize: LottieDecodeSize
    ): android.graphics.Bitmap? {
        val lower = source.lowercase()
        return if (lower.contains("image/svg+xml") || lower.endsWith(".svg")) {
            SvgUtils.createBitmap(ByteArrayInputStream(bytes), decodeSize.width, decodeSize.height)
        } else {
            decodeRasterBitmap(bytes, decodeSize)
        }
    }

    private fun decodeRasterBitmap(bytes: ByteArray, decodeSize: LottieDecodeSize): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = LottieImageMemoryPolicy.fitSourceInto(bounds.outWidth, bounds.outHeight, decodeSize)
            ?: return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= target.width &&
            bounds.outHeight / (sampleSize * 2) >= target.height
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null
        if (decoded.width == target.width && decoded.height == target.height) return decoded
        return android.graphics.Bitmap.createScaledBitmap(decoded, target.width, target.height, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private val defaultFontAssetDelegate = AdvancedTitleFontAssetDelegate {
        ChapterProvider.titlePaint.typeface ?: ChapterProvider.typeface ?: Typeface.DEFAULT
    }

    val textPage get() = binding.contentTextView.textPage

    val selectedText: String get() = binding.contentTextView.getSelectedText()

    fun hasSelection(): Boolean = binding.contentTextView.hasSelection()

    fun hasNativeSelection(): Boolean = binding.contentTextView.hasNativeSelection()

    fun getSelectedReadPosition(): ReadSelectionPosition? =
        binding.contentTextView.getSelectedReadPosition()

    val selectStartPos get() = binding.contentTextView.selectStart

    private companion object {
        const val ADVANCED_TITLE_SIZE_FACTOR = 1.25f
        const val ADVANCED_TITLE_WIDTH_FACTOR = 0.86f
        const val MAX_STYLED_LOTTIE_CACHE_SIZE = 6
    }
}
