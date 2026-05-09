package io.legado.app.ui.book.read.config

import android.app.Activity.RESULT_OK
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.applyUiInputStyle
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.applyUiSubtleButtonStyle
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdvancedTitleConfigDialog : DialogFragment() {

    private val currentBook: Book?
        get() = ReadBook.book
    private var currentJson: String = AdvancedTitleConfig.lottieJson.orEmpty()
    private var jsonCursorPosition: Int = 0
    private val importFromNet by lazy { getString(R.string.advanced_title_import_from_net) }

    private val jsonEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra("text")?.let { text ->
                currentJson = text
                jsonCursorPosition = result.data?.getIntExtra("cursorPosition", text.length) ?: text.length
            }
        }
    }

    private val importJson = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.path == "/$importFromNet") {
                importNetJsonAlert()
            } else {
                runCatching {
                    uri.readText(requireContext())
                }.onSuccess { text ->
                    currentJson = text
                    jsonCursorPosition = text.length
                }.onFailure { error ->
                    context?.toastOnUi(
                        getString(R.string.advanced_title_import_failed, error.localizedMessage.orEmpty())
                    )
                }
            }
        }
    }

    private val exportJson = registerForActivityResult(HandleFileContract()) {
        val uri = it.uri
        if (uri != null) {
            val url = uri.toString()
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                context?.alert(R.string.advanced_title_upload_success) {
                    setMessage(url)
                    positiveButton(R.string.copy_text) {
                        requireContext().sendToClip(url)
                        context?.toastOnUi(getString(R.string.copy_complete))
                    }
                    negativeButton(R.string.cancel)
                }
            } else {
                context?.toastOnUi(getString(R.string.advanced_title_exported))
            }
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
        val emptyText = getString(R.string.empty)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dpToPx(), 12.dpToPx(), 18.dpToPx(), 4.dpToPx())
        }

        fun label(text: String) = TextView(context).apply {
            this.text = text
            setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
            applyUiLabelStyle(context)
        }

        fun edit(text: String, minLines: Int = 1) = EditText(context).apply {
            setText(text)
            applyUiInputStyle(context, minLines)
        }

        fun button(text: String) = TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_book_info_subtle_button)
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
            applyUiSubtleButtonStyle(context)
        }

        val scopeGroup = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbGlobal = RadioButton(context).apply {
            text = getString(R.string.advanced_title_scope_global)
            id = 1
        }
        val rbBook = RadioButton(context).apply {
            text = getString(R.string.advanced_title_scope_book)
            id = 2
            isEnabled = book != null
        }
        scopeGroup.addView(rbGlobal)
        scopeGroup.addView(rbBook)
        scopeGroup.check(if (bookRule != null) rbBook.id else rbGlobal.id)

        val useRegexCheck = CheckBox(context).apply {
            text = getString(R.string.advanced_title_use_regex)
            isChecked = startRule.mode == AdvancedTitleConfig.SPLIT_REGEX
        }
        val ruleEdit = edit(
            if (startRule.mode == AdvancedTitleConfig.SPLIT_REGEX) {
                startRule.regex
            } else {
                startRule.delimiter
            }
        )
        val sampleEdit = edit(getString(R.string.advanced_title_sample_default))
        val heightFactorEdit = edit(AdvancedTitleConfig.heightFactor.toString()).apply {
            hint = getString(R.string.advanced_title_height_factor_hint)
        }
        val openEditorButton = button(getString(R.string.advanced_title_open_editor)).apply {
            setOnClickListener { openJsonEditor() }
        }
        val preview = TextView(context).apply {
            setPadding(0, 8.dpToPx(), 0, 0)
            applyUiSectionTitleStyle(context)
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
                getString(
                    R.string.advanced_title_preview_template,
                    parts.s1.ifBlank { emptyText },
                    parts.s2.ifBlank { emptyText }
                )
            }.getOrElse {
                getString(R.string.advanced_title_rule_error, it.localizedMessage.orEmpty())
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
            text = getString(R.string.advanced_title_dialog_title)
            textSize = 18f
            setPadding(0, 2.dpToPx(), 0, 8.dpToPx())
        })
        root.addView(label(getString(R.string.advanced_title_scope_label)))
        root.addView(scopeGroup)
        root.addView(label(getString(R.string.advanced_title_rule_label)))
        root.addView(useRegexCheck)
        root.addView(ruleEdit)
        root.addView(label(getString(R.string.preview)))
        root.addView(sampleEdit)
        root.addView(preview)
        root.addView(label(getString(R.string.advanced_title_height_factor_label)))
        root.addView(heightFactorEdit)
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(getString(R.string.advanced_title_json_label)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
            })
            addView(openEditorButton)
        })
        root.addView(TextView(context).apply {
            text = getString(R.string.advanced_title_json_hint)
            textSize = 12f
            setPadding(0, 4.dpToPx(), 0, 6.dpToPx())
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        })
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button(getString(R.string.import_str)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 6.dpToPx()
                setOnClickListener {
                    importJson.launch {
                        mode = HandleFileContract.FILE
                        title = getString(R.string.advanced_title_import_title)
                        allowExtensions = arrayOf("json")
                        otherActions = arrayListOf(SelectItem(importFromNet, -1))
                    }
                }
            })
            addView(button(getString(R.string.export_str)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                (layoutParams as LinearLayout.LayoutParams).marginStart = 6.dpToPx()
                setOnClickListener {
                    val json = currentJson.trim()
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
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.dpToPx()
            ).apply {
                topMargin = 10.dpToPx()
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
        })
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dpToPx(), 0, 6.dpToPx())
            addView(button(getString(R.string.restore_default)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 6.dpToPx()
                setOnClickListener {
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
                    dismissAllowingStateLoss()
                }
            })
            addView(button(getString(R.string.cancel)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { dismissAllowingStateLoss() }
            })
            addView(button(getString(R.string.confirm)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                (layoutParams as LinearLayout.LayoutParams).marginStart = 6.dpToPx()
                setOnClickListener {
                    val rule = buildRule()
                    val json = currentJson.trim()
                    if (json.isNotEmpty() && !AdvancedTitleConfig.isValidLottieJson(json)) {
                        context.toastOnUi(getString(R.string.advanced_title_invalid_json))
                        return@setOnClickListener
                    }
                    AdvancedTitleConfig.lottieJson = json
                    AdvancedTitleConfig.lottiePath = null
                    val parsedHeight = heightFactorEdit.text?.toString()?.trim()?.toIntOrNull()
                    AdvancedTitleConfig.heightFactor = (parsedHeight
                        ?: AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR).coerceIn(30, 120)
                    if (scopeGroup.checkedRadioButtonId == rbBook.id && book != null) {
                        AdvancedTitleConfig.setBookRule(book, rule)
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { book.save() }
                        }
                    } else {
                        AdvancedTitleConfig.globalRule = rule
                    }
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5, 8))
                    dismissAllowingStateLoss()
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
            background = context.dialogSurfaceBackground
            addView(
                scrollView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        container.applyUiBodyTypefaceDeep(context.uiTypeface())

        return AlertDialog.Builder(context)
            .setView(container)
            .create()
    }

    private fun importNetJsonAlert() {
        context?.alert(R.string.advanced_title_input_url) {
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
                withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody {
                        url(url)
                    }.use { body ->
                        body.string()
                    }
                }
            }.onSuccess { text ->
                currentJson = text
                jsonCursorPosition = text.length
            }.onFailure { error ->
                context?.toastOnUi(
                    getString(R.string.advanced_title_import_net_failed, error.localizedMessage.orEmpty())
                )
            }
        }
    }

    private fun openJsonEditor() {
        jsonEditLauncher.launch(Intent(requireActivity(), CodeEditActivity::class.java).apply {
            putExtra("text", currentJson)
            putExtra("title", getString(R.string.advanced_title_json_label))
            putExtra("cursorPosition", jsonCursorPosition.coerceIn(0, currentJson.length))
        })
    }
}
