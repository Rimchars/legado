package io.legado.app.ui.main.explore

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.rule.ExploreKind

data class DiscoverTagItem(
    val kind: ExploreKind,
    val text: String,
    val isButton: Boolean,
    val group: String? = null,
)

class DiscoverTagAdapter(
    private val onItemClick: (index: Int, item: DiscoverTagItem) -> Unit
) : RecyclerView.Adapter<DiscoverTagAdapter.ViewHolder>() {

    private val items = mutableListOf<DiscoverTagItem>()
    private var selectedIndex = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_discover_tag_chip, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.textView.text = item.text
        holder.textView.isSelected = position == selectedIndex
        holder.textView.alpha = if (item.isButton) 0.9f else 1f
        holder.textView.setOnClickListener {
            onItemClick(position, item)
        }
    }

    fun submitList(newItems: List<DiscoverTagItem>, selected: Int) {
        items.clear()
        items.addAll(newItems)
        selectedIndex = selected.coerceIn(-1, items.lastIndex)
        notifyDataSetChanged()
    }

    fun updateSelected(index: Int) {
        val newIndex = index.coerceIn(-1, items.lastIndex)
        if (selectedIndex == newIndex) return
        val old = selectedIndex
        selectedIndex = newIndex
        if (old >= 0) notifyItemChanged(old)
        if (selectedIndex >= 0) notifyItemChanged(selectedIndex)
    }

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
