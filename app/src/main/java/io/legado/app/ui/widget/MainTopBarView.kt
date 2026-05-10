package io.legado.app.ui.widget

import android.content.Context
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
import io.legado.app.lib.theme.applyUiTitleTypeface

class MainTopBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    enum class Mode { BOOKSHELF, DISCOVERY, RSS }

    val titleSelect = LinearLayout(context)
    val titleText = TextView(context)
    val titleArrow = AppCompatImageView(context)
    val moreButton = actionButton(R.drawable.ic_more_vert, R.string.menu)
    val searchButton = actionButton(R.drawable.ic_search, R.string.search)
    val filterButton = actionButton(R.drawable.ic_sort, R.string.sort)
    val starButton = actionButton(R.drawable.ic_star, R.string.favorite)
    val refreshButton = actionButton(R.drawable.ic_refresh_black_24dp, R.string.refresh)
    val loginButton = actionButton(R.drawable.ic_bottom_person, R.string.login)
    val selectsBar = RoundedTagBarView(context)
    val tagsBar = RoundedTagBarView(context)

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        val horizontal = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_horizontal)
        setPadding(horizontal, 0, horizontal, 0)
        addView(buildTitleRow())
        addView(selectsBar, tagLayoutParams().apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_top)
        })
        addView(tagsBar, tagLayoutParams().apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_top)
        })
        selectsBar.isVisible = false
        tagsBar.isVisible = false
        setMode(Mode.BOOKSHELF)
    }

    fun setMode(mode: Mode) {
        moreButton.isVisible = mode == Mode.BOOKSHELF
        searchButton.isVisible = mode != Mode.BOOKSHELF
        filterButton.isVisible = mode == Mode.DISCOVERY
        starButton.isVisible = mode == Mode.RSS
        refreshButton.isVisible = mode == Mode.RSS
        loginButton.isVisible = mode != Mode.BOOKSHELF
        titleText.textSize = if (mode == Mode.BOOKSHELF) 24f else 20f
        titleText.applyUiTitleTypeface(context)
        updateIconColors()
    }

    fun setTitle(text: CharSequence) {
        titleText.text = text
    }

    fun showSelects(show: Boolean) {
        selectsBar.isVisible = show
    }

    fun showTags(show: Boolean) {
        tagsBar.isVisible = show
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

    private fun buildTitleRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, resources.getDimensionPixelSize(R.dimen.bookshelf_title_row_margin_top), 0, 0)
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
            addView(Space(context), LayoutParams(0, 1, 1f))
            addAction(searchButton)
            addAction(filterButton)
            addAction(starButton)
            addAction(refreshButton)
            addAction(loginButton)
            addAction(moreButton)
        }
    }

    private fun LinearLayout.addAction(view: View) {
        addView(Space(context), LayoutParams(10.dp, 1))
        addView(view)
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
        listOf(moreButton, searchButton, filterButton, starButton, refreshButton, loginButton).forEach {
            it.setColorFilter(color)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
