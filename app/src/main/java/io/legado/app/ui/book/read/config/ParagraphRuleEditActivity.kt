package io.legado.app.ui.book.read.config

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityParagraphRuleEditBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.utils.stackTraceStr
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ParagraphRuleEditActivity : BaseActivity<ActivityParagraphRuleEditBinding>() {

    override val binding by viewBinding(ActivityParagraphRuleEditBinding::inflate)
    private var rule = ParagraphRule()
    private var focusedEditText: EditText? = null

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val view = focusedEditText ?: return@registerForActivityResult
            result.data?.getStringExtra("text")?.let { view.setText(it) }
            result.data?.getIntExtra("cursorPosition", -1)
                ?.takeIf { it in 0..view.text.length }
                ?.let { view.setSelection(it) }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        val id = intent.getLongExtra("id", 0L)
        lifecycleScope.launch {
            rule = withContext(Dispatchers.IO) { appDb.paragraphRuleDao.get(id) } ?: ParagraphRule()
            bindRule()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.paragraph_rule_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_fullscreen_edit -> onFullEditClicked()
            R.id.menu_save -> save()
            R.id.menu_debug_rule -> debugRule()
            R.id.menu_copy_rule -> sendToClip(GSON.toJson(getRule()))
            R.id.menu_paste_rule -> pasteRule()
            R.id.menu_help -> showHelp("paragraphRuleHelp")
        }
        return true
    }

    private fun initView() = binding.run {
        listOf(etLoginUrl, etLoginUi, etScript, etJsLib).forEach { codeView ->
            codeView.addJsPattern()
            codeView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus && v is EditText) focusedEditText = v
            }
        }
        etName.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && v is EditText) focusedEditText = v
        }
        etTimeout.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && v is EditText) focusedEditText = v
        }
    }

    private fun bindRule() = binding.run {
        etName.setText(rule.name)
        etLoginUrl.setText(rule.loginUrl)
        etLoginUi.setText(rule.loginUi)
        cbIsEnableCookie.isChecked = rule.enabledCookieJar
        etTimeout.setText(rule.validTimeout().toString())
        etScript.setText(rule.script)
        etJsLib.setText(rule.jsLib)
    }

    private fun onFullEditClicked() {
        val view = (window.decorView.findFocus() as? EditText) ?: focusedEditText
        if (view == null) {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
            return
        }
        focusedEditText = view
        textEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", view.text.toString())
            putExtra("title", hintFor(view))
            putExtra("cursorPosition", view.selectionStart)
        })
    }

    private fun hintFor(view: View): String? = when (view.id) {
        R.id.et_name -> getString(R.string.name)
        R.id.et_login_url -> getString(R.string.login_url)
        R.id.et_login_ui -> getString(R.string.login_ui)
        R.id.et_timeout -> getString(R.string.timeout_millisecond)
        R.id.et_script -> getString(R.string.paragraph_rule_script)
        R.id.et_js_lib -> "jsLib"
        else -> null
    }

    private fun debugRule() {
        val debugRule = getRule()
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                val book = ReadBook.book ?: return@withContext null
                val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                book to chapters
            }
            val book = pair?.first
            val chapters = pair?.second.orEmpty()
            if (book == null) {
                toastOnUi(R.string.paragraph_rule_no_book_hint)
                return@launch
            }
            if (chapters.isEmpty()) {
                toastOnUi("No chapters")
                return@launch
            }
            val current = ReadBook.durChapterIndex
            val start = (current - 20).coerceAtLeast(0)
            val candidates = chapters.drop(start).take(80)
            val labels = candidates.map { chapter ->
                "${chapter.index + 1}. ${chapter.title}"
            }
            selector(getString(R.string.debug), labels) { _, index ->
                runDebugRule(debugRule, book, candidates[index])
            }
        }
    }

    private fun runDebugRule(debugRule: ParagraphRule, book: Book, chapter: BookChapter) {
        lifecycleScope.launch {
            val message = kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    val content = BookHelp.getContent(book, chapter) ?: run {
                        val source = ReadBook.bookSource ?: throw IllegalStateException("No book source and no local cache")
                        WebBook.getContentAwait(source, book, chapter, needSave = false)
                    }
                    val debug = ParagraphRuleProcessor.debug(debugRule, book, chapter, content)
                    val result = debug.content
                    buildString {
                        appendLine("book: ${book.name}")
                        appendLine("chapter: ${chapter.title}")
                        appendLine("rawUrl: ${chapter.url}")
                        appendLine("absoluteUrl: ${kotlin.runCatching { chapter.getAbsoluteURL() }.getOrNull().orEmpty()}")
                        appendLine("paragraphs: ${result.split('\n').count { it.isNotBlank() }}")
                        appendLine("length: ${result.length}")
                        appendLine("imageTags: ${Regex("<img\\b", RegexOption.IGNORE_CASE).findAll(result).count()}")
                        appendLine()
                        appendLine("logs:")
                        if (debug.logs.isEmpty()) {
                            appendLine("(empty)")
                        } else {
                            debug.logs.forEach { appendLine(it) }
                        }
                        appendLine()
                        appendLine("result:")
                        append(result.take(4000))
                    }
                }
            }.getOrElse {
                "Paragraph rule debug failed:\n${it.localizedMessage ?: it}\n\n${it.stackTraceStr}"
            }
            alert(getString(R.string.debug), message) {
                okButton()
            }
        }
    }

    private fun pasteRule() {
        val raw = getClipText()
        val imported = GSON.fromJsonObject<ParagraphRule>(raw).getOrNull()
        if (imported == null) {
            toastOnUi(R.string.wrong_format)
            return
        }
        rule = imported.copy(id = rule.id, order = rule.order, updateTime = System.currentTimeMillis())
        bindRule()
    }

    private fun getRule(): ParagraphRule = binding.run {
        rule.copy(
            name = etName.text?.toString().orEmpty().trim(),
            loginUrl = etLoginUrl.text?.toString().orEmpty(),
            loginUi = etLoginUi.text?.toString().orEmpty(),
            enabledCookieJar = cbIsEnableCookie.isChecked,
            timeoutMillisecond = etTimeout.text?.toString()?.toLongOrNull() ?: 3000L,
            script = etScript.text?.toString().orEmpty(),
            jsLib = etJsLib.text?.toString().orEmpty(),
            updateTime = System.currentTimeMillis()
        )
    }

    private fun save() {
        val edited = getRule()
        if (edited.name.isBlank() || edited.script.isBlank()) {
            toastOnUi(R.string.paragraph_rule_save_invalid)
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (edited.id == 0L) {
                    val order = (appDb.paragraphRuleDao.maxOrder() ?: 0) + 1
                    appDb.paragraphRuleDao.insert(edited.copy(order = order))
                } else {
                    appDb.paragraphRuleDao.update(edited)
                }
            }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}
