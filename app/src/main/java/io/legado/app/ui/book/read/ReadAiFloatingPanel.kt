package io.legado.app.ui.book.read

import android.content.Context
import android.os.Build
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.databinding.ViewReadAiFloatingPanelBinding
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setMarkdown
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ReadAiFloatingPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    data class ReadContext(
        val bookUrl: String,
        val bookName: String,
        val author: String,
        val sourceName: String,
        val chapterTitle: String,
        val chapterIndex: Int,
        val selectedText: String
    )

    private val binding = ViewReadAiFloatingPanelBinding.inflate(LayoutInflater.from(context), this, true)
    private val markwon: Markwon by lazy {
        Markwon.builder(context)
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
    }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private var lifecycleOwner: LifecycleOwner? = null
    private var readContext: ReadContext? = null
    private var answerJob: Job? = null
    private var showingHistory = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f

    init {
        orientation = VERTICAL
        binding.tvAnswer.movementMethod = LinkMovementMethod()
        binding.btnClose.setOnClickListener { close() }
        binding.btnHistory.setOnClickListener { toggleHistory() }
        binding.btnClearHistory.setOnClickListener { confirmClearHistory() }
        binding.btnSend.setOnClickListener { askFromInput() }
        binding.etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                askFromInput()
                true
            } else {
                false
            }
        }
        binding.dragHandle.setOnTouchListener { _, event -> handleDrag(event) }
        applyTheme()
    }

    fun attach(lifecycleOwner: LifecycleOwner) {
        this.lifecycleOwner = lifecycleOwner
    }

    fun open(readContext: ReadContext) {
        this.readContext = readContext
        showingHistory = false
        binding.historyContainer.isGone = true
        binding.answerContainer.isVisible = true
        binding.tvContext.text = buildContextLabel(readContext)
        binding.etQuestion.setText("")
        visibility = VISIBLE
        bringToFront()
        post { ensureInsideParent() }
        ask(readContext.selectedText)
    }

    fun close() {
        answerJob?.cancel()
        visibility = GONE
    }

    private fun askFromInput() {
        val question = binding.etQuestion.text?.toString().orEmpty().trim()
        if (question.isBlank()) return
        binding.etQuestion.setText("")
        showingHistory = false
        binding.historyContainer.isGone = true
        binding.answerContainer.isVisible = true
        ask(question)
    }

    private fun ask(question: String) {
        val owner = lifecycleOwner ?: return
        val readContext = readContext ?: return
        answerJob?.cancel()
        renderAnswer(resources.getString(R.string.ai_chat_thinking))
        answerJob = owner.lifecycleScope.launch {
            val result = runCatching {
                withContext(IO) {
                    AiChatService.chatStream(
                        messages = listOf(
                            AiChatMessage(
                                role = AiChatMessage.Role.USER,
                                content = buildPrompt(readContext, question)
                            )
                        ),
                        onPartial = { partial ->
                            if (partial.isNotBlank()) {
                                post { renderAnswer(partial) }
                            }
                        }
                    )
                }
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                throwable.localizedMessage ?: throwable.toString()
            }
            renderAnswer(result)
            saveHistory(readContext, question, result)
        }
    }

    private fun renderAnswer(content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.tvAnswer.setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
        }
        binding.tvAnswer.setMarkdown(markwon, markwon.toMarkdown(content), imgOnLongClickListener = {})
        binding.answerContainer.post {
            binding.answerContainer.fullScroll(FOCUS_DOWN)
        }
    }

    private fun saveHistory(context: ReadContext, question: String, answer: String) {
        if (answer.isBlank() || answer == resources.getString(R.string.ai_chat_thinking)) return
        val list = AppConfig.aiReadHistoryList.toMutableList()
        val index = list.indexOfFirst { it.bookUrl == context.bookUrl }
        val old = list.getOrNull(index)
        val record = ReadAiHistoryRecord(
            question = question,
            answer = answer,
            chapterTitle = context.chapterTitle,
            chapterIndex = context.chapterIndex
        )
        val updated = ReadAiBookHistory(
            bookUrl = context.bookUrl,
            bookName = context.bookName,
            updatedAt = System.currentTimeMillis(),
            records = listOf(record) + (old?.records.orEmpty().filterNot { it.id == record.id })
        )
        if (index >= 0) {
            list[index] = updated
        } else {
            list.add(0, updated)
        }
        AppConfig.aiReadHistoryList = list
        if (showingHistory) {
            renderHistory()
        }
    }

    private fun toggleHistory() {
        showingHistory = !showingHistory
        binding.historyContainer.isVisible = showingHistory
        binding.answerContainer.isGone = showingHistory
        if (showingHistory) {
            renderHistory()
        }
    }

    private fun renderHistory() {
        val context = readContext ?: return
        val records = AppConfig.aiReadHistoryList
            .firstOrNull { it.bookUrl == context.bookUrl }
            ?.records
            .orEmpty()
        binding.historyList.removeAllViews()
        if (records.isEmpty()) {
            binding.historyList.addView(makeHistoryEmptyView())
            return
        }
        records.forEach { record ->
            binding.historyList.addView(makeHistoryItem(record))
        }
    }

    private fun makeHistoryEmptyView(): View {
        return TextView(context).apply {
            text = resources.getString(R.string.ai_read_history_empty)
            setTextColor(context.secondaryTextColor)
            textSize = 14f
            setPadding(12.dpToPx(), 18.dpToPx(), 12.dpToPx(), 18.dpToPx())
        }
    }

    private fun makeHistoryItem(record: ReadAiHistoryRecord): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = resources.getDrawable(R.drawable.bg_read_ai_history_item, context.theme)
            setPadding(12.dpToPx(), 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 8.dpToPx())
            layoutParams = lp
        }
        val text = TextView(context).apply {
            text = buildString {
                append(record.question.lineSequence().firstOrNull().orEmpty())
                if (record.chapterTitle.isNotBlank()) append("\n").append(record.chapterTitle)
                append(" · ").append(timeFormat.format(Date(record.createdAt)))
            }
            setTextColor(context.primaryTextColor)
            textSize = 13f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val delete = TextView(context).apply {
            text = resources.getString(R.string.delete)
            setTextColor(context.accentColor)
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(10.dpToPx(), 0, 4.dpToPx(), 0)
            setOnClickListener { deleteHistoryRecord(record.id) }
        }
        row.addView(delete, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        row.setOnClickListener {
            showingHistory = false
            binding.historyContainer.isGone = true
            binding.answerContainer.isVisible = true
            binding.tvContext.text = record.chapterTitle.ifBlank { buildContextLabel(readContext ?: return@setOnClickListener) }
            renderAnswer(record.answer)
        }
        row.setOnLongClickListener {
            deleteHistoryRecord(record.id)
            true
        }
        return row
    }

    private fun deleteHistoryRecord(recordId: String) {
        val context = readContext ?: return
        val list = AppConfig.aiReadHistoryList.toMutableList()
        val index = list.indexOfFirst { it.bookUrl == context.bookUrl }
        if (index < 0) return
        val old = list[index]
        val records = old.records.filterNot { it.id == recordId }
        if (records.isEmpty()) {
            list.removeAt(index)
        } else {
            list[index] = old.copy(updatedAt = System.currentTimeMillis(), records = records)
        }
        AppConfig.aiReadHistoryList = list
        renderHistory()
    }

    private fun confirmClearHistory() {
        val context = readContext ?: return
        AlertDialog.Builder(this.context)
            .setMessage(R.string.ai_read_clear_history_confirm)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                AppConfig.aiReadHistoryList =
                    AppConfig.aiReadHistoryList.filterNot { it.bookUrl == context.bookUrl }
                if (showingHistory) renderHistory()
            }
            .show()
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        val parentView = parent as? ViewGroup ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = x
                startY = y
                parentView.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val targetX = startX + event.rawX - downRawX
                val targetY = startY + event.rawY - downRawY
                x = targetX.coerceIn(0f, max(0, parentView.width - width).toFloat())
                y = targetY.coerceIn(0f, max(0, parentView.height - height).toFloat())
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ensureInsideParent()
                parentView.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun ensureInsideParent() {
        val parentView = parent as? ViewGroup ?: return
        if (width <= 0 || height <= 0 || parentView.width <= 0 || parentView.height <= 0) return
        x = min(max(0f, x), max(0, parentView.width - width).toFloat())
        y = min(max(0f, y), max(0, parentView.height - height).toFloat())
    }

    private fun applyTheme() {
        binding.btnSend.backgroundTintList = android.content.res.ColorStateList.valueOf(context.accentColor)
        binding.btnSend.setColorFilter(android.graphics.Color.WHITE)
    }

    private fun buildContextLabel(context: ReadContext): String {
        return buildString {
            append(context.bookName.ifBlank { resources.getString(R.string.book_name) })
            if (context.chapterTitle.isNotBlank()) append(" · ").append(context.chapterTitle)
        }
    }

    private fun buildPrompt(context: ReadContext, question: String): String {
        return """
            你是阅读页里的问 AI 助手。请围绕当前书籍和选中文本回答，优先解释原文含义、上下文、人物关系、伏笔或用户提问，不要编造未给出的剧情。

            书名：${context.bookName}
            作者：${context.author.ifBlank { "未知" }}
            书源：${context.sourceName.ifBlank { "未知" }}
            当前章节：${context.chapterTitle.ifBlank { "未知" }}（第 ${context.chapterIndex + 1} 章）

            用户选中或追问：
            $question
        """.trimIndent()
    }
}
