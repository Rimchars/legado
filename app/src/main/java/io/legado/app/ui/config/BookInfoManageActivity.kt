package io.legado.app.ui.config

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.ItemReadRecordComponentBinding
import io.legado.app.help.config.BookInfoComponentConfig
import io.legado.app.help.config.BookInfoComponentItem
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BookInfoManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)
    private val adapter = ComponentAdapter()

    override fun onActivityCreated(savedInstanceState: android.os.Bundle?) {
        binding.titleBar.title = getString(R.string.book_info_manage)
        binding.root.applyUiBodyTypefaceDeep(uiTypeface())
        binding.tabBar.visibility = View.GONE
        binding.tvSummary.text = getString(R.string.book_info_components_hint)
        binding.btnAdd.text = getString(R.string.reset)
        binding.btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this, R.color.background_card),
            ContextCompat.getColor(this, R.color.background_menu),
            UiCorner.actionRadius(this)
        )
        binding.btnAdd.setOnClickListener {
            BookInfoComponentConfig.reset()
            adapter.reload()
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        ItemTouchHelper(ItemTouchCallback(adapter).apply {
            isCanDrag = true
        }).attachToRecyclerView(binding.recyclerView)
    }

    private inner class ComponentAdapter :
        RecyclerView.Adapter<ComponentAdapter.Holder>(),
        ItemTouchCallback.Callback {

        private val pressedColor by lazy { ContextCompat.getColor(this@BookInfoManageActivity, R.color.background_menu) }
        private val items = mutableListOf<BookInfoComponentItem>()

        init {
            reload()
        }

        fun reload() {
            items.clear()
            items.addAll(BookInfoComponentConfig.load())
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                ItemReadRecordComponentBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
            if (srcPosition !in items.indices || targetPosition !in items.indices) return false
            val item = items.removeAt(srcPosition)
            items.add(targetPosition, item)
            notifyItemMoved(srcPosition, targetPosition)
            return true
        }

        override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            save()
        }

        private fun save() {
            BookInfoComponentConfig.save(items)
        }

        inner class Holder(
            private val itemBinding: ItemReadRecordComponentBinding
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(item: BookInfoComponentItem) = itemBinding.run {
                root.background = android.graphics.drawable.StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        UiCorner.opaqueRounded(pressedColor, UiCorner.panelRadius(this@BookInfoManageActivity))
                    )
                    addState(
                        intArrayOf(),
                        UiCorner.panelRounded(
                            this@BookInfoManageActivity,
                            ContextCompat.getColor(this@BookInfoManageActivity, R.color.background_card),
                            UiCorner.panelRadius(this@BookInfoManageActivity)
                        )
                    )
                }
                tvTitle.text = getString(item.type.titleRes)
                tvSubtitle.text = getString(item.type.hintRes)
                tvTitle.applyUiSectionTitleStyle(this@BookInfoManageActivity)
                tvSubtitle.applyUiLabelStyle(this@BookInfoManageActivity)
                cbEnabled.setOnCheckedChangeListener(null)
                cbEnabled.isChecked = item.enabled
                cbEnabled.setOnCheckedChangeListener { _, checked ->
                    if (!checked && items.count { it.enabled } <= 1) {
                        cbEnabled.isChecked = true
                        toastOnUi(R.string.book_info_component_keep_one)
                        return@setOnCheckedChangeListener
                    }
                    item.enabled = checked
                    save()
                }
                root.setOnClickListener {
                    cbEnabled.isChecked = !cbEnabled.isChecked
                }
                ivDrag.setColorFilter(ContextCompat.getColor(this@BookInfoManageActivity, R.color.secondaryText))
                cbEnabled.buttonTintList = android.content.res.ColorStateList.valueOf(accentColor)
            }
        }
    }
}
