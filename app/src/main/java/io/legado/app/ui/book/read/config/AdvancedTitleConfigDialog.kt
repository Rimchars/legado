package io.legado.app.ui.book.read.config

import android.app.Activity.RESULT_OK
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.filletBackground
import io.legado.app.model.ReadBook
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdvancedTitleConfigDialog : DialogFragment() {

    private val currentBook: Book?
        get() = ReadBook.book
    private var jsonEdit: EditText? = null
    private var currentJson: String = AdvancedTitleConfig.lottieJson.orEmpty()
    private val importFormNet = "网络导入"

    private val jsonEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra("text")?.let { text ->
                currentJson = text
                jsonEdit?.setText(text)
            }
        }
    }

    private val importJson = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.path == "/$importFormNet") {
                importNetJsonAlert()
            } else {
                runCatching {
                    uri.readText(requireContext())
                }.onSuccess { text ->
                    currentJson = text
                    jsonEdit?.setText(text)
                }.onFailure { error ->
                    context?.toastOnUi("导入失败：${error.localizedMessage}")
                }
            }
        }
    }

    private val exportJson = registerForActivityResult(HandleFileContract()) {
        if (it.uri != null) {
            context?.toastOnUi("已导出")
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (resources.displayMetrics.heightPixels * 0.78f).toInt()
            )
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val book = currentBook
        val globalRule = AdvancedTitleConfig.globalRule
        val bookRule = AdvancedTitleConfig.bookRule(book)
        val startRule = bookRule ?: globalRule

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dpToPx(), 12.dpToPx(), 18.dpToPx(), 4.dpToPx())
        }

        fun label(text: String) = TextView(context).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
        }

        fun edit(text: String, minLines: Int = 1) = EditText(context).apply {
            setText(text)
            this.minLines = minLines
            maxLines = if (minLines > 1) 8 else 2
            setSingleLine(minLines == 1)
        }

        fun button(text: String) = Button(context).apply {
            this.text = text
            isAllCaps = false
        }

        val scopeGroup = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbGlobal = RadioButton(context).apply {
            text = "全局生效"
            id = 1
        }
        val rbBook = RadioButton(context).apply {
            text = "仅本书生效"
            id = 2
            isEnabled = book != null
        }
        scopeGroup.addView(rbGlobal)
        scopeGroup.addView(rbBook)
        scopeGroup.check(if (bookRule != null) rbBook.id else rbGlobal.id)

        val useRegexCheck = CheckBox(context).apply {
            text = "使用正则"
            isChecked = startRule.mode == AdvancedTitleConfig.SPLIT_REGEX
        }
        val ruleEdit = edit(
            if (startRule.mode == AdvancedTitleConfig.SPLIT_REGEX) {
                startRule.regex
            } else {
                startRule.delimiter
            }
        )
        val sampleEdit = edit("第一章 接生")
        val lottieJsonEdit = edit(currentJson, minLines = 6).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                150.dpToPx()
            )
            setHorizontallyScrolling(true)
            setSingleLine(false)
        }
        jsonEdit = lottieJsonEdit
        val openEditorButton = button("打开编辑器").apply {
            setOnClickListener { openJsonEditor() }
        }
        val preview = TextView(context).apply {
            setPadding(0, 8.dpToPx(), 0, 0)
        }

        fun buildRule(): AdvancedTitleConfig.SplitRule {
            return AdvancedTitleConfig.SplitRule(
                mode = if (useRegexCheck.isChecked) {
                    AdvancedTitleConfig.SPLIT_REGEX
                } else {
                    AdvancedTitleConfig.SPLIT_DELIMITER
                },
                delimiter = if (useRegexCheck.isChecked) startRule.delimiter else ruleEdit.text?.toString().orEmpty(),
                regex = if (useRegexCheck.isChecked) ruleEdit.text?.toString().orEmpty() else startRule.regex
            )
        }

        fun updatePreview() {
            val rule = buildRule()
            preview.text = runCatching {
                val parts = AdvancedTitleConfig.split(sampleEdit.text?.toString().orEmpty(), rule)
                "s1: ${parts.s1.ifBlank { "空" }}\ns2: ${parts.s2.ifBlank { "空" }}"
            }.getOrElse {
                "规则错误：${it.localizedMessage}"
            }
        }

        listOf(ruleEdit, sampleEdit).forEach {
            it.doAfterTextChanged { updatePreview() }
        }
        useRegexCheck.setOnCheckedChangeListener { _, isChecked ->
            ruleEdit.setText(if (isChecked) startRule.regex else startRule.delimiter)
            ruleEdit.setSelection(ruleEdit.text?.length ?: 0)
            updatePreview()
        }

        root.addView(TextView(context).apply {
            text = "高级标题设置"
            textSize = 18f
            setPadding(0, 2.dpToPx(), 0, 8.dpToPx())
        })
        root.addView(label("作用范围"))
        root.addView(scopeGroup)
        root.addView(label("分隔规则"))
        root.addView(useRegexCheck)
        root.addView(ruleEdit)
        root.addView(label("预览"))
        root.addView(sampleEdit)
        root.addView(preview)
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("高级标题 JSON").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
            })
            addView(openEditorButton)
        })
        root.addView(lottieJsonEdit)
        root.addView(TextView(context).apply {
            text = "支持 ${'$'}{s1}/${'$'}{s2}"
            textSize = 12f
            setPadding(0, 4.dpToPx(), 0, 6.dpToPx())
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        })
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("导入").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    importJson.launch {
                        mode = HandleFileContract.FILE
                        title = "导入高级标题"
                        allowExtensions = arrayOf("json")
                        otherActions = arrayListOf(SelectItem(importFormNet, -1))
                    }
                }
            })
            addView(button("导出").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val json = lottieJsonEdit.text?.toString().orEmpty()
                    exportJson.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(
                            "advancedTitle.json",
                            json.toByteArray(),
                            "application/json"
                        )
                    }
                }
            })
        })

        updatePreview()

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val container = CardView(context).apply {
            radius = 16.dpToPx().toFloat()
            cardElevation = 0f
            preventCornerOverlap = false
            useCompatPadding = false
            background = context.filletBackground
            addView(
                scrollView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        return AlertDialog.Builder(context)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val rule = buildRule()
                AdvancedTitleConfig.lottieJson = lottieJsonEdit.text?.toString().orEmpty()
                AdvancedTitleConfig.lottiePath = null
                AdvancedTitleConfig.heightFactor = AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR
                if (scopeGroup.checkedRadioButtonId == rbBook.id && book != null) {
                    AdvancedTitleConfig.setBookRule(book, rule)
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { book.save() }
                    }
                } else {
                    AdvancedTitleConfig.globalRule = rule
                }
                postEvent(EventBus.UP_CONFIG, arrayListOf(5, 8))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("恢复默认") { _, _ ->
                AdvancedTitleConfig.globalRule = AdvancedTitleConfig.SplitRule()
                AdvancedTitleConfig.lottieJson = null
                AdvancedTitleConfig.lottiePath = null
                AdvancedTitleConfig.heightFactor = AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR
                book?.let {
                    AdvancedTitleConfig.setBookRule(it, null)
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { it.save() }
                    }
                }
                postEvent(EventBus.UP_CONFIG, arrayListOf(5, 8))
            }
            .create()
    }

    private fun importNetJsonAlert() {
        context?.alert("输入地址") {
            val input = EditText(requireContext()).apply { hint = "https://..." }
            customView { input }
            okButton {
                val url = input.text?.toString().orEmpty().trim()
                if (url.isNotEmpty()) importNetJson(url)
            }
            cancelButton()
        }
    }

    private fun importNetJson(url: String) {
        lifecycleScope.launch {
            runCatching {
                okHttpClient.newCallResponseBody {
                    url(url)
                }.string()
            }.onSuccess { text ->
                currentJson = text
                jsonEdit?.setText(text)
            }.onFailure { error ->
                context?.toastOnUi("网络导入失败：${error.localizedMessage}")
            }
        }
    }

    private fun openJsonEditor() {
        val editText = jsonEdit ?: return
        currentJson = editText.text?.toString().orEmpty()
        jsonEditLauncher.launch(Intent(requireActivity(), CodeEditActivity::class.java).apply {
            putExtra("text", currentJson)
            putExtra("title", "高级标题 JSON")
            putExtra("cursorPosition", editText.selectionStart.coerceAtLeast(0))
        })
    }
}
