package io.legado.app.ui.main.bookshelf

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ActivityBookshelfTagManageBinding
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookshelfTagManageActivity : BaseActivity<ActivityBookshelfTagManageBinding>() {

    override val binding by viewBinding(ActivityBookshelfTagManageBinding::inflate)
    private val focusGroupId by lazy { intent.getLongExtra("groupId", BookGroup.IdAll) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.tagContainer.applyNavigationBarPadding()
        loadTags()
    }

    private fun loadTags() {
        lifecycleScope.launch {
            val data = withContext(IO) {
                val books = appDb.bookDao.all
                val groups = appDb.bookGroupDao.all
                    .filter { it.groupId >= 0 || it.groupId == BookGroup.IdAll }
                    .sortedWith(compareBy<BookGroup> { if (it.groupId == focusGroupId) 0 else 1 }
                        .thenBy { it.order })
                groups.mapNotNull { group ->
                    val groupBooks = booksInGroup(group, books)
                    val existingTags = groupBooks.flatMap { BookTagHelper.parse(it.customTag) }
                        .distinct()
                        .sorted()
                    val configuredTags = AppConfig.bookshelfGroupTags[group.groupId].orEmpty()
                    val tags = configuredTags.ifEmpty {
                        if (existingTags.isNotEmpty()) {
                            val map = AppConfig.bookshelfGroupTags.toMutableMap()
                            map[group.groupId] = existingTags
                            AppConfig.bookshelfGroupTags = map
                        }
                        existingTags
                    }
                    GroupTags(group, groupBooks, tags)
                }
            }
            render(data)
        }
    }

    private fun render(data: List<GroupTags>) = binding.tagContainer.run {
        removeAllViews()
        if (data.isEmpty()) {
            addView(TextView(this@BookshelfTagManageActivity).apply {
                text = getString(R.string.bookshelf_tag_none)
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                setPadding(28.dpToPx())
            })
            return@run
        }
        data.forEach { groupTags ->
            addGroupCard(groupTags)
        }
    }

    private fun LinearLayout.addGroupCard(groupTags: GroupTags) {
        val hiddenMap = AppConfig.bookshelfHiddenTags
        val hiddenTags = hiddenMap[groupTags.group.groupId].orEmpty()
        val card = LinearLayout(this@BookshelfTagManageActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 8.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dpToPx()
            }
        }
        val header = LinearLayout(this@BookshelfTagManageActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8.dpToPx())
        }
        header.addView(TextView(this@BookshelfTagManageActivity).apply {
            text = "${groupTags.group.groupName} (${groupTags.books.size})"
            setTextColor(primaryTextColor)
            textSize = 17f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this@BookshelfTagManageActivity).apply {
            text = getString(R.string.add)
            setTextColor(primaryTextColor)
            gravity = Gravity.CENTER
            setPadding(10.dpToPx(), 4.dpToPx(), 10.dpToPx(), 4.dpToPx())
            background = cardBackground()
            setOnClickListener { showAddTagDialog(groupTags.group.groupId) }
        })
        card.addView(header)
        if (groupTags.tags.isEmpty()) {
            card.addView(TextView(this@BookshelfTagManageActivity).apply {
                setText(R.string.bookshelf_tag_none)
                setTextColor(secondaryTextColor)
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
            })
        }
        groupTags.tags.forEach { tag ->
            val row = LinearLayout(this@BookshelfTagManageActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = 44.dpToPx()
            }
            val checkBox = CheckBox(this@BookshelfTagManageActivity).apply {
                text = "$tag (${groupTags.books.count { BookTagHelper.has(it.customTag, tag) }})"
                isChecked = tag !in hiddenTags
                setTextColor(primaryTextColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnCheckedChangeListener { _, isChecked ->
                    setTagVisible(groupTags.group.groupId, tag, isChecked)
                }
            }
            row.addView(checkBox)
            row.addView(TextView(this@BookshelfTagManageActivity).apply {
                text = getString(R.string.bookshelf_tag_edit)
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                minWidth = 56.dpToPx()
                setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                setOnClickListener { showAssignBooksDialog(groupTags, tag) }
            })
            row.addView(TextView(this@BookshelfTagManageActivity).apply {
                text = getString(R.string.delete)
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                minWidth = 48.dpToPx()
                setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                setOnClickListener { confirmDeleteTag(groupTags, tag) }
            })
            card.addView(row)
        }
        addView(card)
    }

    private fun showAddTagDialog(groupId: Long) {
        val editText = EditText(this).apply {
            hint = getString(R.string.bookshelf_tag_new_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(false)
            minLines = 1
        }
        alert(titleResource = R.string.bookshelf_tag_edit) {
            customView { editText }
            okButton {
                val newTags = BookTagHelper.parse(editText.text?.toString())
                if (newTags.isEmpty()) return@okButton
                val map = AppConfig.bookshelfGroupTags.toMutableMap()
                map[groupId] = (map[groupId].orEmpty() + newTags).distinct()
                AppConfig.bookshelfGroupTags = map
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
                loadTags()
            }
            cancelButton()
        }
    }

    private fun showAssignBooksDialog(groupTags: GroupTags, tag: String) {
        if (groupTags.books.isEmpty()) return
        val checked = BooleanArray(groupTags.books.size) { index ->
            BookTagHelper.has(groupTags.books[index].customTag, tag)
        }
        val labels = groupTags.books.map { it.name }.toTypedArray()
        alert(title = "${groupTags.group.groupName} · $tag") {
            multiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            okButton {
                lifecycleScope.launch(IO) {
                    groupTags.books.forEachIndexed { index, book ->
                        val tags = BookTagHelper.parse(book.customTag).toMutableList()
                        val hasTag = tags.any { it.equals(tag, ignoreCase = true) }
                        when {
                            checked[index] && !hasTag -> tags.add(tag)
                            !checked[index] && hasTag -> tags.removeAll { it.equals(tag, ignoreCase = true) }
                            else -> return@forEachIndexed
                        }
                        book.customTag = BookTagHelper.join(tags)
                        appDb.bookDao.update(book)
                    }
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        loadTags()
                    }
                }
            }
            cancelButton()
        }
    }

    private fun setTagVisible(groupId: Long, tag: String, visible: Boolean) {
        val map = AppConfig.bookshelfHiddenTags.toMutableMap()
        val tags = map[groupId].orEmpty().toMutableSet()
        if (visible) {
            tags.remove(tag)
        } else {
            tags.add(tag)
        }
        if (tags.isEmpty()) {
            map.remove(groupId)
        } else {
            map[groupId] = tags
        }
        AppConfig.bookshelfHiddenTags = map
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    private fun confirmDeleteTag(groupTags: GroupTags, tag: String) {
        alert(
            title = getString(R.string.bookshelf_tag_delete_title),
            message = getString(R.string.bookshelf_tag_delete_message, tag, groupTags.group.groupName)
        ) {
            okButton {
                lifecycleScope.launch(IO) {
                    groupTags.books.forEach { book ->
                        if (BookTagHelper.has(book.customTag, tag)) {
                            book.customTag = BookTagHelper.join(
                                BookTagHelper.parse(book.customTag)
                                    .filterNot { it.equals(tag, ignoreCase = true) }
                            )
                            appDb.bookDao.update(book)
                        }
                    }
                    val map = AppConfig.bookshelfHiddenTags.toMutableMap()
                    map[groupTags.group.groupId]?.let {
                        map[groupTags.group.groupId] = it.filterNot { hidden ->
                            hidden.equals(tag, ignoreCase = true)
                        }.toSet()
                    }
                    AppConfig.bookshelfHiddenTags = map
                    val tagMap = AppConfig.bookshelfGroupTags.toMutableMap()
                    tagMap[groupTags.group.groupId] = tagMap[groupTags.group.groupId].orEmpty()
                        .filterNot { it.equals(tag, ignoreCase = true) }
                    AppConfig.bookshelfGroupTags = tagMap
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        loadTags()
                    }
                }
            }
            cancelButton()
        }
    }

    private fun cardBackground(): GradientDrawable {
        val fill = ColorUtils.blendColors(
            backgroundColor,
            if (ColorUtils.isColorLight(backgroundColor)) 0xffffffff.toInt() else primaryTextColor,
            if (ColorUtils.isColorLight(backgroundColor)) 0.58f else 0.08f
        )
        return GradientDrawable().apply {
            cornerRadius = 12f.dpToPx()
            setColor(fill)
            setStroke(1.dpToPx(), ColorUtils.adjustAlpha(primaryTextColor, 0.08f))
        }
    }

    private fun booksInGroup(group: BookGroup, books: List<Book>): List<Book> {
        return when (group.groupId) {
            BookGroup.IdAll -> books
            else -> books.filter { it.group and group.groupId > 0 }
        }
    }

    private data class GroupTags(
        val group: BookGroup,
        val books: List<Book>,
        val tags: List<String>
    )
}
