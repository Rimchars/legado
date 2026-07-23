package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.dao.BookTagInfo
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ActivityBookshelfTagManageBinding
import io.legado.app.help.book.BookTagManagement
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.postEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookshelfTagManageActivity : BaseActivity<ActivityBookshelfTagManageBinding>() {

    override val binding by viewBinding(ActivityBookshelfTagManageBinding::inflate)
    private val focusGroupId by lazy { intent.getLongExtra("groupId", BookGroup.IdAll) }
    private var groupsState by mutableStateOf<List<BookshelfTagGroupUi>>(emptyList())
    private var loadingState by mutableStateOf(true)
    private var assignmentState by mutableStateOf<BookTagAssignmentUi?>(null)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            LegadoComposeTheme {
                BookshelfTagManageScreen(
                    groups = groupsState,
                    focusGroupId = focusGroupId,
                    loading = loadingState,
                    assignment = assignmentState,
                    onBack = ::finish,
                    onAddTags = ::addTags,
                    onTagVisibilityChange = ::setTagVisible,
                    onManageBooks = { group, tag ->
                        assignmentState = BookTagAssignmentUi(
                            groupId = group.groupId,
                            groupName = group.groupName,
                            tag = tag,
                            books = group.books,
                            initiallySelectedUrls = group.books.asSequence()
                                .filter { BookTagHelper.has(it.customTag, tag) }
                                .mapTo(linkedSetOf()) { it.bookUrl }
                        )
                    },
                    onDeleteTag = ::confirmDeleteTag,
                    onDismissAssignment = { assignmentState = null },
                    onSaveAssignment = ::saveAssignment
                )
            }
        }
        loadTags()
    }

    private fun loadTags() {
        loadingState = true
        lifecycleScope.launch {
            val data = withContext(IO) {
                val books = appDb.bookDao.allTagInfos
                val groups = appDb.bookGroupDao.all
                    .filter { it.groupId != BookGroup.IdRoot }
                    .sortedWith(
                        compareBy<BookGroup> { if (it.groupId == focusGroupId) 0 else 1 }
                            .thenBy { it.order }
                    )
                val userGroupMask = groups.asSequence()
                    .filter { it.groupId > 0 }
                    .fold(0L) { acc, group -> acc or group.groupId }
                val configuredMap = AppConfig.bookshelfGroupTags.toMutableMap()
                var configuredChanged = false
                val hiddenMap = AppConfig.bookshelfHiddenTags
                val result = groups.mapNotNull { group ->
                    val groupBooks = booksInGroup(group, books, userGroupMask)
                    val existingTags = groupBooks
                        .flatMap { BookTagHelper.parse(it.customTag) }
                    val configuredTags = configuredMap[group.groupId].orEmpty()
                    val tags = BookTagManagement.mergeTags(configuredTags, existingTags)
                    if (configuredTags != tags) {
                        configuredMap[group.groupId] = tags
                        configuredChanged = true
                    }
                    val hiddenTags = hiddenMap[group.groupId].orEmpty()
                    BookshelfTagGroupUi(
                        groupId = group.groupId,
                        groupName = group.groupName,
                        books = groupBooks,
                        tags = tags.map { tag ->
                            BookshelfTagItemUi(
                                name = tag,
                                assignedCount = groupBooks.count {
                                    BookTagHelper.has(it.customTag, tag)
                                },
                                visible = hiddenTags.none {
                                    it.equals(tag, ignoreCase = true)
                                }
                            )
                        }
                    )
                }
                if (configuredChanged) {
                    AppConfig.bookshelfGroupTags = configuredMap
                }
                result
            }
            groupsState = data
            loadingState = false
        }
    }

    private fun addTags(groupId: Long, tags: List<String>) {
        val newTags = BookTagManagement.mergeTags(emptyList(), tags)
        if (newTags.isEmpty()) return
        val map = AppConfig.bookshelfGroupTags.toMutableMap()
        map[groupId] = BookTagManagement.mergeTags(map[groupId].orEmpty(), newTags)
        AppConfig.bookshelfGroupTags = map
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
        loadTags()
    }

    private fun saveAssignment(assignment: BookTagAssignmentUi, selectedUrls: Set<String>) {
        assignmentState = null
        lifecycleScope.launch {
            withContext(IO) {
                appDb.withTransaction {
                    assignment.books.forEach { book ->
                        val shouldHaveTag = book.bookUrl in selectedUrls
                        val write = BookTagManagement.updateTag(
                            customTag = book.customTag,
                            tag = assignment.tag,
                            selected = shouldHaveTag
                        ) ?: return@forEach
                        appDb.bookDao.updateCustomTag(book.bookUrl, write.customTag)
                    }
                }
            }
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
            loadTags()
        }
    }

    private fun setTagVisible(groupId: Long, tag: String, visible: Boolean) {
        val map = AppConfig.bookshelfHiddenTags.toMutableMap()
        val tags = map[groupId].orEmpty().toMutableSet()
        tags.removeAll { it.equals(tag, ignoreCase = true) }
        if (!visible) tags.add(tag)
        if (tags.isEmpty()) map.remove(groupId) else map[groupId] = tags
        AppConfig.bookshelfHiddenTags = map
        groupsState = groupsState.map { group ->
            if (group.groupId != groupId) group else group.copy(
                tags = group.tags.map { item ->
                    if (item.name.equals(tag, ignoreCase = true)) item.copy(visible = visible) else item
                }
            )
        }
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    private fun confirmDeleteTag(group: BookshelfTagGroupUi, tag: String) {
        alert(
            title = getString(R.string.bookshelf_tag_delete_title),
            message = getString(R.string.bookshelf_tag_delete_message, tag, group.groupName)
        ) {
            okButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.withTransaction {
                            group.books.forEach { book ->
                                val write = BookTagManagement.updateTag(
                                    customTag = book.customTag,
                                    tag = tag,
                                    selected = false
                                ) ?: return@forEach
                                // write.customTag may be null when the last tag was removed.
                                appDb.bookDao.updateCustomTag(book.bookUrl, write.customTag)
                            }
                        }
                        val hiddenMap = AppConfig.bookshelfHiddenTags.toMutableMap()
                        hiddenMap[group.groupId] = hiddenMap[group.groupId].orEmpty()
                            .filterNot { it.equals(tag, ignoreCase = true) }
                            .toSet()
                        if (hiddenMap[group.groupId].isNullOrEmpty()) hiddenMap.remove(group.groupId)
                        AppConfig.bookshelfHiddenTags = hiddenMap
                        val tagMap = AppConfig.bookshelfGroupTags.toMutableMap()
                        tagMap[group.groupId] = tagMap[group.groupId].orEmpty()
                            .filterNot { it.equals(tag, ignoreCase = true) }
                        AppConfig.bookshelfGroupTags = tagMap
                    }
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    loadTags()
                }
            }
            cancelButton()
        }
    }

    private fun booksInGroup(
        group: BookGroup,
        books: List<BookTagInfo>,
        userGroupMask: Long
    ): List<BookTagInfo> {
        return when (group.groupId) {
            BookGroup.IdAll -> books
            BookGroup.IdLocal -> books.filter { it.type and BookType.local > 0 }
            BookGroup.IdAudio -> books.filter { it.type and BookType.audio > 0 }
            BookGroup.IdVideo -> books.filter { it.type and BookType.video > 0 }
            BookGroup.IdError -> books.filter { it.type and BookType.updateError > 0 }
            BookGroup.IdNetNone -> books.filter {
                it.type and BookType.audio == 0 &&
                    it.type and BookType.video == 0 &&
                    it.type and BookType.local == 0 &&
                    (it.group and userGroupMask) == 0L
            }
            BookGroup.IdLocalNone -> books.filter {
                it.type and BookType.audio == 0 &&
                    it.type and BookType.video == 0 &&
                    it.type and BookType.local > 0 &&
                    (it.group and userGroupMask) == 0L
            }
            else -> if (group.groupId > 0) {
                books.filter { it.group and group.groupId > 0 }
            } else {
                emptyList()
            }
        }
    }

}
