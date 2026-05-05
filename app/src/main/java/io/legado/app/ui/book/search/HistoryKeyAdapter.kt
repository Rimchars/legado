package io.legado.app.ui.book.search

import android.view.ViewGroup
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.databinding.ItemSearchHistoryChipBinding
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.anima.explosion_field.ExplosionField
import splitties.views.onLongClick

class HistoryKeyAdapter(activity: SearchActivity, val callBack: CallBack) :
    RecyclerAdapter<SearchKeyword, ItemSearchHistoryChipBinding>(activity) {

    private val explosionField = ExplosionField.attach2Window(activity)

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getViewBinding(parent: ViewGroup): ItemSearchHistoryChipBinding {
        return ItemSearchHistoryChipBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSearchHistoryChipBinding,
        item: SearchKeyword,
        payloads: MutableList<Any>
    ) {
        binding.run {
            textView.text = item.word
            textView.background = UiCorner.actionSelector(
                ContextCompat.getColor(context, R.color.background_card),
                ContextCompat.getColor(context, R.color.background_menu),
                UiCorner.searchRadius(14f)
            )
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSearchHistoryChipBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    callBack.searchHistory(it.word)
                }
            }
            onLongClick {
                explosionField.explode(this, true)
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    callBack.deleteHistory(it)
                }
            }
        }
    }

    interface CallBack {
        fun searchHistory(key: String)
        fun deleteHistory(searchKeyword: SearchKeyword)
    }
}
