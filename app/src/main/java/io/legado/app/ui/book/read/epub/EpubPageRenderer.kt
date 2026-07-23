package io.legado.app.ui.book.read.epub

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import io.legado.app.model.localBook.epubcore.font.EpubTypefaceResolver
import io.legado.app.model.localBook.epubcore.image.EpubImageResolver
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.style.EpubBackground
import io.legado.app.model.localBook.epubcore.style.EpubBackgroundPosition
import io.legado.app.model.localBook.epubcore.style.EpubBackgroundRepeat
import io.legado.app.model.localBook.epubcore.style.EpubBackgroundSize
import io.legado.app.model.localBook.epubcore.style.EpubBorderRadius
import io.legado.app.model.localBook.epubcore.style.EpubSizeValue
import io.legado.app.model.localBook.epubcore.layout.EpubContainerFragment
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.layout.EpubFlexFragment
import io.legado.app.model.localBook.epubcore.layout.EpubImageFragment
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredBoxFragment
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextFragment
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextKind
import io.legado.app.model.localBook.epubcore.layout.EpubPageFragment
import io.legado.app.model.localBook.epubcore.layout.EpubTableFragment
import io.legado.app.model.localBook.epubcore.layout.EpubTextFragment
import io.legado.app.model.localBook.epubcore.layout.EpubWebFragment

class EpubPageRenderer {

    var imageResolver: EpubImageResolver? = null
        set(value) {
            if (field !== value) {
                field = value
                markRenderStateChanged()
            }
        }
    var typefaceResolver: EpubTypefaceResolver? = null
        set(value) {
            if (field !== value) {
                field = value
                markRenderStateChanged()
            }
        }
    var backgroundColor: Int = Color.rgb(250, 248, 241)
        set(value) {
            if (field != value) {
                field = value
                markRenderStateChanged()
            }
        }
    var textColor: Int = Color.rgb(62, 61, 59)
        set(value) {
            if (field != value) {
                field = value
                markRenderStateChanged()
            }
        }
    var pageNumberColor: Int = Color.rgb(120, 120, 120)
        set(value) {
            if (field != value) {
                field = value
                markRenderStateChanged()
            }
        }
    var textPaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 40f
    }
        set(value) {
            field = value
            markRenderStateChanged()
        }
    var lineSpacingExtra: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                markRenderStateChanged()
            }
        }
    var lineSpacingMultiplier: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                markRenderStateChanged()
            }
        }
    var layoutConfig: EpubCoreLayoutConfig? = null
        set(value) {
            if (field != value) {
                field = value
                markRenderStateChanged()
            }
        }
    var renderStateVersion: Long = 0L
        private set

    private val pageNumberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val contentBounds = RectF()
    private val scratchRect = RectF()
    private val textClipRect = RectF()
    private val imageDest = RectF()
    private val pageBackgroundRect = RectF()

    private fun markRenderStateChanged() {
        renderStateVersion++
    }

    fun drawPage(
        canvas: Canvas,
        page: EpubCorePage?,
        pageIndex: Int,
        pageCount: Int,
        viewport: RectF = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
    ) {
        canvas.drawColor(backgroundColor)
        contentBounds.set(resolveContentBounds(viewport))
        page?.paintFragments?.forEach { fragment ->
            if (fragment is EpubMeasuredBoxFragment && fragment.isPageBackground) {
                drawMeasuredBoxFragment(canvas, fragment, 0f, 0f, viewport)
            }
        }
        val saveCount = canvas.save()
        canvas.clipRect(contentBounds)
        page?.paintFragments?.forEach { fragment ->
            if (fragment is EpubMeasuredBoxFragment && fragment.isPageBackground) return@forEach
            drawFragment(canvas, fragment, contentBounds.left, contentBounds.top)
        }
        canvas.restoreToCount(saveCount)
        // EPUB pages already expose progress through the native read menu.
    }

    private fun drawFragment(canvas: Canvas, fragment: EpubPageFragment, offsetX: Float, offsetY: Float) {
        when (fragment) {
            is EpubTextFragment -> drawTextFragment(canvas, fragment, offsetX, offsetY)
            is EpubMeasuredTextFragment -> drawMeasuredTextFragment(canvas, fragment, offsetX, offsetY)
            is EpubImageFragment -> drawImageFragment(canvas, fragment, offsetX, offsetY)
            is EpubContainerFragment -> drawContainerFragment(canvas, fragment, offsetX, offsetY)
            is EpubMeasuredBoxFragment -> drawMeasuredBoxFragment(canvas, fragment, offsetX, offsetY)
            is EpubTableFragment -> drawChildren(canvas, fragment.children, offsetX + fragment.frame.left, offsetY + fragment.frame.top)
            is EpubFlexFragment -> drawChildren(canvas, fragment.children, offsetX + fragment.frame.left, offsetY + fragment.frame.top)
            is EpubWebFragment -> drawWebPlaceholder(canvas, fragment, offsetX, offsetY)
        }
    }

    private fun drawTextFragment(canvas: Canvas, fragment: EpubTextFragment, offsetX: Float, offsetY: Float) {
        if (fragment.text.isEmpty()) return
        val frame = fragment.effectiveFrame()
        val layout = fragment.staticLayout
        canvas.save()
        canvas.clipRect(offsetX + frame.left, offsetY + frame.top, offsetX + frame.right, offsetY + frame.bottom)
        canvas.translate(offsetX + frame.left, offsetY + frame.top - fragment.lineTopOffsetPx)
        if (layout != null) {
            layout.draw(canvas)
        } else {
            fallbackTextPaint.color = textColor
            canvas.drawText(fragment.text.toString(), 0f, fallbackTextPaint.textSize, fallbackTextPaint)
        }
        canvas.restore()
    }

    private fun drawMeasuredTextFragment(canvas: Canvas, fragment: EpubMeasuredTextFragment, offsetX: Float, offsetY: Float) {
        if (fragment.text.isEmpty()) return
        val frame = fragment.effectiveFrame()
        scratchRect.set(frame)
        scratchRect.offset(offsetX, offsetY)
        configureMeasuredTextPaint(fragment)
        val baseline = resolveMeasuredBaseline(fragment, fallbackTextPaint, scratchRect.height())
        resolveMeasuredTextClip(fragment, fallbackTextPaint, scratchRect, baseline)
        val saveCount = canvas.save()
        canvas.clipRect(textClipRect)
        if (!drawMeasuredGlyphs(canvas, fragment, offsetX, offsetY, baseline)) {
            canvas.drawText(fragment.text.toString(), scratchRect.left, scratchRect.top + baseline, fallbackTextPaint)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawMeasuredGlyphs(
        canvas: Canvas,
        fragment: EpubMeasuredTextFragment,
        offsetX: Float,
        offsetY: Float,
        fallbackBaseline: Float
    ): Boolean {
        val glyphs = fragment.glyphs.takeIf { it.isNotEmpty() } ?: return false
        var drew = false
        glyphs.forEach { glyph ->
            if (glyph.text.isEmpty()) return@forEach
            val cellLeft = offsetX + glyph.xPx
            val cellWidth = glyph.widthPx.takeIf { it.isFinite() && it > 0f } ?: fallbackTextPaint.textSize
            val y = offsetY + glyph.yPx + (glyph.baselinePx ?: fallbackBaseline)
            if (!cellLeft.isFinite() || !cellWidth.isFinite() || !y.isFinite()) return@forEach
            drawGlyphInFixedCell(canvas, glyph.text, cellLeft, y, cellWidth)
            drew = true
        }
        return drew
    }

    private fun drawGlyphInFixedCell(canvas: Canvas, text: String, cellLeft: Float, baseline: Float, cellWidth: Float) {
        val originalScaleX = fallbackTextPaint.textScaleX
        val naturalWidth = fallbackTextPaint.measureText(text)
        if (!naturalWidth.isFinite() || naturalWidth <= 0f) {
            canvas.drawText(text, cellLeft, baseline, fallbackTextPaint)
            return
        }
        val scale = if (naturalWidth > cellWidth * 1.04f) {
            (cellWidth / naturalWidth).coerceIn(0.72f, 1f)
        } else {
            1f
        }
        fallbackTextPaint.textScaleX = originalScaleX * scale
        val scaledWidth = naturalWidth * scale
        val drawX = cellLeft + (cellWidth - scaledWidth) / 2f
        canvas.drawText(text, drawX, baseline, fallbackTextPaint)
        fallbackTextPaint.textScaleX = originalScaleX
    }

    private fun configureMeasuredTextPaint(fragment: EpubMeasuredTextFragment) {
        fallbackTextPaint.reset()
        fallbackTextPaint.isAntiAlias = true
        fallbackTextPaint.color = (fragment.color ?: textColor).withAlpha((fragment.opacity.coerceIn(0f, 1f) * 255).toInt())
        fallbackTextPaint.textSize = fragment.resolvedMeasuredTextSize()
        fallbackTextPaint.textScaleX = fragment.textScaleX?.takeIf { it.isFinite() && it > 0f } ?: 1f
        if (fallbackTextPaint.textSize > 0f) {
            fallbackTextPaint.letterSpacing = ((fragment.letterSpacingPx ?: 0f) / fallbackTextPaint.textSize)
                .takeIf { it.isFinite() }
                ?: 0f
        }
        val typefaceStyle = when {
            fragment.bold && fragment.italic -> Typeface.BOLD_ITALIC
            fragment.bold -> Typeface.BOLD
            fragment.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        fallbackTextPaint.typeface = resolveMeasuredTypeface(fragment, typefaceStyle)
    }

    private fun EpubMeasuredTextFragment.resolvedMeasuredTextSize(): Float {
        fontSizePx
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let { return it.coerceAtLeast(1f) }
        return when (kind) {
            EpubMeasuredTextKind.RubyText -> textPaint.textSize * 0.72f
            EpubMeasuredTextKind.Superscript, EpubMeasuredTextKind.Subscript -> textPaint.textSize * 0.78f
            else -> when (tagName?.lowercase()) {
                "rt", "rp" -> textPaint.textSize * 0.72f
                "sup", "sub" -> textPaint.textSize * 0.78f
                else -> textPaint.textSize
            }
        }.coerceAtLeast(1f)
    }

    private fun EpubMeasuredTextFragment.resolvedTextRole(): String {
        return when (kind) {
            EpubMeasuredTextKind.RubyText -> "rt"
            EpubMeasuredTextKind.Superscript -> "sup"
            EpubMeasuredTextKind.Subscript -> "sub"
            EpubMeasuredTextKind.Ruby -> "ruby"
            EpubMeasuredTextKind.Text -> tagName?.lowercase().orEmpty()
        }
    }

    private fun resolveMeasuredBaseline(fragment: EpubMeasuredTextFragment, paint: TextPaint, rectHeight: Float): Float {
        val metrics = paint.fontMetrics
        return (rectHeight - metrics.descent - metrics.ascent) / 2f
    }

    private fun resolveMeasuredTextClip(
        fragment: EpubMeasuredTextFragment,
        paint: TextPaint,
        rect: RectF,
        baseline: Float
    ) {
        val metrics = paint.fontMetrics
        val glyphTop = rect.top + baseline + metrics.ascent
        val glyphBottom = rect.top + baseline + metrics.descent
        val verticalPad = ((fragment.lineHeightPx ?: rect.height()) - rect.height())
            .takeIf { it.isFinite() && it > 0f }
            ?.let { it / 2f }
            ?: 0f
        val glyphBounds = fragment.glyphs.takeIf { it.isNotEmpty() }?.let { glyphs ->
            val left = glyphs.minOfOrNull { it.xPx } ?: rect.left
            val right = glyphs.maxOfOrNull { it.xPx + it.widthPx } ?: rect.right
            if (left.isFinite() && right.isFinite() && right > left) {
                RectF(left, rect.top, right, rect.bottom)
            } else {
                null
            }
        }
        val measuredRight = glyphBounds?.right
            ?: (rect.left + paint.measureText(fragment.text.toString()) + 4f)
                .takeIf { it.isFinite() }
            ?: rect.right
        val left = ((glyphBounds?.left ?: rect.left) - 2f).coerceAtLeast(contentBounds.left)
        val top = minOf(rect.top - verticalPad, glyphTop - 2f).coerceAtLeast(contentBounds.top)
        val right = maxOf(rect.right + 2f, measuredRight).coerceAtMost(contentBounds.right)
        val bottom = maxOf(rect.bottom + verticalPad, glyphBottom + 2f).coerceAtMost(contentBounds.bottom)
        if (right > left && bottom > top) {
            textClipRect.set(left, top, right, bottom)
        } else {
            textClipRect.set(rect)
        }
    }

    private fun resolveMeasuredTypeface(fragment: EpubMeasuredTextFragment, style: Int): Typeface {
        if (fragment.shouldUseEmbeddedTypeface()) {
            typefaceResolver?.resolve(fragment.fontFamily, style)?.let { return it }
        }
        val fallback = Typeface.create(
            textPaint.typeface ?: Typeface.DEFAULT,
            style
        )
        val family = fragment.fontFamily
            ?.split(',')
            ?.asSequence()
            ?.map { it.trim().trim('"', '\'') }
            ?.firstOrNull { it.isNotBlank() && !it.equals("serif", true) && !it.equals("sans-serif", true) }
            ?: return fallback
        return if (fragment.shouldUseSystemCssTypeface()) {
            Typeface.create(family, style) ?: fallback
        } else {
            fallback
        }
    }

    private fun EpubMeasuredTextFragment.shouldUseEmbeddedTypeface(): Boolean {
        return !readerFontInherited && !fontFamily.isNullOrBlank()
    }

    private fun EpubMeasuredTextFragment.shouldUseSystemCssTypeface(): Boolean {
        return !readerFontInherited && !fontFamily.isNullOrBlank()
    }

    private fun drawImageFragment(canvas: Canvas, fragment: EpubImageFragment, offsetX: Float, offsetY: Float) {
        val frame = fragment.effectiveFrame()
        scratchRect.set(frame)
        scratchRect.offset(offsetX, offsetY)
        val forceCover = isDominantPageImage(scratchRect) || isViewportSizedBackground(scratchRect)
        val targetRect = if (forceCover) {
            fullPageImageDest(scratchRect)
        } else {
            scratchRect
        }
        val bitmap = imageResolver?.decode(
            fragment.href,
            targetRect.width().toInt().coerceAtLeast(1),
            targetRect.height().toInt().coerceAtLeast(1)
        )
        if (bitmap != null) {
            val save = canvas.save()
            clipRounded(canvas, targetRect, fragment.borderRadius)
            bitmapPaint.alpha = (fragment.opacity.coerceIn(0f, 1f) * 255).toInt()
            if (!forceCover) {
                canvas.drawBitmap(bitmap, null, targetRect, bitmapPaint)
            } else {
                val cover = backgroundImageDest(
                    width = bitmap.width,
                    height = bitmap.height,
                    rect = targetRect,
                    background = EpubBackground(position = EpubBackgroundPosition.Center),
                    forceCover = true
                )
                canvas.drawBitmap(bitmap, null, cover, bitmapPaint)
            }
            canvas.restoreToCount(save)
            return
        }
        drawImagePlaceholder(canvas, fragment, scratchRect)
    }

    private fun fullPageImageDest(rect: RectF): RectF {
        if (!isDominantPageImage(rect) && !isViewportSizedBackground(rect)) return rect
        imageDest.set(resolvePageBackgroundRect())
        return imageDest
    }

    private fun isDominantPageImage(rect: RectF): Boolean {
        if (rect.isEmpty || contentBounds.isEmpty) return false
        val contentWidth = contentBounds.width().coerceAtLeast(1f)
        val contentHeight = contentBounds.height().coerceAtLeast(1f)
        val widthRatio = rect.width() / contentWidth
        val heightRatio = rect.height() / contentHeight
        val areaRatio = (rect.width() * rect.height()) / (contentWidth * contentHeight)
        val nearTop = rect.top <= contentBounds.top + contentHeight * 0.12f
        return widthRatio >= 0.82f &&
            (heightRatio >= 0.55f || areaRatio >= 0.45f) &&
            nearTop
    }

    private fun drawImagePlaceholder(canvas: Canvas, fragment: EpubImageFragment, rect: RectF) {
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 2f
        borderPaint.color = pageNumberColor
        canvas.drawRoundRect(rect, fragment.borderRadius.maxPx, fragment.borderRadius.maxPx, borderPaint)
        placeholderPaint.reset()
        placeholderPaint.isAntiAlias = true
        placeholderPaint.color = pageNumberColor
        placeholderPaint.textSize = 28f
        placeholderPaint.textAlign = Paint.Align.CENTER
        val label = fragment.alt ?: fragment.href.substringAfterLast('/')
        canvas.drawText(label.take(24), rect.centerX(), rect.centerY(), placeholderPaint)
    }

    private fun drawWebPlaceholder(canvas: Canvas, fragment: EpubWebFragment, offsetX: Float, offsetY: Float) {
        val text = fragment.fallbackText ?: return
        drawTextFragment(
            canvas,
            EpubTextFragment(
                text = text,
                frame = fragment.effectiveFrame(),
                source = fragment.source
            ),
            offsetX,
            offsetY
        )
    }

    private fun drawContainerFragment(canvas: Canvas, fragment: EpubContainerFragment, offsetX: Float, offsetY: Float) {
        scratchRect.set(fragment.effectiveFrame())
        scratchRect.offset(offsetX, offsetY)
        val alpha = (fragment.opacity.coerceIn(0f, 1f) * 255).toInt()
        fragment.backgroundColor?.let { color ->
            borderPaint.style = Paint.Style.FILL
            borderPaint.color = color.withAlpha(alpha)
            drawRoundedRect(canvas, scratchRect, fragment.borderRadius, borderPaint)
        }
        drawBackgroundImage(
            canvas = canvas,
            background = fragment.background,
            rect = if (isViewportSizedBackground(scratchRect)) resolvePageBackgroundRect() else scratchRect,
            radius = fragment.borderRadius,
            alpha = alpha,
            forceCover = isViewportSizedBackground(scratchRect)
        )
        if (fragment.borderColor != null && fragment.borderWidthPx > 0f) {
            borderPaint.style = Paint.Style.STROKE
            borderPaint.strokeWidth = fragment.borderWidthPx
            borderPaint.color = fragment.borderColor.withAlpha(alpha)
            drawRoundedRect(canvas, scratchRect, fragment.borderRadius, borderPaint)
        }
        drawChildren(canvas, fragment.children, scratchRect.left, scratchRect.top)
    }

    private fun drawMeasuredBoxFragment(
        canvas: Canvas,
        fragment: EpubMeasuredBoxFragment,
        offsetX: Float,
        offsetY: Float,
        overrideRect: RectF? = null
    ) {
        scratchRect.set(overrideRect ?: fragment.effectiveFrame())
        scratchRect.offset(offsetX, offsetY)
        val backgroundRect = if (fragment.isPageBackground || isViewportSizedBackground(scratchRect)) {
            resolvePageBackgroundRect()
        } else {
            scratchRect
        }
        val alpha = (fragment.opacity.coerceIn(0f, 1f) * 255).toInt()
        fragment.backgroundColor?.let { color ->
            borderPaint.style = Paint.Style.FILL
            borderPaint.color = color.withAlpha(alpha)
            drawRoundedRect(canvas, backgroundRect, fragment.borderRadius, borderPaint)
        }
        drawBackgroundImage(
            canvas = canvas,
            background = fragment.background,
            rect = backgroundRect,
            radius = fragment.borderRadius,
            alpha = alpha,
            forceCover = fragment.isPageBackground || isViewportSizedBackground(backgroundRect)
        )
        if (fragment.borderColor != null && fragment.borderWidthPx > 0f) {
            borderPaint.style = Paint.Style.STROKE
            borderPaint.strokeWidth = fragment.borderWidthPx
            borderPaint.color = fragment.borderColor.withAlpha(alpha)
            drawRoundedRect(canvas, backgroundRect, fragment.borderRadius, borderPaint)
        }
    }

    private fun resolvePageBackgroundRect(): RectF {
        val pageWidth = layoutConfig?.pageWidthPx?.toFloat()?.takeIf { it > 0f }
        val pageHeight = layoutConfig?.pageHeightPx?.toFloat()?.takeIf { it > 0f }
        if (pageWidth != null && pageHeight != null) {
            pageBackgroundRect.set(0f, 0f, pageWidth, pageHeight)
        } else {
            pageBackgroundRect.set(0f, 0f, contentBounds.right, contentBounds.bottom)
        }
        return pageBackgroundRect
    }

    private fun drawBackgroundImage(
        canvas: Canvas,
        background: EpubBackground,
        rect: RectF,
        radius: EpubBorderRadius,
        alpha: Int,
        forceCover: Boolean = false
    ) {
        val href = background.imageHref ?: return
        val bitmap = imageResolver?.decode(href, rect.width().toInt(), rect.height().toInt()) ?: return
        val save = canvas.save()
        clipRounded(canvas, rect, radius)
        bitmapPaint.alpha = alpha
        if (forceCover) {
            val dest = backgroundImageDest(bitmap.width, bitmap.height, rect, background, forceCover = true)
            canvas.drawBitmap(bitmap, null, dest, bitmapPaint)
        } else when (background.repeat) {
            EpubBackgroundRepeat.NoRepeat -> {
                val dest = backgroundImageDest(bitmap.width, bitmap.height, rect, background)
                canvas.drawBitmap(bitmap, null, dest, bitmapPaint)
            }
            EpubBackgroundRepeat.RepeatX,
            EpubBackgroundRepeat.RepeatY,
            EpubBackgroundRepeat.Repeat -> drawRepeatedBackground(canvas, bitmap, rect, background)
        }
        canvas.restoreToCount(save)
    }

    private fun drawRepeatedBackground(canvas: Canvas, bitmap: android.graphics.Bitmap, rect: RectF, background: EpubBackground) {
        val tile = backgroundImageDest(bitmap.width, bitmap.height, rect, background)
        val tileWidth = tile.width().coerceAtLeast(1f)
        val tileHeight = tile.height().coerceAtLeast(1f)
        val repeat = background.repeat
        var startX = tile.left
        var startY = tile.top
        if (repeat != EpubBackgroundRepeat.RepeatY) {
            while (startX > rect.left) startX -= tileWidth
        }
        if (repeat != EpubBackgroundRepeat.RepeatX) {
            while (startY > rect.top) startY -= tileHeight
        }
        var y = startY
        while (y < rect.bottom) {
            var x = startX
            while (x < rect.right) {
                imageDest.set(x, y, x + tileWidth, y + tileHeight)
                canvas.drawBitmap(bitmap, null, imageDest, bitmapPaint)
                if (repeat == EpubBackgroundRepeat.RepeatY) break
                x += tileWidth
            }
            if (repeat == EpubBackgroundRepeat.RepeatX) break
            y += tileHeight
        }
    }

    private fun backgroundImageDest(
        width: Int,
        height: Int,
        rect: RectF,
        background: EpubBackground,
        forceCover: Boolean = false
    ): RectF {
        val sourceWidth = width.coerceAtLeast(1).toFloat()
        val sourceHeight = height.coerceAtLeast(1).toFloat()
        val backgroundSize = if (forceCover) EpubBackgroundSize.Cover else background.size
        val (targetWidth, targetHeight) = when (val size = backgroundSize) {
            EpubBackgroundSize.Auto -> sourceWidth to sourceHeight
            EpubBackgroundSize.Cover -> {
                val scale = maxOf(rect.width() / sourceWidth, rect.height() / sourceHeight)
                sourceWidth * scale to sourceHeight * scale
            }
            EpubBackgroundSize.Contain -> {
                val scale = minOf(rect.width() / sourceWidth, rect.height() / sourceHeight)
                sourceWidth * scale to sourceHeight * scale
            }
            is EpubBackgroundSize.Explicit -> {
                val w = size.width.resolve(rect.width()) ?: sourceWidth
                val h = size.height.resolve(rect.height()) ?: sourceHeight * (w / sourceWidth)
                w to h
            }
        }
        val left = rect.left + (rect.width() - targetWidth) * background.position.xPercent
        val top = rect.top + (rect.height() - targetHeight) * background.position.yPercent
        imageDest.set(left, top, left + targetWidth, top + targetHeight)
        return imageDest
    }

    private fun isViewportSizedBackground(rect: RectF): Boolean {
        if (rect.isEmpty) return false
        val config = layoutConfig
        val pageWidth = config?.pageWidthPx?.toFloat()?.takeIf { it > 0f }
            ?: contentBounds.width().takeIf { it > 0f }
            ?: return false
        val pageHeight = config?.pageHeightPx?.toFloat()?.takeIf { it > 0f }
            ?: contentBounds.height().takeIf { it > 0f }
            ?: return false
        val readerFlowHeight = (pageHeight -
            (config?.readerPaddingTopPx ?: 0) -
            (config?.readerPaddingBottomPx ?: 0)
        ).coerceAtLeast(pageHeight * 0.5f)
        val viewportArea = pageWidth * pageHeight
        val contentArea = contentBounds.width().coerceAtLeast(1f) * contentBounds.height().coerceAtLeast(1f)
        val rectArea = rect.width() * rect.height()
        val coversViewport = rect.width() >= pageWidth * 0.82f && rect.height() >= pageHeight * 0.82f
        val coversReaderFlow = rect.width() >= pageWidth * 0.82f &&
            rect.height() >= readerFlowHeight * 0.92f &&
            rect.top <= pageHeight * 0.18f
        val coversContent = rect.width() >= contentBounds.width() * 0.92f &&
            rect.height() >= contentBounds.height() * 0.92f &&
            rectArea >= contentArea * 0.85f
        return coversViewport || coversReaderFlow || coversContent || rectArea >= viewportArea * 0.82f
    }

    private fun drawChildren(canvas: Canvas, children: List<EpubPageFragment>, offsetX: Float, offsetY: Float) {
        children.forEach { drawFragment(canvas, it, offsetX, offsetY) }
    }

    private fun drawRoundedRect(canvas: Canvas, rect: RectF, radius: EpubBorderRadius, paint: Paint) {
        val r = radius.maxPx
        if (r > 0f) {
            canvas.drawRoundRect(rect, r, r, paint)
        } else {
            canvas.drawRect(rect, paint)
        }
    }

    private fun clipRounded(canvas: Canvas, rect: RectF, radius: EpubBorderRadius) {
        val r = radius.maxPx
        if (r > 0f) {
            val path = android.graphics.Path().apply {
                addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(path)
        } else {
            canvas.clipRect(rect)
        }
    }

    private fun Int.withAlpha(alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(this), Color.green(this), Color.blue(this))
    }

    private fun EpubSizeValue.resolve(relativeTo: Float): Float? {
        return when (this) {
            EpubSizeValue.Auto -> null
            is EpubSizeValue.Percent -> relativeTo * value
            is EpubSizeValue.Px -> value
        }
    }

    private fun EpubPageFragment.effectiveFrame(): RectF {
        if (frame.width() > 0f && frame.height() > 0f) return frame
        return RectF(0f, 0f, contentBounds.width(), contentBounds.height())
    }

    private fun resolveContentBounds(pageBounds: RectF): RectF {
        val config = layoutConfig ?: return pageBounds
        val left = pageBounds.left + config.paddingLeftPx
        val top = pageBounds.top + config.paddingTopPx
        val right = (left + config.contentWidthPx).coerceAtMost(pageBounds.right)
        val bottom = (top + config.contentHeightPx).coerceAtMost(pageBounds.bottom)
        return RectF(left, top, right.coerceAtLeast(left), bottom.coerceAtLeast(top))
    }

}
