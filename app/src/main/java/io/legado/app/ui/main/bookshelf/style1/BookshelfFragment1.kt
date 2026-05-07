@file:Suppress("DEPRECATION")

package io.legado.app.ui.main.bookshelf.style1

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf1Binding
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.books.BooksFragment
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.isCreated
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch

/**
 * 书架界面
 */
class BookshelfFragment1() : BaseBookshelfFragment(R.layout.fragment_bookshelf1) {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf1Binding::bind)
    private val adapter by lazy { TabFragmentPageAdapter(childFragmentManager) }
    private val bookGroups = mutableListOf<BookGroup>()
    private val fragmentMap = hashMapOf<Long, BooksFragment>()
    private var groupMenuPopup: PopupWindow? = null
    private var bookTags = emptyList<String>()
    private var selectedBookTag = ""
    private val groupBooksCache = hashMapOf<Long, List<Book>>()
    private var currentGroupIndex = 0
    override val groupId: Long get() = selectedGroup?.groupId ?: 0

    override val books: List<Book>
        get() {
            val fragment = fragmentMap[groupId]
            return fragment?.getBooks() ?: emptyList()
        }

    override var onlyUpdateRead = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initBookGroupData()
    }

    private val selectedGroup: BookGroup?
        get() = bookGroups.getOrNull(binding.viewPagerBookshelf.currentItem)

    private fun initView() {
        binding.root.applyStatusBarPadding()
        binding.viewPagerBookshelf.setEdgeEffectColor(primaryColor)
        binding.btnMore.setOnClickListener {
            showModernBookshelfMenu(it)
        }
        binding.llTitleSelect.setOnClickListener {
            showGroupSwitchMenu(it)
        }
        binding.tabBarGlassView.visibility = View.GONE
        binding.tabBarShellOverlay.visibility = View.GONE
        binding.tabIndicatorContainer.visibility = View.GONE
        binding.btnMoreGlassView.visibility = View.GONE
        binding.btnMoreShellOverlay.visibility = View.GONE
        binding.btnMore.setBackgroundResource(R.drawable.bg_more_icon_button_clear)
        val iconColor = ContextCompat.getColor(requireContext(), R.color.primaryText)
        binding.btnMore.setColorFilter(iconColor)
        binding.ivBookshelfTitleArrow.setColorFilter(iconColor)
        binding.tabLayout.setOnTagClickListener { index ->
            val tag = bookTags.getOrNull(index).orEmpty()
            if (tag == selectedBookTag) {
                selectedGroup?.let { group ->
                    fragmentMap[group.groupId]?.let { fragment ->
                        val label = tag.ifBlank { getString(R.string.bookshelf_tag_all) }
                        toastOnUi("${group.groupName} · $label(${fragment.getBooksCount()})")
                    }
                }
            } else {
                selectedBookTag = tag
                binding.tabLayout.setSelectedIndex(index, smooth = true)
                fragmentMap[groupId]?.setBookTagFilter(tag)
            }
        }
        binding.tabLayout.setOnTagLongClickListener { index ->
            selectedBookTag = bookTags.getOrNull(index).orEmpty()
            fragmentMap[groupId]?.setBookTagFilter(selectedBookTag)
            true
        }
        binding.viewPagerBookshelf.offscreenPageLimit = 1
        binding.viewPagerBookshelf.adapter = adapter
        binding.viewPagerBookshelf.addOnPageChangeListener(
            object : androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    if (position !in bookGroups.indices) return
                    currentGroupIndex = position
                    selectedBookTag = ""
                    updateHeaderTitle()
                    val group = bookGroups[position]
                    fragmentMap[group.groupId]?.setBookTagFilter("")
                    renderBookTags(groupBooksCache[group.groupId].orEmpty())
                }
            }
        )
        updateHeaderTitle()
    }

    @Synchronized
    override fun upGroup(data: List<BookGroup>) {
        if (data.isEmpty()) {
            appDb.bookGroupDao.enableGroup(BookGroup.IdAll)
        } else if (data != bookGroups) {
            bookGroups.clear()
            bookGroups.addAll(data)
            adapter.notifyDataSetChanged()
            selectSavedGroup()
        } else {
            renderGroupTags()
        }
        updateHeaderTitle()
    }

    override fun upSort() {
        adapter.notifyDataSetChanged()
    }

    private fun selectSavedGroup() {
        binding.viewPagerBookshelf.post {
            if (bookGroups.isEmpty()) {
                binding.tabLayout.submitItems(emptyList(), -1)
                updateHeaderTitle()
                return@post
            }
            val target = AppConfig.saveTabPosition.coerceIn(0, bookGroups.lastIndex)
            switchToGroup(target)
        }
    }

    override fun gotoTop() {
        fragmentMap[groupId]?.gotoTop()
    }

    private fun renderGroupTags() {
        renderBookTags(emptyList())
    }

    private fun updateHeaderTitle() {
        binding.tvBookshelfTitle.text = selectedGroup?.groupName ?: getString(R.string.bookshelf)
    }

    fun onBooksChanged(groupId: Long, books: List<Book>) {
        groupBooksCache[groupId] = books
        if (groupId != this.groupId) return
        renderBookTags(books)
    }

    private fun renderBookTags(books: List<Book>) {
        if (!isAdded) return
        val allText = getString(R.string.bookshelf_tag_all)
        val storedTags = AppConfig.bookshelfGroupTags[groupId].orEmpty()
        val tags = storedTags.ifEmpty {
            val migratedTags = books.asSequence()
                .flatMap { BookTagHelper.parse(it.customTag).asSequence() }
                .distinct()
                .sorted()
                .toList()
            if (migratedTags.isNotEmpty()) {
                val map = AppConfig.bookshelfGroupTags.toMutableMap()
                map[groupId] = migratedTags
                AppConfig.bookshelfGroupTags = map
            }
            migratedTags
        }
            .filterNot { it in AppConfig.bookshelfHiddenTags[groupId].orEmpty() }
        bookTags = listOf("") + tags
        if (selectedBookTag.isNotBlank() && selectedBookTag !in tags) {
            selectedBookTag = ""
            fragmentMap[groupId]?.setBookTagFilter("")
        }
        binding.tabLayout.submitItems(
            bookTags.map { RoundedTagBarView.Item(it.ifBlank { allText }) },
            bookTags.indexOf(selectedBookTag).takeIf { it >= 0 } ?: 0
        )
    }

    private fun showGroupSwitchMenu(anchor: View) {
        if (bookGroups.isEmpty()) return
        val selectedId = selectedGroup?.groupId
        val actions = bookGroups.mapIndexed { index, group ->
            val prefix = if (group.groupId == selectedId) "✓ " else ""
            ModernActionPopup.Action(prefix + group.groupName) {
                selectedBookTag = ""
                switchToGroup(index)
            }
        }
        groupMenuPopup = ModernActionPopup.show(anchor, actions, groupMenuPopup)
    }

    private fun switchToGroup(index: Int) {
        if (index !in bookGroups.indices) return
        currentGroupIndex = index
        binding.viewPagerBookshelf.setCurrentItem(index, false)
        AppConfig.saveTabPosition = index
        selectedBookTag = ""
        fragmentMap[groupId]?.setBookTagFilter("")
        renderBookTags(groupBooksCache[groupId].orEmpty())
        updateHeaderTitle()
    }

    override fun showBookTagManageAlert() {
        val group = selectedGroup ?: return
        val targetBooks = groupBooksCache[group.groupId].orEmpty()
        val tags = targetBooks
            .flatMap { BookTagHelper.parse(it.customTag) }
            .distinct()
            .sorted()
        if (tags.isEmpty()) {
            toastOnUi(R.string.bookshelf_tag_none)
            return
        }
        val checked = BooleanArray(tags.size) { true }
        val labels = tags.map { tag ->
            "$tag (${targetBooks.count { BookTagHelper.has(it.customTag, tag) }})"
        }.toTypedArray()
        alert(title = "${group.groupName} · ${getString(R.string.bookshelf_tag_manage)}") {
            setMessage(getString(R.string.bookshelf_tag_manage_hint))
            multiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            okButton {
                val keepTags = tags.filterIndexed { index, _ -> checked[index] }.toSet()
                lifecycleScope.launch(IO) {
                    targetBooks.forEach { book ->
                        val normalized = BookTagHelper.join(
                            BookTagHelper.parse(book.customTag).filter { it in keepTags }
                        )
                        if (normalized != book.customTag) {
                            book.customTag = normalized
                            appDb.bookDao.update(book)
                        }
                    }
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
            }
            cancelButton()
        }
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            renderBookTags(groupBooksCache[groupId].orEmpty())
        }
    }

    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getPageTitle(position: Int): CharSequence {
            return bookGroups[position].groupName
        }

        /**
         * 确定视图位置是否更改时调用
         * @return POSITION_NONE 已更改,刷新视图. POSITION_UNCHANGED 未更改,不刷新视图
         */
        override fun getItemPosition(any: Any): Int {
            val fragment = any as BooksFragment
            val position = fragment.position
            val group = bookGroups.getOrNull(position)
            if (fragment.groupId != group?.groupId) {
                return POSITION_NONE
            }
            val bookSort = group.getRealBookSort()
            fragment.setEnableRefresh(group.enableRefresh)
            if (fragment.bookSort != bookSort) {
                fragment.upBookSort(bookSort)
            }
            return POSITION_UNCHANGED
        }

        override fun getItem(position: Int): Fragment {
            val group = bookGroups[position]
            onlyUpdateRead = group.onlyUpdateRead
            return BooksFragment(position, group)
        }

        override fun getCount(): Int {
            return bookGroups.size
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as BooksFragment
            val group = bookGroups[position]
            /**
             * Activity recreate 会复用之前的 Fragment，不正确的需要重新创建
             */
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as BooksFragment
            }
            fragmentMap[group.groupId] = fragment
            return fragment
        }

    }
}
