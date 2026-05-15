package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.TopBarSearchStyle
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.StatusBarInsetAware

class MainTopBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), StatusBarInsetAware {

    enum class Mode { BOOKSHELF, DISCOVERY, RSS, READ_RECORD }

    val titleSelect = LinearLayout(context)
    val titleText = TextView(context)
    val titleArrow = AppCompatImageView(context)
    val searchEntry = LinearLayout(context)
    private val searchEntryText = TextView(context)
    private val searchEntryIcon = AppCompatImageView(context)
    val moreButton = actionButton(R.drawable.ic_more_vert, R.string.menu)
    val searchButton = actionButton(R.drawable.ic_search, R.string.search)
    val filterButton = actionButton(R.drawable.ic_sort, R.string.sort)
    val starButton = actionButton(R.drawable.ic_star, R.string.favorite)
    val refreshButton = actionButton(R.drawable.ic_refresh_black_24dp, R.string.refresh)
    val loginButton = actionButton(R.drawable.ic_bottom_person, R.string.login)
    val primaryBar = RoundedTagBarView(context)
    val selectsBar = RoundedTagBarView(context)
    val tagsBar = RoundedTagBarView(context)
    private val primaryFilterRow = LinearLayout(context)
    private val filterToggleButton = actionButton(R.drawable.ic_expand_more, R.string.screen)
    private val titleSpacer = Space(context)
    private val titleRow = buildTitleRow()
    private var mode = Mode.BOOKSHELF
    private var styleSignature: String? = null
    private var primaryBarRequested = false
    private var selectsBarRequested = false
    private var tagsBarRequested = false
    private var filtersExpanded = false
    private var searchEntryRequested = true
    private var wallpaperBitmapKey: String? = null
    private var wallpaperBitmap: Bitmap? = null
    private var onHeightChanged: (() -> Unit)? = null
    private var onFilterExpandedChanged: ((Boolean) -> Unit)? = null
    private var statusBarInsetTop: Int = 0

    init {
        orientation = VERTICAL
        clipChildren = true
        clipToPadding = false
        val horizontal = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_horizontal)
        setPadding(horizontal, paddingTop, horizontal, 0)
        addView(titleRow)
        addView(primaryFilterRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(primaryBar, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(filterToggleButton)
        }, tagLayoutParams().apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_top)
        })
        addView(selectsBar, tagLayoutParams().apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_top)
        })
        addView(tagsBar, tagLayoutParams().apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_top)
        })
        primaryBar.isVisible = false
        primaryFilterRow.isVisible = false
        filterToggleButton.isVisible = false
        selectsBar.isVisible = false
        tagsBar.isVisible = false
        filterToggleButton.setOnClickListener {
            filtersExpanded = !filtersExpanded
            updateFilterBarsVisibility()
            onFilterExpandedChanged?.invoke(filtersExpanded)
        }
        setMode(Mode.BOOKSHELF)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyTopBarStyle(force = true)
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        moreButton.isVisible = mode == Mode.BOOKSHELF || mode == Mode.READ_RECORD
        searchButton.isVisible = mode == Mode.DISCOVERY || mode == Mode.RSS
        filterButton.isVisible = mode == Mode.DISCOVERY
        starButton.isVisible = mode == Mode.RSS
        refreshButton.isVisible = mode == Mode.RSS
        loginButton.isVisible = mode == Mode.DISCOVERY || mode == Mode.RSS
        titleText.textSize = if (mode == Mode.BOOKSHELF) 24f else 20f
        titleText.applyUiTitleTypeface(context)
        applyTopBarStyle(force = true)
    }

    fun setTitle(text: CharSequence) {
        titleText.text = text
    }

    fun setSearchHint(text: CharSequence) {
        searchEntryText.text = text
    }

    fun setSearchEntryVisible(visible: Boolean) {
        if (searchEntryRequested == visible) return
        searchEntryRequested = visible
        applyTopBarStyle(force = true)
    }

    fun setPrimaryItems(items: List<RoundedTagBarView.Item>, selectedIndex: Int) {
        primaryBarRequested = items.isNotEmpty()
        primaryBar.submitItems(items, selectedIndex)
        updatePrimaryBarVisibility()
    }

    fun isRegularStyle(): Boolean {
        return TopBarConfig.currentConfig(context, AppConfig.isNightTheme).style == TopBarConfig.STYLE_REGULAR
    }

    fun isOverlayMode(): Boolean {
        return isRegularStyle()
    }

    fun refreshStyle() {
        styleSignature = null
        wallpaperBitmapKey = null
        wallpaperBitmap?.takeIf { !it.isRecycled }?.recycle()
        wallpaperBitmap = null
        listOf(primaryBar, selectsBar, tagsBar).forEach { it.applyTopBarStyle(force = true) }
        applyTopBarStyle(force = true)
        requestLayout()
        invalidate()
    }

    fun setOnHeightChangedListener(listener: (() -> Unit)?) {
        onHeightChanged = listener
        notifyHeightChangedAfterLayout()
    }

    fun setOnFilterExpandedChangedListener(listener: ((Boolean) -> Unit)?) {
        onFilterExpandedChanged = listener
    }

    fun setFiltersExpanded(expanded: Boolean) {
        if (filtersExpanded == expanded) {
            updateFilterBarsVisibility()
            return
        }
        filtersExpanded = expanded
        updateFilterBarsVisibility()
    }

    fun showSelects(show: Boolean) {
        selectsBarRequested = show
        if (!show) {
            filtersExpanded = tagsBarRequested && filtersExpanded
        }
        updateFilterBarsVisibility()
    }

    fun showTags(show: Boolean) {
        tagsBarRequested = show
        if (!show) {
            filtersExpanded = selectsBarRequested && filtersExpanded
        }
        updateFilterBarsVisibility()
    }

    fun setActionsVisible(
        search: Boolean? = null,
        filter: Boolean? = null,
        star: Boolean? = null,
        refresh: Boolean? = null,
        login: Boolean? = null
    ) {
        search?.let { searchButton.isVisible = it }
        filter?.let { filterButton.isVisible = it }
        star?.let { starButton.isVisible = it }
        refresh?.let { refreshButton.isVisible = it }
        login?.let { loginButton.isVisible = it }
    }

    private fun applyTopBarStyle(force: Boolean = false, resetFilters: Boolean = force) {
        val signature = "${TopBarConfig.currentSignature(AppConfig.isNightTheme)}|$mode"
        if (!force && styleSignature == signature) return
        val signatureChanged = styleSignature != signature
        styleSignature = signature
        val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
        if (resetFilters && (force || signatureChanged)) {
            filtersExpanded = config.style == TopBarConfig.STYLE_REGULAR && config.expandFiltersByDefault
        }
        if (config.style == TopBarConfig.STYLE_REGULAR) {
            applyRegularStyle(config)
        } else {
            applyDefaultStyle()
        }
        updatePrimaryBarVisibility()
        updateFilterBarsVisibility()
        updateIconColors()
    }

    override fun onStatusBarInsetChanged(insetTop: Int, initialPaddingTop: Int) {
        if (statusBarInsetTop == insetTop) return
        statusBarInsetTop = insetTop
        applyTopBarStyle(force = true, resetFilters = false)
        notifyHeightChangedAfterLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) {
            notifyHeightChangedAfterLayout()
        }
    }

    private fun notifyHeightChangedAfterLayout() {
        post { onHeightChanged?.invoke() }
    }
    private fun updateTitleRowControlHeight(height: Int) {
        searchEntry.layoutParams = (searchEntry.layoutParams as? LayoutParams ?: LayoutParams(0, height, 1f)).apply {
            this.height = height
        }
        titleSelect.layoutParams = (titleSelect.layoutParams as? LayoutParams ?: LayoutParams(LayoutParams.WRAP_CONTENT, height)).apply {
            this.height = height
        }
    }

    private fun applyDefaultStyle() {
        val horizontal = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_horizontal)
        setPadding(horizontal, statusBarInsetTop, horizontal, 0)
        background = null
        titleRow.background = null
        titleRow.setPadding(0, resources.getDimensionPixelSize(R.dimen.bookshelf_title_row_margin_top), 0, 0)
        updateTitleRowControlHeight(resources.getDimensionPixelSize(R.dimen.bookshelf_title_select_height))
        searchEntry.isVisible = false
        titleSelect.isVisible = true
        titleSpacer.isVisible = true
        titleSelect.background = ContextCompat.getDrawable(context, R.drawable.bg_discover_embedded_action)
        listOf(moreButton, searchButton, filterButton, starButton, refreshButton, loginButton, filterToggleButton).forEach {
            it.background = ContextCompat.getDrawable(context, R.drawable.bg_discover_embedded_action)
            it.layoutParams = (it.layoutParams as LayoutParams).apply {
                width = resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size)
                height = resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size)
                marginStart = 8.dp
            }
            val padding = resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_padding)
            it.setPadding(padding, padding, padding, padding)
        }
        titleText.gravity = Gravity.CENTER_VERTICAL
        titleText.setTextColor(ContextCompat.getColor(context, R.color.primaryText))
        searchEntryText.setTextColor(ContextCompat.getColor(context, R.color.primaryText))
        primaryBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        selectsBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        tagsBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        primaryBar.setBackgroundOverrideColor(null)
        selectsBar.setBackgroundOverrideColor(null)
        tagsBar.setBackgroundOverrideColor(null)
        primaryBar.setSelectedBackgroundVisible(true)
        selectsBar.setSelectedBackgroundVisible(true)
        tagsBar.setSelectedBackgroundVisible(true)
    }

    private fun applyRegularStyle(config: TopBarConfig.Config) {
        val horizontal = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_horizontal)
        val vertical = 5.dp
        setPadding(horizontal, statusBarInsetTop + vertical, horizontal, vertical)
        background = regularBackground(config)
        titleRow.background = null
        titleRow.setPadding(0, 0, 0, 0)
        updateTitleRowControlHeight(resources.getDimensionPixelSize(R.dimen.top_bar_regular_action_size))
        titleSelect.isVisible = !searchEntryRequested
        searchEntry.isVisible = searchEntryRequested
        titleSpacer.isVisible = !searchEntryRequested
        titleSelect.background = null
        searchEntry.background = TopBarSearchStyle.actionBackground(context)
        searchEntry.setPadding(14.dp, 0, 14.dp, 0)
        titleSelect.setPadding(12.dp, 0, 8.dp, 0)
        listOf(moreButton, searchButton, filterButton, starButton, refreshButton, loginButton, filterToggleButton).forEach {
            it.background = null
            it.layoutParams = (it.layoutParams as LayoutParams).apply {
                width = resources.getDimensionPixelSize(R.dimen.top_bar_regular_action_size)
                height = resources.getDimensionPixelSize(R.dimen.top_bar_regular_action_size)
                marginStart = 6.dp
            }
            val padding = 8.dp
            it.setPadding(padding, padding, padding, padding)
        }
        titleText.gravity = Gravity.CENTER_VERTICAL
        titleText.setTextColor(context.primaryTextColor)
        searchEntryText.setTextColor(context.primaryTextColor)
        primaryBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        selectsBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        tagsBar.setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
        primaryBar.setBackgroundOverrideColor(null)
        selectsBar.setBackgroundOverrideColor(null)
        tagsBar.setBackgroundOverrideColor(null)
        primaryBar.setSelectedBackgroundVisible(true)
        selectsBar.setSelectedBackgroundVisible(true)
        tagsBar.setSelectedBackgroundVisible(false)
    }

    private fun buildTitleRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, resources.getDimensionPixelSize(R.dimen.bookshelf_title_row_margin_top), 0, 0)
            addView(searchEntry.apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                visibility = View.GONE
                val height = resources.getDimensionPixelSize(R.dimen.bookshelf_title_select_height)
                layoutParams = LayoutParams(0, height, 1f)
                setPadding(14.dp, 0, 14.dp, 0)
                addView(searchEntryIcon.apply {
                    setImageResource(R.drawable.ic_search)
                    layoutParams = LayoutParams(17.dp, 17.dp)
                })
                addView(searchEntryText.apply {
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = 14f
                    alpha = 0.78f
                    applyUiTitleTypeface(context)
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 8.dp
                    }
                })
            })
            addView(titleSelect.apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(context, R.drawable.bg_discover_embedded_action)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, resources.getDimensionPixelSize(R.dimen.bookshelf_title_select_height))
                addView(titleText.apply {
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(context, R.color.primaryText))
                    applyUiTitleTypeface(context)
                })
                addView(titleArrow.apply {
                    setImageResource(R.drawable.ic_arrow_drop_down)
                    layoutParams = LayoutParams(
                        resources.getDimensionPixelSize(R.dimen.bookshelf_title_arrow_size),
                        resources.getDimensionPixelSize(R.dimen.bookshelf_title_arrow_size)
                    )
                })
            })
            addView(titleSpacer, LayoutParams(0, 1, 1f))
            addAction(searchButton)
            addAction(filterButton)
            addAction(starButton)
            addAction(refreshButton)
            addAction(loginButton)
            addAction(moreButton)
        }
    }

    private fun LinearLayout.addAction(view: View) {
        addView(view.apply {
            layoutParams = (layoutParams as? LayoutParams ?: LayoutParams(
                resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size),
                resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size)
            )).apply {
                marginStart = 8.dp
            }
        })
    }

    private fun actionButton(drawableRes: Int, contentDescRes: Int): AppCompatImageButton {
        return AppCompatImageButton(context).apply {
            setImageResource(drawableRes)
            contentDescription = context.getString(contentDescRes)
            background = ContextCompat.getDrawable(context, R.drawable.bg_discover_embedded_action)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val padding = resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_padding)
            setPadding(padding, padding, padding, padding)
            layoutParams = LayoutParams(
                resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size),
                resources.getDimensionPixelSize(R.dimen.bookshelf_action_button_size)
            )
        }
    }

    private fun tagLayoutParams(): LayoutParams {
        return LayoutParams(
            LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_height)
        )
    }

    private fun updateIconColors() {
        val color = ContextCompat.getColor(context, R.color.primaryText)
        titleArrow.setColorFilter(color)
        searchEntryIcon.setColorFilter(color)
        listOf(moreButton, searchButton, filterButton, starButton, refreshButton, loginButton, filterToggleButton).forEach {
            it.setColorFilter(color)
        }
    }

    private fun updatePrimaryBarVisibility() {
        primaryBar.isVisible = isRegularStyle() && primaryBarRequested
        primaryFilterRow.isVisible = primaryBar.isVisible || filterToggleButton.isVisible
    }

    private fun updateFilterBarsVisibility() {
        val hasFilters = selectsBarRequested || tagsBarRequested
        val oldRowVisible = primaryFilterRow.isVisible
        val oldToggleVisible = filterToggleButton.isVisible
        val oldSelectsVisible = selectsBar.isVisible
        val oldTagsVisible = tagsBar.isVisible
        if (isRegularStyle()) {
            filterToggleButton.isVisible = hasFilters
            filterToggleButton.setImageResource(if (filtersExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
            selectsBar.isVisible = filtersExpanded && selectsBarRequested
            tagsBar.isVisible = filtersExpanded && tagsBarRequested
        } else {
            filterToggleButton.isVisible = false
            selectsBar.isVisible = selectsBarRequested
            tagsBar.isVisible = tagsBarRequested
        }
        primaryFilterRow.isVisible = (isRegularStyle() && primaryBarRequested) || filterToggleButton.isVisible
        if (
            oldRowVisible != primaryFilterRow.isVisible ||
            oldToggleVisible != filterToggleButton.isVisible ||
            oldSelectsVisible != selectsBar.isVisible ||
            oldTagsVisible != tagsBar.isVisible
        ) {
            requestLayout()
            invalidate()
            notifyHeightChangedAfterLayout()
        }
    }

    private fun bottomRoundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadii = floatArrayOf(
                0f, 0f,
                0f, 0f,
                radius, radius,
                radius, radius
            )
        }
    }

    private fun regularBackground(config: TopBarConfig.Config): Drawable {
        val radius = TopBarConfig.cornerRadius(context, config)
        val backgroundColor = TopBarConfig.withOpacity(
            TopBarConfig.resolveBackgroundColor(config),
            config.wallpaperAlpha
        )
        val file = TopBarConfig.currentWallpaperFile(context, AppConfig.isNightTheme)
            ?: return bottomRoundedBackground(backgroundColor, radius)
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        val bitmap = wallpaperBitmap?.takeIf { wallpaperBitmapKey == key && !it.isRecycled }
            ?: kotlin.runCatching {
                BitmapUtils.decodeBitmap(
                    file.absolutePath,
                    resources.displayMetrics.widthPixels.coerceAtLeast(1),
                    resources.getDimensionPixelSize(R.dimen.main_bottom_bar_height).coerceAtLeast(1) * 4
                )
            }.getOrNull()?.also {
                wallpaperBitmapKey = key
                wallpaperBitmap = it
            }
            ?: return bottomRoundedBackground(backgroundColor, radius)
        return LayerDrawable(
            arrayOf(
                bottomRoundedBackground(backgroundColor, radius),
                BottomRoundedBitmapDrawable(bitmap, radius, config.wallpaperAlpha)
            )
        )
    }

    private class BottomRoundedBitmapDrawable(
        private val bitmap: Bitmap,
        private val radius: Float,
        alphaPercent: Int
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            alpha = (alphaPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        }
        private val rect = RectF()
        private val matrix = Matrix()
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0) return
            rect.set(bounds)
            val scale = maxOf(
                bounds.width() / bitmap.width.toFloat(),
                bounds.height() / bitmap.height.toFloat()
            )
            val dx = bounds.left + (bounds.width() - bitmap.width * scale) / 2f
            val dy = bounds.top + (bounds.height() - bitmap.height * scale) / 2f
            matrix.reset()
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            paint.shader?.setLocalMatrix(matrix)
            path.reset()
            path.addRoundRect(
                rect,
                floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius),
                Path.Direction.CW
            )
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
