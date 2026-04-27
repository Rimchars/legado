package io.legado.app.ui.main.ai

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.text.Spannable
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemAiMessageAssistantBinding
import io.legado.app.databinding.ItemAiMessageUserBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import org.json.JSONObject

class AiChatAdapter(
    private val context: Context
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<AiChatMessage>()
    private val markwon: Markwon by lazy {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
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

    private fun installSearchBookLinks(textView: TextView) {
        val spannable = textView.text as? Spannable ?: return
        val spans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        spans.forEach { span ->
            val url = span.url
            if (!url.startsWith(searchBookScheme)) return@forEach
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val flags = spannable.getSpanFlags(span)
            spannable.removeSpan(span)
            spannable.setSpan(SearchBookSpan(url), start, end, flags)
        }
    }

    private fun parseMessageContent(content: String): ParsedMessage {
        val cards = mutableListOf<SearchBookCard>()
        val visibleContent = searchResultBlockRegex.replace(content) { match ->
            runCatching {
                val payload = JSONObject(match.groupValues[1])
                val results = payload.optJSONArray("results") ?: return@runCatching
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val bookUrl = item.optString("bookUrl")
                    val origin = item.optString("origin")
                    if (bookUrl.isBlank() || origin.isBlank()) continue
                    cards += SearchBookCard(
                        name = item.optString("name").ifBlank { "未命名" },
                        author = item.optString("author"),
                        originName = item.optString("originName"),
                        kind = item.optString("kind"),
                        intro = item.optString("intro"),
                        latestChapterTitle = item.optString("latestChapterTitle"),
                        bookUrl = bookUrl,
                        origin = origin,
                        target = item.optString("target")
                    )
                }
            }
            ""
        }.trim()
        return ParsedMessage(visibleContent, cards.distinctBy { it.bookUrl })
    }

    private fun bindSearchCards(container: LinearLayout, cards: List<SearchBookCard>) {
        container.removeAllViews()
        container.isVisible = cards.isNotEmpty()
        cards.forEach { card ->
            container.addView(createSearchCardView(card))
        }
    }

    private fun createSearchCardView(card: SearchBookCard): View {
        val cardPaddingH = 13.dpToPx()
        val cardPaddingV = 10.dpToPx()
        val cardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPaddingH, cardPaddingV, cardPaddingH, cardPaddingV)
            background = GradientDrawable().apply {
                cornerRadius = 12.dpToPx().toFloat()
                setColor(ColorUtils.blendColors(context.backgroundColor, context.accentColor, 0.08f))
                setStroke(1.dpToPx(), ColorUtils.adjustAlpha(context.accentColor, 0.18f))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                SearchBookOpenHelper.open(
                    context,
                    SearchBook(
                        name = card.name,
                        author = card.author,
                        bookUrl = card.bookUrl,
                        origin = card.origin,
                        originName = card.originName
                    ),
                    card.target == "video"
                )
            }
        }
        cardView.addView(TextView(context).apply {
            text = card.name
            setTextColor(context.primaryTextColor)
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
        })
        val meta = listOf(card.author, card.originName, card.kind)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (meta.isNotBlank()) {
            cardView.addView(TextView(context).apply {
                text = meta
                setTextColor(context.secondaryTextColor)
                textSize = 12.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        val desc = card.latestChapterTitle.ifBlank { card.intro }
        if (desc.isNotBlank()) {
            cardView.addView(TextView(context).apply {
                text = desc.replace(Regex("\\s+"), " ").trim()
                setTextColor(context.secondaryTextColor)
                textSize = 12.5f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        cardView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 6.dpToPx()
        }
        return cardView
    }

    private inner class SearchBookSpan(
        private val url: String
    ) : ClickableSpan() {

        override fun onClick(widget: View) {
            val uri = Uri.parse(url)
            val isVideo = uri.getQueryParameter("target") == "video"
            val book = SearchBook(
                name = uri.getQueryParameter("name").orEmpty(),
                author = uri.getQueryParameter("author").orEmpty(),
                bookUrl = uri.getQueryParameter("bookUrl").orEmpty(),
                origin = uri.getQueryParameter("origin").orEmpty(),
                originName = uri.getQueryParameter("originName").orEmpty()
            )
            if (book.bookUrl.isBlank() || book.origin.isBlank()) return
            SearchBookOpenHelper.open(context, book, isVideo)
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.color = context.accentColor
            ds.isUnderlineText = false
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
            val parsed = parseMessageContent(message.content)
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
            binding.tvMessage.movementMethod = LinkMovementMethod.getInstance()
            binding.tvMessage.linksClickable = true
            binding.tvMessage.isVisible = parsed.content.isNotBlank()
            markwon.setMarkdown(binding.tvMessage, parsed.content.ifBlank { " " })
            installSearchBookLinks(binding.tvMessage)
            bindSearchCards(binding.searchCards, parsed.searchCards)
        }
    }

    private data class ParsedMessage(
        val content: String,
        val searchCards: List<SearchBookCard>
    )

    private data class SearchBookCard(
        val name: String,
        val author: String,
        val originName: String,
        val kind: String,
        val intro: String,
        val latestChapterTitle: String,
        val bookUrl: String,
        val origin: String,
        val target: String
    )

    private companion object {
        const val TYPE_USER = 1
        const val TYPE_ASSISTANT = 2
        const val searchBookScheme = "legado-search-book://"
        val searchResultBlockRegex = Regex(
            "```legado-search-results\\s*\\n([\\s\\S]*?)\\n```",
            setOf(RegexOption.MULTILINE)
        )
    }
}
