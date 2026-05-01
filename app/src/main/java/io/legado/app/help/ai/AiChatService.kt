package io.legado.app.help.ai

import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.ui.main.ai.AiChatException
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiProviderConfig
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx

object AiChatService {

    private const val MAX_TOOL_ROUNDS = 12
    private const val MAX_SEARCH_RESULT_CARDS = 8

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    private data class ToolCallBuilder(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    private data class ToolEvent(
        val name: String,
        val stage: String,
        val content: String,
        val success: Boolean = true
    )

    private data class AssistantTurn(
        val content: String,
        val toolCalls: List<ToolCall>,
        val rawMessage: JSONObject,
        val reasoningContent: String = ""
    )

    suspend fun chat(messages: List<AiChatMessage>): String {
        return chatStream(messages, onPartial = {})
    }

    suspend fun fetchModels(provider: AiProviderConfig): List<String> {
        val baseUrl = provider.baseUrl.trim()
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        val response = okHttpClient.newCallResponse {
            url(resolveModelsUrl(baseUrl))
            addHeader("Accept", "application/json")
            provider.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            addHeaders(parseCustomHeaders(provider.headers.orEmpty()))
        }
        response.use { rawResponse ->
            val payload = rawResponse.body?.string().orEmpty()
            if (!rawResponse.isSuccessful) {
                throw AiChatException(
                    message = extractError(payload).ifBlank {
                        "${rawResponse.code} ${rawResponse.message}"
                    },
                    debugLog = "url=${resolveModelsUrl(baseUrl)}\nresponse=$payload\n"
                )
            }
            val root = JSONObject(payload)
            val data = root.optJSONArray("data") ?: return emptyList()
            return buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    item.optString("id").trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }.distinct()
        }
    }

    suspend fun chatStream(
        messages: List<AiChatMessage>,
        onPartial: (String) -> Unit,
        onThinking: (String) -> Unit = {},
        onStatus: (JSONObject) -> Unit = {},
        includeStructuredBlocks: Boolean = true
    ): String {
        val provider = AppConfig.aiCurrentProvider
        val modelConfig = AppConfig.aiCurrentModelConfig
        val baseUrl = provider?.baseUrl?.trim().orEmpty()
        val model = modelConfig?.modelId?.trim().orEmpty()
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        require(model.isNotBlank()) { "Model is empty" }

        val tools = runCatching { AiToolRegistry.resolveAvailableTools() }.getOrDefault(emptyList())
        val conversation = buildConversation(messages)
        val requestLog = StringBuilder().apply {
            append("url=${resolveChatUrl(baseUrl)}").append('\n')
            append("model=$model").append('\n')
            append("provider=${provider?.name.orEmpty()}").append('\n')
            append("tools=${tools.joinToString { it.name }}").append('\n')
        }

        return runCatching {
            executeToolLoop(
                baseUrl = baseUrl,
                model = model,
                providerApiKey = provider?.apiKey.orEmpty(),
                providerHeaders = provider?.headers.orEmpty(),
                conversation = conversation,
                tools = tools,
                requestLog = requestLog,
                onPartial = onPartial,
                onThinking = onThinking,
                onStatus = onStatus,
                includeStructuredBlocks = includeStructuredBlocks
            )
        }.getOrElse { throwable ->
            if (throwable is AiChatException) {
                throw throwable
            }
            throw AiChatException(
                message = throwable.message ?: throwable.javaClass.simpleName,
                debugLog = requestLog.toString(),
                cause = throwable
            )
        }
    }

    private suspend fun executeToolLoop(
        baseUrl: String,
        model: String,
        providerApiKey: String,
        providerHeaders: String,
        conversation: MutableList<JSONObject>,
        tools: List<AiResolvedTool>,
        requestLog: StringBuilder,
        onPartial: (String) -> Unit,
        onThinking: (String) -> Unit,
        onStatus: (JSONObject) -> Unit,
        includeStructuredBlocks: Boolean
    ): String {
        val toolMap = tools.associateBy { it.name }
        val searchResultCards = JSONArray()
        val toolEvents = JSONArray()
        repeat(MAX_TOOL_ROUNDS) { round ->
            val assistantTurn = requestCompletionStream(
                baseUrl = baseUrl,
                model = model,
                providerApiKey = providerApiKey,
                providerHeaders = providerHeaders,
                messages = conversation,
                tools = tools,
                requestLog = requestLog,
                round = round + 1,
                onPartial = onPartial,
                onThinking = onThinking
            )
            conversation += assistantTurn.rawMessage
            if (assistantTurn.toolCalls.isEmpty()) {
                val content = assistantTurn.content
                if (content.isBlank()) {
                    throw AiChatException(
                        message = "Empty response",
                        debugLog = requestLog.toString()
                    )
                }
                return if (includeStructuredBlocks) {
                    appendStructuredBlocks(content, searchResultCards, toolEvents)
                } else {
                    content
                }
            }
            assistantTurn.toolCalls.forEach { toolCall ->
                onStatus(
                    JSONObject().apply {
                        put("key", toolCall.id.ifBlank { toolCall.name })
                        put("kind", "tool")
                        put("name", toolCall.name)
                        put("stage", "call")
                        put("label", appCtx.getString(R.string.ai_tool_status_calling))
                        put("content", toolCall.arguments)
                        put("success", true)
                    }
                )
                toolEvents.put(
                    JSONObject().apply {
                        put("name", toolCall.name)
                        put("stage", "call")
                        put("content", toolCall.arguments)
                        put("success", true)
                    }
                )
                val result = executeToolCall(toolCall, toolMap)
                collectSearchResultCards(toolCall, result, searchResultCards)
                val resultSuccess = parseToolResultSuccess(result)
                toolEvents.put(
                    JSONObject().apply {
                        put("name", toolCall.name)
                        put("stage", "result")
                        put("content", result)
                        put("success", resultSuccess)
                    }
                )
                onStatus(
                    JSONObject().apply {
                        put("key", toolCall.id.ifBlank { toolCall.name })
                        put("kind", "tool")
                        put("name", toolCall.name)
                        put("stage", "result")
                        put(
                            "label",
                            appCtx.getString(
                                if (resultSuccess) R.string.ai_tool_status_done else R.string.ai_tool_status_failed
                            )
                        )
                        put("content", result)
                        put("success", resultSuccess)
                    }
                )
                conversation += JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", toolCall.id)
                    put("content", result)
                }
            }
        }
        conversation += JSONObject().apply {
            put("role", "system")
            put(
                "content",
                appCtx.getString(R.string.ai_tool_round_limit_system_prompt)
            )
        }
        val finalTurn = requestCompletionStream(
            baseUrl = baseUrl,
            model = model,
            providerApiKey = providerApiKey,
            providerHeaders = providerHeaders,
            messages = conversation,
            tools = emptyList(),
            requestLog = requestLog,
            round = MAX_TOOL_ROUNDS + 1,
            onPartial = onPartial,
            onThinking = onThinking
        )
        if (finalTurn.content.isBlank()) {
            throw AiChatException(
                message = appCtx.getString(R.string.ai_tool_round_limit_summary),
                debugLog = requestLog.toString()
            )
        }
        return if (includeStructuredBlocks) {
            appendStructuredBlocks(finalTurn.content, searchResultCards, toolEvents)
        } else {
            finalTurn.content
        }
    }

    private fun collectSearchResultCards(
        toolCall: ToolCall,
        result: String,
        cards: JSONArray
    ) {
        if (toolCall.name != "search_book_source") return
        runCatching {
            val results = JSONObject(result).optJSONArray("results") ?: return
            for (index in 0 until results.length()) {
                if (cards.length() >= MAX_SEARCH_RESULT_CARDS) break
                val item = results.optJSONObject(index) ?: continue
                if (item.optString("bookUrl").isBlank() || item.optString("origin").isBlank()) continue
                cards.put(JSONObject().apply {
                    put("name", item.optString("name").take(80))
                    put("author", item.optString("author").take(60))
                    put("originName", item.optString("originName").take(60))
                    put("kind", item.optString("kind").take(80))
                    put("intro", item.optString("intro").replace(Regex("\\s+"), " ").trim().take(160))
                    put("latestChapterTitle", item.optString("latestChapterTitle").take(80))
                    put("coverUrl", item.optString("coverUrl"))
                    put("bookUrl", item.optString("bookUrl"))
                    put("origin", item.optString("origin"))
                    put("target", item.optString("target"))
                })
            }
        }
    }

    private fun appendStructuredBlocks(content: String, cards: JSONArray, toolEvents: JSONArray): String {
        if (cards.length() == 0 && toolEvents.length() == 0) return content
        val payload = JSONObject().apply {
            put("type", "search_book_results")
            put("results", cards)
        }
        return buildString {
            append(content.trimEnd())
            if (toolEvents.length() > 0) {
                append("\n\n```legado-tool-events\n")
                append(JSONObject().apply {
                    put("events", toolEvents)
                })
                append("\n```")
            }
            if (cards.length() > 0) {
                append("\n\n```legado-search-results\n")
                append(payload)
                append("\n```")
            }
        }
    }

    private fun parseToolResultSuccess(result: String): Boolean {
        return runCatching {
            JSONObject(result).optBoolean("ok", true)
        }.getOrDefault(true)
    }

    private suspend fun executeToolCall(
        toolCall: ToolCall,
        toolMap: Map<String, AiResolvedTool>
    ): String {
        val enabled = AppConfig.aiEnabledToolNames.ifEmpty { AiToolRegistry.defaultEnabledTools }
        if (toolCall.name !in enabled) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "Tool is disabled: ${toolCall.name}")
            }.toString()
        }
        val resolvedTool = toolMap[toolCall.name]
        if (resolvedTool == null) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "Unknown tool: ${toolCall.name}")
            }.toString()
        }
        return runCatching {
            val arguments = toolCall.arguments.trim().takeIf { it.isNotBlank() }?.let(::JSONObject)
            resolvedTool.execute(arguments)
        }.getOrElse { throwable ->
            JSONObject().apply {
                put("ok", false)
                put("error", throwable.message ?: throwable.javaClass.simpleName)
            }.toString()
        }
    }

    private suspend fun requestCompletionStream(
        baseUrl: String,
        model: String,
        providerApiKey: String,
        providerHeaders: String,
        messages: List<JSONObject>,
        tools: List<AiResolvedTool>,
        requestLog: StringBuilder,
        round: Int,
        onPartial: (String) -> Unit,
        onThinking: (String) -> Unit
    ): AssistantTurn {
        val requestBody = buildRequestBody(messages, model, tools, stream = true)
        requestLog.append("round=").append(round).append('\n')
            .append("request=").append(requestBody).append('\n')
        val response = okHttpClient.newCallResponse {
            url(resolveChatUrl(baseUrl))
            addHeader("Accept", "text/event-stream, application/json")
            addHeader("Content-Type", "application/json")
            providerApiKey.trim().takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            addHeaders(parseCustomHeaders(providerHeaders))
            postJson(requestBody)
        }
        response.use { rawResponse ->
            val body = rawResponse.body ?: throw AiChatException(
                message = "Empty response body",
                debugLog = requestLog.append("response=<empty body>\n").toString()
            )
            if (!rawResponse.isSuccessful) {
                val payload = body.string()
                throw AiChatException(
                    message = extractError(payload).ifBlank {
                        "${rawResponse.code} ${rawResponse.message}"
                    },
                    debugLog = buildString {
                        append(requestLog)
                        append("status=${rawResponse.code} ${rawResponse.message}").append('\n')
                        append("response=$payload").append('\n')
                    }
                )
            }
            val rendered = StringBuilder()
            val rawRendered = StringBuilder()
            val reasoningRendered = StringBuilder()
            val rawPayload = StringBuilder()
            val toolCallBuilders = linkedMapOf<Int, ToolCallBuilder>()
            body.byteStream().bufferedReader().use { reader ->
                while (true) {
                    val rawLine = reader.readLine()?.trim() ?: break
                    if (rawLine.isEmpty()) continue
                    rawPayload.append(rawLine).append('\n')
                    if (rawLine.startsWith("data:")) {
                        val payload = rawLine.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        consumeStreamPayload(payload, rawRendered, rendered, reasoningRendered, toolCallBuilders, onPartial, onThinking)
                    } else if (rawLine.startsWith("{")) {
                        consumeStreamPayload(rawLine, rawRendered, rendered, reasoningRendered, toolCallBuilders, onPartial, onThinking)
                    }
                }
            }
            requestLog.append("response=").append(rawPayload).append('\n')
            val toolCalls = toolCallBuilders.map { (index, builder) ->
                ToolCall(
                    id = builder.id.ifBlank { "call_$index" },
                    name = builder.name,
                    arguments = builder.arguments.toString().ifBlank { "{}" }
                )
            }.filter { it.name.isNotBlank() }
            if (rendered.isBlank() && toolCalls.isEmpty()) {
                val fallback = runCatching { extractContent(rawPayload.toString()) }.getOrDefault("")
                if (fallback.isNotBlank()) {
                    val visibleFallback = stripInlineThinking(fallback, onThinking)
                    onPartial(visibleFallback)
                    return AssistantTurn(
                        visibleFallback,
                        emptyList(),
                        buildAssistantRawMessage(visibleFallback, emptyList(), reasoningRendered.toString()),
                        reasoningRendered.toString()
                    )
                }
            }
            return AssistantTurn(
                content = rendered.toString(),
                toolCalls = toolCalls,
                rawMessage = buildAssistantRawMessage(rendered.toString(), toolCalls, reasoningRendered.toString()),
                reasoningContent = reasoningRendered.toString()
            )
        }
    }

    private fun buildRequestBody(
        messages: List<JSONObject>,
        model: String,
        tools: List<AiResolvedTool>,
        stream: Boolean
    ): String {
        return JSONObject().apply {
            put("model", model)
            put("stream", stream)
            put("messages", JSONArray().apply {
                messages.forEach { put(it) }
            })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { put(it.definition) }
                })
                put("tool_choice", "auto")
            }
        }.toString()
    }

    private fun consumeStreamPayload(
        payload: String,
        rawRendered: StringBuilder,
        rendered: StringBuilder,
        reasoningRendered: StringBuilder,
        toolCallBuilders: MutableMap<Int, ToolCallBuilder>,
        onPartial: (String) -> Unit,
        onThinking: (String) -> Unit
    ) {
        extractError(payload).takeIf { it.isNotBlank() }?.let {
            throw IllegalStateException(it)
        }
        val root = JSONObject(payload)
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return
        val reasoningText = extractContentText(delta.opt("reasoning_content"))
            .ifBlank { extractContentText(delta.opt("reasoning")) }
            .ifBlank { extractContentText(delta.opt("thinking")) }
        if (reasoningText.isNotBlank()) {
            reasoningRendered.append(reasoningText)
            onThinking(reasoningText)
        }
        val deltaText = extractContentText(delta.opt("content"))
        if (deltaText.isNotEmpty()) {
            rawRendered.append(deltaText)
            val visibleText = stripInlineThinking(rawRendered.toString(), onThinking)
            if (visibleText != rendered.toString()) {
                rendered.clear()
                rendered.append(visibleText)
                onPartial(visibleText)
            }
        }
        val toolCalls = delta.optJSONArray("tool_calls") ?: return
        for (i in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(i) ?: continue
            val index = toolCall.optInt("index", i)
            val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }
            toolCall.optString("id").takeIf { it.isNotBlank() }?.let { builder.id = it }
            val function = toolCall.optJSONObject("function") ?: continue
            function.optString("name").takeIf { it.isNotBlank() }?.let { builder.name = it }
            val args = function.opt("arguments")
            when (args) {
                is String -> builder.arguments.append(args)
                is JSONObject, is JSONArray -> builder.arguments.append(args.toString())
            }
        }
    }

    private fun buildAssistantRawMessage(
        content: String,
        toolCalls: List<ToolCall>,
        reasoningContent: String = ""
    ): JSONObject {
        return JSONObject().apply {
            put("role", "assistant")
            put("content", if (content.isBlank()) JSONObject.NULL else content)
            if (reasoningContent.isNotBlank()) {
                put("reasoning_content", reasoningContent)
            }
            if (toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    JSONArray().apply {
                        toolCalls.forEach { toolCall ->
                            put(
                                JSONObject().apply {
                                    put("id", toolCall.id)
                                    put("type", "function")
                                    put(
                                        "function",
                                        JSONObject().apply {
                                            put("name", toolCall.name)
                                            put("arguments", toolCall.arguments)
                                        }
                                    )
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    private fun buildConversation(messages: List<AiChatMessage>): MutableList<JSONObject> {
        val conversation = mutableListOf<JSONObject>()
        conversation += JSONObject().apply {
            put("role", "system")
            put("content", AppConfig.aiSystemPrompt.ifBlank { AppConfig.DEFAULT_AI_SYSTEM_PROMPT })
        }
        AppConfig.aiEnabledSkills.forEach { skill ->
            conversation += JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    buildString {
                        append("以下是用户启用的真实 SKILL.md，请把它作为当前 agent 的能力规范执行。")
                        append("Skill 名称：")
                        append(skill.name)
                        if (skill.description.isNotBlank()) {
                            append("\nSkill 描述：")
                            append(skill.description)
                        }
                        if (skill.sourceUrl.isNotBlank()) {
                            append("\nSkill 来源：")
                            append(skill.sourceUrl)
                        }
                        append("\n\n")
                        append(skill.content)
                    }
                )
            }
        }
        if (requiresBookshelfTool(messages)) {
            conversation += JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "本轮用户请求涉及本地书架、书籍详情、阅读记录、分组、标签或书源搜索。回复正文前必须先调用合适的本地工具；不要只说明将要查询。需要选择书源时先调用 list_book_sources。search_book_source 的结果会由客户端自动渲染成可点击卡片，回复里不要生成链接、不要输出内部 URL、不要手写 Markdown 打开链接，只需要用自然语言简短说明搜索结果。"
                )
            }
        }
        messages.takeLast(12).forEach { message ->
            conversation += JSONObject().apply {
                put(
                    "role",
                    if (message.role == AiChatMessage.Role.USER) "user" else "assistant"
                )
                if (message.role == AiChatMessage.Role.ASSISTANT) {
                    val (visibleContent, reasoningContent) = splitInlineThinking(
                        stripSearchResultBlocks(message.content)
                    )
                    put("content", visibleContent)
                    if (reasoningContent.isNotBlank()) {
                        put("reasoning_content", reasoningContent)
                    }
                } else {
                    put("content", stripSearchResultBlocks(message.content))
                }
            }
        }
        return conversation
    }

    private fun stripSearchResultBlocks(content: String): String {
        return searchResultBlockRegex.replace(content, "").trim()
    }

    private fun requiresBookshelfTool(messages: List<AiChatMessage>): Boolean {
        val content = messages.lastOrNull { it.role == AiChatMessage.Role.USER }
            ?.content
            ?.lowercase()
            .orEmpty()
        if (content.isBlank()) return false
        return listOf(
            "书架",
            "书籍",
            "书名",
            "作者",
            "阅读记录",
            "最近读",
            "在读",
            "简介",
            "书源",
            "分组",
            "标签",
            "分类",
            "整理",
            "批量"
        ).any { content.contains(it) }
    }

    private fun parseAssistantTurn(response: JSONObject): AssistantTurn {
        val message = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: JSONObject()
        val content = extractContentText(message.opt("content"))
        val reasoningContent = extractContentText(message.opt("reasoning_content"))
            .ifBlank { extractContentText(message.opt("reasoning")) }
            .ifBlank { extractContentText(message.opt("thinking")) }
        val toolCalls = buildList {
            val array = message.optJSONArray("tool_calls") ?: JSONArray()
            for (index in 0 until array.length()) {
                val toolCall = array.optJSONObject(index) ?: continue
                val function = toolCall.optJSONObject("function") ?: continue
                add(
                    ToolCall(
                        id = toolCall.optString("id").ifBlank { "call_$index" },
                        name = function.optString("name"),
                        arguments = extractToolArguments(function.opt("arguments"))
                    )
                )
            }
        }
        return AssistantTurn(
            content = content,
            toolCalls = toolCalls,
            rawMessage = JSONObject().apply {
                put("role", "assistant")
                put("content", if (content.isBlank()) JSONObject.NULL else content)
                if (reasoningContent.isNotBlank()) {
                    put("reasoning_content", reasoningContent)
                }
                if (toolCalls.isNotEmpty()) {
                    put(
                        "tool_calls",
                        JSONArray().apply {
                            toolCalls.forEach { toolCall ->
                                put(
                                    JSONObject().apply {
                                        put("id", toolCall.id)
                                        put("type", "function")
                                        put(
                                            "function",
                                            JSONObject().apply {
                                                put("name", toolCall.name)
                                                put("arguments", toolCall.arguments)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    )
                }
            },
            reasoningContent = reasoningContent
        )
    }

    private fun parseCustomHeaders(rawHeaders: String): Map<String, String> {
        val text = rawHeaders.trim()
        if (text.isBlank()) return emptyMap()
        runCatching {
            val json = JSONObject(text)
            return buildMap {
                json.keys().forEach { key ->
                    val value = json.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                }
            }
        }
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf(':').takeIf { it > 0 } ?: line.indexOf('=').takeIf { it > 0 }
                separator?.let {
                    line.substring(0, it).trim() to line.substring(it + 1).trim()
                }
            }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .toMap()
    }

    private fun resolveChatUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.endsWith("/chat/completions") -> normalized
            normalized.endsWith("/v1") -> "$normalized/chat/completions"
            else -> "$normalized/v1/chat/completions"
        }
    }

    private fun resolveModelsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.endsWith("/models") -> normalized
            normalized.endsWith("/chat/completions") -> normalized.removeSuffix("/chat/completions") + "/models"
            normalized.endsWith("/v1") -> "$normalized/models"
            else -> "$normalized/v1/models"
        }
    }

    private fun extractError(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")
                ?: root.optString("message")
        }.getOrNull().orEmpty()
    }

    private fun extractContent(body: String): String {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: return root.optString("response")
        val first = choices.optJSONObject(0) ?: return ""
        val message = first.optJSONObject("message")
        return extractContentText(message?.opt("content"))
            .ifBlank { first.optString("text") }
    }

    private fun extractContentText(content: Any?): String {
        return when (content) {
            is String -> content
            is JSONArray -> contentArrayToText(content)
            is JSONObject -> content.optString("text")
            else -> ""
        }
    }

    private fun stripInlineThinking(
        text: String,
        onThinking: (String) -> Unit
    ): String {
        val (visible, reasoning) = splitInlineThinking(text)
        reasoning.takeIf { it.isNotBlank() }?.let(onThinking)
        return visible.trimStart()
    }

    private fun splitInlineThinking(text: String): Pair<String, String> {
        var visible = text
        val reasoningParts = mutableListOf<String>()
        val closedThinkRegex = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)
        closedThinkRegex.findAll(text).forEach { match ->
            match.groups[1]?.value
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(reasoningParts::add)
        }
        visible = closedThinkRegex.replace(visible, "")
        val openMatch = Regex("<think>", RegexOption.IGNORE_CASE).find(visible)
        if (openMatch != null) {
            val thinking = visible.substring(openMatch.range.last + 1)
                .replace(Regex("</think>", RegexOption.IGNORE_CASE), "")
                .trim()
            if (thinking.isNotBlank()) {
                reasoningParts += thinking
            }
            visible = visible.substring(0, openMatch.range.first)
        }
        return visible.trimStart() to reasoningParts.joinToString("\n\n")
    }

    private fun extractToolArguments(arguments: Any?): String {
        return when (arguments) {
            is String -> arguments.ifBlank { "{}" }
            is JSONObject -> arguments.toString()
            is JSONArray -> arguments.toString()
            else -> "{}"
        }
    }

    private fun contentArrayToText(content: JSONArray): String {
        return buildString {
            for (index in 0 until content.length()) {
                val part = content.opt(index)
                if (part is JSONObject) {
                    append(part.optString("text"))
                } else if (part is String) {
                    append(part)
                }
            }
        }
    }

    private val searchResultBlockRegex = Regex(
        "```legado-search-results\\s*\\n([\\s\\S]*?)\\n```",
        setOf(RegexOption.MULTILINE)
    )
}
