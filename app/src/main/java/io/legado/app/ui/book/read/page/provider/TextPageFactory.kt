package io.legado.app.ui.book.read.page.provider

import io.legado.app.R
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.api.DataSource
import io.legado.app.ui.book.read.page.api.PageFactory
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextPage
import splitties.init.appCtx

class TextPageFactory(dataSource: DataSource) : PageFactory<TextPage>(dataSource) {

    private val keepSwipeTip = appCtx.getString(R.string.keep_swipe_tip)
    private val useDoublePageSpread: Boolean
        get() = ChapterProvider.doublePage && !dataSource.isScroll
    private val pageStep: Int
        get() = if (useDoublePageSpread) 2 else 1

    override fun hasPrev(): Boolean = with(dataSource) {
        return hasPrevChapter() || pageIndex > 0
    }

    override fun hasNext(): Boolean = with(dataSource) {
        if (!useDoublePageSpread) {
            return hasNextChapter() || (currentChapter != null && currentChapter?.isLastIndex(pageIndex) != true)
        }
        return spreadPositionAt(1) != null || hasNextChapter()
    }

    override fun hasNextPlus(): Boolean = with(dataSource) {
        if (!useDoublePageSpread) {
            return hasNextChapter() || pageIndex < (currentChapter?.pageSize ?: 1) - 2
        }
        return spreadPositionAt(2) != null || hasNextChapter()
    }

    override fun moveToFirst() {
        ReadBook.setPageIndex(0)
    }

    override fun moveToLast() = with(dataSource) {
        currentChapter?.let {
            if (it.pageSize == 0) {
                ReadBook.setPageIndex(0)
            } else {
                ReadBook.setPageIndex(if (useDoublePageSpread) spreadStartIndex(it.lastIndex) else it.lastIndex)
            }
        } ?: ReadBook.setPageIndex(0)
    }

    override fun moveToNext(upContent: Boolean): Boolean = with(dataSource) {
        if (useDoublePageSpread) {
            return@with moveBySpread(1, upContent)
        }
        return if (hasNext()) {
            val pageIndex = pageIndex
            if (currentChapter == null || currentChapter?.isLastIndex(pageIndex) == true) {
                if ((currentChapter == null || isScroll) && nextChapter == null) {
                    return@with false
                }
                ReadBook.moveToNextChapter(upContent, false)
            } else {
                if (pageIndex < 0 || currentChapter?.isLastIndexCurrent(pageIndex) == true) {
                    return@with false
                }
                ReadBook.setPageIndex(pageIndex.plus(1))
            }
            if (upContent) upContent(resetPageOffset = false)
            true
        } else {
            false
        }
    }

    override fun moveToPrev(upContent: Boolean): Boolean = with(dataSource) {
        if (useDoublePageSpread) {
            return@with moveBySpread(-1, upContent)
        }
        return if (hasPrev()) {
            if (pageIndex <= 0) {
                if (currentChapter == null && prevChapter == null) {
                    return@with false
                }
                if (prevChapter != null && prevChapter?.isCompleted == false) {
                    return@with false
                }
                ReadBook.moveToPrevChapter(upContent, upContentInPlace = false)
            } else {
                if (currentChapter == null) {
                    return@with false
                }
                ReadBook.setPageIndex(pageIndex.minus(1))
            }
            if (upContent) upContent(resetPageOffset = false)
            true
        } else {
            false
        }
    }

    override val curPage: TextPage
        get() = if (useDoublePageSpread) {
            spreadPageAt(0, removeAloudSpan = false) ?: messageOrEmptyPage()
        } else {
            pageAtOffset(0, removeAloudSpan = false) ?: messageOrEmptyPage()
        }

    override val nextPage: TextPage
        get() = if (useDoublePageSpread) {
            spreadPageAt(1) ?: nextFallbackPage()
        } else {
            pageAtOffset(pageStep) ?: nextFallbackPage()
        }

    override val prevPage: TextPage
        get() = if (useDoublePageSpread) {
            spreadPageAt(-1) ?: prevFallbackPage()
        } else {
            pageAtOffset(-pageStep) ?: prevFallbackPage()
        }

    override val nextPlusPage: TextPage
        get() = if (useDoublePageSpread) {
            spreadPageAt(2) ?: TextPage().format()
        } else {
            pageAtOffset(pageStep * 2) ?: TextPage().format()
        }

    val curPairPage: TextPage?
        get() = if (useDoublePageSpread) spreadPageAt(0, pairOffset = 1) else null

    val nextPairPage: TextPage?
        get() = if (useDoublePageSpread) spreadPageAt(1, pairOffset = 1) else null

    val prevPairPage: TextPage?
        get() = if (useDoublePageSpread) spreadPageAt(-1, pairOffset = 1) else null

    private fun moveBySpread(spreadOffset: Int, upContent: Boolean): Boolean = with(dataSource) {
        val chapter = currentChapter
        if (chapter == null) {
            return@with if (spreadOffset > 0) {
                ReadBook.moveToNextChapter(upContent, upContentInPlace = false)
            } else {
                ReadBook.moveToPrevChapter(upContent, upContentInPlace = false)
            }
        }
        val currentStart = spreadStartIndex(pageIndex)
        val targetStart = currentStart + spreadOffset * 2
        if (targetStart in 0 until chapter.pageSize) {
            ReadBook.setPageIndex(targetStart)
            if (upContent) upContent(resetPageOffset = false)
            return@with true
        }
        if (targetStart >= chapter.pageSize) {
            if (!hasNextChapter()) return@with false
            val next = nextChapter
            if (next == null) {
                return@with ReadBook.moveToNextChapter(upContent, upContentInPlace = false)
            }
            val virtualSize = evenPageSize(chapter)
            val nextStart = spreadStartIndex((targetStart - virtualSize).coerceAtLeast(0))
                .coerceAtMost(next.lastIndex.coerceAtLeast(0))
            if (!ReadBook.moveToNextChapter(false, upContentInPlace = false)) return@with false
            if (nextStart > 0) {
                ReadBook.setPageIndex(nextStart)
            }
            if (upContent) upContent(resetPageOffset = false)
            return@with true
        }
        if (!hasPrevChapter()) return@with false
        val prev = prevChapter
        if (prev == null || !prev.isCompleted) {
            return@with ReadBook.moveToPrevChapter(upContent, upContentInPlace = false)
        }
        val prevStart = previousChapterSpreadStart(prev, targetStart)
        if (!ReadBook.moveToPrevChapter(false, toLast = false, upContentInPlace = false)) return@with false
        ReadBook.setPageIndex(prevStart)
        if (upContent) upContent(resetPageOffset = false)
        true
    }

    private fun spreadPageAt(
        spreadOffset: Int,
        pairOffset: Int = 0,
        removeAloudSpan: Boolean = true
    ): TextPage? = with(dataSource) {
        ReadBook.msg?.let {
            return@with if (spreadOffset == 0 && pairOffset == 0) TextPage(text = it).format() else null
        }
        val position = spreadPositionAt(spreadOffset) ?: return@with null
        val targetIndex = position.index + pairOffset
        if (targetIndex !in 0 until position.chapter.pageSize) return@with null
        return@with position.chapter.pageOrPlaceholder(targetIndex, removeAloudSpan)
    }

    private fun spreadPositionAt(spreadOffset: Int): SpreadPosition? = with(dataSource) {
        val chapter = currentChapter ?: return@with null
        val currentStart = spreadStartIndex(pageIndex)
        val targetStart = currentStart + spreadOffset * 2
        return@with when {
            targetStart in 0 until chapter.pageSize -> SpreadPosition(chapter, targetStart)
            targetStart >= chapter.pageSize -> {
                val next = nextChapter ?: return@with null
                val nextStart = spreadStartIndex((targetStart - evenPageSize(chapter)).coerceAtLeast(0))
                if (nextStart in 0 until next.pageSize) SpreadPosition(next, nextStart) else null
            }
            else -> {
                val prev = prevChapter ?: return@with null
                if (!prev.isCompleted) return@with null
                val prevStart = previousChapterSpreadStart(prev, targetStart)
                if (prevStart in 0 until prev.pageSize) SpreadPosition(prev, prevStart) else null
            }
        }
    }

    private fun pageAtOffset(offset: Int, removeAloudSpan: Boolean = true): TextPage? = with(dataSource) {
        ReadBook.msg?.let {
            return@with TextPage(text = it).format()
        }
        val chapter = currentChapter ?: return@with null
        val targetIndex = pageIndex + offset
        return@with when {
            targetIndex in 0 until chapter.pageSize -> chapter.pageOrPlaceholder(targetIndex, removeAloudSpan)
            targetIndex >= chapter.pageSize -> nextChapter?.let { next ->
                val nextIndex = targetIndex - chapter.pageSize
                if (nextIndex in 0 until next.pageSize) {
                    next.pageOrPlaceholder(nextIndex, true)
                } else {
                    null
                }
            }
            else -> prevChapter?.let { prev ->
                val prevIndex = prev.pageSize + targetIndex
                if (prevIndex in 0 until prev.pageSize) {
                    prev.pageOrPlaceholder(prevIndex, true)
                } else {
                    null
                }
            }
        }
    }

    private fun TextChapter.pageOrPlaceholder(index: Int, removeAloudSpan: Boolean): TextPage {
        val page = getPage(index)
        if (page != null) {
            return if (removeAloudSpan) page.removePageAloudSpan() else page
        }
        return TextPage(title = title).apply { textChapter = this@pageOrPlaceholder }.format()
    }

    private fun spreadStartIndex(index: Int): Int {
        val safeIndex = index.coerceAtLeast(0)
        return safeIndex - safeIndex % 2
    }

    private fun evenPageSize(chapter: TextChapter): Int {
        return chapter.pageSize + chapter.pageSize % 2
    }

    private fun previousChapterSpreadStart(prev: TextChapter, targetStart: Int): Int {
        val stepsIntoPrev = (-targetStart / 2).coerceAtLeast(1)
        return (spreadStartIndex(prev.lastIndex) - (stepsIntoPrev - 1) * 2).coerceAtLeast(0)
    }

    private data class SpreadPosition(
        val chapter: TextChapter,
        val index: Int
    )

    private fun messageOrEmptyPage(): TextPage = with(dataSource) {
        ReadBook.msg?.let {
            return@with TextPage(text = it).format()
        }
        currentChapter?.let {
            return@with TextPage(title = it.title).apply { textChapter = it }.format()
        }
        TextPage().format()
    }

    private fun nextFallbackPage(): TextPage = with(dataSource) {
        ReadBook.msg?.let {
            return@with TextPage(text = it).format()
        }
        currentChapter?.let {
            if (!it.isCompleted) {
                return@with TextPage(title = it.title).format()
            }
        }
        nextChapter?.let {
            return@with it.getPage(0)?.removePageAloudSpan()
                ?: TextPage(title = it.title).format()
        }
        TextPage().format()
    }

    private fun prevFallbackPage(): TextPage = with(dataSource) {
        ReadBook.msg?.let {
            return@with TextPage(text = it).format()
        }
        currentChapter?.let {
            if (!it.isCompleted) {
                return@with TextPage(title = it.title).format()
            }
        }
        prevChapter?.let {
            return@with it.getPage(spreadStartIndex(it.lastIndex))?.removePageAloudSpan()
                ?: TextPage(title = it.title).format()
        }
        TextPage().format()
    }
}