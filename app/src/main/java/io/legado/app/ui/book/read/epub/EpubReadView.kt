package io.legado.app.ui.book.read.epub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.Magnifier
import android.widget.Scroller
import io.legado.app.constant.PageAnim
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.layout.EpubContainerFragment
import io.legado.app.model.localBook.epubcore.layout.EpubFlexFragment
import io.legado.app.model.localBook.epubcore.layout.EpubPageFragment
import io.legado.app.model.localBook.epubcore.layout.EpubTableFragment
import io.legado.app.model.localBook.epubcore.layout.EpubWebFragment
import io.legado.app.model.localBook.epubcore.layout.pageKey
import io.legado.app.model.localBook.epubcore.selector.EpubPageSelectorBuilder
import io.legado.app.model.localBook.epubcore.selector.EpubSelectionGeometry
import io.legado.app.model.localBook.epubcore.selector.EpubSelectableBlock
import io.legado.app.model.localBook.epubcore.selector.EpubSelectablePage
import io.legado.app.model.localBook.epubcore.selector.EpubSelectableLine
import io.legado.app.model.localBook.epubcore.selector.EpubTextHit
import io.legado.app.model.localBook.epubcore.web.EpubWebSelectionAction
import io.legado.app.model.localBook.epubcore.web.EpubWebSelectionPayload
import kotlin.math.*

@Suppress("DEPRECATION")
class EpubReadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener : EpubGestureController.Listener {
        override fun onTapAction(action: Int, x: Float, y: Float) = Unit
        fun onPageChanged(pageIndex: Int, pageCount: Int) = Unit
        fun onPageBoundary(direction: Int) = Unit
        fun onTextSelected(startX: Float, topY: Float, endX: Float, bottomY: Float, startBottomY: Float = bottomY, endBottomY: Float = bottomY) = Unit
        fun onSelectionCleared() = Unit
        fun onWebTextSelectionRequested(
            page: EpubCorePage,
            pageIndex: Int,
            action: EpubWebSelectionAction,
            x: Float,
            y: Float,
            generation: Long,
            pageKey: String
        ): Boolean = false
    }

    data class SelectionAnchor(
        val startX: Float,
        val topY: Float,
        val endX: Float,
        val bottomY: Float,
        val startBottomY: Float = bottomY,
        val endBottomY: Float = bottomY
    )

    private enum class GestureMode {
        None,
        Horizontal,
        BoundaryRequest,
        VerticalScroll
    }

    private data class HorizontalSnapshotSession(
        val fromIndex: Int,
        var toIndex: Int,
        val direction: Int,
        val pageAnim: Int,
        val fromSnapshot: EpubPageBitmapSnapshot?,
        val toSnapshot: EpubPageBitmapSnapshot?,
        var toPageKey: String? = null,
        var pageCommitted: Boolean = false
    ) {
        fun recycle() {
            fromSnapshot?.recycle()
            toSnapshot?.recycle()
        }
    }

    private data class SelectionHighlight(
        val rects: List<RectF>,
        val paths: List<Path>,
        val anchor: SelectionAnchor
    )

    private val selectableIndex = mutableMapOf<String, EpubSelectablePage>()
    private var selectionStartBlock: EpubSelectableBlock? = null
    private var selectionStartOffset: Int = 0
    private var selectionEndBlock: EpubSelectableBlock? = null
    private var selectionEndOffset: Int = 0

    val renderer: EpubPageRenderer = EpubPageRenderer()

    private val prevSlot = EpubPageSlotView(context, renderer)
    private val nextSlot = EpubPageSlotView(context, renderer)
    private val nextPlusSlot = EpubPageSlotView(context, renderer)
    private val currentSlot = EpubPageSlotView(context, renderer)
    private val horizontalScroller = Scroller(context, LinearInterpolator())
    private val linkedHorizontalScroller = Scroller(context, PathInterpolator(0.4f, 0f, 0.2f, 1f))
    private val verticalScroller = Scroller(context)
    private val simulationTurnRenderer = EpubSimulationTurnRenderer()
    private val velocityTracker = VelocityTracker.obtain()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val edgeShadowDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x66111111, 0x00000000)
    ).apply {
        gradientType = GradientDrawable.LINEAR_GRADIENT
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5538A8FF
        style = Paint.Style.FILL
    }
    private val loadingOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }
    private val loadingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val loadingBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private var pages: List<EpubCorePage> = emptyList()
    private var listener: Listener? = null
    private var gestureMode = GestureMode.None
    private var horizontalDirection = 0
    private var horizontalTargetIndex = -1
    private var horizontalPageAnim = PageAnim.slidePageAnim
    private var horizontalSession: HorizontalSnapshotSession? = null
    private var horizontalOffset = 0f
    private var scrollOffsetY = 0f
    private var lastScrollerY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var horizontalAnimating = false
    private var horizontalCancelling = false
    private var verticalAnimating = false
    private var boundaryRequestDirection = 0
    private var boundaryLoadingTransactionId = 0L
    private var boundaryLoadingTargetChapterIndex = -1
    private var animationGeneration = 0
    private var simulationTouchX = 0.1f
    private var simulationTouchY = 0.1f
    private var loadingMessage: String? = null
    private var boundaryLoadingAnchorKey: String? = null
    private var selectedText: String = ""
    private var selectionAnchor: SelectionAnchor? = null
    private var selectionHighlight: SelectionHighlight? = null
    private var webSelectionActive = false
    private var selectionGeneration = 0L
    private var primaryTouchActive = false
    private var selectionExistedOnDown = false
    private var selectionHandleDragActive = false
    private var selectionMenuPending = false
    private var longPressTriggered = false
    private var selectionMagnifier: Magnifier? = null
    private val longPressRunnable = Runnable {
        if (isVerticalScrollMode()) return@Runnable
        longPressTriggered = true
        selectTextAt(downX, downY)?.let { anchor ->
            deferOrShowSelectionMenu(anchor)
        }
    }

    private companion object {
        const val BoundaryLoadingPrefix = "loading:epub-boundary:"
    }

    var pageIndex: Int = 0
        private set

    private val isLandscape: Boolean
        get() = width > height

    val isTextSelected: Boolean
        get() = selectedText.isNotBlank()

    var layoutConfig: EpubCoreLayoutConfig?
        get() = renderer.layoutConfig
        set(value) {
            renderer.layoutConfig = value
            invalidateSlotDisplayLists()
        }

    init {
        isFocusable = true
        isClickable = true
        setWillNotDraw(false)
        clipChildren = true
        clipToPadding = true
        addView(prevSlot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(nextSlot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(nextPlusSlot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(currentSlot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        resetSlotVisibility()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setPages(pages: List<EpubCorePage>, initialPageIndex: Int = 0) {
        abortAnimation(resetSlots = false)
        hideLoading()
        clearSelection()
        boundaryLoadingAnchorKey = null
        boundaryRequestDirection = 0
        boundaryLoadingTransactionId = 0L
        boundaryLoadingTargetChapterIndex = -1
        this.pages = pages
        selectableIndex.clear()
        pageIndex = initialPageIndex.coerceInPageRange()
        scrollOffsetY = 0f
        horizontalOffset = 0f
        bindIdleSlots()
        notifyPageChanged()
    }

    fun showLoading(chapterIndex: Int, message: String) {
        loadingMessage = message
        invalidate()
    }

    fun setError(message: String) {
        loadingMessage = message
        invalidate()
    }

    fun clearLoading() {
        if (loadingMessage == null) return
        loadingMessage = null
        invalidate()
    }

    fun hideLoading() {
        clearLoading()
    }

    fun clearBoundaryLoadingTurn(): Boolean {
        val hadLoading = boundaryLoadingAnchorKey != null ||
            pages.any { it.chapterHref.startsWith(BoundaryLoadingPrefix) }
        boundaryLoadingAnchorKey = null
        boundaryRequestDirection = 0
        boundaryLoadingTransactionId = 0L
        boundaryLoadingTargetChapterIndex = -1
        loadingMessage = null
        val currentKey = currentPage()?.pageKey()
        val filtered = pages.filterNot { it.chapterHref.startsWith(BoundaryLoadingPrefix) }
        if (filtered.size == pages.size) {
            invalidate()
            return hadLoading
        }
        pages = filtered
        pageIndex = currentKey?.let { key ->
            pages.indexOfFirst { it.pageKey() == key }.takeIf { it >= 0 }
        } ?: pageIndex.coerceInPageRange()
        clearSelection()
        bindIdleSlots()
        invalidate()
        notifyPageChanged()
        return true
    }

    fun currentPage(): EpubCorePage? = pages.getOrNull(pageIndex)

    fun nextPage(): Boolean = setPageIndex(pageIndex + 1)

    fun previousPage(): Boolean = setPageIndex(pageIndex - 1)

    fun setPageIndex(index: Int): Boolean {
        val nextIndex = index.coerceInPageRange()
        if (nextIndex == pageIndex) return false
        commitPageIndex(nextIndex, resetScroll = true)
        return true
    }

    fun currentChapterPageIndex(): Int {
        val page = currentPage() ?: return 0
        return pages.take(pageIndex + 1).count { it.chapterIndex == page.chapterIndex }.coerceAtLeast(1) - 1
    }

    fun currentChapterPageCount(): Int {
        val page = currentPage() ?: return 0
        return pages.count { it.chapterIndex == page.chapterIndex }.coerceAtLeast(1)
    }

    fun setChapterPageIndex(chapterIndex: Int, chapterPageIndex: Int): Boolean {
        val target = pages.withIndex()
            .filter { it.value.chapterIndex == chapterIndex }
            .getOrNull(chapterPageIndex.coerceAtLeast(0))
            ?.index
            ?: return false
        return setPageIndex(target)
    }

    fun setChapterPageEdge(chapterIndex: Int, toLastPage: Boolean): Boolean {
        val matches = pages.withIndex().filter { it.value.chapterIndex == chapterIndex }
        val target = if (toLastPage) matches.lastOrNull()?.index else matches.firstOrNull()?.index
        return target?.let { setPageIndex(it) } ?: false
    }

    fun preferredPageIndexForReload(chapterIndex: Int, currentPosition: Int = 0): Int {
        val current = currentPage()
        return if (current?.chapterIndex == chapterIndex) currentChapterPageIndex() else currentPosition
    }

    fun selectTextAt(x: Float, y: Float): SelectionAnchor? {
        if (isVerticalScrollMode()) return null
        val page = currentPage() ?: return null
        selectTextAtCanvasFallback(x, y)?.let { anchor ->
            showSelectionMagnifier(x, y)
            return anchor
        }
        if (isPointInsideWebFragment(page, x, y)) {
            val generation = nextSelectionGeneration()
            if (listener?.onWebTextSelectionRequested(
                    page,
                    pageIndex,
                    EpubWebSelectionAction.SelectWord,
                    x,
                    y,
                    generation,
                    page.pageKey()
                ) == true
            ) {
                return null
            }
        }
        return null
    }

    fun selectTextAtCanvasFallback(x: Float, y: Float): SelectionAnchor? {
        if (isVerticalScrollMode()) return null
        val page = currentPage() ?: return null
        val selectablePage = ensureSelectablePage(page)
        val hit = hitTestSelection(selectablePage, x, y, strict = true) ?: return null
        webSelectionActive = false
        selectionStartBlock = hit.block
        selectionStartOffset = hit.expandedStartOffset()
        selectionEndBlock = hit.block
        selectionEndOffset = hit.expandedEndOffset()
        updateSelection(selectablePage)
        return selectionAnchor
    }

    fun selectStartMove(x: Float, y: Float) {
        if (isVerticalScrollMode()) return
        val page = currentPage() ?: return
        val generation = nextSelectionGeneration()
        if (webSelectionActive &&
            listener?.onWebTextSelectionRequested(
                page,
                pageIndex,
                EpubWebSelectionAction.MoveStart,
                x,
                y,
                generation,
                page.pageKey()
            ) == true
        ) {
            return
        }
        val selectablePage = ensureSelectablePage(page)
        val hit = hitTestSelection(
            selectablePage,
            x,
            y,
            strict = false,
            maxDistance = selectionHandleHitDistance()
        ) ?: return
        if (selectionStartBlock == null || selectionEndBlock == null) {
            selectionStartBlock = hit.block
            selectionStartOffset = hit.textOffset
            selectionEndBlock = hit.block
            selectionEndOffset = hit.textOffset
        } else {
            selectionStartBlock = hit.block
            selectionStartOffset = hit.textOffset
        }
        updateSelection(selectablePage)
        showSelectionMagnifier(x, y)
    }

    fun selectEndMove(x: Float, y: Float) {
        if (isVerticalScrollMode()) return
        val page = currentPage() ?: return
        val generation = nextSelectionGeneration()
        if (webSelectionActive &&
            listener?.onWebTextSelectionRequested(
                page,
                pageIndex,
                EpubWebSelectionAction.MoveEnd,
                x,
                y,
                generation,
                page.pageKey()
            ) == true
        ) {
            return
        }
        val selectablePage = ensureSelectablePage(page)
        val hit = hitTestSelection(
            selectablePage,
            x,
            y,
            strict = false,
            maxDistance = selectionHandleHitDistance()
        ) ?: return
        if (selectionStartBlock == null || selectionEndBlock == null) {
            selectionStartBlock = hit.block
            selectionStartOffset = hit.textOffset
            selectionEndBlock = hit.block
            selectionEndOffset = hit.textOffset
        } else {
            selectionEndBlock = hit.block
            selectionEndOffset = hit.textOffset
        }
        updateSelection(selectablePage)
        showSelectionMagnifier(x, y)
    }

    fun selectStartMoveOnScreen(rawX: Float, rawY: Float) {
        val local = screenToLocal(rawX, rawY)
        selectStartMove(local.x, local.y)
    }

    fun selectEndMoveOnScreen(rawX: Float, rawY: Float) {
        val local = screenToLocal(rawX, rawY)
        selectEndMove(local.x, local.y)
    }

    fun getSelectedText(): String = selectedText

    fun isSelectionRequestCurrent(
        generation: Long,
        pageKey: String,
        chapterIndex: Int,
        requestPageIndex: Int
    ): Boolean {
        val page = currentPage() ?: return false
        return generation == selectionGeneration &&
            page.pageKey() == pageKey &&
            page.chapterIndex == chapterIndex &&
            pageIndex == requestPageIndex
    }

    fun beginSelectionHandleDrag() {
        selectionHandleDragActive = true
        selectionMenuPending = false
    }

    fun endSelectionHandleDrag() {
        selectionHandleDragActive = false
        dismissSelectionMagnifier()
        if (selectedText.isNotBlank() && selectionAnchor != null) {
            selectionMenuPending = true
            notifySelectionMenuIfReady()
        }
    }

    fun cancelSelectionHandleDrag() {
        selectionHandleDragActive = false
        selectionMenuPending = false
        dismissSelectionMagnifier()
    }

    fun deferOrShowSelectionMenu(anchor: SelectionAnchor) {
        selectionMenuPending = true
        selectionAnchor = anchor
        notifySelectionMenuIfReady()
    }

    fun applyWebSelectionPayload(payload: EpubWebSelectionPayload): SelectionAnchor? {
        val page = currentPage() ?: return null
        if (payload.chapterIndex != page.chapterIndex || payload.pageIndex != pageIndex) return null
        val rects = normalizeWebSelectionRects(payload.rects.map { RectF(it.rect) })
        if (payload.rects.isNotEmpty() && rects.isEmpty()) {
            AppLog.putDebug(
                "EPUB Web selection rects filtered: chapter=${payload.chapterIndex} page=${payload.pageIndex} raw=${payload.rects.size}"
            )
        }
        if (payload.selectedText.isBlank() || rects.isEmpty()) return null
        selectedText = payload.selectedText
        webSelectionActive = true
        selectionStartBlock = null
        selectionEndBlock = null
        selectionStartOffset = 0
        selectionEndOffset = 0
        val firstRect = rects.first()
        val lastRect = rects.last()
        selectionAnchor = SelectionAnchor(
            startX = firstRect.left,
            topY = rects.minOf { it.top },
            endX = lastRect.right,
            bottomY = rects.maxOf { it.bottom },
            startBottomY = firstRect.bottom,
            endBottomY = lastRect.bottom
        )
        selectionHighlight = SelectionHighlight(rects, emptyList(), selectionAnchor!!)
        invalidate()
        return selectionAnchor
    }

    private fun normalizeWebSelectionRects(rawRects: List<RectF>): List<RectF> {
        if (rawRects.isEmpty()) return emptyList()
        val pageWidth = width.toFloat().takeIf { it > 0f } ?: (layoutConfig?.pageWidthPx?.toFloat() ?: 0f)
        val pageHeight = height.toFloat().takeIf { it > 0f } ?: (layoutConfig?.pageHeightPx?.toFloat() ?: 0f)
        if (pageWidth <= 0f || pageHeight <= 0f) return rawRects.filter { it.width() > 0f && it.height() > 0f }
        val filtered = rawRects.mapNotNull { source ->
            val rect = RectF(
                source.left.coerceIn(0f, pageWidth),
                source.top.coerceIn(0f, pageHeight),
                source.right.coerceIn(0f, pageWidth),
                source.bottom.coerceIn(0f, pageHeight)
            )
            if (rect.width() <= 0.5f || rect.height() <= 0.5f) return@mapNotNull null
            if (rect.width() >= pageWidth * 0.92f && rect.height() <= pageHeight * 0.12f) return@mapNotNull null
            rect
        }.sortedWith(compareBy<RectF> { it.top }.thenBy { it.left })
        if (filtered.isEmpty()) return emptyList()
        val merged = ArrayList<RectF>(filtered.size)
        filtered.forEach { rect ->
            val last = merged.lastOrNull()
            if (last != null && sameSelectionLine(last, rect) && rect.left <= last.right + 3f) {
                last.union(rect)
            } else {
                merged += RectF(rect)
            }
        }
        return merged
    }

    private fun sameSelectionLine(a: RectF, b: RectF): Boolean {
        val overlap = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val minHeight = minOf(a.height(), b.height()).coerceAtLeast(1f)
        return overlap >= minHeight * 0.55f || abs(a.top - b.top) <= maxOf(2f, minHeight * 0.35f)
    }

    fun clearSelection(notify: Boolean = true) {
        nextSelectionGeneration()
        dismissSelectionMagnifier()
        selectedText = ""
        selectionAnchor = null
        selectionHighlight = null
        webSelectionActive = false
        selectionMenuPending = false
        selectionStartBlock = null
        selectionEndBlock = null
        selectionStartOffset = 0
        selectionEndOffset = 0
        invalidate()
        if (notify) {
            listener?.onSelectionCleared()
        }
    }

    private fun nextSelectionGeneration(): Long {
        selectionGeneration += 1
        return selectionGeneration
    }

    private fun notifySelectionMenuIfReady() {
        val anchor = selectionAnchor ?: return
        if (!selectionMenuPending || primaryTouchActive || selectionHandleDragActive) return
        selectionMenuPending = false
        listener?.onTextSelected(
            anchor.startX,
            anchor.topY,
            anchor.endX,
            anchor.bottomY,
            anchor.startBottomY,
            anchor.endBottomY
        )
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        clearSelection()
    }

    fun startBoundaryLoadingTurn(
        transactionId: Long,
        direction: Int,
        targetChapterIndex: Int,
        message: String,
        anchorPage: EpubCorePage? = currentPage()
    ): Boolean {
        if (direction == 0 || anchorPage == null) return false
        clearBoundaryLoadingTurn()
        val anchorKey = anchorPage.pageKey()
        val loading = EpubCorePage(
            chapterIndex = anchorPage.chapterIndex + direction,
            chapterHref = "$BoundaryLoadingPrefix$anchorKey",
            pageIndex = 0,
            totalPagesInChapter = 1,
            text = "",
            fragments = emptyList(),
            start = null,
            end = null
        )
        boundaryLoadingAnchorKey = anchorKey
        boundaryRequestDirection = direction
        boundaryLoadingTransactionId = transactionId
        boundaryLoadingTargetChapterIndex = targetChapterIndex
        val insertAt = if (direction > 0) (pageIndex + 1).coerceAtMost(pages.size) else pageIndex.coerceAtLeast(0)
        pages = pages.toMutableList().apply { add(insertAt, loading) }
        pageIndex = insertAt.coerceInPageRange()
        loadingMessage = message
        bindIdleSlots()
        invalidate()
        notifyPageChanged()
        return true
    }

    fun replaceBoundaryLoadingPage(
        transactionId: Long,
        direction: Int,
        targetChapterIndex: Int,
        targetPages: List<EpubCorePage>,
        resetPageOffset: Boolean
    ): Boolean {
        if (targetPages.isEmpty()) return false
        if (transactionId != boundaryLoadingTransactionId ||
            direction != boundaryRequestDirection ||
            targetChapterIndex != boundaryLoadingTargetChapterIndex
        ) {
            AppLog.putDebug(
                "EPUB boundary loading stale: tx=$transactionId current=$boundaryLoadingTransactionId " +
                    "dir=$direction currentDir=$boundaryRequestDirection target=$targetChapterIndex " +
                    "currentTarget=$boundaryLoadingTargetChapterIndex"
            )
            return false
        }
        val index = pages.indexOfFirst { it.chapterHref.startsWith(BoundaryLoadingPrefix) }
        if (index < 0) return false
        pages = pages.toMutableList().apply {
            removeAt(index)
            addAll(index, targetPages)
        }
        pageIndex = if (resetPageOffset) index else (index + targetPages.lastIndex).coerceInPageRange()
        boundaryLoadingAnchorKey = null
        boundaryRequestDirection = 0
        boundaryLoadingTransactionId = 0L
        boundaryLoadingTargetChapterIndex = -1
        loadingMessage = null
        selectableIndex.clear()
        bindIdleSlots()
        invalidate()
        notifyPageChanged()
        return true
    }

    fun mergeAdjacentPages(newPages: List<EpubCorePage>): Boolean {
        if (newPages.isEmpty()) return false
        val currentKey = currentPage()?.pageKey()
        val merged = (pages + newPages)
            .distinctBy { it.pageKey() }
            .sortedWith(compareBy<EpubCorePage> { it.chapterIndex }.thenBy { it.pageIndex })
        if (merged.size == pages.size) return false
        pages = merged
        pageIndex = currentKey?.let { key -> pages.indexOfFirst { it.pageKey() == key } }
            ?.takeIf { it >= 0 }
            ?: pageIndex.coerceInPageRange()
        boundaryLoadingAnchorKey = null
        boundaryRequestDirection = 0
        boundaryLoadingTransactionId = 0L
        boundaryLoadingTargetChapterIndex = -1
        loadingMessage = null
        selectableIndex.clear()
        bindIdleSlots()
        invalidate()
        notifyPageChanged()
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        if (selectedText.isNotBlank()) {
            clearSelection()
            return
        }
        val listener = listener ?: return
        listener.onPageClick(x, y)
        val column = when {
            x < width / 3f -> 0
            x < width * 2f / 3f -> 1
            else -> 2
        }
        val row = when {
            y < height / 3f -> 0
            y < height * 2f / 3f -> 1
            else -> 2
        }
        val action = when (row * 3 + column) {
            0 -> AppConfig.clickActionTL
            1 -> AppConfig.clickActionTC
            2 -> AppConfig.clickActionTR
            3 -> AppConfig.clickActionML
            4 -> AppConfig.clickActionMC
            5 -> AppConfig.clickActionMR
            6 -> AppConfig.clickActionBL
            7 -> AppConfig.clickActionBC
            else -> AppConfig.clickActionBR
        }
        listener.onTapAction(action, x, y)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (isVerticalScrollMode() && selectedText.isNotBlank()) {
                    clearSelection()
                }
                primaryTouchActive = true
                selectionExistedOnDown = selectedText.isNotBlank()
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                longPressTriggered = false
                velocityTracker.clear()
                velocityTracker.addMovement(event)
                if (selectedText.isBlank() && !isVerticalScrollMode()) {
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker.addMovement(event)
                val deltaX = event.x - lastX
                val deltaY = event.y - lastY
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                if (selectedText.isNotBlank()) {
                    if (!moved && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                        moved = true
                        removeCallbacks(longPressRunnable)
                    }
                    if (longPressTriggered && !selectionExistedOnDown) {
                        selectEndMove(event.x, event.y)
                    }
                    lastX = event.x
                    lastY = event.y
                    return true
                }
                if (!moved && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                    moved = true
                    removeCallbacks(longPressRunnable)
                    if (gestureMode == GestureMode.None) {
                        val scrollMode = !isLandscape && ReadBook.pageAnim() == PageAnim.scrollPageAnim
                        gestureMode = when {
                            scrollMode -> GestureMode.VerticalScroll
                            abs(totalDx) > abs(totalDy) -> GestureMode.Horizontal
                            else -> GestureMode.BoundaryRequest
                        }
                    }
                    if (gestureMode == GestureMode.Horizontal) {
                        val direction = if (totalDx < 0f) 1 else -1
                        beginHorizontalTurn(direction)
                    }
                }
                if (!moved) return true
                when (gestureMode) {
                    GestureMode.Horizontal -> updateHorizontalTurn(deltaX, event.x, event.y)
                    GestureMode.VerticalScroll -> scrollByContent(deltaY)
                    else -> Unit
                }
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                dismissSelectionMagnifier()
                primaryTouchActive = false
                velocityTracker.addMovement(event)
                velocityTracker.computeCurrentVelocity(1000)
                val velocityX = velocityTracker.xVelocity
                val velocityY = velocityTracker.yVelocity
                if (selectedText.isNotBlank() && selectionExistedOnDown) {
                    clearSelection()
                } else if (selectedText.isNotBlank()) {
                    notifySelectionMenuIfReady()
                } else if (!moved) {
                    if (!maybeQuickHorizontalTurn(velocityX, velocityY, event.x)) {
                        handleTap(event.x, event.y)
                    }
                } else {
                    when (gestureMode) {
                        GestureMode.Horizontal -> {
                            if (shouldCompleteHorizontalTurn(velocityX)) {
                                completeHorizontalTurn()
                            } else {
                                cancelHorizontalTurn()
                            }
                        }
                        GestureMode.VerticalScroll -> {
                            if (abs(velocityY) > minFlingVelocity) {
                                startScrollFling(velocityY)
                            } else {
                                applyVerticalOffsets()
                                invalidate()
                            }
                        }
                        else -> Unit
                    }
                }
                gestureMode = GestureMode.None
                velocityTracker.clear()
                notifySelectionMenuIfReady()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                dismissSelectionMagnifier()
                primaryTouchActive = false
                selectionExistedOnDown = false
                selectionMenuPending = false
                velocityTracker.clear()
                if (horizontalDirection != 0) {
                    cancelHorizontalTurn()
                }
                gestureMode = GestureMode.None
                moved = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginHorizontalTurn(direction: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (horizontalDirection != 0 || horizontalAnimating) {
            abortAnimation(resetSlots = true, commitHorizontal = true)
        } else {
            abortScrollerOnly()
        }
        val rawTargetIndex = pageIndex + direction
        if (rawTargetIndex !in pages.indices) {
            requestPageBoundary(direction)
            return false
        }
        val targetIndex = rawTargetIndex
        bindIdleSlots()
        animationGeneration++
        horizontalDirection = direction
        horizontalTargetIndex = targetIndex
        horizontalPageAnim = ReadBook.pageAnim()
        horizontalSession = HorizontalSnapshotSession(
            fromIndex = pageIndex,
            toIndex = targetIndex,
            direction = direction,
            pageAnim = horizontalPageAnim,
            fromSnapshot = recordSnapshot(pageIndex),
            toSnapshot = recordSnapshot(targetIndex),
            toPageKey = pages.getOrNull(targetIndex)?.pageKey()
        )
        horizontalOffset = 0f
        horizontalAnimating = false
        horizontalCancelling = false
        verticalAnimating = false
        gestureMode = GestureMode.Horizontal
        if (horizontalPageAnim == PageAnim.simulationPageAnim) {
            simulationTurnRenderer.setViewSize(width, height)
            simulationTurnRenderer.start(direction, downX, downY)
            updateSimulationTouch(downX, downY)
        }
        resetSlotVisibility()
        applyHorizontalOffset()
        return true
    }

    private fun updateHorizontalTurn(deltaX: Float, touchX: Float, touchY: Float) {
        if (horizontalDirection == 0) return
        val viewWidth = width.toFloat().coerceAtLeast(1f)
        horizontalOffset = if (horizontalDirection > 0) {
            (horizontalOffset + deltaX).coerceIn(-viewWidth, 0f)
        } else {
            (horizontalOffset + deltaX).coerceIn(0f, viewWidth)
        }
        if (horizontalPageAnim == PageAnim.simulationPageAnim) {
            simulationTurnRenderer.updateTouch(touchX, touchY)
            updateSimulationTouch(touchX, touchY)
        }
        applyHorizontalOffset()
    }

    private fun shouldCompleteHorizontalTurn(velocityX: Float): Boolean {
        if (horizontalDirection == 0 || width <= 0) return false
        val offset = abs(horizontalOffset)
        val threshold = maxOf(touchSlop.toFloat(), width * 0.03f)
        if (offset <= touchSlop) return false
        if (offset >= threshold) return true
        val turnVelocity = if (horizontalDirection > 0) -velocityX else velocityX
        return turnVelocity > minFlingVelocity
    }

    private fun maybeQuickHorizontalTurn(velocityX: Float, velocityY: Float, upX: Float): Boolean {
        if (pages.isEmpty() || horizontalDirection != 0) return false
        if (abs(velocityX) <= minFlingVelocity * 0.65f) return false
        if (abs(velocityX) <= abs(velocityY) * 1.35f) return false
        if (abs(upX - downX) <= touchSlop * 0.25f) return false
        val direction = if (velocityX < 0f) 1 else -1
        if (!beginHorizontalTurn(direction)) return false
        completeHorizontalTurn()
        return true
    }

    private fun applyHorizontalOffset() {
        invalidate()
    }

    override fun computeScroll() {
        super.computeScroll()
        val session = horizontalSession
        if (session != null && horizontalAnimating) {
            val scroller = activeHorizontalScroller()
            if (scroller.computeScrollOffset()) {
                if (horizontalPageAnim == PageAnim.simulationPageAnim) {
                    val touchX = scroller.currX.toFloat()
                    val touchY = scroller.currY.toFloat()
                    simulationTurnRenderer.updateTouch(touchX, touchY)
                    updateSimulationTouch(touchX, touchY)
                } else {
                    horizontalOffset = scroller.currX.toFloat()
                }
                postInvalidateOnAnimation()
                return
            }
            if (horizontalCancelling) {
                finishHorizontalCancel()
            } else {
                finishHorizontalTurn()
            }
            return
        }
        if (verticalAnimating) {
            if (verticalScroller.computeScrollOffset()) {
                val currY = verticalScroller.currY.toFloat()
                scrollByContent(currY - lastScrollerY)
                lastScrollerY = currY
                postInvalidateOnAnimation()
            } else {
                verticalAnimating = false
                lastScrollerY = 0f
                bindIdleSlots()
                invalidate()
            }
        }
    }

    private fun completeHorizontalTurn() {
        if (horizontalDirection == 0 || horizontalTargetIndex !in pages.indices || horizontalSession == null) return
        commitHorizontalTurnPage()
        if (horizontalPageAnim == PageAnim.simulationPageAnim) {
            horizontalAnimating = true
            horizontalCancelling = false
            verticalAnimating = false
            if (!simulationTurnRenderer.startCompleteAnimation(horizontalScroller, horizontalAnimationSpeed())) {
                finishHorizontalTurn()
                return
            }
            postInvalidateOnAnimation()
            return
        }
        val targetOffset = if (horizontalDirection > 0) -width.toFloat() else width.toFloat()
        val distance = abs(targetOffset - horizontalOffset).roundToInt()
        if (distance == 0) {
            finishHorizontalTurn()
            return
        }
        val animationSpeed = horizontalAnimationSpeed()
        val duration = ((animationSpeed * distance) / width.coerceAtLeast(1))
            .coerceIn(96, animationSpeed)
        horizontalAnimating = true
        horizontalCancelling = false
        verticalAnimating = false
        activeHorizontalScroller().startScroll(
            horizontalOffset.roundToInt(),
            0,
            (targetOffset - horizontalOffset).roundToInt(),
            0,
            duration
        )
        postInvalidateOnAnimation()
    }

    private fun cancelHorizontalTurn() {
        if (horizontalDirection == 0) {
            clearHorizontalTurn()
            bindIdleSlots()
            return
        }
        if (horizontalPageAnim == PageAnim.simulationPageAnim) {
            val dx = (downX - simulationTouchX).roundToInt()
            val dy = (downY - simulationTouchY).roundToInt()
            if (dx == 0 && dy == 0) {
                finishHorizontalCancel()
                return
            }
            horizontalAnimating = true
            horizontalCancelling = true
            verticalAnimating = false
            horizontalScroller.startScroll(
                simulationTouchX.roundToInt(),
                simulationTouchY.roundToInt(),
                dx,
                dy,
                140
            )
            postInvalidateOnAnimation()
            return
        }
        val distance = abs(horizontalOffset).roundToInt()
        if (distance == 0) {
            finishHorizontalCancel()
            return
        }
        horizontalAnimating = true
        horizontalCancelling = true
        verticalAnimating = false
        activeHorizontalScroller().startScroll(
            horizontalOffset.roundToInt(),
            0,
            -horizontalOffset.roundToInt(),
            0,
            ((horizontalAnimationSpeed() * distance) / width.coerceAtLeast(1)).coerceIn(80, 180)
        )
        postInvalidateOnAnimation()
    }

    private fun finishHorizontalTurn() {
        horizontalAnimating = false
        horizontalCancelling = false
        commitHorizontalTurnPage()
        clearHorizontalTurn()
        bindIdleSlots()
    }

    private fun commitHorizontalTurnPage() {
        val session = horizontalSession ?: return
        if (session.pageCommitted) return
        val targetIndex = horizontalSession?.toPageKey
            ?.let { key -> pages.indexOfFirst { it.pageKey() == key } }
            ?.takeIf { it >= 0 }
            ?: horizontalSession?.toIndex
            ?: horizontalTargetIndex
        if (targetIndex in pages.indices) {
            pageIndex = targetIndex
            scrollOffsetY = 0f
            boundaryRequestDirection = 0
            notifyPageChanged()
        }
        session.pageCommitted = true
    }

    private fun finishHorizontalCancel() {
        horizontalAnimating = false
        horizontalCancelling = false
        boundaryRequestDirection = 0
        clearHorizontalTurn()
        bindIdleSlots()
        invalidate()
    }

    private fun clearHorizontalTurn() {
        horizontalSession?.recycle()
        horizontalDirection = 0
        horizontalTargetIndex = -1
        horizontalPageAnim = PageAnim.slidePageAnim
        horizontalSession = null
        horizontalOffset = 0f
        horizontalCancelling = false
        gestureMode = GestureMode.None
    }

    private fun horizontalAnimationSpeed(): Int {
        val baseSpeed = 332
        return when (horizontalPageAnim) {
            PageAnim.simulationPageAnim -> 300
            PageAnim.linkedCoverPageAnim -> (baseSpeed * 1.32f).roundToInt()
            else -> baseSpeed
        }
    }

    private fun activeHorizontalScroller(): Scroller {
        return if (horizontalPageAnim == PageAnim.linkedCoverPageAnim) {
            linkedHorizontalScroller
        } else {
            horizontalScroller
        }
    }

    private fun linkedDisplayProgress(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        if (!horizontalAnimating) return clamped
        val eased = 1f - (1f - clamped) * (1f - clamped) * (1f - clamped)
        return (0.2f * clamped + 0.8f * eased).coerceIn(0f, 1f)
    }

    private fun updateSimulationTouch(x: Float, y: Float) {
        if (horizontalPageAnim != PageAnim.simulationPageAnim) return
        var nextY = y
        if ((downY > height / 3f && downY < height * 2f / 3f) || horizontalDirection < 0) {
            nextY = height.toFloat()
        }
        if (downY > height / 3f && downY < height / 2f && horizontalDirection > 0) {
            nextY = 1f
        }
        simulationTouchX = x.takeIf { it.isFinite() } ?: 0.1f
        simulationTouchY = nextY.takeIf { it.isFinite() } ?: 0.1f
        if (abs(simulationTouchX) < 0.1f) simulationTouchX = 0.1f
        if (abs(simulationTouchY) < 0.1f) simulationTouchY = 0.1f
    }

    private fun drawSimulationTurn(canvas: Canvas, session: HorizontalSnapshotSession) {
        if (width <= 0 || height <= 0) return
        if (session.fromSnapshot == null || session.toSnapshot == null) {
            drawSlideTurn(canvas, session)
            return
        }
        simulationTurnRenderer.draw(
            canvas = canvas,
            currentBitmap = session.fromSnapshot.bitmapOrNull(),
            targetBitmap = session.toSnapshot.bitmapOrNull(),
            backgroundColor = renderer.backgroundColor
        )
    }

    private fun drawSlideTurn(canvas: Canvas, session: HorizontalSnapshotSession) {
        if (session.direction > 0) {
            drawSnapshot(canvas, session.toSnapshot, session.toIndex, horizontalOffset + width)
            drawSnapshot(canvas, session.fromSnapshot, session.fromIndex, horizontalOffset)
        } else {
            drawSnapshot(canvas, session.fromSnapshot, session.fromIndex, horizontalOffset)
            drawSnapshot(canvas, session.toSnapshot, session.toIndex, horizontalOffset - width)
        }
    }

    private fun drawCoverTurn(canvas: Canvas, session: HorizontalSnapshotSession) {
        if (session.direction > 0) {
            val edge = (horizontalOffset + width).coerceIn(0f, width.toFloat())
            drawSnapshotClipped(canvas, session.toSnapshot, session.toIndex, 0f, edge, width.toFloat())
            drawSnapshot(canvas, session.fromSnapshot, session.fromIndex, horizontalOffset)
            drawEdgeShadow(edge, canvas)
        } else {
            val prevLeft = horizontalOffset - width
            val edge = horizontalOffset.coerceIn(0f, width.toFloat())
            drawSnapshot(canvas, session.fromSnapshot, session.fromIndex, 0f)
            drawSnapshot(canvas, session.toSnapshot, session.toIndex, prevLeft)
            drawEdgeShadow(edge, canvas)
        }
    }

    private fun drawLinkedCoverTurn(canvas: Canvas, session: HorizontalSnapshotSession) {
        val viewWidth = width.toFloat().coerceAtLeast(1f)
        if (session.direction > 0) {
            val progress = (-horizontalOffset / viewWidth).coerceIn(0f, 1f)
            val displayProgress = linkedDisplayProgress(progress)
            val currentLeft = -viewWidth * displayProgress
            val currentRight = (width + currentLeft).coerceIn(0f, width.toFloat())
            val nextLeft = viewWidth * 0.2f * (1f - displayProgress)
            drawSnapshotClipped(canvas, session.toSnapshot, session.toIndex, nextLeft, currentRight, width.toFloat())
            drawNextMask(canvas, currentRight, width.toFloat(), displayProgress)
            drawSnapshot(canvas, session.fromSnapshot, session.fromIndex, currentLeft)
            drawEdgeShadow(currentRight, canvas)
        } else {
            val progress = (horizontalOffset / viewWidth).coerceIn(0f, 1f)
            val displayProgress = linkedDisplayProgress(progress)
            val prevLeft = -viewWidth + viewWidth * displayProgress
            val currentLeft = viewWidth * 0.2f * displayProgress
            drawSnapshot(canvas, session.fromSnapshot, session.fromIndex, currentLeft)
            drawCurrentMask(
                canvas = canvas,
                left = currentLeft,
                right = currentLeft + width,
                progress = displayProgress
            )
            drawSnapshot(canvas, session.toSnapshot, session.toIndex, prevLeft)
            drawEdgeShadow((prevLeft + width).coerceIn(0f, width.toFloat()), canvas)
        }
    }

    private fun drawSnapshot(
        canvas: Canvas,
        snapshot: EpubPageBitmapSnapshot?,
        index: Int,
        left: Float
    ) {
        val saveCount = canvas.save()
        canvas.translate(left, 0f)
        drawSnapshotContent(canvas, snapshot, index)
        canvas.restoreToCount(saveCount)
    }

    private fun drawSnapshotClipped(
        canvas: Canvas,
        snapshot: EpubPageBitmapSnapshot?,
        index: Int,
        left: Float,
        clipLeft: Float,
        clipRight: Float
    ) {
        val realLeft = clipLeft.coerceIn(0f, width.toFloat())
        val realRight = clipRight.coerceIn(0f, width.toFloat())
        if (realRight <= realLeft) return
        val saveCount = canvas.save()
        canvas.clipRect(realLeft, 0f, realRight, height.toFloat())
        canvas.translate(left, 0f)
        drawSnapshotContent(canvas, snapshot, index)
        canvas.restoreToCount(saveCount)
    }

    private fun drawSnapshotContent(canvas: Canvas, snapshot: EpubPageBitmapSnapshot?, index: Int) {
        if (snapshot != null && snapshot.matches(index, width, height, renderer.renderStateVersion)) {
            snapshot.draw(canvas)
            return
        }
        renderer.drawPage(
            canvas = canvas,
            page = pages.getOrNull(index),
            pageIndex = index,
            pageCount = pages.size,
            viewport = RectF(0f, 0f, width.toFloat(), height.toFloat())
        )
    }

    private fun drawNextMask(canvas: Canvas, left: Float, right: Float, progress: Float) {
        val alpha = (102 * (1f - progress)).toInt().coerceIn(0, 102)
        if (alpha <= 0 || right <= left) return
        maskPaint.color = alpha shl 24
        canvas.drawRect(left, 0f, right, height.toFloat(), maskPaint)
    }

    private fun drawCurrentMask(canvas: Canvas, left: Float, right: Float, progress: Float) {
        val alpha = (76 * progress).toInt().coerceIn(0, 76)
        if (alpha <= 0 || right <= left) return
        maskPaint.color = alpha shl 24
        canvas.drawRect(left, 0f, right, height.toFloat(), maskPaint)
    }

    private fun drawEdgeShadow(left: Float, canvas: Canvas) {
        if (left <= 0f || left >= width) return
        edgeShadowDrawable.setBounds(0, 0, 36, height)
        val saveCount = canvas.save()
        canvas.translate(left, 0f)
        edgeShadowDrawable.draw(canvas)
        canvas.restoreToCount(saveCount)
    }


    private fun scrollByContent(deltaY: Float) {
        if (pages.isEmpty() || height <= 0) return
        showVerticalSlots()
        scrollOffsetY += deltaY
        normalizeScrollPosition()
        applyVerticalOffsets()
    }

    private fun normalizeScrollPosition() {
        var changed = false
        while (scrollOffsetY <= -pageHeight(pageIndex) && pageIndex < pages.lastIndex) {
            scrollOffsetY += pageHeight(pageIndex)
            pageIndex += 1
            changed = true
        }
        while (scrollOffsetY > 0f && pageIndex > 0) {
            pageIndex -= 1
            scrollOffsetY -= pageHeight(pageIndex)
            changed = true
        }
        if (pageIndex == 0 && scrollOffsetY > 0f) {
            scrollOffsetY = 0f
            if (verticalAnimating) verticalScroller.abortAnimation()
        }
        if (pageIndex == pages.lastIndex && scrollOffsetY < 0f) {
            scrollOffsetY = 0f
            if (verticalAnimating) verticalScroller.abortAnimation()
        }
        if (changed) {
            boundaryRequestDirection = 0
            bindScrollSlots()
            notifyPageChanged()
        }
    }

    private fun startScrollFling(velocityY: Float) {
        if (abs(velocityY) < minFlingVelocity) {
            verticalAnimating = false
            return
        }
        verticalAnimating = true
        horizontalAnimating = false
        lastScrollerY = 0f
        verticalScroller.fling(
            0,
            0,
            0,
            velocityY.roundToInt(),
            0,
            0,
            -height.coerceAtLeast(1),
            height.coerceAtLeast(1)
        )
        postInvalidateOnAnimation()
    }


    private fun commitPageIndex(index: Int, resetScroll: Boolean) {
        abortAnimation(resetSlots = false)
        pageIndex = index.coerceInPageRange()
        if (resetScroll) {
            scrollOffsetY = 0f
        }
        clearSelection()
        bindIdleSlots()
        notifyPageChanged()
    }

    private fun bindIdleSlots() {
        bindSlotData()
        currentSlot.visibility = if (pages.isEmpty()) View.INVISIBLE else View.VISIBLE
        currentSlot.translationX = 0f
        currentSlot.translationY = scrollOffsetY.takeIf {
            ReadBook.pageAnim() == PageAnim.scrollPageAnim
        } ?: 0f
        if (ReadBook.pageAnim() == PageAnim.scrollPageAnim && scrollOffsetY != 0f) {
            showVerticalSlots()
            applyVerticalOffsets()
        } else {
            resetSlotVisibility()
        }
    }

    private fun bindSlotData() {
        currentSlot.bind(pages.getOrNull(pageIndex), pageIndex, pages.size, visible = true)
        prevSlot.bind(pages.getOrNull(pageIndex - 1), pageIndex - 1, pages.size, visible = false)
        nextSlot.bind(pages.getOrNull(pageIndex + 1), pageIndex + 1, pages.size, visible = false)
        nextPlusSlot.bind(pages.getOrNull(pageIndex + 2), pageIndex + 2, pages.size, visible = false)
    }

    private fun bindScrollSlots() {
        currentSlot.bind(pages.getOrNull(pageIndex), pageIndex, pages.size, visible = true)
        prevSlot.bind(pages.getOrNull(pageIndex - 1), pageIndex - 1, pages.size, visible = pageIndex > 0)
        nextSlot.bind(pages.getOrNull(pageIndex + 1), pageIndex + 1, pages.size, visible = pageIndex < pages.lastIndex)
        nextPlusSlot.bind(
            pages.getOrNull(pageIndex + 2),
            pageIndex + 2,
            pages.size,
            visible = pageIndex + 2 <= pages.lastIndex
        )
    }

    private fun showVerticalSlots() {
        bindScrollSlots()
        applyVerticalOffsets()
    }

    private fun applyVerticalOffsets() {
        currentSlot.translationX = 0f
        currentSlot.translationY = scrollOffsetY
        prevSlot.translationX = 0f
        prevSlot.translationY = scrollOffsetY - pageHeight(pageIndex - 1)
        nextSlot.translationX = 0f
        nextSlot.translationY = scrollOffsetY + pageHeight(pageIndex)
        nextPlusSlot.translationX = 0f
        nextPlusSlot.translationY = scrollOffsetY + pageHeight(pageIndex) + pageHeight(pageIndex + 1)
    }

    private fun pageHeight(index: Int): Float {
        if (index !in pages.indices) return height.toFloat().coerceAtLeast(1f)
        return height.toFloat().coerceAtLeast(1f)
    }

    private fun requestPageBoundary(direction: Int) {
        if (direction == 0) return
        val boundaryLoadingActive = boundaryLoadingAnchorKey != null ||
            pages.any { it.chapterHref.startsWith(BoundaryLoadingPrefix) }
        if (boundaryLoadingActive && boundaryRequestDirection == direction) return
        listener?.onPageBoundary(direction)
    }

    private fun resetSlotVisibility() {
        prevSlot.visibility = View.INVISIBLE
        nextSlot.visibility = View.INVISIBLE
        nextPlusSlot.visibility = View.INVISIBLE
        currentSlot.visibility = if (pages.isEmpty()) View.INVISIBLE else View.VISIBLE
        prevSlot.translationX = 0f
        prevSlot.translationY = 0f
        nextSlot.translationX = 0f
        nextSlot.translationY = 0f
        nextPlusSlot.translationX = 0f
        nextPlusSlot.translationY = 0f
        currentSlot.translationX = 0f
        currentSlot.translationY = scrollOffsetY.takeIf {
            ReadBook.pageAnim() == PageAnim.scrollPageAnim
        } ?: 0f
    }

    private fun invalidateSlotDisplayLists() {
        prevSlot.invalidateDisplayList()
        currentSlot.invalidateDisplayList()
        nextSlot.invalidateDisplayList()
        nextPlusSlot.invalidateDisplayList()
    }

    fun invalidateRendererStyle() {
        invalidateSlotDisplayLists()
        invalidate()
    }

    private fun abortScrollerOnly() {
        if (!horizontalScroller.isFinished) {
            horizontalScroller.abortAnimation()
        }
        if (!linkedHorizontalScroller.isFinished) {
            linkedHorizontalScroller.abortAnimation()
        }
        if (!verticalScroller.isFinished) {
            verticalScroller.abortAnimation()
        }
    }

    private fun abortAnimation(resetSlots: Boolean = true) {
        abortAnimation(resetSlots = resetSlots, commitHorizontal = false)
    }

    private fun abortAnimation(resetSlots: Boolean = true, commitHorizontal: Boolean) {
        val interruptedSession = horizontalSession
        val interruptedTargetIndex = interruptedSession?.toIndex
        val shouldCommitHorizontal = commitHorizontal &&
            (horizontalAnimating || interruptedSession != null) &&
            interruptedSession?.pageCommitted != true &&
            interruptedTargetIndex != null &&
            interruptedTargetIndex in pages.indices
        abortScrollerOnly()
        horizontalAnimating = false
        horizontalCancelling = false
        verticalAnimating = false
        if (shouldCommitHorizontal) {
            pageIndex = interruptedTargetIndex
            scrollOffsetY = 0f
            notifyPageChanged()
        }
        clearHorizontalTurn()
        if (resetSlots || shouldCommitHorizontal) {
            bindIdleSlots()
        }
    }

    private fun Int.coerceInPageRange(): Int {
        if (pages.isEmpty()) return 0
        return coerceIn(0, pages.lastIndex)
    }

    private fun notifyPageChanged() {
        listener?.onPageChanged(pageIndex, pages.size)
    }

    private fun ensureSelectablePage(page: EpubCorePage): EpubSelectablePage {
        val key = "${page.pageKey()}:${renderer.renderStateVersion}"
        return selectableIndex.getOrPut(key) {
            EpubPageSelectorBuilder.build(page)
        }
    }

    private fun updateSelection(page: EpubSelectablePage) {
        val startBlock = selectionStartBlock ?: return clearSelection()
        val endBlock = selectionEndBlock ?: return clearSelection()
        val contentOffsetX = contentOffsetX()
        val contentOffsetY = contentOffsetY()
        val geometry = EpubPageSelectorBuilder.selectionGeometry(
            page = page,
            startBlock = startBlock,
            startOffset = selectionStartOffset,
            endBlock = endBlock,
            endOffset = selectionEndOffset,
            offsetX = contentOffsetX,
            offsetY = contentOffsetY,
            leftLimit = contentOffsetX,
            rightLimit = contentRightX().coerceAtLeast(contentOffsetX + 1f)
        )
        if (geometry.rects.isEmpty() || geometry.selectedText.isBlank()) {
            selectedText = ""
            selectionAnchor = null
            selectionHighlight = null
            invalidate()
            return
        }
        selectedText = geometry.selectedText
        selectionAnchor = SelectionAnchor(
            startX = geometry.anchorStartX,
            topY = geometry.anchorTopY,
            endX = geometry.anchorEndX,
            bottomY = geometry.anchorBottomY,
            startBottomY = geometry.anchorStartBottomY,
            endBottomY = geometry.anchorEndBottomY
        )
        selectionHighlight = SelectionHighlight(geometry.rects, geometry.paths, selectionAnchor!!)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val session = horizontalSession
        if (session != null && horizontalDirection != 0) {
            when (horizontalPageAnim) {
                PageAnim.coverPageAnim -> drawCoverTurn(canvas, session)
                PageAnim.linkedCoverPageAnim -> drawLinkedCoverTurn(canvas, session)
                PageAnim.simulationPageAnim -> drawSimulationTurn(canvas, session)
                else -> drawSlideTurn(canvas, session)
            }
        } else {
            super.dispatchDraw(canvas)
        }
        drawSelectionOverlay(canvas)
        drawLoadingOverlay(canvas)
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        val highlight = selectionHighlight ?: return
        highlight.rects.forEach { rect ->
            canvas.drawRect(rect, selectionPaint)
        }
    }

    private fun hitTestSelection(
        page: EpubSelectablePage,
        x: Float,
        y: Float,
        strict: Boolean,
        maxDistance: Float? = null
    ): EpubTextHit? {
        return EpubPageSelectorBuilder.hitTest(
            page = page,
            x = x - contentOffsetX(),
            y = y - contentOffsetY(),
            strict = strict,
            maxDistance = maxDistance
        )
    }

    private fun isVerticalScrollMode(): Boolean {
        return !isLandscape && ReadBook.pageAnim() == PageAnim.scrollPageAnim
    }

    private fun selectionHandleHitDistance(): Float {
        return maxOf(touchSlop * 8f, 48f)
    }

    private fun showSelectionMagnifier(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isAttachedToWindow) return
        val safeX = x.coerceIn(0f, width.toFloat())
        val safeY = y.coerceIn(0f, height.toFloat())
        (selectionMagnifier ?: Magnifier(this).also { selectionMagnifier = it }).show(safeX, safeY)
    }

    private fun dismissSelectionMagnifier() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        selectionMagnifier?.dismiss()
    }

    private fun isPointInsideWebFragment(page: EpubCorePage, x: Float, y: Float): Boolean {
        val localX = x - contentOffsetX()
        val localY = y - contentOffsetY()
        return page.paintFragments.any { it.containsWebFragmentAt(localX, localY, 0f, 0f) }
    }

    private fun EpubPageFragment.containsWebFragmentAt(x: Float, y: Float, offsetX: Float, offsetY: Float): Boolean {
        val rect = RectF(frame).apply { offset(offsetX, offsetY) }
        return when (this) {
            is EpubWebFragment -> rect.contains(x, y)
            is EpubContainerFragment -> children.any { it.containsWebFragmentAt(x, y, rect.left, rect.top) }
            is EpubTableFragment -> children.any { it.containsWebFragmentAt(x, y, rect.left, rect.top) }
            is EpubFlexFragment -> children.any { it.containsWebFragmentAt(x, y, rect.left, rect.top) }
            else -> false
        }
    }

    private fun screenToLocal(rawX: Float, rawY: Float): PointF {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return PointF(rawX - location[0], rawY - location[1])
    }

    private fun contentOffsetX(): Float = (layoutConfig?.paddingLeftPx ?: 0).toFloat()

    private fun contentOffsetY(): Float = (layoutConfig?.paddingTopPx ?: 0).toFloat()

    private fun contentRightX(): Float = (width - (layoutConfig?.paddingRightPx ?: 0)).toFloat()

    private fun EpubTextHit.expandedStartOffset(): Int {
        return (textOffset - 1).coerceAtLeast(line.textStart)
    }

    private fun EpubTextHit.expandedEndOffset(): Int {
        return (textOffset + 1).coerceAtMost(line.textEnd)
    }

    private fun drawLoadingOverlay(canvas: Canvas) {
        val message = loadingMessage ?: return
        loadingTextPaint.color = renderer.textColor
        loadingTextPaint.textSize = renderer.textPaint.textSize
        if (currentPage() == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), loadingOverlayPaint)
            val centerY = height / 2f - (loadingTextPaint.descent() + loadingTextPaint.ascent()) / 2f
            canvas.drawText(message, width / 2f, centerY, loadingTextPaint)
            return
        }
        val textSize = (renderer.textPaint.textSize * 0.75f).coerceAtLeast(24f)
        loadingTextPaint.textSize = textSize
        loadingTextPaint.color = 0xFFFFFFFF.toInt()
        val horizontalPadding = textSize
        val verticalPadding = textSize * 0.45f
        val textWidth = loadingTextPaint.measureText(message)
        val left = (width - textWidth) / 2f - horizontalPadding
        val top = height - (layoutConfig?.paddingBottomPx ?: 0) - textSize * 3f
        val right = left + textWidth + horizontalPadding * 2f
        val bottom = top + textSize + verticalPadding * 2f
        if (right > left && bottom > top) {
            canvas.drawRoundRect(left, top, right, bottom, textSize * 0.5f, textSize * 0.5f, loadingBadgePaint)
            val baseline = top + verticalPadding + textSize - loadingTextPaint.descent()
            canvas.drawText(message, width / 2f, baseline, loadingTextPaint)
        }
    }

    private fun recordSnapshot(index: Int): EpubPageBitmapSnapshot? {
        if (index !in pages.indices || width <= 0 || height <= 0) return null
        val bitmap = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val canvas = Canvas(bitmap)
        renderer.drawPage(
            canvas = canvas,
            page = pages.getOrNull(index),
            pageIndex = index,
            pageCount = pages.size,
            viewport = RectF(0f, 0f, width.toFloat(), height.toFloat())
        )
        return EpubPageBitmapSnapshot(
            pageIndex = index,
            width = width,
            height = height,
            rendererVersion = renderer.renderStateVersion,
            bitmap = bitmap
        )
    }

    private class EpubPageBitmapSnapshot(
        val pageIndex: Int,
        private val width: Int,
        private val height: Int,
        private val rendererVersion: Long,
        private val bitmap: Bitmap
    ) {

        fun matches(pageIndex: Int, width: Int, height: Int, rendererVersion: Long): Boolean {
            return this.pageIndex == pageIndex &&
                this.width == width &&
                this.height == height &&
                this.rendererVersion == rendererVersion &&
                !bitmap.isRecycled
        }

        fun draw(canvas: Canvas) {
            if (!bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        }

        fun bitmapOrNull(): Bitmap? {
            return bitmap.takeUnless { it.isRecycled }
        }

        fun recycle() {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private class EpubPageSlotView(
        context: Context,
        private val renderer: EpubPageRenderer
    ) : View(context) {

        private val viewport = RectF()
        private var page: EpubCorePage? = null
        private var pageIndex: Int = 0
        private var pageCount: Int = 0
        private var displayList: EpubPageDisplayList? = null

        init {
            isClickable = false
            isFocusable = false
        }

        fun bind(page: EpubCorePage?, pageIndex: Int, pageCount: Int, visible: Boolean) {
            val changed = this.page !== page ||
                this.pageIndex != pageIndex ||
                this.pageCount != pageCount
            this.page = page
            this.pageIndex = pageIndex
            this.pageCount = pageCount
            visibility = if (visible && page != null) View.VISIBLE else View.INVISIBLE
            if (changed) {
                displayList = null
            }
            invalidate()
        }

        fun invalidateDisplayList() {
            displayList = null
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w != oldw || h != oldh) {
                displayList = null
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val page = page ?: return
            viewport.set(0f, 0f, width.toFloat(), height.toFloat())
            val cached = displayList?.takeIf {
                it.matches(pageIndex, width, height, renderer.renderStateVersion)
            }
            if (cached != null) {
                cached.draw(canvas)
                return
            }
            val recorded = EpubPageDisplayList.record(
                renderer = renderer,
                page = page,
                pageIndex = pageIndex,
                pageCount = pageCount,
                viewport = viewport
            )
            displayList = recorded
            if (recorded != null) {
                recorded.draw(canvas)
            } else {
                renderer.drawPage(
                    canvas = canvas,
                    page = page,
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    viewport = viewport
                )
            }
        }
    }
}
