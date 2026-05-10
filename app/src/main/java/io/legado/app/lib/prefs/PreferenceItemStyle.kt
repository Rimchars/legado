package io.legado.app.lib.prefs

import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.getPrefString
import java.util.WeakHashMap
import kotlin.math.roundToInt
import androidx.preference.Preference as AndroidPreference
import androidx.preference.PreferenceCategory as AndroidPreferenceCategory

object PreferenceItemStyle {

    private val itemHeights = WeakHashMap<AndroidPreference, Int>()

    fun apply(preference: AndroidPreference, holder: PreferenceViewHolder) {
        val parent = preference.parent ?: return
        val hasPrev = hasVisibleSibling(parent, preference, forward = false)
        val hasNext = hasVisibleSibling(parent, preference, forward = true)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        val itemColor = UiCorner.surfaceColor(
            ContextCompat.getColor(preference.context, R.color.background_card)
        )
        val pressedColor = UiCorner.surfaceColor(
            ContextCompat.getColor(preference.context, R.color.background_card),
            pressed = true
        )
        val dividerColor = ContextCompat.getColor(preference.context, R.color.bg_divider_line)
        val radius = UiCorner.panelRadius(preference.context)
        val dividerInset = holder.itemView.dp(16).toFloat()
        val imageKey = panelImageKey(preference)
        val groupHeight = estimateGroupHeight(holder.itemView, parent)
        val offsetY = estimateOffsetY(holder.itemView, preference, parent)
        val panelImage = buildPanelImage(holder.itemView, preference, parent, radius, groupHeight, offsetY)
        val current = holder.itemView.background as? PreferenceGroupBackgroundDrawable
        if (current == null || !current.hasSameConfig(
                normalColor = itemColor,
                pressedColor = pressedColor,
                dividerColor = dividerColor,
                radius = radius,
                hasPrev = hasPrev,
                hasNext = hasNext,
                dividerInset = dividerInset,
                panelImageKey = imageKey,
                groupHeight = groupHeight,
                offsetY = offsetY
            )
        ) {
            holder.itemView.background = PreferenceGroupBackgroundDrawable(
                normalColor = itemColor,
                pressedColor = pressedColor,
                dividerColor = dividerColor,
                radius = radius,
                hasPrev = hasPrev,
                hasNext = hasNext,
                dividerInset = dividerInset,
                panelImage = panelImage,
                panelImageKey = imageKey,
                groupHeight = groupHeight,
                offsetY = offsetY
            )
        }
        holder.itemView.updateGroupMargins(!hasPrev, !hasNext, parent)
        holder.itemView.post {
            val height = holder.itemView.height
            if (height > 0 && itemHeights[preference] != height) {
                itemHeights[preference] = height
                holder.itemView.background = null
                apply(preference, holder)
            }
        }
    }

    private fun hasVisibleSibling(
        parent: PreferenceGroup,
        preference: AndroidPreference,
        forward: Boolean
    ): Boolean {
        val index = parent.indexOf(preference)
        if (index == -1) return false
        val range = if (forward) {
            (index + 1) until parent.preferenceCount
        } else {
            (index - 1) downTo 0
        }
        for (i in range) {
            val sibling = parent.getPreference(i)
            if (!sibling.isVisible) continue
            if (sibling is AndroidPreferenceCategory) return false
            return true
        }
        return false
    }

    private fun PreferenceGroup.indexOf(preference: AndroidPreference): Int {
        for (i in 0 until preferenceCount) {
            if (getPreference(i) == preference) return i
        }
        return -1
    }

    private fun View.updateGroupMargins(
        isFirst: Boolean,
        isLast: Boolean,
        parent: PreferenceGroup
    ) {
        val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val horizontal = dp(12)
        val edge = if (parent is AndroidPreferenceCategory) 0 else dp(8)
        val top = if (isFirst) edge else 0
        val bottom = if (isLast) dp(8) else 0
        if (
            lp.leftMargin != horizontal ||
            lp.rightMargin != horizontal ||
            lp.topMargin != top ||
            lp.bottomMargin != bottom
        ) {
            lp.setMargins(horizontal, top, horizontal, bottom)
            layoutParams = lp
        }
    }

    private fun View.dp(value: Int): Int {
        return (resources.displayMetrics.density * value).roundToInt()
    }

    private fun panelImageKey(preference: AndroidPreference): String {
        val path = preference.context.getPrefString(
            if (AppConfig.isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage
        ).orEmpty()
        val mode = preference.context.getPrefString(
            if (AppConfig.isNightTheme) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType
        ).orEmpty()
        return "$path|$mode"
    }

    private fun buildPanelImage(
        itemView: View,
        preference: AndroidPreference,
        parent: PreferenceGroup,
        radius: Float,
        groupHeight: Int,
        offsetY: Int
    ): android.graphics.drawable.Drawable? {
        val path = preference.context.getPrefString(
            if (AppConfig.isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage
        )
        val bitmap = UiCorner.loadPanelBitmap(path) ?: return null
        val mode = preference.context.getPrefString(
            if (AppConfig.isNightTheme) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType
        )
        return GroupPanelImageDrawable(
            bitmap = bitmap,
            mode = mode ?: ThemeConfig.PANEL_BG_CROP,
            groupHeight = groupHeight,
            offsetY = offsetY,
            topRadius = if (hasVisibleSibling(parent, preference, forward = false)) 0f else radius,
            bottomRadius = if (hasVisibleSibling(parent, preference, forward = true)) 0f else radius
        )
    }

    private fun estimateGroupHeight(itemView: View, parent: PreferenceGroup): Int {
        var height = 0
        val fallback = itemView.height
            .coerceAtLeast(itemView.measuredHeight)
            .coerceAtLeast(itemView.dp(60))
        for (i in 0 until parent.preferenceCount) {
            val sibling = parent.getPreference(i)
            if (!sibling.isVisible || sibling is AndroidPreferenceCategory) continue
            height += itemHeights[sibling]?.takeIf { it > 0 } ?: fallback
        }
        return height.coerceAtLeast(fallback)
    }

    private fun estimateOffsetY(
        itemView: View,
        preference: AndroidPreference,
        parent: PreferenceGroup
    ): Int {
        var offset = 0
        val fallback = itemView.height
            .coerceAtLeast(itemView.measuredHeight)
            .coerceAtLeast(itemView.dp(60))
        for (i in 0 until parent.preferenceCount) {
            val sibling = parent.getPreference(i)
            if (!sibling.isVisible) continue
            if (sibling is AndroidPreferenceCategory) continue
            if (sibling == preference) break
            offset += itemHeights[sibling]?.takeIf { it > 0 } ?: fallback
        }
        return offset
    }

}
