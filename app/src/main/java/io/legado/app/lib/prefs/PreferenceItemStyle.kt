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
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt
import androidx.preference.Preference as AndroidPreference
import androidx.preference.PreferenceCategory as AndroidPreferenceCategory

object PreferenceItemStyle {

    private val itemHeights = WeakHashMap<AndroidPreference, Int>()
    private val itemViews = WeakHashMap<AndroidPreference, WeakReference<View>>()
    private val pendingGroupRefresh = WeakHashMap<PreferenceGroup, Boolean>()

    fun apply(preference: AndroidPreference, holder: PreferenceViewHolder) {
        val parent = preference.parent ?: return
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        itemViews[preference] = WeakReference(holder.itemView)
        holder.itemView.recordHeightIfReady(preference, parent)
        applyBackground(preference, holder.itemView, parent)
        holder.itemView.post {
            val height = holder.itemView.realHeight()
            if (height > 0 && itemHeights[preference] != height) {
                itemHeights[preference] = height
                scheduleGroupRefresh(holder.itemView, parent)
            }
        }
    }

    private fun applyBackground(
        preference: AndroidPreference,
        itemView: View,
        parent: PreferenceGroup
    ) {
        val hasPrev = hasVisibleSibling(parent, preference, forward = false)
        val hasNext = hasVisibleSibling(parent, preference, forward = true)
        val itemColor = UiCorner.surfaceColor(
            ContextCompat.getColor(preference.context, R.color.background_card)
        )
        val pressedColor = UiCorner.surfaceColor(
            ContextCompat.getColor(preference.context, R.color.background_card),
            pressed = true
        )
        val dividerColor = ContextCompat.getColor(preference.context, R.color.bg_divider_line)
        val radius = UiCorner.panelRadius(preference.context)
        val dividerInset = itemView.dp(16).toFloat()
        val panel = buildPanelImage(itemView, preference, parent, radius)
        val imageKey = panelImageKey(preference)
        val groupHeight = panel?.groupHeight ?: 0
        val offsetY = panel?.offsetY ?: 0
        val borderColor = UiCorner.panelBorderColor(preference.context)
        val borderWidth = itemView.dp(1).toFloat()
        val current = itemView.background as? PreferenceGroupBackgroundDrawable
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
                offsetY = offsetY,
                borderColor = borderColor,
                borderWidth = borderWidth
            )
        ) {
            itemView.background = PreferenceGroupBackgroundDrawable(
                normalColor = itemColor,
                pressedColor = pressedColor,
                dividerColor = dividerColor,
                radius = radius,
                hasPrev = hasPrev,
                hasNext = hasNext,
                dividerInset = dividerInset,
                panelImage = panel?.drawable,
                panelImageKey = imageKey,
                groupHeight = groupHeight,
                offsetY = offsetY,
                borderColor = borderColor,
                borderWidth = borderWidth
            )
        }
        itemView.updateGroupMargins(!hasPrev, !hasNext, parent)
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

    private fun visibleGroupMembers(
        parent: PreferenceGroup,
        preference: AndroidPreference
    ): List<AndroidPreference> {
        val index = parent.indexOf(preference)
        if (index == -1) return emptyList()
        var start = index
        while (start > 0) {
            val prev = parent.getPreference(start - 1)
            if (prev.isVisible && prev is AndroidPreferenceCategory) break
            start--
        }
        var end = index
        while (end + 1 < parent.preferenceCount) {
            val next = parent.getPreference(end + 1)
            if (next.isVisible && next is AndroidPreferenceCategory) break
            end++
        }
        val result = ArrayList<AndroidPreference>()
        for (i in start..end) {
            val sibling = parent.getPreference(i)
            if (sibling.isVisible && sibling !is AndroidPreferenceCategory) {
                result.add(sibling)
            }
        }
        return result
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

    private fun View.realHeight(): Int {
        return height.coerceAtLeast(measuredHeight)
    }

    private fun View.recordHeightIfReady(
        preference: AndroidPreference,
        parent: PreferenceGroup
    ) {
        val height = realHeight()
        if (height > 0 && itemHeights[preference] != height) {
            itemHeights[preference] = height
            scheduleGroupRefresh(this, parent)
        }
    }

    private fun scheduleGroupRefresh(anchor: View, parent: PreferenceGroup) {
        if (pendingGroupRefresh[parent] == true) return
        pendingGroupRefresh[parent] = true
        anchor.post {
            pendingGroupRefresh.remove(parent)
            refreshVisibleGroup(parent)
        }
    }

    private fun refreshVisibleGroup(parent: PreferenceGroup) {
        for (i in 0 until parent.preferenceCount) {
            val preference = parent.getPreference(i)
            if (!preference.isVisible || preference is AndroidPreferenceCategory) continue
            val view = itemViews[preference]?.get() ?: continue
            applyBackground(preference, view, parent)
        }
    }

    private fun panelImageKey(preference: AndroidPreference): String {
        val path = preference.context.getPrefString(
            if (AppConfig.isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage
        ).orEmpty()
        val mode = preference.context.getPrefString(
            if (AppConfig.isNightTheme) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType
        ).orEmpty()
        val fileKey = path.takeUnless { it.isBlank() || it.startsWith("http", ignoreCase = true) }
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
            ?: path
        return "$fileKey|$mode|${UiCorner.layoutAlpha()}"
    }

    private fun buildPanelImage(
        itemView: View,
        preference: AndroidPreference,
        parent: PreferenceGroup,
        radius: Float
    ): PanelImage? {
        val path = preference.context.getPrefString(
            if (AppConfig.isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage
        )
        val bitmap = UiCorner.loadPanelBitmap(path) ?: return null
        val mode = preference.context.getPrefString(
            if (AppConfig.isNightTheme) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType
        ) ?: ThemeConfig.PANEL_BG_CROP
        val members = visibleGroupMembers(parent, preference)
        if (members.isEmpty()) return null
        var groupHeight = 0
        var offsetY = 0
        var reachedCurrent = false
        val fallbackHeight = itemView.dp(60)
        for (member in members) {
            val height = itemHeights[member]
                ?: if (member == preference) {
                    itemView.realHeight().takeIf { it > 0 } ?: return null
                } else {
                    fallbackHeight
                }
            if (member == preference) {
                reachedCurrent = true
            } else if (!reachedCurrent) {
                offsetY += height
            }
            groupHeight += height
        }
        if (groupHeight <= 0) return null
        return PanelImage(
            drawable = GroupPanelImageDrawable(
                bitmap = bitmap,
                mode = mode,
                groupHeight = groupHeight,
                offsetY = offsetY,
                topRadius = if (hasVisibleSibling(parent, preference, forward = false)) 0f else radius,
                bottomRadius = if (hasVisibleSibling(parent, preference, forward = true)) 0f else radius
            ),
            groupHeight = groupHeight,
            offsetY = offsetY
        )
    }

    private data class PanelImage(
        val drawable: android.graphics.drawable.Drawable,
        val groupHeight: Int,
        val offsetY: Int
    )
}
