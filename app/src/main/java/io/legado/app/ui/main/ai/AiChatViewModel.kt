package io.legado.app.ui.main.ai

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.UUID

class AiChatViewModel : ViewModel() {

    val messagesLiveData = MutableLiveData<List<AiChatMessage>>(emptyList())
    val requestingLiveData = MutableLiveData(false)
    var isRequesting = false
        private set

    private val messages = mutableListOf<AiChatMessage>()
    private var currentSessionId: String = AppConfig.aiCurrentChatSessionId ?: UUID.randomUUID().toString()

    companion object {
        private val requestScope = CoroutineScope(SupervisorJob() + IO)
        private var activeJob: Job? = null
        private var activeSessionId: String? = null
        private var activeViewModel: AiChatViewModel? = null
        private var activePendingContent: String = ""
        private var activeThinkingMessageId: String? = null
        private val activeToolMessageIds = linkedMapOf<String, String>()
    }

    init {
        restoreCurrentSession()
        activeViewModel = this
    }

    fun append(message: AiChatMessage) {
        messages.add(message)
        publish()
    }

    fun startRequest(
        userContent: String,
        thinkingText: String,
        cancelledText: String,
        failureMessage: (String) -> String
    ) {
        if (isRequesting || activeJob?.isActive == true) return
        setRequesting(true)
        activeSessionId = currentSessionId
        val requestSessionId = currentSessionId
        activeViewModel = this
        activeThinkingMessageId = null
        activeToolMessageIds.clear()
        append(AiChatMessage(role = AiChatMessage.Role.USER, content = userContent))
        val thinkingMessageId = UUID.randomUUID().toString()
        activeThinkingMessageId = thinkingMessageId
        append(
            AiChatMessage(
                id = thinkingMessageId,
                role = AiChatMessage.Role.ASSISTANT,
                content = thinkingText,
                pending = true,
                kind = AiChatMessage.Kind.STATUS,
                statusName = "思考中",
                statusStage = "thinking",
                statusSuccess = true
            )
        )
        append(
            AiChatMessage(
                role = AiChatMessage.Role.ASSISTANT,
                content = "",
                pending = true
            )
        )
        activePendingContent = ""
        val requestMessages = snapshotForRequest()
        activeJob = requestScope.launch {
            val result = runCatching {
                AiChatService.chatStream(
                    messages = requestMessages,
                    onPartial = { partial ->
                        targetFor(requestSessionId).markThinkingCompleted()
                        activePendingContent = partial
                        targetFor(requestSessionId).upsertPendingAssistant(partial.ifBlank { "" })
                    },
                    onThinking = { thinking ->
                        targetFor(requestSessionId).upsertThinkingStatus(thinking)
                    },
                    onStatus = { status ->
                        targetFor(requestSessionId).upsertStatus(status)
                    }
                )
            }
            targetFor(requestSessionId).setRequesting(false)
            activeJob = null
            activeSessionId = null
            result.onSuccess { content ->
                activePendingContent = ""
                activeToolMessageIds.clear()
                targetFor(requestSessionId).markThinkingCompleted()
                targetFor(requestSessionId).replacePendingAssistant(content.ifBlank { thinkingText })
            }.onFailure { throwable ->
                activePendingContent = ""
                activeToolMessageIds.clear()
                targetFor(requestSessionId).markThinkingCompleted()
                if (throwable is CancellationException) {
                    targetFor(requestSessionId).replacePendingAssistant(cancelledText)
                    return@onFailure
                }
                val chatError = throwable as? AiChatException ?: AiChatException(
                    message = throwable.localizedMessage ?: throwable.javaClass.simpleName,
                    debugLog = throwable.stackTraceToString(),
                    cause = throwable
                )
                AppLog.put("AI 请求失败\n${chatError.debugLog}", chatError)
                targetFor(requestSessionId).failPendingAssistant(failureMessage(chatError.message))
            }
        }
    }

    fun stopRequest(cancelledText: String) {
        val job = activeJob ?: return
        job.cancel(CancellationException("User stopped generation"))
        activeJob = null
        activeSessionId = null
        activePendingContent = ""
        activeThinkingMessageId = null
        activeToolMessageIds.clear()
        setRequesting(false)
        if (cancelledText.isNotBlank()) {
            replacePendingAssistant(cancelledText)
        }
    }

    fun replacePendingAssistant(content: String) {
        upsertPendingAssistant(content)
        finishPendingAssistant()
    }

    fun upsertPendingAssistant(content: String) {
        val index = messages.indexOfLast {
            it.role == AiChatMessage.Role.ASSISTANT && it.pending
        }
        if (index >= 0) {
            messages[index] = messages[index].copy(content = content, pending = true)
        } else {
            messages.add(
                AiChatMessage(
                    role = AiChatMessage.Role.ASSISTANT,
                    content = content,
                    pending = true
                )
            )
        }
        publish()
    }

    fun upsertThinkingStatus(thinking: String) {
        val normalized = thinking.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return
        val id = activeThinkingMessageId ?: UUID.randomUUID().toString().also {
            activeThinkingMessageId = it
            messages.add(
                messages.indexOfLast { message ->
                    message.role == AiChatMessage.Role.ASSISTANT && message.pending && message.kind == AiChatMessage.Kind.TEXT
                }.coerceAtLeast(0),
                AiChatMessage(
                    id = it,
                    role = AiChatMessage.Role.ASSISTANT,
                    content = normalized.takeLast(280),
                    pending = true,
                    kind = AiChatMessage.Kind.STATUS,
                    statusName = "思考中",
                    statusStage = "thinking",
                    statusSuccess = true
                )
            )
        }
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) {
            messages[index] = messages[index].copy(
                content = normalized.takeLast(280),
                pending = true,
                kind = AiChatMessage.Kind.STATUS,
                statusName = "思考中",
                statusStage = "thinking",
                statusSuccess = true
            )
            publish()
        }
    }

    fun upsertStatus(status: org.json.JSONObject) {
        markThinkingCompleted()
        val key = status.optString("key").ifBlank { UUID.randomUUID().toString() }
        val name = status.optString("name").ifBlank { "工具调用" }
        val stage = status.optString("stage").ifBlank { "call" }
        val label = status.optString("label").ifBlank {
            when (stage) {
                "call" -> "调用中"
                "result" -> if (status.optBoolean("success", true)) "调用完成" else "调用失败"
                else -> stage
            }
        }
        val content = status.optString("content")
        val success = status.optBoolean("success", true)
        val messageId = activeToolMessageIds[key]
        if (messageId != null) {
            val index = messages.indexOfFirst { it.id == messageId }
            if (index >= 0) {
                messages[index] = messages[index].copy(
                    content = content,
                    pending = stage == "call",
                    kind = AiChatMessage.Kind.STATUS,
                    statusName = "$name · $label",
                    statusStage = stage,
                    statusSuccess = success
                )
                publish()
                return
            }
        }
        val newId = UUID.randomUUID().toString()
        activeToolMessageIds[key] = newId
        val pendingTextIndex = messages.indexOfLast {
            it.role == AiChatMessage.Role.ASSISTANT && it.pending && it.kind == AiChatMessage.Kind.TEXT
        }
        val insertIndex = when {
            pendingTextIndex < 0 -> messages.size
            messages[pendingTextIndex].content.isNotBlank() -> pendingTextIndex + 1
            else -> pendingTextIndex
        }
        messages.add(
            insertIndex,
            AiChatMessage(
                id = newId,
                role = AiChatMessage.Role.ASSISTANT,
                content = content,
                pending = stage == "call",
                kind = AiChatMessage.Kind.STATUS,
                statusName = "$name · $label",
                statusStage = stage,
                statusSuccess = success
            )
        )
        publish()
    }

    fun finishPendingAssistant() {
        val index = messages.indexOfLast {
            it.role == AiChatMessage.Role.ASSISTANT && it.pending
        }
        if (index >= 0) {
            messages[index] = messages[index].copy(pending = false)
            publish()
        }
    }

    fun failPendingAssistant(content: String) {
        val index = messages.indexOfLast {
            it.role == AiChatMessage.Role.ASSISTANT && it.pending
        }
        if (index >= 0) {
            messages[index] = messages[index].copy(content = content, pending = false)
        } else {
            messages.add(AiChatMessage(role = AiChatMessage.Role.ASSISTANT, content = content))
        }
        publish()
    }

    fun clearCurrentSession() {
        messages.clear()
        AppConfig.aiChatSessionList =
            AppConfig.aiChatSessionList.filterNot { it.id == currentSessionId }
        currentSessionId = UUID.randomUUID().toString()
        AppConfig.aiCurrentChatSessionId = currentSessionId
        publish(saveHistory = false)
    }

    fun startNewSession() {
        currentSessionId = UUID.randomUUID().toString()
        AppConfig.aiCurrentChatSessionId = currentSessionId
        messages.clear()
        setRequesting(false)
        publish(saveHistory = false)
    }

    fun historySessions(): List<AiChatSession> {
        return AppConfig.aiChatSessionList.sortedByDescending { it.updatedAt }
    }

    fun loadSession(sessionId: String) {
        val session = AppConfig.aiChatSessionList.firstOrNull { it.id == sessionId } ?: return
        currentSessionId = session.id
        AppConfig.aiCurrentChatSessionId = session.id
        messages.clear()
        messages.addAll(session.messages.map { it.copy(pending = false) })
        setRequesting(activeJob?.isActive == true && activeSessionId == currentSessionId)
        publish(saveHistory = false)
    }

    fun deleteSession(sessionId: String) {
        AppConfig.aiChatSessionList = AppConfig.aiChatSessionList.filterNot { it.id == sessionId }
        if (currentSessionId == sessionId) {
            currentSessionId = UUID.randomUUID().toString()
            AppConfig.aiCurrentChatSessionId = currentSessionId
            messages.clear()
            setRequesting(false)
            publish(saveHistory = false)
        }
    }

    fun clearAllSessions() {
        AppConfig.aiChatSessionList = emptyList()
        currentSessionId = UUID.randomUUID().toString()
        AppConfig.aiCurrentChatSessionId = currentSessionId
        messages.clear()
        setRequesting(false)
        publish(saveHistory = false)
    }

    fun snapshotForRequest(): List<AiChatMessage> {
        return messages.filterNot { it.pending || it.kind == AiChatMessage.Kind.STATUS }
    }

    fun restoreCurrentSession() {
        val sessions = AppConfig.aiChatSessionList
        val session = sessions.firstOrNull { it.id == currentSessionId } ?: sessions.firstOrNull()
        if (session != null) {
            currentSessionId = session.id
            AppConfig.aiCurrentChatSessionId = session.id
            messages.addAll(session.messages.map { it.copy(pending = false) })
        } else {
            AppConfig.aiCurrentChatSessionId = currentSessionId
        }
        val requesting = activeJob?.isActive == true && activeSessionId == currentSessionId
        if (requesting && messages.none { it.role == AiChatMessage.Role.ASSISTANT && it.pending }) {
            messages.add(
                AiChatMessage(
                    role = AiChatMessage.Role.ASSISTANT,
                    content = activePendingContent.ifBlank {
                        appCtx.getString(R.string.ai_restore_thinking)
                    },
                    pending = true
                )
            )
        }
        setRequesting(requesting)
        publish(saveHistory = false)
    }

    override fun onCleared() {
        super.onCleared()
        if (activeViewModel === this) {
            activeViewModel = null
        }
    }

    private fun setRequesting(value: Boolean) {
        isRequesting = value
        requestingLiveData.postValue(value)
    }

    private fun targetFor(sessionId: String): AiChatViewModel {
        return activeViewModel?.takeIf { it.currentSessionId == sessionId } ?: this
    }

    private fun publish(saveHistory: Boolean = true) {
        if (saveHistory) {
            saveCurrentSession()
        }
        messagesLiveData.postValue(messages.toList())
    }

    private fun markThinkingCompleted() {
        val id = activeThinkingMessageId ?: return
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) {
            val old = messages[index]
            messages[index] = old.copy(
                pending = false,
                statusName = "思考完成",
                statusStage = "done"
            )
            publish()
        }
        activeThinkingMessageId = null
    }

    private fun saveCurrentSession() {
        val snapshot = messages.filterNot { it.pending }
            .map { it.copy(pending = false) }
            .filter { it.content.isNotBlank() }
        val history = AppConfig.aiChatSessionList.toMutableList()
        val index = history.indexOfFirst { it.id == currentSessionId }
        if (snapshot.isEmpty()) {
            if (index >= 0) {
                history.removeAt(index)
                AppConfig.aiChatSessionList = history
            }
            return
        }
        val session = AiChatSession(
            id = currentSessionId,
            title = resolveSessionTitle(snapshot),
            updatedAt = System.currentTimeMillis(),
            messages = snapshot
        )
        if (index >= 0) {
            history[index] = session
        } else {
            history.add(0, session)
        }
        AppConfig.aiChatSessionList = history.sortedByDescending { it.updatedAt }
        AppConfig.aiCurrentChatSessionId = currentSessionId
    }

    private fun resolveSessionTitle(messages: List<AiChatMessage>): String {
        val titleSource = messages.firstOrNull { it.role == AiChatMessage.Role.USER }?.content
            ?: messages.first().content
        return titleSource.replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let {
                if (it.length > 24) "${it.take(24)}…" else it
            }
            .ifBlank { "AI Chat" }
    }
}
