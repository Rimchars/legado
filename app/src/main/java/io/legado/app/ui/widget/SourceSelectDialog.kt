package io.legado.app.ui.widget

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.dpToPx

object SourceSelectDialog {

    fun <T> show(
        context: android.content.Context,
        title: CharSequence,
        items: List<T>,
        selectedKey: String?,
        displayName: (T) -> String,
        searchTexts: (T) -> List<String>,
        itemKey: (T) -> String,
        onSelect: (T) -> Unit
    ) {
        if (items.isEmpty()) return
        var dialog: AlertDialog? = null
        var filteredItems = items.toList()
        val adapter = object : RecyclerView.Adapter<SourceViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
                return SourceViewHolder(SourceOptionView(parent.context))
            }

            override fun getItemCount(): Int = filteredItems.size

            override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
                val item = filteredItems[position]
                val selectedPrefix = if (itemKey(item) == selectedKey) "✓ " else ""
                holder.bind(selectedPrefix + displayName(item)) {
                    dialog?.dismiss()
                    onSelect(item)
                }
            }
        }
        val searchView = SearchView(context).apply {
            queryHint = context.getString(R.string.screen_find)
            isIconified = false
            isSubmitButtonEnabled = false
            background = GradientDrawable().apply {
                cornerRadius = UiCorner.searchRadius(10f)
                setColor(ContextCompat.getColor(context, R.color.background_menu))
            }
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = true

                override fun onQueryTextChange(newText: String?): Boolean {
                    val key = newText.orEmpty().trim()
                    filteredItems = if (key.isBlank()) {
                        items
                    } else {
                        items.filter { item ->
                            searchTexts(item).any { text -> text.contains(key, true) }
                        }
                    }
                    adapter.notifyDataSetChanged()
                    return true
                }
            })
        }
        searchView.applyUiBodyTypefaceDeep(context.uiTypeface())
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                360.dpToPx()
            ).apply {
                topMargin = 10.dpToPx()
            }
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UiCorner.opaqueRounded(
                ContextCompat.getColor(context, R.color.background_card),
                UiCorner.panelRadius(context)
            )
            setPadding(14.dpToPx(), 14.dpToPx(), 14.dpToPx(), 12.dpToPx())
            addView(
                TextView(context).apply {
                    text = title
                    applyUiSectionTitleStyle(context)
                    textSize = 18f
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(2.dpToPx(), 0, 2.dpToPx(), 12.dpToPx())
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    32.dpToPx()
                )
            )
            addView(
                searchView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    42.dpToPx()
                )
            )
            addView(recyclerView)
        }
        dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private class SourceOptionView(context: android.content.Context) : TextView(context) {
        init {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            minHeight = 48.dpToPx()
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            applyUiLabelStyle(context)
            textSize = 15f
            setPadding(18.dpToPx(), 0, 18.dpToPx(), 0)
            background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                ContextCompat.getColor(context, R.color.background_menu),
                UiCorner.actionRadius(context)
            )
            isClickable = true
            isFocusable = true
        }
    }

    private class SourceViewHolder(private val rowView: SourceOptionView) : RecyclerView.ViewHolder(rowView) {
        fun bind(title: CharSequence, onClick: () -> Unit) {
            rowView.text = title
            rowView.setOnClickListener { onClick() }
        }
    }
}
