package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.uiTypeface

class RoundedTagBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class DisplayMode { CHIP, LIGHT, TEXT }

    data class Item(
        val text: CharSequence,
        val alpha: Float = 1f
    )

    private val layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
    private val adapter = TagAdapter()
    private val recyclerView = RecyclerView(context).apply {
        layoutManager = this@RoundedTagBarView.layoutManager
        adapter = this@RoundedTagBarView.adapter
        overScrollMode = OVER_SCROLL_NEVER
        itemAnimator = null
        clipToPadding = false
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = false
        isVerticalFadingEdgeEnabled = false
        setFadingEdgeLength(0)
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_recycler_padding_vertical)
        setPadding(0, verticalPadding, 0, verticalPadding)
    }
    private var items = emptyList<Item>()
    private var selectedIndex = RecyclerView.NO_POSITION
    private var onTagClick: ((Int) -> Unit)? = null
    private var onTagLongClick: ((Int) -> Boolean)? = null
    private var styleSignature: String? = null
    private var selectedBackgroundVisible = true
    private var displayMode = DisplayMode.CHIP
    private var backgroundOverrideColor: Int? = null

    init {
        clipToOutline = true
        applyTopBarStyle(force = true)
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_padding_horizontal)
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_padding_vertical)
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        addView(
            recyclerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyTopBarStyle()
    }

    fun applyTopBarStyle(force: Boolean = false) {
        val signature = "${TopBarConfig.currentSignature(AppConfig.isNightTheme)}|$displayMode|$backgroundOverrideColor"
        if (!force && styleSignature == signature) return
        styleSignature = signature
        val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
        val tagBarColor = config.tagBarColor
            ?: if (config.style == TopBarConfig.STYLE_REGULAR) {
                Color.WHITE
            } else {
                ContextCompat.getColor(context, R.color.background_menu)
            }
        val selectedColor = config.tagSelectedColor
            ?: ContextCompat.getColor(context, R.color.background_card)
        background = when (displayMode) {
            DisplayMode.TEXT -> null
            else -> UiCorner.opaqueRounded(
                backgroundOverrideColor ?: TopBarConfig.withOpacity(tagBarColor, config.tagBarAlpha),
                UiCorner.panelRadius(context)
            )
        }
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_padding_horizontal)
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_padding_vertical)
        setPadding(
            if (displayMode == DisplayMode.TEXT) 0 else horizontalPadding,
            if (displayMode == DisplayMode.TEXT) 0 else verticalPadding,
            if (displayMode == DisplayMode.TEXT) 0 else horizontalPadding,
            if (displayMode == DisplayMode.TEXT) 0 else verticalPadding
        )
        adapter.selectedBackgroundColor = TopBarConfig.withOpacity(selectedColor, config.tagSelectedAlpha)
        adapter.notifyDataSetChanged()
    }

    fun setDisplayMode(mode: DisplayMode) {
        if (displayMode == mode) return
        displayMode = mode
        styleSignature = null
        applyTopBarStyle(force = true)
    }

    fun setBackgroundOverrideColor(color: Int?) {
        if (backgroundOverrideColor == color) return
        backgroundOverrideColor = color
        styleSignature = null
        applyTopBarStyle(force = true)
    }

    fun setSelectedBackgroundVisible(visible: Boolean) {
        if (selectedBackgroundVisible == visible) return
        selectedBackgroundVisible = visible
        adapter.notifyDataSetChanged()
    }

    fun submitItems(items: List<Item>, selectedIndex: Int = this.selectedIndex) {
        val sameItems = this.items == items
        if (sameItems) {
            setSelectedIndex(selectedIndex, smooth = false)
            return
        }
        this.items = items.toList()
        this.selectedIndex = normalizeIndex(selectedIndex)
        adapter.notifyDataSetChanged()
        if (this.selectedIndex != RecyclerView.NO_POSITION) {
            scrollToIndex(this.selectedIndex, smooth = false)
        }
    }

    fun setSelectedIndex(index: Int, smooth: Boolean = true) {
        val newIndex = normalizeIndex(index)
        if (selectedIndex == newIndex) {
            if (newIndex != RecyclerView.NO_POSITION) {
                scrollToIndex(newIndex, smooth)
            }
            return
        }
        val oldIndex = selectedIndex
        selectedIndex = newIndex
        if (oldIndex in items.indices) {
            adapter.notifyItemChanged(oldIndex)
        }
        if (newIndex != RecyclerView.NO_POSITION) {
            adapter.notifyItemChanged(newIndex)
            scrollToIndex(newIndex, smooth)
        }
    }

    fun getSelectedIndex(): Int = selectedIndex

    fun setOnTagClickListener(listener: ((Int) -> Unit)?) {
        onTagClick = listener
    }

    fun setOnTagLongClickListener(listener: ((Int) -> Boolean)?) {
        onTagLongClick = listener
    }

    private fun normalizeIndex(index: Int): Int {
        return if (index in items.indices) index else RecyclerView.NO_POSITION
    }

    private fun scrollToIndex(index: Int, smooth: Boolean) {
        recyclerView.post {
            if (index !in items.indices) return@post
            val child = layoutManager.findViewByPosition(index)
            if (child == null) {
                if (smooth) {
                    recyclerView.smoothScrollToPosition(index)
                } else {
                    recyclerView.scrollToPosition(index)
                }
                recyclerView.post { centerVisibleChild(index, false) }
                return@post
            }
            centerChild(child.left, child.width, smooth)
        }
    }

    private fun centerVisibleChild(index: Int, smooth: Boolean) {
        val child = layoutManager.findViewByPosition(index) ?: return
        centerChild(child.left, child.width, smooth)
    }

    private fun centerChild(childLeft: Int, childWidth: Int, smooth: Boolean) {
        val dx = childLeft - (recyclerView.width - childWidth) / 2
        if (dx == 0) return
        if (smooth) {
            recyclerView.smoothScrollBy(dx, 0)
        } else {
            recyclerView.scrollBy(dx, 0)
        }
    }

    private inner class TagAdapter : RecyclerView.Adapter<TagViewHolder>() {

        var selectedBackgroundColor: Int = ContextCompat.getColor(context, R.color.background_card)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): TagViewHolder {
            val textView = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bookshelf_group_tag, parent, false) as TextView
            textView.background = UiCorner.actionSelector(
                android.graphics.Color.TRANSPARENT,
                selectedBackgroundColor,
                UiCorner.actionRadius(parent.context)
            )
            textView.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                    intArrayOf(parent.context.accentColor, parent.context.primaryTextColor)
                )
            )
            return TagViewHolder(textView)
        }

        override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
            val item = items[position]
            holder.textView.background = UiCorner.actionSelector(
                android.graphics.Color.TRANSPARENT,
                when {
                    !selectedBackgroundVisible -> android.graphics.Color.TRANSPARENT
                    displayMode == DisplayMode.TEXT -> android.graphics.Color.TRANSPARENT
                    else -> selectedBackgroundColor
                },
                UiCorner.actionRadius(holder.textView.context)
            )
            val verticalPadding = if (displayMode == DisplayMode.TEXT) 0 else resources.getDimensionPixelSize(R.dimen.bookshelf_tag_recycler_padding_vertical)
            val horizontalPadding = if (displayMode == DisplayMode.TEXT) 8.dp else resources.getDimensionPixelSize(R.dimen.bookshelf_tag_item_padding_horizontal)
            holder.textView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            holder.textView.text = item.text
            holder.textView.typeface = holder.textView.context.uiTypeface()
            holder.textView.alpha = item.alpha
            holder.textView.isSelected = position == selectedIndex
            holder.textView.setOnClickListener {
                val bindingPosition = holder.bindingAdapterPosition
                if (bindingPosition != RecyclerView.NO_POSITION) {
                    onTagClick?.invoke(bindingPosition)
                }
            }
            holder.textView.setOnLongClickListener {
                val bindingPosition = holder.bindingAdapterPosition
                if (bindingPosition == RecyclerView.NO_POSITION) {
                    false
                } else {
                    onTagLongClick?.invoke(bindingPosition) ?: false
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class TagViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
