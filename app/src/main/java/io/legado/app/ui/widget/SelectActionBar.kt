package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.databinding.ViewSelectActionBarBinding
import io.legado.app.lib.theme.TintHelper
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.getSecondaryDisabledTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.visible


@Suppress("unused")
class SelectActionBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val bgIsLight = ColorUtils.isColorLight(context.bottomBackground)
    private val primaryTextColor = context.getPrimaryTextColor(bgIsLight)
    private val disabledColor = context.getSecondaryDisabledTextColor(bgIsLight)

    private var callBack: CallBack? = null
    private var selMenu: Menu? = null
    private var modernMenuPopup: PopupWindow? = null
    private var menuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private val binding = ViewSelectActionBarBinding
        .inflate(LayoutInflater.from(context), this, true)

    init {
        if (!isInEditMode) {
            val transparentNavBar = context.transparentNavBar
            if (transparentNavBar) {
                setBackgroundColor(Color.TRANSPARENT)
            } else {
                setBackgroundColor(context.bottomBackground)
                elevation = context.elevation
            }
            binding.cbSelectedAll.setTextColor(primaryTextColor)
            TintHelper.setTint(binding.cbSelectedAll, context.accentColor, !bgIsLight)
            binding.ivMenuMore.setColorFilter(disabledColor, PorterDuff.Mode.SRC_IN)
            binding.cbSelectedAll.setOnUserCheckedChangeListener { isChecked ->
                callBack?.selectAll(isChecked)
            }
            binding.btnRevertSelection.setOnClickListener { callBack?.revertSelection() }
            binding.btnSelectActionMain.setOnClickListener { callBack?.onClickSelectBarMainAction() }
            binding.ivMenuMore.setOnClickListener {
                val menu = selMenu ?: return@setOnClickListener
                modernMenuPopup = ModernActionPopup.show(
                    binding.ivMenuMore,
                    menu.visibleActions(),
                    modernMenuPopup
                )
            }
            applyNavigationBarPadding()
        }
    }

    fun setMainActionText(text: String) = binding.run {
        btnSelectActionMain.text = text
        btnSelectActionMain.visible()
    }

    fun setMainActionText(@StringRes id: Int) = binding.run {
        btnSelectActionMain.setText(id)
        btnSelectActionMain.visible()
    }

    fun inflateMenu(@MenuRes resId: Int): Menu? {
        val popupMenu = PopupMenu(context, binding.ivMenuMore)
        popupMenu.inflate(resId)
        selMenu = popupMenu.menu
        binding.ivMenuMore.visible()
        return selMenu
    }

    fun setCallBack(callBack: CallBack) {
        this.callBack = callBack
    }

    fun setOnMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener) {
        menuItemClickListener = listener
    }

    private fun Menu.visibleActions(): List<ModernActionPopup.Action> {
        val actions = mutableListOf<ModernActionPopup.Action>()
        for (index in 0 until size()) {
            val item = getItem(index)
            if (item.isVisible) {
                actions.add(
                    ModernActionPopup.Action(item.title.toString()) {
                        menuItemClickListener?.onMenuItemClick(item)
                    }
                )
            }
        }
        return actions
    }

    fun upCountView(selectCount: Int, allCount: Int) = binding.run {
        if (selectCount == 0) {
            cbSelectedAll.isChecked = false
        } else {
            cbSelectedAll.isChecked = selectCount >= allCount
        }

        //重置全选的文字
        if (cbSelectedAll.isChecked) {
            cbSelectedAll.text = context.getString(
                R.string.select_cancel_count,
                selectCount,
                allCount
            )
        } else {
            cbSelectedAll.text = context.getString(
                R.string.select_all_count,
                selectCount,
                allCount
            )
        }
        setMenuClickable(selectCount > 0)
    }

    private fun setMenuClickable(isClickable: Boolean) = binding.run {
        btnRevertSelection.isEnabled = isClickable
        btnRevertSelection.isClickable = isClickable
        btnSelectActionMain.isEnabled = isClickable
        btnSelectActionMain.isClickable = isClickable
        if (isClickable) {
            ivMenuMore.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        } else {
            ivMenuMore.setColorFilter(disabledColor, PorterDuff.Mode.SRC_IN)
        }
        ivMenuMore.isEnabled = isClickable
        ivMenuMore.isClickable = isClickable
    }

    interface CallBack {

        fun selectAll(selectAll: Boolean)

        fun revertSelection()

        fun onClickSelectBarMainAction() {}
    }
}
