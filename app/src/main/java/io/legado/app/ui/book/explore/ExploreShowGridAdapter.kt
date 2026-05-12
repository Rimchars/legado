package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemBookshelfGrid2Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.applyUiTitleTypeface

class ExploreShowGridAdapter(
    context: Context,
    private val callBack: ExploreShowAdapter.CallBack,
    private val spanCount: Int = 3
) : RecyclerAdapter<SearchBook, ItemBookshelfGrid2Binding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfGrid2Binding {
        return ItemBookshelfGrid2Binding.inflate(inflater, parent, false).apply {
            tvName.applyUiTitleTypeface(context)
            bvUnread.isVisible = false
            rlLoading.isVisible = false
        }
    }

    override fun getSpanSize(viewType: Int, position: Int): Int {
        return if (viewType >= TYPE_FOOTER_VIEW) spanCount else 1
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfGrid2Binding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            payloads.forEach { payload ->
                val bundle = payload as? Bundle ?: return@forEach
                if (bundle.containsKey("isInBookshelf")) {
                    binding.bvUnread.isVisible = callBack.isInBookshelf(item)
                }
            }
            return
        }
        binding.run {
            tvName.text = item.name
            bvUnread.isVisible = callBack.isInBookshelf(item)
            rlLoading.isVisible = false
            ivCover.load(item, AppConfig.loadCoverOnlyWifi)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfGrid2Binding) {
        holder.itemView.setOnClickListener {
            getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                callBack.showBookInfo(it)
            }
        }
    }
}