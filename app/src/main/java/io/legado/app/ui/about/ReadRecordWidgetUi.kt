package io.legado.app.ui.about

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemReadRecordCoverBinding
import io.legado.app.databinding.ItemReadRecordRankBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiInputStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.applyUiSubtleButtonStyle
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.BookCover
import io.legado.app.help.glide.ImageLoader
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi

fun Context.openReadRecordBook(
    book: io.legado.app.data.entities.Book?,
    fallbackName: String? = null
) {
    if (book == null) {
        fallbackName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            SearchActivity.start(this, it)
            return
        }
        toastOnUi(getString(R.string.read_record_goal_open_missing))
        return
    }
    startActivityForBook(book)
}

fun Context.openReadRecordBookInfo(
    book: Book?,
    fallbackName: String? = null
) {
    if (book == null) {
        fallbackName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            SearchActivity.start(this, it)
            return
        }
        toastOnUi(getString(R.string.read_record_goal_open_missing))
        return
    }
    startActivity(
        Intent(this, BookInfoActivity::class.java).apply {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
            putExtra("origin", book.origin)
            putExtra("originName", book.originName)
        }
    )
}

fun Context.showReadRecordBookActionDialog(
    title: String,
    book: Book?,
    fallbackName: String? = null,
    onDeleteRecord: () -> Unit
) {
    alert(title) {
        items(
            listOf(
                getString(R.string.read_record_open_book_info),
                getString(R.string.read_record_delete_entry)
            )
        ) { _, _, index ->
            when (index) {
                0 -> openReadRecordBookInfo(book, fallbackName)
                1 -> onDeleteRecord()
            }
        }
    }
}

fun ImageView.loadReadRecordCover(path: String?) {
    BookCover.load(context, path).into(this)
}

fun ImageView.loadReadRecordAvatar(path: String?) {
    ImageLoader.load(context, path)
        .placeholder(R.drawable.ic_read_record_default_avatar)
        .error(R.drawable.ic_read_record_default_avatar)
        .centerCrop()
        .into(this)
}

class ReadRecordCoverAdapter(
    private val context: Context,
    private val items: List<ReadRecentVisualItem>,
    private val onClick: (ReadRecentVisualItem) -> Unit,
    private val onLongClick: ((ReadRecentVisualItem) -> Unit)? = null
) : RecyclerView.Adapter<ReadRecordCoverAdapter.CoverHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CoverHolder {
        return CoverHolder(
            ItemReadRecordCoverBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: CoverHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class CoverHolder(private val binding: ItemReadRecordCoverBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ReadRecentVisualItem) {
            binding.ivCover.loadReadRecordCover(item.snapshot.displayCover())
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick?.invoke(item)
                onLongClick != null
            }
            binding.root.alpha = if (item.book == null) 0.72f else 1f
        }
    }
}

class ReadRecordRankAdapter(
    private val context: Context,
    private val items: List<ReadRecordRankItem>,
    private val formatDuring: (Long) -> String,
    private val onClick: (ReadRecordRankItem) -> Unit,
    private val onLongClick: ((ReadRecordRankItem) -> Unit)? = null
) : RecyclerView.Adapter<ReadRecordRankAdapter.RankHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RankHolder {
        return RankHolder(
            ItemReadRecordRankBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RankHolder, position: Int) {
        holder.bind(items[position], position)
    }

    inner class RankHolder(private val binding: ItemReadRecordRankBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ReadRecordRankItem, position: Int) {
            val name = item.book?.name ?: item.snapshot?.name ?: item.displayName
            val author = item.book?.author ?: item.snapshot?.author ?: item.displayAuthor
            binding.tvName.text = name
            binding.tvMeta.text = if (author.isBlank()) {
                context.getString(R.string.read_record_rank_number, position + 1)
            } else {
                "${position + 1}. $author"
            }
            binding.tvTime.text = formatDuring(item.readTime)
            binding.tvName.typeface = context.uiTypeface()
            binding.tvMeta.typeface = context.uiTypeface()
            binding.tvTime.typeface = context.uiTypeface()
            binding.ivCover.loadReadRecordCover(
                item.book?.getDisplayCover() ?: item.snapshot?.displayCover()
            )
            binding.root.alpha = if (item.book == null) 0.72f else 1f
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick?.invoke(item)
                onLongClick != null
            }
        }
    }
}

object ReadRecordRankDialog {
    fun show(
        context: Context,
        items: List<ReadRecordRankItem>,
        formatDuring: (Long) -> String,
        onDeleteRecord: ((ReadRecordRankItem) -> Unit)? = null
    ) {
        val rankItems = items.toMutableList()
        lateinit var rankAdapter: ReadRecordRankAdapter
        val recyclerView = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            rankAdapter = ReadRecordRankAdapter(
                context = context,
                items = rankItems,
                formatDuring = formatDuring,
                onClick = {
                    context.openReadRecordBook(it.book, it.displayName)
                },
                onLongClick = { item ->
                    context.showReadRecordBookActionDialog(
                        title = item.book?.name ?: item.snapshot?.name ?: item.displayName,
                        book = item.book,
                        fallbackName = item.displayName
                    ) {
                        onDeleteRecord?.invoke(item)
                        val index = rankItems.indexOf(item)
                        if (index >= 0) {
                            rankItems.removeAt(index)
                            rankAdapter.notifyItemRemoved(index)
                        }
                    }
                }
            )
            adapter = rankAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 8.dpToPx())
            addView(
                androidx.appcompat.widget.AppCompatTextView(context).apply {
                    text = context.getString(R.string.read_record_read_rank)
                    applyUiSectionTitleStyle(context)
                    textSize = 18f
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                recyclerView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    420.dpToPx()
                ).apply {
                    topMargin = 14.dpToPx()
                }
            )
        }
        AlertDialog.Builder(context)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .applyTint()
            .show()
    }
}

fun Context.showReadRecordGoalDialog(
    initial: ReadRecordGoalConfig,
    onPickAvatarRequest: (((String) -> Unit) -> Unit)? = null,
    onSave: (ReadRecordGoalConfig) -> Unit
) {
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 0)
    }
    container.applyUiBodyTypefaceDeep(uiTypeface())
    val userNameInput = EditText(this).apply {
        hint = getString(R.string.read_record_goal_user_name_hint)
        setText(initial.userName.orEmpty())
        inputType = InputType.TYPE_CLASS_TEXT
        applyUiInputStyle(this@showReadRecordGoalDialog)
    }
    val avatarInput = EditText(this).apply {
        hint = getString(R.string.read_record_goal_avatar_hint)
        setText(initial.avatar.orEmpty())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        applyUiInputStyle(this@showReadRecordGoalDialog)
        maxLines = 2
    }
    val avatarButton = Button(this).apply {
        text = getString(R.string.read_record_goal_avatar_pick)
        applyUiSubtleButtonStyle(this@showReadRecordGoalDialog)
        setOnClickListener {
            onPickAvatarRequest?.invoke { value ->
                avatarInput.setText(value)
                avatarInput.setSelection(avatarInput.text?.length ?: 0)
            }
        }
    }
    val goalInput = EditText(this).apply {
        hint = getString(R.string.read_record_goal_minutes)
        setText(initial.dailyGoalMinutes.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
        applyUiInputStyle(this@showReadRecordGoalDialog)
    }
    fun sectionTitle(textRes: Int) =
        androidx.appcompat.widget.AppCompatTextView(this).apply {
            text = getString(textRes)
            applyUiSectionTitleStyle(this@showReadRecordGoalDialog)
        }
    container.addView(sectionTitle(R.string.read_record_goal_user_name))
    container.addView(userNameInput)
    container.addView(sectionTitle(R.string.read_record_goal_avatar).apply {
        setPadding(0, 14.dpToPx(), 0, 0)
    })
    container.addView(
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(
                avatarInput,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 10.dpToPx()
                }
            )
            addView(
                avatarButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    )
    container.addView(sectionTitle(R.string.read_record_goal_target).apply {
        setPadding(0, 14.dpToPx(), 0, 0)
    })
    container.addView(goalInput)
    AlertDialog.Builder(this)
        .setTitle(R.string.read_record_goal_card)
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val minutes = goalInput.text?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 120
            onSave(
                ReadRecordGoalConfig(
                    userName = userNameInput.text?.toString()?.trim().orEmpty().ifBlank { null },
                    avatar = avatarInput.text?.toString()?.trim().orEmpty().ifBlank { null },
                    dailyGoalMinutes = minutes
                )
            )
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .apply {
            setOnShowListener { applyTint() }
        }
        .show()
}

fun buildReadRecordPreviewBackground(context: Context, weight: Float = 1f): GradientDrawable {
    return UiCorner.rounded(
        ColorUtils.adjustAlpha(ContextCompat.getColor(context, R.color.background_menu), 0.92f),
        UiCorner.panelRadius(context) * weight.coerceAtLeast(0.8f)
    )
}
