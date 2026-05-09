package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.MenuRes
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiMenuItemTypeface
import io.legado.app.utils.dpToPx

object ModernActionPopup {

    data class Action(
        val title: String,
        val invoke: () -> Unit
    )

    fun show(
        anchor: View,
        actions: List<Action>,
        previousPopup: PopupWindow? = null
    ): PopupWindow? {
        if (actions.isEmpty()) return previousPopup
        val context = anchor.context
        var popup: PopupWindow? = null
        val content = createContent(context, actions) {
            popup?.dismiss()
        }
        previousPopup?.dismiss()
        val popupSize = measurePopupSize(anchor, content)
        popup = PopupWindow(
            content,
            popupSize.first,
            popupSize.second,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 0f
            }
            showAnchored(anchor, content)
        }
        return popup
    }

    fun showFromMenu(
        anchor: View,
        @MenuRes menuRes: Int,
        previousPopup: PopupWindow? = null,
        prepare: (Menu.() -> Unit)? = null,
        onClick: (MenuItem) -> Boolean
    ): PopupWindow? {
        val popupMenu = PopupMenu(anchor.context, anchor)
        popupMenu.inflate(menuRes)
        prepare?.invoke(popupMenu.menu)
        val actions = mutableListOf<Action>()
        for (index in 0 until popupMenu.menu.size()) {
            val item = popupMenu.menu.getItem(index)
            if (item.isVisible) {
                actions.add(Action(item.title.toString()) { onClick(item) })
            }
        }
        return show(anchor, actions, previousPopup)
    }

    private fun createContent(
        context: Context,
        actions: List<Action>,
        dismiss: () -> Unit
    ): ScrollView {
        val textColor = ContextCompat.getColor(context, R.color.primaryText)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 6.dpToPx())
            actions.forEach { action ->
                addView(createItem(context, action, textColor, dismiss))
            }
        }
        return ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = UiCorner.opaqueRounded(
                ContextCompat.getColor(context, R.color.background_card),
                UiCorner.panelRadius(context)
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clipToOutline = true
            }
            addView(
                list,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun LinearLayout.createItem(
        context: Context,
        action: Action,
        textColor: Int,
        dismiss: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = action.title
            gravity = Gravity.CENTER_VERTICAL
            minWidth = 132.dpToPx()
            minHeight = 42.dpToPx()
            setTextColor(textColor)
            textSize = 14f
            applyUiMenuItemTypeface(context)
            includeFontPadding = false
            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
            background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                ContextCompat.getColor(context, R.color.background_menu),
                UiCorner.actionRadius(context)
            )
            setOnClickListener {
                dismiss()
                action.invoke()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                42.dpToPx()
            ).apply {
                setMargins(0, 1.dpToPx(), 0, 1.dpToPx())
            }
        }
    }

    private fun measurePopupSize(anchor: View, content: View): Pair<Int, Int> {
        val gap = 8.dpToPx()
        val visibleFrame = Rect()
        anchor.rootView.getWindowVisibleDisplayFrame(visibleFrame)
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val maxHeight = ((visibleFrame.height() - gap * 2) * 0.62f).toInt()
            .coerceAtLeast(160.dpToPx())
        return content.measuredWidth to content.measuredHeight.coerceAtMost(maxHeight)
    }

    private fun PopupWindow.showAnchored(anchor: View, content: View) {
        val gap = 4.dpToPx()
        val location = IntArray(2)
        val visibleFrame = Rect()
        anchor.getLocationOnScreen(location)
        anchor.rootView.getWindowVisibleDisplayFrame(visibleFrame)

        val popupWidth = width
        val popupHeight = height
        val desiredX = location[0] + anchor.width - popupWidth
        val x = desiredX.coerceIn(
            visibleFrame.left + gap,
            (visibleFrame.right - popupWidth - gap).coerceAtLeast(visibleFrame.left + gap)
        )
        val belowY = location[1] + anchor.height + gap
        val aboveY = location[1] - popupHeight - gap
        val hasRoomBelow = belowY + popupHeight <= visibleFrame.bottom - gap
        val y = if (hasRoomBelow || aboveY < visibleFrame.top + gap) {
            belowY
        } else {
            aboveY
        }.coerceIn(
            visibleFrame.top + gap,
            (visibleFrame.bottom - popupHeight - gap).coerceAtLeast(visibleFrame.top + gap)
        )
        showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, x, y)
    }
}
