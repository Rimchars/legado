package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import com.airbnb.lottie.FontAssetDelegate
import com.airbnb.lottie.ImageAssetDelegate
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.airbnb.lottie.LottieImageAsset
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieAnimationView
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
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
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
    private var currentTextPage: TextPage? = null
    private var pairedTextPage: TextPage? = null
    private var advancedTitleLottieKey: String? = null
    private var advancedTitlePairLottieKey: String? = null
    private val lottieImageCache = object : LinkedHashMap<String, android.graphics.Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?): Boolean {
            return size > MAX_LOTTIE_IMAGE_CACHE_SIZE
        }
    }
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
        binding.advancedTitleLottie.cancelAnimation()
        binding.advancedTitleLottiePair.cancelAnimation()
        synchronized(lottieImageCache) {
            lottieImageCache.clear()
        }
        synchronized(styledLottieJsonCache) {
            styledLottieJsonCache.clear()
        }
        super.onDetachedFromWindow()
    }

    fun upStyle() = binding.run {
        upTipStyle()
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
                1 -> false
                2 -> true
                else -> !ReadBookConfig.hideStatusBar
            }
        }
        llFooter.isGone = if (isEpub) {
            true
        } else {
            when (ReadTipConfig.footerMode) {
                1 -> true
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
        val bgDrawable = ReadBookConfig.bg
        val followScrollBackground =
            AppConfig.readScrollFollowBackground &&
                isScroll &&
                !ReadBookConfig.isNineBgImg &&
                bgDrawable is BitmapDrawable
        val bgAlpha = (ReadBookConfig.bgAlpha / 100f * 255).toInt()
        val foregroundDrawable = if (followScrollBackground) {
            binding.contentTextView.setScrollFollowBackground(bgDrawable.bitmap, bgAlpha)
            null
        } else {
            binding.contentTextView.setScrollFollowBackground(null, bgAlpha)
            bgDrawable
        }
        binding.vwRoot.background = foregroundDrawable?.let {
            LayerDrawable(
                arrayOf(
                    ReadBookConfig.bgMeanColor.toDrawable(),
                    it
                )
            )
        } ?: ReadBookConfig.bgMeanColor.toDrawable()
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
            ReadBookConfig.bg?.alpha = bgAlpha
        }
        binding.vwRoot.invalidate()
    }

    /**
     * 更新时间信息
     */
    fun upTime() {
        tvTime?.text = timeFormat.format(Date(System.currentTimeMillis()))
        upTimeBattery()
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
    fun setContent(
        textPage: TextPage,
        pairedTextPage: TextPage? = null,
        resetPageOffset: Boolean = true
    ) {
        currentTextPage = textPage
        this.pairedTextPage = pairedTextPage
        upTipStyle(textPage)
        upAdvancedTitleLotties(textPage, pairedTextPage)
        if (isMainView && !isScroll) {
            setProgress(textPage)
        } else {
            post {
                setProgress(textPage)
            }
        }
        if (resetPageOffset) {
            resetPageOffset()
        }
        binding.contentTextView.setContent(textPage, pairedTextPage, resetPageOffset)
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
    fun setProgress(textPage: TextPage) = textPage.apply {
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
            val pageSize = if (pageSizeInt <= 0) "-" else "~$pageSizeInt"
            tvPageAndTotal?.setTextIfNotEqual("${index.plus(1)}/$pageSize  $readProgress")
            tvPage?.setTextIfNotEqual("${index.plus(1)}/$pageSize")
        }
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
        val contentWidth = binding.contentTextView.width.takeIf { it > 0 } ?: width
        val useDoublePage = ChapterProvider.doublePage && !isScroll
        val pairOffsetX = if (useDoublePage) {
            contentWidth / 2f
        } else {
            0f
        }
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
            pageWidth = pageWidth
        )
        advancedTitlePairLottieKey = upAdvancedTitleLottie(
            textPage = pairedTextPage,
            lottieView = binding.advancedTitleLottiePair,
            fallbackView = binding.advancedTitleFallbackPair,
            currentKey = advancedTitlePairLottieKey,
            pageOffsetX = pairOffsetX,
            pageWidth = pageWidth
        )
    }

    private fun upAdvancedTitleLottie(
        textPage: TextPage?,
        lottieView: LottieAnimationView,
        fallbackView: TextView,
        currentKey: String?,
        pageOffsetX: Float,
        pageWidth: Float
    ): String? {
        fun hide(): String? {
            lottieView.cancelAnimation()
            lottieView.visibility = GONE
            fallbackView.visibility = GONE
            return null
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
            val contentHeight = binding.contentTextView.height
            if (contentHeight <= 0) return block.offsetY
            val maxTranslation = (contentHeight - targetHeight).toFloat().coerceAtLeast(0f)
            return block.offsetY.coerceIn(0f, maxTranslation)
        }

        fun showFallback(block: TextPage.EpubEmbeddedBlock): String? {
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
            return hide()
        }
        val block = textPage?.epubEmbeddedBlocks?.firstOrNull {
            it.role == AdvancedTitleConfig.LOTTIE_BLOCK_ROLE
        } ?: return hide()
        if (isScroll) {
            return showFallback(block)
        }
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
        lottieView.repeatCount = LottieDrawable.INFINITE
        lottieView.setFontAssetDelegate(defaultFontAssetDelegate)
        lottieView.setImageAssetDelegate(dataUriImageAssetDelegate)
        val json = block.payload?.takeIf { it.isNotBlank() }
        val resolvedJson = json?.let { applyLottieTextFallbackStyle(it, advancedTitleTextLayerScale(block, pageWidth)) }
        val nextKey = resolvedJson?.let { "advanced_title:${it.hashCode()}" } ?: "advanced_title:raw"
        if (currentKey != nextKey) {
            runCatching {
                if (resolvedJson != null) {
                    lottieView.setAnimationFromJson(resolvedJson, nextKey)
                } else {
                    lottieView.setAnimation(R.raw.advanced_title_lottie)
                }
            }.onFailure {
                return showFallback(block)
            }
        }
        fallbackView.visibility = GONE
        lottieView.visibility = VISIBLE
        runCatching {
            if (!isScroll && !lottieView.isAnimating) {
                lottieView.playAnimation()
            } else if (isScroll) {
                lottieView.pauseAnimation()
                lottieView.progress = 1f
            }
        }.onFailure {
            return showFallback(block)
        }
        return nextKey
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
                    if (!style.has("fc") || style.optJSONArray("fc") == null) {
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

    private val dataUriImageAssetDelegate = ImageAssetDelegate { asset: LottieImageAsset ->
        val source = resolveLottieAssetSource(asset) ?: return@ImageAssetDelegate null
        synchronized(lottieImageCache) {
            lottieImageCache[source]?.let { return@ImageAssetDelegate it }
        }
        val bitmap = loadLottieAssetBitmap(source)
        if (bitmap != null) {
            synchronized(lottieImageCache) {
                lottieImageCache[source] = bitmap
            }
        }
        bitmap
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

    private fun loadLottieAssetBitmap(source: String): android.graphics.Bitmap? {
        return runCatching {
            val bytes = source.decodeBase64DataUrlBytes() ?: return@runCatching null
            decodeBitmapByType(source, bytes)
        }.getOrNull()
    }

    private fun decodeBitmapByType(source: String, bytes: ByteArray): android.graphics.Bitmap? {
        val lower = source.lowercase()
        return if (lower.contains("image/svg+xml") || lower.endsWith(".svg")) {
            SvgUtils.createBitmap(ByteArrayInputStream(bytes), 1200, 1200)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private val defaultFontAssetDelegate = object : FontAssetDelegate() {
        override fun fetchFont(fontFamily: String): Typeface {
            return ChapterProvider.titlePaint.typeface ?: ChapterProvider.typeface ?: Typeface.DEFAULT
        }
    }

    val textPage get() = binding.contentTextView.textPage

    val selectedText: String get() = binding.contentTextView.getSelectedText()

    fun hasSelection(): Boolean = binding.contentTextView.hasSelection()

    fun hasNativeSelection(): Boolean = binding.contentTextView.hasNativeSelection()

    val selectStartPos get() = binding.contentTextView.selectStart

    private companion object {
        const val ADVANCED_TITLE_SIZE_FACTOR = 1.25f
        const val ADVANCED_TITLE_WIDTH_FACTOR = 0.86f
        const val MAX_STYLED_LOTTIE_CACHE_SIZE = 6
        const val MAX_LOTTIE_IMAGE_CACHE_SIZE = 4
    }
}
