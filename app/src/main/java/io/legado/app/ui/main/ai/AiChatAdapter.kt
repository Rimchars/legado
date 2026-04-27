package io.legado.app.ui.main.ai

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.ItemAiMessageAssistantBinding
import io.legado.app.databinding.ItemAiMessageUserBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin

class AiChatAdapter(
    private val context: Context
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<AiChatMessage>()
    private val markwon: Markwon by lazy {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .build()
    }

    fun submitList(list: List<AiChatMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position].role) {
            AiChatMessage.Role.USER -> TYPE_USER
            AiChatMessage.Role.ASSISTANT -> TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserViewHolder(
                ItemAiMessageUserBinding.inflate(inflater, parent, false)
            )

            else -> AssistantViewHolder(
                ItemAiMessageAssistantBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is UserViewHolder -> holder.bind(item)
            is AssistantViewHolder -> holder.bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun createBubble(
        fillColor: Int,
        strokeColor: Int,
        isUser: Boolean
    ): GradientDrawable {
        val large = 18f.dpToPx()
        val small = 6f.dpToPx()
        return GradientDrawable().apply {
            cornerRadii = if (isUser) {
                floatArrayOf(
                    large, large,
                    large, large,
                    small, small,
                    large, large
                )
            } else {
                floatArrayOf(
                    large, large,
                    large, large,
                    large, large,
                    small, small
                )
            }
            setColor(fillColor)
            setStroke(1.dpToPx(), strokeColor)
        }
    }

    private inner class UserViewHolder(
        private val binding: ItemAiMessageUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: AiChatMessage) {
            val bubbleColor = ColorUtils.blendColors(context.backgroundColor, context.accentColor, 0.18f)
            val strokeColor = ColorUtils.adjustAlpha(context.accentColor, 0.18f)
            binding.tvMessage.text = message.content
            binding.tvMessage.background = createBubble(bubbleColor, strokeColor, isUser = true)
            binding.tvMessage.setTextColor(context.primaryTextColor)
            binding.tvMessage.alpha = 1f
        }
    }

    private inner class AssistantViewHolder(
        private val binding: ItemAiMessageAssistantBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: AiChatMessage) {
            val backgroundColor = context.backgroundColor
            val bubbleColor = if (ColorUtils.isColorLight(backgroundColor)) {
                ColorUtils.blendColors(
                    backgroundColor,
                    ContextCompat.getColor(context, R.color.background_card),
                    0.68f
                )
            } else {
                ColorUtils.blendColors(
                    backgroundColor,
                    ContextCompat.getColor(context, R.color.white),
                    0.12f
                )
            }
            val strokeColor = ColorUtils.adjustAlpha(context.secondaryTextColor, 0.08f)
            binding.tvMessage.background = createBubble(bubbleColor, strokeColor, isUser = false)
            binding.tvMessage.setTextColor(context.primaryTextColor)
            binding.tvMessage.alpha = if (message.pending) 0.76f else 1f
            markwon.setMarkdown(binding.tvMessage, message.content)
        }
    }

    private companion object {
        const val TYPE_USER = 1
        const val TYPE_ASSISTANT = 2
    }
}
