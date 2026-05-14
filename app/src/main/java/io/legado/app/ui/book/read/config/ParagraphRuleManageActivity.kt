package io.legado.app.ui.book.read.config

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.gson.reflect.TypeToken
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookParagraphRule
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ParagraphRuleVar
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ParagraphRuleManageActivity : BaseActivity<ActivityThemeManageBinding>(), ItemTouchCallback.Callback {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)
    private val adapter = RuleAdapter()
    private var bookUrl: String? = null
    private var book: Book? = null
    private var enabledIds: Set<Long> = emptySet()

    private val importDoc = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            lifecycleScope.launch {
                kotlin.runCatching {
                    parseImportedRules(uri.readText(this@ParagraphRuleManageActivity))
                }.onSuccess { rules ->
                    if (rules.isEmpty()) {
                        toastOnUi(R.string.wrong_format)
                    } else {
                        withContext(Dispatchers.IO) { insertImportedRules(rules) }
                        load()
                        toastOnUi(R.string.success)
                    }
                }.onFailure {
                    toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
                }
            }
        }
    }

    private val exportRuleResult = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { toastOnUi(R.string.export_success) }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra("bookUrl") ?: ReadBook.book?.bookUrl
        book = bookUrl?.let { appDb.bookDao.getBook(it) }
        initView()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun initView() = binding.run {
        titleBar.title = getString(R.string.paragraph_rule_manage)
        tabBar.visibility = View.GONE
        btnAdd.text = getString(R.string.add)
        btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this@ParagraphRuleManageActivity, R.color.background_card),
            ContextCompat.getColor(this@ParagraphRuleManageActivity, R.color.background_menu),
            UiCorner.actionRadius(this@ParagraphRuleManageActivity)
        )
        btnAdd.setOnClickListener { showAddActions() }
        tvSummary.applyUiLabelStyle(this@ParagraphRuleManageActivity)
        tvSummary.setTextColor(secondaryTextColor)
        recyclerView.layoutManager = LinearLayoutManager(this@ParagraphRuleManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        ItemTouchHelper(ItemTouchCallback(this@ParagraphRuleManageActivity).apply {
            isCanDrag = true
        }).attachToRecyclerView(recyclerView)
        root.applyUiBodyTypefaceDeep(this@ParagraphRuleManageActivity.uiTypeface())
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_IMPORT, 0, R.string.import_str).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_HELP, 1, R.string.help).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_IMPORT -> importDoc.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.import_str)
                allowExtensions = arrayOf("json")
            }
            MENU_HELP -> showHelp("paragraphRuleHelp")
        }
        return true
    }

    private fun showAddActions() {
        selector(
            getString(R.string.paragraph_rule_manage),
            listOf(getString(R.string.add), getString(R.string.import_str))
        ) { _, index ->
            when (index) {
                0 -> startActivity<ParagraphRuleEditActivity>()
                1 -> importDoc.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.import_str)
                    allowExtensions = arrayOf("json")
                }
            }
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) {
                bookUrl?.let { enabledIds = appDb.paragraphRuleDao.enabledRuleIdsForBook(it).toSet() }
                appDb.paragraphRuleDao.all()
            }
            adapter.items = rules
            binding.tvSummary.text = when {
                bookUrl == null -> getString(R.string.paragraph_rule_no_book_hint)
                rules.isEmpty() -> getString(R.string.paragraph_rule_empty)
                else -> getString(R.string.paragraph_rule_manage_summary)
            }
        }
    }

    private fun toggle(rule: ParagraphRule, enable: Boolean) {
        val url = bookUrl
        if (url == null) {
            toastOnUi(R.string.paragraph_rule_no_book_hint)
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (enable) {
                    appDb.paragraphRuleDao.insertBookRule(
                        BookParagraphRule(url, rule.id, true, adapter.items.indexOfFirst { it.id == rule.id })
                    )
                } else {
                    appDb.paragraphRuleDao.deleteBookRule(url, rule.id)
                }
            }
            refreshReadBookIfNeeded()
            load()
        }
    }

    private fun login(rule: ParagraphRule) {
        startActivity<SourceLoginActivity> {
            putExtra("bookType", -1)
            putExtra("type", "paragraphRule")
            putExtra("key", rule.id.toString())
            bookUrl?.let { putExtra("bookUrl", it) }
        }
    }

    private fun showActions(rule: ParagraphRule) {
        val actions = listOf(
            Action.EDIT,
            Action.EXPORT,
            Action.COPY,
            Action.VARS,
            Action.DELETE
        )
        selector(rule.displayName(), actions.map { getString(it.titleRes) }) { _, index ->
            when (actions[index]) {
                Action.EDIT -> startActivity<ParagraphRuleEditActivity> { putExtra("id", rule.id) }
                Action.EXPORT -> exportRule(rule)
                Action.COPY -> sendToClip(serializeRule(rule))
                Action.VARS -> editVars(rule)
                Action.DELETE -> deleteRule(rule)
            }
        }
    }

    private fun editVars(rule: ParagraphRule) {
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                GSON.toJson(appDb.paragraphRuleDao.vars(rule.id).associate { it.name to it.value })
            }
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.paragraph_rule_vars_json)
                editView.setText(raw)
            }
            alert(titleResource = R.string.paragraph_rule_vars) {
                customView { alertBinding.root }
                okButton {
                    val text = alertBinding.editView.text?.toString().orEmpty()
                    saveVars(rule.id, text)
                }
                cancelButton()
            }
        }
    }

    private fun saveVars(ruleId: Long, raw: String) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val values: Map<String, String> = if (raw.isBlank()) emptyMap() else GSON.fromJson(raw, type)
                withContext(Dispatchers.IO) {
                    appDb.paragraphRuleDao.deleteVars(ruleId)
                    values.forEach { (key, value) ->
                        appDb.paragraphRuleDao.putVar(ParagraphRuleVar(ruleId, key, value))
                    }
                }
            }.onSuccess {
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
            }
        }
    }

    private fun parseImportedRules(raw: String): List<ParagraphRule> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        GSON.fromJsonArray<ParagraphRule>(text).getOrNull()?.let { return it }
        GSON.fromJsonObject<ParagraphRule>(text).getOrNull()?.let { return listOf(it) }
        return emptyList()
    }

    private fun insertImportedRules(rules: List<ParagraphRule>) {
        var order = (appDb.paragraphRuleDao.maxOrder() ?: 0) + 1
        rules.forEach { imported ->
            val rule = imported.copy(id = 0L, order = order++, updateTime = System.currentTimeMillis())
            appDb.paragraphRuleDao.insert(rule)
        }
    }

    private fun exportRule(rule: ParagraphRule) {
        exportRuleResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "paragraphRule-${rule.displayName()}.json",
                serializeRule(rule).toByteArray(),
                "application/json"
            )
        }
    }

    private fun serializeRule(rule: ParagraphRule): String = GSON.toJson(rule)

    private fun deleteRule(rule: ParagraphRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.displayName())
            yesButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { appDb.paragraphRuleDao.deleteWithRelations(rule) }
                    refreshReadBookIfNeeded()
                    load()
                }
            }
            noButton()
        }
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        if (srcPosition !in adapter.items.indices || targetPosition !in adapter.items.indices) return false
        val mutable = adapter.items.toMutableList()
        val item = mutable.removeAt(srcPosition)
        mutable.add(targetPosition, item)
        adapter.items = mutable
        adapter.notifyItemMoved(srcPosition, targetPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                adapter.items.forEachIndexed { index, rule ->
                    if (rule.order != index) appDb.paragraphRuleDao.update(rule.copy(order = index, updateTime = System.currentTimeMillis()))
                    bookUrl?.let { url ->
                        if (enabledIds.contains(rule.id)) {
                            appDb.paragraphRuleDao.insertBookRule(BookParagraphRule(url, rule.id, true, index))
                        }
                    }
                }
            }
            load()
        }
    }

    private fun refreshReadBookIfNeeded() {
        if (bookUrl != ReadBook.book?.bookUrl) return
        ReadBook.clearTextChapter()
        ReadBook.callBack?.upContent(resetPageOffset = false)
    }

    private inner class RuleAdapter : RecyclerView.Adapter<RuleAdapter.Holder>() {
        var items: List<ParagraphRule> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        init { setHasStableIds(true) }

        override fun getItemId(position: Int): Long = items[position].id
        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemThemePackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(private val itemBinding: ItemThemePackageBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(rule: ParagraphRule) = itemBinding.run {
                val enabled = enabledIds.contains(rule.id)
                root.background = UiCorner.panelRounded(
                    this@ParagraphRuleManageActivity,
                    ContextCompat.getColor(this@ParagraphRuleManageActivity, R.color.background_card),
                    UiCorner.panelRadius(this@ParagraphRuleManageActivity)
                )
                cardPreview.visibility = View.GONE
                tvSource.visibility = View.GONE
                (layInfo.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.marginStart = 0
                    layInfo.layoutParams = it
                }
                tvName.text = rule.displayName()
                tvInfo.text = getString(if (enabled) R.string.paragraph_rule_enabled_for_book else R.string.paragraph_rule_disabled_for_book)
                tvName.applyUiSectionTitleStyle(this@ParagraphRuleManageActivity)
                tvInfo.applyUiLabelStyle(this@ParagraphRuleManageActivity)
                tvInfo.setTextColor(secondaryTextColor)
                listOf(btnApply, btnEdit, btnMore).forEach {
                    it.background = UiCorner.actionSelector(
                        Color.TRANSPARENT,
                        ContextCompat.getColor(this@ParagraphRuleManageActivity, R.color.background_menu),
                        UiCorner.actionRadius(this@ParagraphRuleManageActivity)
                    )
                    it.typeface = this@ParagraphRuleManageActivity.uiTypeface()
                }
                btnApply.text = getString(if (enabled) R.string.disable else R.string.enable)
                btnEdit.text = getString(R.string.login)
                btnMore.text = getString(R.string.more)
                btnApply.setTextColor(accentColor)
                btnEdit.setTextColor(primaryTextColor)
                btnMore.setTextColor(primaryTextColor)
                btnApply.setOnClickListener { toggle(rule, !enabled) }
                btnEdit.setOnClickListener { login(rule) }
                btnMore.setOnClickListener { showActions(rule) }
                root.setOnClickListener { showActions(rule) }
            }
        }
    }

    private enum class Action(val titleRes: Int) {
        EDIT(R.string.edit),
        EXPORT(R.string.export_str),
        COPY(R.string.copy_rule),
        VARS(R.string.paragraph_rule_vars),
        DELETE(R.string.delete)
    }

    companion object {
        private const val MENU_IMPORT = 1
        private const val MENU_HELP = 2
    }
}