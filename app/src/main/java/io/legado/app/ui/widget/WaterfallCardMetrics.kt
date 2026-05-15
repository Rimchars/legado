package io.legado.app.ui.widget

import android.view.ViewGroup
import io.legado.app.R

object WaterfallCardMetrics {

    data class Metrics(
        val cardWidth: Int,
        val cardHeight: Int,
        val coverHeight: Int
    )

    fun resolve(parent: ViewGroup, columns: Int): Metrics {
        val resources = parent.resources
        val safeColumns = columns.coerceAtLeast(1)
        val parentWidth = parent.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val gap = resources.getDimensionPixelSize(R.dimen.waterfall_card_gap)
        val horizontalSpace = parent.paddingStart + parent.paddingEnd + safeColumns * gap
        val cardWidth = ((parentWidth - horizontalSpace) / safeColumns)
            .coerceAtLeast(resources.getDimensionPixelSize(R.dimen.waterfall_card_min_width))
        val cardHeight = (cardWidth * resources.getInteger(R.integer.waterfall_card_height_ratio_x100) / 100f).toInt()
            .coerceAtLeast(resources.getDimensionPixelSize(R.dimen.waterfall_card_min_height))
        val coverHeight = (cardWidth * resources.getInteger(R.integer.waterfall_cover_height_ratio_x100) / 100f).toInt()
            .coerceAtLeast(resources.getDimensionPixelSize(R.dimen.waterfall_cover_min_height))
        return Metrics(cardWidth, cardHeight, coverHeight)
    }
}
