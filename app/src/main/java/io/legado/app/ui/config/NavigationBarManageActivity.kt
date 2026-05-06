package io.legado.app.ui.config

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class NavigationBarManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val adapter = Adapter()
    private var isNightMode = false
    private var pendingRequest: IconRequest? = null
    private val selectIcon = registerForActivityResult(HandleFileContract()) { result ->
        val request = pendingRequest?.takeIf { it.code == result.requestCode } ?: return@registerForActivityResult
        val uri = result.uri ?: return@registerForActivityResult
        val targetSize = resources.getDimensionPixelSize(R.dimen.main_bottom_nav_icon_size)
        val success = NavigationBarIconConfig.saveIcon(
            this,
            uri,
            request.mode,
            request.item.key,
            request.selected,
            targetSize
        )
        if (success) {
            adapter.notifyDataSetChanged()
            toastOnUi(R.string.success)
        } else {
            toastOnUi(R.string.navigation_icon_decode_failed)
        }
        pendingRequest = null
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.navigation_bar_manage)
        initView()
    }

    private fun initView() = binding.run {
        tabBar.background = UiCorner.opaqueRounded(
            ContextCompat.getColor(this@NavigationBarManageActivity, R.color.background_menu),
            UiCorner.panelRadius(this@NavigationBarManageActivity)
        )
        listOf(btnDay, btnNight).forEach {
            it.background = UiCorner.actionSelector(
                android.graphics.Color.TRANSPARENT,
                ContextCompat.getColor(this@NavigationBarManageActivity, R.color.background_card),
                UiCorner.actionRadius(this@NavigationBarManageActivity)
            )
        }
        btnAdd.text = getString(R.string.restore_default)
        btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this@NavigationBarManageActivity, R.color.background_card),
            ContextCompat.getColor(this@NavigationBarManageActivity, R.color.background_menu),
            UiCorner.actionRadius(this@NavigationBarManageActivity)
        )
        btnAdd.setOnClickListener {
            NavigationBarIconConfig.clearMode(currentMode())
            adapter.notifyDataSetChanged()
            toastOnUi(R.string.success)
        }
        tvSummary.text = getString(R.string.navigation_bar_manage_summary)
        recyclerView.layoutManager = LinearLayoutManager(this@NavigationBarManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        btnDay.setOnClickListener {
            if (isNightMode) {
                isNightMode = false
                updateTabs()
                adapter.notifyDataSetChanged()
            }
        }
        btnNight.setOnClickListener {
            if (!isNightMode) {
                isNightMode = true
                updateTabs()
                adapter.notifyDataSetChanged()
            }
        }
        updateTabs()
    }

    private fun updateTabs() = binding.run {
        btnDay.isSelected = !isNightMode
        btnNight.isSelected = isNightMode
        btnDay.setTextColor(if (!isNightMode) accentColor else primaryTextColor)
        btnNight.setTextColor(if (isNightMode) accentColor else primaryTextColor)
    }

    private fun currentMode(): String {
        return if (isNightMode) NavigationBarIconConfig.MODE_NIGHT else NavigationBarIconConfig.MODE_DAY
    }

    private fun showItemActions(item: NavigationBarIconConfig.NavItem) {
        selector(
            getString(item.titleRes),
            listOf(
                getString(R.string.navigation_icon_set_normal),
                getString(R.string.navigation_icon_set_selected),
                getString(R.string.navigation_icon_clear_normal),
                getString(R.string.navigation_icon_clear_selected)
            )
        ) { _, index ->
            when (index) {
                0 -> launchIconPicker(item, selected = false)
                1 -> launchIconPicker(item, selected = true)
                2 -> {
                    NavigationBarIconConfig.clearIcon(currentMode(), item.key, selected = false)
                    adapter.notifyDataSetChanged()
                }
                3 -> {
                    NavigationBarIconConfig.clearIcon(currentMode(), item.key, selected = true)
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun launchIconPicker(item: NavigationBarIconConfig.NavItem, selected: Boolean) {
        val code = NavigationBarIconConfig.items.indexOf(item) * 2 + if (selected) 1 else 0
        val request = IconRequest(code, currentMode(), item, selected)
        pendingRequest = request
        selectIcon.launch {
            mode = HandleFileContract.FILE
            requestCode = request.code
            title = getString(R.string.navigation_icon_select_file)
            allowExtensions = arrayOf("ico", "svg", "png", "jpg", "jpeg")
        }
    }

    private data class IconRequest(
        val code: Int,
        val mode: String,
        val item: NavigationBarIconConfig.NavItem,
        val selected: Boolean
    )

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
                background = UiCorner.opaqueRounded(
                    ContextCompat.getColor(parent.context, R.color.background_card),
                    UiCorner.panelRadius(parent.context)
                )
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 10.dpToPx()
                }
            }
            val title = TextView(parent.context).apply {
                textSize = 16f
                setTextColor(primaryTextColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val (normalWrap, normal) = iconPreview(parent.context.getString(R.string.navigation_icon_normal))
            val (selectedWrap, selected) = iconPreview(parent.context.getString(R.string.navigation_icon_selected))
            root.addView(title)
            root.addView(normalWrap)
            root.addView(selectedWrap)
            return Holder(root, title, normal, selected)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = NavigationBarIconConfig.items[position]
            holder.title.setText(item.titleRes)
            holder.normal.setImageDrawable(
                NavigationBarIconConfig.previewDrawable(
                    this@NavigationBarManageActivity,
                    currentMode(),
                    item,
                    selected = false
                )
            )
            holder.selected.setImageDrawable(
                NavigationBarIconConfig.previewDrawable(
                    this@NavigationBarManageActivity,
                    currentMode(),
                    item,
                    selected = true
                )
            )
            holder.itemView.setOnClickListener { showItemActions(item) }
            holder.normal.setOnClickListener { launchIconPicker(item, selected = false) }
            holder.selected.setOnClickListener { launchIconPicker(item, selected = true) }
        }

        override fun getItemCount(): Int = NavigationBarIconConfig.items.size

        private fun iconPreview(labelText: String): Pair<LinearLayout, ImageView> {
            val icon = ImageView(this@NavigationBarManageActivity).apply {
                contentDescription = labelText
                setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = UiCorner.actionSelector(
                    ContextCompat.getColor(context, R.color.background_menu),
                    ContextCompat.getColor(context, R.color.background_card),
                    UiCorner.actionRadius(context)
                )
                layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx())
            }
            val label = TextView(this@NavigationBarManageActivity).apply {
                text = labelText
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(secondaryTextColor)
            }
            val wrapper = LinearLayout(this@NavigationBarManageActivity).apply {
                contentDescription = labelText
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(56.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = 8.dpToPx()
                }
                addView(icon)
                addView(label)
            }
            return wrapper to icon
        }
    }

    private class Holder(
        itemView: View,
        val title: TextView,
        val normal: ImageView,
        val selected: ImageView
    ) : RecyclerView.ViewHolder(itemView)
}
