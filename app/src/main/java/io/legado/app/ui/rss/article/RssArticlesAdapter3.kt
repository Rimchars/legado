package io.legado.app.ui.rss.article

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ItemRssArticle3Binding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.WaterfallCardMetrics
import io.legado.app.utils.getCompatColor

class RssArticlesAdapter3(context: Context, callBack: CallBack) :
    BaseRssArticlesAdapter<ItemRssArticle3Binding>(context, callBack) {

    private val orientation = context.resources.configuration.orientation
    private val columnCount = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

    override fun getViewBinding(parent: ViewGroup): ItemRssArticle3Binding {
        return ItemRssArticle3Binding.inflate(inflater, parent, false).apply {
            val metrics = WaterfallCardMetrics.resolve(parent, columnCount)
            root.layoutParams = (root.layoutParams ?: ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )).apply {
                height = metrics.cardHeight
            }
            imageView.layoutParams = imageView.layoutParams.apply {
                height = metrics.coverHeight
            }
            root.setCardBackgroundColor(Color.TRANSPARENT)
            root.getChildAt(0)?.let { content ->
                content.layoutParams = content.layoutParams.apply {
                    height = metrics.cardHeight
                }
                content.background = UiCorner.panelRounded(
                    root.context,
                    ContextCompat.getColor(root.context, R.color.background_card),
                    UiCorner.panelRadius(root.context)
                )
            }
        }
    }

    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssArticle3Binding,
        item: RssArticle,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            payloads.forEach { payload ->
                when (payload) {
                    "read" -> {
                        if (item.read) {
                            binding.tvTitle.setTextColor(context.getCompatColor(R.color.tv_text_summary))
                        } else {
                            binding.tvTitle.setTextColor(context.getCompatColor(R.color.primaryText))
                        }
                    }
                    "title" -> {
                        binding.tvTitle.text = item.title
                    }
                }
            }
            return
        }
        binding.run {
            tvTitle.text = item.title
            if (item.read) {
                tvTitle.setTextColor(context.getCompatColor(R.color.tv_text_summary))
            } else {
                tvTitle.setTextColor(context.getCompatColor(R.color.primaryText))
            }
            tvPubDate.text = item.pubDate
            val radius = UiCorner.panelRadius(context)
            imageView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            imageView.clipToOutline = true
            imageView.adjustViewBounds = false
            val imageUrl = item.image
            if (imageUrl.isNullOrEmpty()) {
                imageView.setImageResource(R.drawable.image_rss_article)
                return
            }
            ImageLoader.load(context, imageUrl)
                .apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, item.origin))
                .placeholder(R.drawable.transparent_placeholder)
                .centerCrop()
                .into(imageView)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssArticle3Binding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.readRss(it)
            }
        }
    }
}
