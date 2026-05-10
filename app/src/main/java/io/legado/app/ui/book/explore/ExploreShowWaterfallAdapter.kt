package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemSearchWaterfallBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.visible

class ExploreShowWaterfallAdapter(
    context: Context,
    private val callBack: ExploreShowAdapter.CallBack,
    private val columns: Int
) : RecyclerAdapter<SearchBook, ItemSearchWaterfallBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemSearchWaterfallBinding {
        return ItemSearchWaterfallBinding.inflate(inflater, parent, false).apply {
            val parentWidth = parent.width.takeIf { it > 0 }
                ?: parent.resources.displayMetrics.widthPixels
            val horizontalSpace = parent.paddingStart + parent.paddingEnd + columns * 8.dpToPx()
            val cardWidth = ((parentWidth - horizontalSpace) / columns).coerceAtLeast(82.dpToPx())
            val cardHeight = (cardWidth * 2.08f).toInt().coerceAtLeast(260.dpToPx())
            root.layoutParams = (root.layoutParams ?: ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )).apply {
                height = cardHeight
            }
            ivCover.layoutParams = ivCover.layoutParams.apply {
                height = (cardWidth * 1.36f).toInt().coerceAtLeast(108.dpToPx())
            }
            root.background = UiCorner.panelRounded(
                root.context,
                ContextCompat.getColor(root.context, R.color.background_card),
                UiCorner.panelRadius(root.context)
            )
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSearchWaterfallBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            payloads.forEach { payload ->
                val bundle = payload as? Bundle ?: return@forEach
                if (bundle.containsKey("isInBookshelf")) {
                    binding.ivInBookshelf.isVisible = callBack.isInBookshelf(item)
                }
            }
            return
        }
        binding.run {
            tvName.text = item.name
            tvAuthor.text = context.getString(R.string.author_show, item.author)
            ivInBookshelf.isVisible = callBack.isInBookshelf(item)
            if (item.latestChapterTitle.isNullOrEmpty()) {
                tvLasted.gone()
            } else {
                tvLasted.text = context.getString(R.string.lasted_show, item.latestChapterTitle)
                tvLasted.visible()
            }
            tvIntroduce.text = item.trimIntro(context)
            val kinds = item.getKindList()
            if (kinds.isEmpty()) {
                llKind.gone()
            } else {
                llKind.visible()
                llKind.setLabels(kinds.take(4))
            }
            ivCover.load(item, AppConfig.loadCoverOnlyWifi)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSearchWaterfallBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                callBack.showBookInfo(it)
            }
        }
    }

    override fun onViewAttachedToWindow(holder: ItemViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder.itemViewType < 0 || holder.itemViewType >= TYPE_FOOTER_VIEW) {
            (holder.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan = true
        }
    }
}
