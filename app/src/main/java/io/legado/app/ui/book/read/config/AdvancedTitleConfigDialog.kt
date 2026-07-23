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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiInputStyle
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.applyUiSubtleButtonStyle
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi

class AdvancedTitleConfigDialog : DialogFragment() {

    companion object {
        private const val ARG_ENTRY_ID = "entryId"
        private const val ARG_NAME = "name"
        private const val ARG_SPLIT_MODE = "splitMode"
        private const val ARG_DELIMITER = "delimiter"
        private const val ARG_REGEX = "regex"
        private const val ARG_HEIGHT_FACTOR = "heightFactor"

        fun edit(
            entryId: String,
            name: String,
            json: String,
            splitRule: AdvancedTitleConfig.SplitRule,
            heightFactor: Int
        ) = AdvancedTitleConfigDialog().apply {
            currentJson = json
            arguments = Bundle().apply {
                putString(ARG_ENTRY_ID, entryId)
                putString(ARG_NAME, name)
                putInt(ARG_SPLIT_MODE, splitRule.mode)
                putString(ARG_DELIMITER, splitRule.delimiter)
                putString(ARG_REGEX, splitRule.regex)
                putInt(ARG_HEIGHT_FACTOR, heightFactor.coerceIn(30, 120))
            }
        }
    }

    private var currentJson: String = ""
    private var jsonCursorPosition: Int = 0

    interface Host {
        fun onAdvancedTitleSaved(
            entryId: String,
            name: String,
            json: String,
            splitRule: AdvancedTitleConfig.SplitRule,
            heightFactor: Int
        )
    }

    private val jsonEditor = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val cacheKey = data.getStringExtra("cacheKey")
        val text = if (cacheKey != null) {
            CacheManager.getFromMemory(cacheKey) as? String
        } else {
            data.getStringExtra("text")
        } ?: return@registerForActivityResult
        currentJson = text
        jsonCursorPosition = data.getIntExtra("cursorPosition", text.length)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (resources.displayMetrics.heightPixels * 0.72f).toInt()
            )
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val args = requireArguments()
        val entryId = args.getString(ARG_ENTRY_ID).orEmpty()
        val initialName = args.getString(ARG_NAME).orEmpty()
        val startRule = AdvancedTitleConfig.SplitRule(
            mode = args.getInt(ARG_SPLIT_MODE, AdvancedTitleConfig.SPLIT_DELIMITER),
            delimiter = args.getString(ARG_DELIMITER) ?: " ",
            regex = args.getString(ARG_REGEX) ?: AdvancedTitleConfig.DEFAULT_REGEX
        )
        val initialHeightFactor = args.getInt(
            ARG_HEIGHT_FACTOR,
            AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR
        )
        if (currentJson.isBlank()) {
            currentJson = runCatching {
                AdvancedTitlePackageManager.readTemplate(entryId)
            }.getOrDefault("")
        }
        val emptyText = getString(R.string.empty)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dpToPx(), 12.dpToPx(), 18.dpToPx(), 4.dpToPx())
        }

        fun label(value: String) = TextView(context).apply {
            text = value
            setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
            applyUiLabelStyle(context)
        }

        fun edit(value: String) = EditText(context).apply {
            setText(value)
            applyUiInputStyle(context, 1)
        }

        fun button(value: String) = TextView(context).apply {
            text = value
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_book_info_subtle_button)
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
            applyUiSubtleButtonStyle(context)
        }

        val nameEdit = edit(initialName).apply {
            hint = getString(R.string.advanced_title_name)
            isSingleLine = true
        }

        val regexCheck = CheckBox(context).apply {
            text = getString(R.string.advanced_title_use_regex)
            isChecked = startRule.mode == AdvancedTitleConfig.SPLIT_REGEX
            typeface = context.uiTypeface()
        }
        val ruleEdit = edit(
            if (startRule.mode == AdvancedTitleConfig.SPLIT_REGEX) startRule.regex
            else startRule.delimiter
        )
        val sampleEdit = edit(getString(R.string.advanced_title_sample_default))
        val heightEdit = edit(initialHeightFactor.coerceIn(30, 120).toString()).apply {
            hint = getString(R.string.advanced_title_height_factor_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val preview = TextView(context).apply {
            setPadding(0, 8.dpToPx(), 0, 0)
            applyUiSectionTitleStyle(context)
        }
        val openEditorButton = button(getString(R.string.advanced_title_open_editor)).apply {
            setOnClickListener { openJsonEditor() }
        }

        fun buildRule() = AdvancedTitleConfig.SplitRule(
            mode = if (regexCheck.isChecked) {
                AdvancedTitleConfig.SPLIT_REGEX
            } else {
                AdvancedTitleConfig.SPLIT_DELIMITER
            },
            delimiter = if (regexCheck.isChecked) startRule.delimiter
            else ruleEdit.text?.toString().orEmpty(),
            regex = if (regexCheck.isChecked) ruleEdit.text?.toString().orEmpty()
            else startRule.regex
        )

        fun updatePreview() {
            preview.text = runCatching {
                val parts = AdvancedTitleConfig.split(
                    sampleEdit.text?.toString().orEmpty(),
                    buildRule()
                )
                getString(
                    R.string.advanced_title_preview_template,
                    parts.s1.ifBlank { emptyText },
                    parts.s2.ifBlank { emptyText }
                )
            }.getOrElse {
                getString(R.string.advanced_title_rule_error, it.localizedMessage.orEmpty())
            }
        }

        listOf(ruleEdit, sampleEdit).forEach { field ->
            field.doAfterTextChanged { updatePreview() }
        }
        regexCheck.setOnCheckedChangeListener { _, checked ->
            ruleEdit.setText(if (checked) startRule.regex else startRule.delimiter)
            ruleEdit.setSelection(ruleEdit.text?.length ?: 0)
            updatePreview()
        }

        root.addView(TextView(context).apply {
            text = getString(R.string.advanced_title_edit_title)
            textSize = 18f
            applyUiTitleTypeface(context)
            setPadding(0, 2.dpToPx(), 0, 8.dpToPx())
        })
        root.addView(label(getString(R.string.advanced_title_name)))
        root.addView(nameEdit)
        root.addView(label(getString(R.string.advanced_title_rule_label)))
        root.addView(regexCheck)
        root.addView(ruleEdit)
        root.addView(label(getString(R.string.preview)))
        root.addView(sampleEdit)
        root.addView(preview)
        root.addView(label(getString(R.string.advanced_title_height_factor_label)))
        root.addView(heightEdit)
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(getString(R.string.advanced_title_json_label)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            addView(openEditorButton)
        })
        root.addView(TextView(context).apply {
            text = getString(R.string.advanced_title_json_hint)
            textSize = 12f
            typeface = context.uiTypeface()
            setPadding(0, 4.dpToPx(), 0, 6.dpToPx())
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        })
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.dpToPx()
            ).apply { topMargin = 12.dpToPx() }
            setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
        })
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12.dpToPx(), 0, 6.dpToPx())
            addView(button(getString(R.string.cancel)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { dismissAllowingStateLoss() }
            })
            addView(button(getString(R.string.confirm)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 6.dpToPx() }
                setOnClickListener {
                    val name = nameEdit.text?.toString()?.trim().orEmpty()
                    if (name.isEmpty()) {
                        context.toastOnUi(getString(R.string.advanced_title_name_required))
                        return@setOnClickListener
                    }
                    val json = currentJson.trim()
                    val jsonError = runCatching {
                        AdvancedTitlePackageManager.validateJson(json)
                    }.exceptionOrNull()
                    if (jsonError != null) {
                        context.toastOnUi(
                            jsonError.localizedMessage
                                ?: getString(R.string.advanced_title_invalid_json)
                        )
                        return@setOnClickListener
                    }
                    val rule = buildRule()
                    val heightFactor = heightEdit.text?.toString()
                        ?.trim()
                        ?.toIntOrNull()
                        ?.coerceIn(30, 120)
                        ?: AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR
                    dismissAllowingStateLoss()
                    (activity as? Host)?.onAdvancedTitleSaved(
                        entryId,
                        name,
                        json,
                        rule,
                        heightFactor
                    )
                }
            })
        })

        updatePreview()

        val scroll = ScrollView(context).apply {
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
                scroll,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        container.applyUiBodyTypefaceDeep(context.uiTypeface())
        return AlertDialog.Builder(context).setView(container).create()
    }

    private fun openJsonEditor() {
        val bytes = AdvancedTitlePackageManager.utf8SizeUpTo(
            currentJson,
            AdvancedTitlePackageManager.MAX_EDITABLE_JSON_BYTES
        )
        if (bytes > AdvancedTitlePackageManager.MAX_EDITABLE_JSON_BYTES) {
            context?.toastOnUi(R.string.advanced_title_json_too_large_to_edit)
            return
        }
        // ~300KB+ JSON already crashes many devices if stuffed into Intent extras.
        jsonEditor.launch(Intent(requireContext(), CodeEditActivity::class.java).apply {
            val key = "advanced_title_edit_" + System.nanoTime()
            CacheManager.putMemory(key, currentJson)
            putExtra("cacheKey", key)
            putExtra("writable", true)
            putExtra("title", getString(R.string.advanced_title_json_label))
            putExtra("cursorPosition", jsonCursorPosition.coerceIn(0, currentJson.length))
        })
    }
}
