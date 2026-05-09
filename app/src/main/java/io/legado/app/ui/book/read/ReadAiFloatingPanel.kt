package io.legado.app.ui.book.read

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.ItemReadAiMessageBinding
import io.legado.app.databinding.ViewReadAiFloatingPanelBinding
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.toastOnUi
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    data class Anchor(
        val centerX: Int,
        val topY: Int,
        val bottomY: Int
    )

    private val binding = ViewReadAiFloatingPanelBinding.inflate(LayoutInflater.from(context), this, true)
    private val markwon: Markwon by lazy {
        Markwon.builder(context)
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
    }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val messageAdapter = MessageAdapter()
    private var lifecycleOwner: LifecycleOwner? = null
    private var readContext: ReadContext? = null
    private var currentSessionId: String = ""
    private var answerJob: Job? = null
    private var showingHistory = false
    private var streamingAssistantContent: String? = null
    private var streamingAssistantMessageId: String? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f

    init {
        orientation = VERTICAL
        binding.root.applyUiBodyTypefaceDeep(context.uiTypeface())
        binding.answerContainer.layoutManager = LinearLayoutManager(context).apply {
            stackFromEnd = true
        }
        binding.answerContainer.adapter = messageAdapter
        binding.btnClose.setOnClickListener { close() }
        binding.btnNewChat.setOnClickListener { startNewChat() }
        binding.btnHistory.setOnClickListener { toggleHistory() }
        binding.btnSend.setOnClickListener {
            if (answerJob?.isActive == true) {
                stopAnswer()
            } else {
                askFromInput()
            }
        }
        binding.etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (AppConfig.aiEnterToSend && actionId == EditorInfo.IME_ACTION_SEND) {
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

    fun open(readContext: ReadContext, anchor: Anchor? = null) {
        this.readContext = readContext
        currentSessionId = ensureSession(readContext, createNew = false).id
        showingHistory = false
        showMessages()
        binding.tvContext.text = buildContextLabel(readContext)
        binding.etQuestion.setText("")
        animate().cancel()
        translationY = 0f
        if (visibility != VISIBLE) {
            alpha = 0f
            visibility = VISIBLE
        } else {
            visibility = VISIBLE
        }
        bringToFront()
        doOnLayout {
            if (anchor != null) {
                placeNearAnchor(anchor)
            }
            ensureInsideParent()
            if (alpha < 1f) {
                animate()
                    .alpha(1f)
                    .setDuration(160L)
                    .start()
            }
        }
        if (readContext.selectedText.isNotBlank()) {
            ask(readContext.selectedText)
        }
    }

    fun close() {
        updateSendButtonState()
        visibility = GONE
    }

    private fun stopAnswer() {
        val context = readContext
        answerJob?.cancel()
        streamingAssistantContent = null
        streamingAssistantMessageId = null
        if (context != null) {
            val pending = currentBookHistory(context).sessions
                .firstOrNull { it.id == currentSessionId }
                ?.messages
                ?.lastOrNull()
            if (pending?.role == ReadAiMessage.Role.ASSISTANT &&
                pending.content == resources.getString(R.string.ai_chat_thinking)
            ) {
                replaceMessage(context, pending.id, resources.getString(R.string.ai_chat_cancelled))
            }
        }
        updateSendButtonState()
        if (!showingHistory) renderCurrentSession()
    }

    private fun startNewChat() {
        val context = readContext ?: return
        answerJob?.cancel()
        streamingAssistantContent = null
        streamingAssistantMessageId = null
        currentSessionId = ensureSession(context, createNew = true).id
        showingHistory = false
        showMessages()
        binding.tvContext.text = buildContextLabel(context)
    }

    private fun askFromInput() {
        val question = binding.etQuestion.text?.toString().orEmpty().trim()
        if (question.isBlank()) return
        binding.etQuestion.setText("")
        showingHistory = false
        showMessages()
        ask(question)
    }

    private fun ask(question: String) {
        val context = readContext ?: return
        answerJob?.cancel()
        val requestSessionId = currentSessionId
        appendMessage(context, ReadAiMessage.Role.USER, question)
        val pendingAssistantId = appendMessage(
            context,
            ReadAiMessage.Role.ASSISTANT,
            resources.getString(R.string.ai_chat_thinking)
        )
        val requestMessages = buildRequestMessages(context, question)
        streamingAssistantMessageId = pendingAssistantId
        answerJob = requestScope.launch {
            post { updateSendButtonState() }
            val result = runCatching {
                withContext(IO) {
                    AiChatService.chatStream(
                        messages = requestMessages,
                        onPartial = { partial ->
                            if (partial.isNotBlank()) {
                                post {
                                    streamingAssistantContent = partial
                                    if (!showingHistory) renderCurrentSession()
                                }
                            }
                        },
                        includeStructuredBlocks = false
                    )
                }
            }
            post {
                streamingAssistantContent = null
                streamingAssistantMessageId = null
                val content = result.fold(
                    onSuccess = { it.ifBlank { resources.getString(R.string.ai_chat_cancelled) } },
                    onFailure = { throwable ->
                        if (throwable is CancellationException) {
                            resources.getString(R.string.ai_chat_cancelled)
                        } else {
                            resources.getString(
                                R.string.ai_request_failed,
                                throwable.localizedMessage
                                    ?: throwable.message
                                    ?: resources.getString(R.string.ai_request_cancelled)
                            )
                        }
                    }
                )
                replaceMessage(context, pendingAssistantId, content, requestSessionId)
                answerJob = null
                updateSendButtonState()
                if (!showingHistory) renderCurrentSession()
            }
        }
        updateSendButtonState()
    }

    private fun showMessages() {
        binding.historyContainer.isGone = true
        binding.answerContainer.isVisible = true
        renderCurrentSession()
    }

    private fun renderCurrentSession() {
        val context = readContext ?: return
        val session = currentBookHistory(context).sessions.firstOrNull { it.id == currentSessionId }
        val messages = session?.messages.orEmpty()
        val displayMessages = streamingAssistantContent?.let { partial ->
            messages.dropLast(1) + (messages.lastOrNull()?.copy(content = partial)
                ?: ReadAiMessage(role = ReadAiMessage.Role.ASSISTANT, content = partial))
        } ?: messages
        if (displayMessages.isEmpty()) {
            renderMessages(
                listOf(
                    ReadAiMessage(
                        role = ReadAiMessage.Role.ASSISTANT,
                        content = resources.getString(R.string.ai_chat_empty)
                    )
                ),
                allowDelete = false
            )
        } else {
            renderMessages(displayMessages, allowDelete = true)
        }
    }

    private fun renderMessages(messages: List<ReadAiMessage>, allowDelete: Boolean) {
        messageAdapter.allowDelete = allowDelete
        messageAdapter.streamingMessageId = streamingAssistantMessageId
        messageAdapter.submit(messages)
        binding.answerContainer.post {
            if (messageAdapter.itemCount > 0) {
                binding.answerContainer.scrollToPosition(messageAdapter.itemCount - 1)
            }
        }
    }

    private fun toggleHistory() {
        showingHistory = !showingHistory
        binding.historyContainer.isVisible = showingHistory
        binding.answerContainer.isGone = showingHistory
        if (showingHistory) {
            renderHistory()
        } else {
            renderCurrentSession()
        }
    }

    private fun renderHistory() {
        val context = readContext ?: return
        val sessions = currentBookHistory(context).sessions
        binding.historyList.removeAllViews()
        if (sessions.isEmpty()) {
            binding.historyList.addView(makeHistoryEmptyView())
            return
        }
        sessions.forEach { session ->
            binding.historyList.addView(makeHistoryItem(session))
        }
        binding.historyList.addView(makeClearAllView())
    }

    private fun makeHistoryEmptyView(): View {
        return TextView(context).apply {
            text = resources.getString(R.string.ai_read_history_empty)
            applyUiLabelStyle(context)
            setTextColor(context.secondaryTextColor)
            setPadding(12.dpToPx(), 18.dpToPx(), 12.dpToPx(), 18.dpToPx())
        }
    }

    private fun makeHistoryItem(session: ReadAiSession): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = resources.getDrawable(R.drawable.bg_read_ai_history_item, context.theme)
            setPadding(12.dpToPx(), 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 8.dpToPx())
            layoutParams = lp
        }
        val titleView = TextView(context).apply {
            text = buildString {
                append(session.title.ifBlank { resources.getString(R.string.ai_new_chat) })
                if (session.chapterTitle.isNotBlank()) append("\n").append(session.chapterTitle)
                append(" · ").append(timeFormat.format(Date(session.updatedAt)))
            }
            applyUiLabelStyle(context)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val deleteView = TextView(context).apply {
            text = resources.getString(R.string.delete)
            applyUiLabelStyle(context)
            setTextColor(context.accentColor)
            gravity = android.view.Gravity.CENTER
            setPadding(10.dpToPx(), 0, 4.dpToPx(), 0)
            setOnClickListener { deleteSession(session.id) }
        }
        row.addView(deleteView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        row.setOnClickListener {
            currentSessionId = session.id
            setCurrentSession(readContext ?: return@setOnClickListener, session.id)
            showingHistory = false
            showMessages()
        }
        row.setOnLongClickListener {
            deleteSession(session.id)
            true
        }
        return row
    }

    private fun makeClearAllView(): View {
        return TextView(context).apply {
            text = resources.getString(R.string.ai_read_clear_history)
            applyUiSectionTitleStyle(context)
            setTextColor(context.accentColor)
            gravity = android.view.Gravity.CENTER
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
            setOnClickListener { confirmClearHistory() }
        }
    }

    private fun ensureSession(context: ReadContext, createNew: Boolean): ReadAiSession {
        val history = currentBookHistory(context)
        if (!createNew) {
            val current = history.sessions.firstOrNull { it.id == history.currentSessionId }
                ?: history.sessions.firstOrNull()
            if (current != null) return current
        }
        val session = ReadAiSession(
            title = context.selectedText.lineSequence().firstOrNull()?.take(24).orEmpty()
                .ifBlank { resources.getString(R.string.ai_new_chat) },
            chapterTitle = context.chapterTitle,
            chapterIndex = context.chapterIndex
        )
        saveBookHistory(
            context,
            history.copy(
                updatedAt = System.currentTimeMillis(),
                currentSessionId = session.id,
                sessions = listOf(session) + history.sessions
            )
        )
        return session
    }

    private fun appendMessage(context: ReadContext, role: ReadAiMessage.Role, content: String): String {
        val message = ReadAiMessage(role = role, content = content)
        updateCurrentSession(context) { session ->
            val title = if (session.title.isBlank() && role == ReadAiMessage.Role.USER) {
                content.lineSequence().firstOrNull().orEmpty().take(24)
            } else {
                session.title
            }
            session.copy(
                title = title,
                updatedAt = System.currentTimeMillis(),
                messages = session.messages + message
            )
        }
        if (!showingHistory) renderCurrentSession()
        return message.id
    }

    private fun replaceMessage(
        context: ReadContext,
        messageId: String,
        content: String,
        sessionId: String = currentSessionId
    ) {
        updateSession(context, sessionId) { session ->
            session.copy(
                updatedAt = System.currentTimeMillis(),
                messages = session.messages.map {
                    if (it.id == messageId) it.copy(content = content) else it
                }
            )
        }
    }

    private fun deleteMessage(context: ReadContext, messageId: String) {
        updateSession(context, currentSessionId) { session ->
            session.copy(
                updatedAt = System.currentTimeMillis(),
                messages = session.messages.filterNot { it.id == messageId }
            )
        }
        renderCurrentSession()
    }

    private fun deleteSession(sessionId: String) {
        val context = readContext ?: return
        val history = currentBookHistory(context)
        val sessions = history.sessions.filterNot { it.id == sessionId }
        if (sessions.isEmpty()) {
            AppConfig.aiReadHistoryList = AppConfig.aiReadHistoryList.filterNot { it.bookUrl == context.bookUrl }
            currentSessionId = ""
        } else {
            val nextId = if (currentSessionId == sessionId) sessions.first().id else currentSessionId
            currentSessionId = nextId
            saveBookHistory(
                context,
                history.copy(
                    updatedAt = System.currentTimeMillis(),
                    currentSessionId = nextId,
                    sessions = sessions
                )
            )
        }
        if (showingHistory) renderHistory() else renderCurrentSession()
    }

    private fun confirmClearHistory() {
        val context = readContext ?: return
        AlertDialog.Builder(this.context)
            .setMessage(R.string.ai_read_clear_history_confirm)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                AppConfig.aiReadHistoryList =
                    AppConfig.aiReadHistoryList.filterNot { it.bookUrl == context.bookUrl }
                currentSessionId = ""
                if (showingHistory) renderHistory() else renderCurrentSession()
            }
            .show()
    }

    private fun updateCurrentSession(context: ReadContext, mapper: (ReadAiSession) -> ReadAiSession) {
        updateSession(context, currentSessionId, mapper)
    }

    private fun updateSession(
        context: ReadContext,
        sessionId: String,
        mapper: (ReadAiSession) -> ReadAiSession
    ) {
        val history = currentBookHistory(context)
        val session = history.sessions.firstOrNull { it.id == sessionId }
            ?: ensureSession(context, createNew = false)
        val mapped = mapper(session)
        saveBookHistory(
            context,
            history.copy(
                updatedAt = System.currentTimeMillis(),
                currentSessionId = mapped.id,
                sessions = listOf(mapped) + history.sessions.filterNot { it.id == mapped.id }
            )
        )
    }

    private fun setCurrentSession(context: ReadContext, sessionId: String) {
        saveBookHistory(context, currentBookHistory(context).copy(currentSessionId = sessionId))
    }

    private fun currentBookHistory(context: ReadContext): ReadAiBookHistory {
        return AppConfig.aiReadHistoryList.firstOrNull { it.bookUrl == context.bookUrl }
            ?: ReadAiBookHistory(bookUrl = context.bookUrl, bookName = context.bookName)
    }

    private fun saveBookHistory(context: ReadContext, history: ReadAiBookHistory) {
        val list = AppConfig.aiReadHistoryList.toMutableList()
        val index = list.indexOfFirst { it.bookUrl == context.bookUrl }
        val normalized = history.copy(
            bookUrl = context.bookUrl,
            bookName = context.bookName,
            updatedAt = System.currentTimeMillis()
        )
        if (index >= 0) {
            list[index] = normalized
        } else {
            list.add(0, normalized)
        }
        AppConfig.aiReadHistoryList = list
        currentSessionId = normalized.currentSessionId
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

    private fun placeNearAnchor(anchor: Anchor) {
        val parentView = parent as? ViewGroup ?: return
        if (width <= 0 || height <= 0 || parentView.width <= 0 || parentView.height <= 0) return
        val margin = 10.dpToPx()
        val preferredX = anchor.centerX - width / 2
        val maxX = (parentView.width - width - margin).coerceAtLeast(margin)
        x = preferredX.toFloat().coerceIn(margin.toFloat(), maxX.toFloat())
        val spaceAbove = anchor.topY - margin
        val spaceBelow = parentView.height - anchor.bottomY - margin
        y = if (spaceBelow >= height || spaceBelow >= spaceAbove) {
            (anchor.bottomY + margin).toFloat()
                .coerceAtMost((parentView.height - height - margin).toFloat())
        } else {
            (anchor.topY - height - margin).toFloat()
                .coerceAtLeast(margin.toFloat())
        }
    }

    private fun applyTheme() {
        binding.btnSend.backgroundTintList = ColorStateList.valueOf(context.accentColor)
        binding.btnSend.setColorFilter(Color.WHITE)
        binding.btnClose.imageTintList = ColorStateList.valueOf(context.secondaryTextColor)
        binding.btnHistory.imageTintList = ColorStateList.valueOf(context.secondaryTextColor)
        binding.btnNewChat.imageTintList = ColorStateList.valueOf(context.secondaryTextColor)
        binding.inputContainer.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.adjustAlpha(context.primaryTextColor, 0.06f))
        updateSendButtonState()
    }

    private fun updateSendButtonState() {
        val requesting = answerJob?.isActive == true
        binding.btnSend.contentDescription = resources.getString(
            if (requesting) R.string.ai_chat_stop else R.string.ai_chat_send
        )
        binding.btnSend.setImageResource(
            if (requesting) R.drawable.ic_stop_black_24dp else R.drawable.ic_arrow_right
        )
    }

    private fun buildContextLabel(context: ReadContext): String {
        return buildString {
            append(context.bookName.ifBlank { resources.getString(R.string.book_name) })
            if (context.chapterTitle.isNotBlank()) append(" · ").append(context.chapterTitle)
        }
    }

    private fun buildPrompt(context: ReadContext, question: String): String {
        return resources.getString(
            R.string.ai_read_prompt_template,
            context.bookName,
            context.author.ifBlank { resources.getString(R.string.unknown) },
            context.sourceName.ifBlank { resources.getString(R.string.unknown) },
            context.chapterTitle.ifBlank { resources.getString(R.string.unknown) },
            context.chapterIndex + 1,
            question
        )
    }

    private fun buildRequestMessages(context: ReadContext, question: String): List<AiChatMessage> {
        val historyMessages = currentBookHistory(context).sessions
            .firstOrNull { it.id == currentSessionId }
            ?.messages
            .orEmpty()
            .dropLast(2)
            .takeLast(12)
            .mapNotNull { message ->
                val content = message.content.trim()
                if (content.isBlank()) return@mapNotNull null
                AiChatMessage(
                    role = when (message.role) {
                        ReadAiMessage.Role.USER -> AiChatMessage.Role.USER
                        ReadAiMessage.Role.ASSISTANT -> AiChatMessage.Role.ASSISTANT
                    },
                    content = content
                )
            }
        return historyMessages + AiChatMessage(
            role = AiChatMessage.Role.USER,
            content = buildPrompt(context, question)
        )
    }

    private fun createBubble(fillColor: Int, strokeColor: Int, isUser: Boolean): GradientDrawable {
        val large = 18f.dpToPx()
        val small = 7f.dpToPx()
        return GradientDrawable().apply {
            cornerRadii = if (isUser) {
                floatArrayOf(
                    large, large,
                    large, large,
                    small, small,
                    large, large
                )
            } else {
                floatArrayOf(
                    large, large,
                    large, large,
                    large, large,
                    small, small
                )
            }
            setColor(fillColor)
            setStroke(1.dpToPx(), strokeColor)
        }
    }

    private fun showMessageActions(anchor: View, message: ReadAiMessage) {
        PopupMenu(context, anchor).apply {
            menu.add(0, actionCopyMessage, 0, R.string.copy_text)
            if (message.id.isNotBlank()) {
                menu.add(0, actionDeleteMessage, 1, R.string.delete)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    actionCopyMessage -> {
                        context.sendToClip(message.content)
                        context.toastOnUi(R.string.copy_complete)
                        true
                    }
                    actionDeleteMessage -> {
                        deleteMessage(readContext ?: return@setOnMenuItemClickListener true, message.id)
                        true
                    }
                    else -> false
                }
            }
        }.show()
    }

    private inner class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {
        private val messages = arrayListOf<ReadAiMessage>()
        var allowDelete: Boolean = true
        var streamingMessageId: String? = null

        fun submit(items: List<ReadAiMessage>) {
            messages.clear()
            messages.addAll(items)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                ItemReadAiMessageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(messages[position])
        }

        override fun getItemCount(): Int = messages.size

        inner class Holder(private val itemBinding: ItemReadAiMessageBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(message: ReadAiMessage) = itemBinding.run {
                val isUser = message.role == ReadAiMessage.Role.USER
                val params = tvMessage.layoutParams as FrameLayout.LayoutParams
                params.gravity = if (isUser) Gravity.END else Gravity.START
                tvMessage.layoutParams = params
                val backgroundColor = context.backgroundColor
                val bubbleColor = if (isUser) {
                    ColorUtils.blendColors(backgroundColor, context.accentColor, 0.18f)
                } else if (ColorUtils.isColorLight(backgroundColor)) {
                    ColorUtils.blendColors(
                        backgroundColor,
                        ContextCompat.getColor(context, R.color.background_card),
                        0.68f
                    )
                } else {
                    ColorUtils.blendColors(
                        backgroundColor,
                        ContextCompat.getColor(context, R.color.white),
                        0.12f
                    )
                }
                val strokeColor = if (isUser) {
                    ColorUtils.adjustAlpha(context.accentColor, 0.18f)
                } else {
                    ColorUtils.adjustAlpha(context.secondaryTextColor, 0.08f)
                }
                tvMessage.background = createBubble(bubbleColor, strokeColor, isUser)
                tvMessage.ellipsize = null
                tvMessage.maxLines = Int.MAX_VALUE
                tvMessage.setTextColor(context.primaryTextColor)
                tvMessage.typeface = context.uiTypeface()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tvMessage.setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
                }
                if (message.id == streamingMessageId) {
                    tvMessage.text = message.content
                } else {
                    tvMessage.setMarkdown(markwon, markwon.toMarkdown(message.content), imgOnLongClickListener = {})
                }
                tvMessage.setOnLongClickListener {
                    if (!allowDelete) return@setOnLongClickListener false
                    showMessageActions(tvMessage, message)
                    true
                }
            }
        }
    }

    companion object {
        private val requestScope = CoroutineScope(SupervisorJob() + IO)
        private const val actionCopyMessage = 1
        private const val actionDeleteMessage = 2
    }
}
