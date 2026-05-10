package io.legado.app.ui.about

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.DialogReadRecordComponentsBinding
import io.legado.app.databinding.ItemReadRecordComponentBinding
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import kotlin.math.min

object ReadRecordComponentConfigDialog {

    fun show(
        context: Context,
        initialItems: List<ReadRecordComponentItem>,
        onSaved: (List<ReadRecordComponentItem>) -> Unit
    ) {
        val binding = DialogReadRecordComponentsBinding.inflate(LayoutInflater.from(context))
        binding.root.applyUiBodyTypefaceDeep(context.uiTypeface())
        val adapter = ComponentAdapter(context, initialItems.map { it.copy() }.toMutableList())
        binding.root.layoutParams = ViewGroup.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        val fixedListHeight = min(420.dpToPx(), (context.resources.displayMetrics.heightPixels * 0.48f).toInt())
            .coerceAtLeast(260.dpToPx())
        binding.recyclerView.layoutParams = binding.recyclerView.layoutParams.apply {
            height = fixedListHeight
        }
        binding.recyclerView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        val itemTouchCallback = ItemTouchCallback(adapter).apply {
            isCanDrag = true
        }
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.applyTint()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val items = adapter.items.map { it.copy() }
                if (items.none { it.enabled }) {
                    items.firstOrNull()?.enabled = true
                }
                onSaved(items)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private class ComponentAdapter(
        private val context: Context,
        val items: MutableList<ReadRecordComponentItem>
    ) : RecyclerView.Adapter<ComponentAdapter.ComponentViewHolder>(), ItemTouchCallback.Callback {

        private val panelColor by lazy { ContextCompat.getColor(context, R.color.background_card) }
        private val pressedColor by lazy { ContextCompat.getColor(context, R.color.background_menu) }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ComponentViewHolder {
            val binding = ItemReadRecordComponentBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ComponentViewHolder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ComponentViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
            if (srcPosition !in items.indices || targetPosition !in items.indices) return false
            val item = items.removeAt(srcPosition)
            items.add(targetPosition, item)
            notifyItemMoved(srcPosition, targetPosition)
            return true
        }

        inner class ComponentViewHolder(
            private val binding: ItemReadRecordComponentBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(item: ReadRecordComponentItem) = with(binding) {
                root.background = UiCorner.panelRounded(
                    context,
                    panelColor,
                    UiCorner.panelRadius(context)
                )
                tvTitle.text = context.getString(item.type.titleRes)
                tvSubtitle.text = context.getString(item.type.hintRes)
                tvTitle.applyUiSectionTitleStyle(context)
                tvSubtitle.applyUiLabelStyle(context)
                cbEnabled.setOnCheckedChangeListener(null)
                cbEnabled.isChecked = item.enabled
                cbEnabled.setOnCheckedChangeListener { _, checked ->
                    item.enabled = checked
                }
                root.setOnClickListener {
                    cbEnabled.isChecked = !cbEnabled.isChecked
                }
                ivDrag.setColorFilter(ContextCompat.getColor(context, R.color.secondaryText))
            }
        }
    }
}
