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
import org.json.JSONArray
import org.json.JSONObject
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
        private val activeStatusEvents = linkedMapOf<String, JSONObject>()
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
        activeStatusEvents.clear()
        append(AiChatMessage(role = AiChatMessage.Role.USER, content = userContent))
        append(
            AiChatMessage(
                role = AiChatMessage.Role.ASSISTANT,
                content = buildPendingAssistantContent(thinkingText),
                pending = true
            )
        )
        activePendingContent = thinkingText
        val requestMessages = snapshotForRequest()
        activeJob = requestScope.launch {
            val result = runCatching {
                AiChatService.chatStream(
                    messages = requestMessages,
                    onPartial = { partial ->
                        activePendingContent = partial.ifBlank { thinkingText }
                        targetFor(requestSessionId).upsertPendingAssistant(
                            buildPendingAssistantContent(activePendingContent)
                        )
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
                activeStatusEvents.clear()
                targetFor(requestSessionId).replacePendingAssistant(content.ifBlank { thinkingText })
            }.onFailure { throwable ->
                activePendingContent = ""
                activeStatusEvents.clear()
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
        activeStatusEvents.clear()
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
        activeStatusEvents["thinking"] = JSONObject().apply {
            put("key", "thinking")
            put("kind", "thinking")
            put("name", "思考中")
            put("stage", "thinking")
            put("label", "思考中")
            put("content", normalized.takeLast(280))
            put("success", true)
        }
        upsertPendingAssistant(buildPendingAssistantContent(activePendingContent))
    }

    fun upsertStatus(status: JSONObject) {
        val key = status.optString("key").ifBlank { UUID.randomUUID().toString() }
        activeStatusEvents[key] = JSONObject(status.toString())
        upsertPendingAssistant(buildPendingAssistantContent(activePendingContent))
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
        return messages.filterNot { it.pending }
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

    private fun buildPendingAssistantContent(visibleContent: String): String {
        if (activeStatusEvents.isEmpty()) return visibleContent
        return buildString {
            append("```legado-status-events\n")
            append(
                JSONObject().apply {
                    put(
                        "events",
                        JSONArray().apply {
                            activeStatusEvents.values.forEach { put(it) }
                        }
                    )
                }
            )
            append("\n```\n")
            append(visibleContent)
        }
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
