package io.legado.app.help.config

import android.content.SharedPreferences
import android.os.Build
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.utils.GSON
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isNightMode
import io.legado.app.utils.parseIpsFromString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.sysConfiguration
import io.legado.app.utils.toastOnUi
import io.legado.app.ui.main.ai.AiChatSession
import io.legado.app.ui.main.ai.AiMcpServerConfig
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.main.ai.AiSkillConfig
import splitties.init.appCtx
import java.net.InetAddress
import java.net.URI

@Suppress("MemberVisibilityCanBePrivate", "ConstPropertyName")
object AppConfig : SharedPreferences.OnSharedPreferenceChangeListener {
    const val DEFAULT_AI_SYSTEM_PROMPT =
        "你是阅读应用内的 AI 助手。回答直接、准确、简洁。需要真实应用数据时必须优先调用工具，工具返回的数据优先级高于你的记忆，不允许编造工具未返回的结果。用户询问书架、书籍、作者、阅读记录、书籍简介、书源、分组、标签、分类方案时，必须先调用 query_bookshelf、get_bookshelf_book_info、manage_bookshelf_group 或 manage_bookshelf_tag，不要只说“我先看看”却不调用工具。用户要求创建、修改或调试书源时，你要像一个小型书源 agent 一样闭环执行：新建书源先调用 create_book_source(save=false) 生成草稿；修改已有书源先调用 get_book_source 读取完整 JSON；缺少页面结构时调用 fetch_source_html 获取搜索页、详情页、目录页或正文页 HTML；每次调试失败后都调用 update_book_source(save=false) 按日志和 HTML 修正规则，可传 patch，也可直接传 ruleToc/ruleContent/searchUrl 等字段；再调用 debug_book_source 调试，优先使用用户给出的详情页 URL、目录 URL、正文 URL 或关键词；最多循环 3 次。不要在第一次调试前询问“是否继续”，也不要只输出未经调试的 JSON。只有搜索、详情、目录、正文主要链路通过，或达到 3 次仍失败时，才给出最终结果和剩余失败点。只有用户明确要求保存、导入或完成时，才调用 update_book_source(save=true) 或 create_book_source(save=true) 写入本地书源库。用户要求整理书架时，顶层书架使用分组，分组内的小分类使用书籍标签；未分标签的书按“全部”处理。批量设置标签、重命名标签、移动分组或删除分组前，先查询书架并给出方案，用户确认后再调用写入工具。任何情况下都不允许删除书籍。"

    val isCronet = appCtx.getPrefBoolean(PreferKey.cronet)
    var useAntiAlias = appCtx.getPrefBoolean(PreferKey.antiAlias)
    var useHighRefreshRate = appCtx.getPrefBoolean(PreferKey.highBrush, true)
    var userAgent: String = getPrefUserAgent()
    var customHosts = appCtx.getPrefString(PreferKey.customHosts)
    var editTheme = appCtx.getPrefInt(PreferKey.editTheme, 0)
    var editThemeDark = appCtx.getPrefInt(PreferKey.editThemeDark, 0)
    var editTemeAuto = appCtx.getPrefBoolean(PreferKey.editTemeAuto)
    var isEInkMode = appCtx.getPrefString(PreferKey.themeMode) == "3"
    var clickActionTL = appCtx.getPrefInt(PreferKey.clickActionTL, 2)
    var clickActionTC = appCtx.getPrefInt(PreferKey.clickActionTC, 2)
    var clickActionTR = appCtx.getPrefInt(PreferKey.clickActionTR, 1)
    var clickActionML = appCtx.getPrefInt(PreferKey.clickActionML, 2)
    var clickActionMC = appCtx.getPrefInt(PreferKey.clickActionMC, 0)
    var clickActionMR = appCtx.getPrefInt(PreferKey.clickActionMR, 1)
    var clickActionBL = appCtx.getPrefInt(PreferKey.clickActionBL, 2)
    var clickActionBC = appCtx.getPrefInt(PreferKey.clickActionBC, 1)
    var clickActionBR = appCtx.getPrefInt(PreferKey.clickActionBR, 1)
    var themeMode = appCtx.getPrefString(PreferKey.themeMode, "0")
    var useDefaultCover = appCtx.getPrefBoolean(PreferKey.useDefaultCover, false)
    var loadCoverHighQuality = appCtx.getPrefBoolean(PreferKey.loadCoverHighQuality, false)
    var optimizeRender = CanvasRecorderFactory.isSupport
            && appCtx.getPrefBoolean(PreferKey.optimizeRender, false)
    var recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)
    var editFontScale = appCtx.getPrefInt(PreferKey.editFontScale, 16)
    var editNonPrintable = appCtx.getPrefInt(PreferKey.editNonPrintable, 0)
    var editAutoWrap = appCtx.getPrefBoolean(PreferKey.editAutoWrap, true)
    var editAutoComplete = appCtx.getPrefBoolean(PreferKey.editAutoComplete, true)
    var showBoardLine = appCtx.getPrefInt(PreferKey.showBoardLine, 1)
    var adaptSpecialStyle = appCtx.getPrefBoolean(PreferKey.adaptSpecialStyle, true)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.editFontScale -> editFontScale = appCtx.getPrefInt(PreferKey.editFontScale, 16)
            PreferKey.editNonPrintable -> editNonPrintable = appCtx.getPrefInt(PreferKey.editNonPrintable, 0)
            PreferKey.editAutoWrap -> editAutoWrap = appCtx.getPrefBoolean(PreferKey.editAutoWrap, true)
            PreferKey.editAutoComplete -> editAutoComplete = appCtx.getPrefBoolean(PreferKey.editAutoComplete, true)
            PreferKey.showBoardLine -> showBoardLine = appCtx.getPrefInt(PreferKey.showBoardLine, 1)
            PreferKey.adaptSpecialStyle -> adaptSpecialStyle = appCtx.getPrefBoolean(PreferKey.adaptSpecialStyle, true)

            PreferKey.themeMode -> {
                themeMode = appCtx.getPrefString(PreferKey.themeMode, "0")
                isEInkMode = themeMode == "3"
            }

            PreferKey.clickActionTL -> clickActionTL =
                appCtx.getPrefInt(PreferKey.clickActionTL, 2)

            PreferKey.clickActionTC -> clickActionTC =
                appCtx.getPrefInt(PreferKey.clickActionTC, 2)

            PreferKey.clickActionTR -> clickActionTR =
                appCtx.getPrefInt(PreferKey.clickActionTR, 1)

            PreferKey.clickActionML -> clickActionML =
                appCtx.getPrefInt(PreferKey.clickActionML, 2)

            PreferKey.clickActionMC -> clickActionMC =
                appCtx.getPrefInt(PreferKey.clickActionMC, 0)

            PreferKey.clickActionMR -> clickActionMR =
                appCtx.getPrefInt(PreferKey.clickActionMR, 1)

            PreferKey.clickActionBL -> clickActionBL =
                appCtx.getPrefInt(PreferKey.clickActionBL, 2)

            PreferKey.clickActionBC -> clickActionBC =
                appCtx.getPrefInt(PreferKey.clickActionBC, 1)

            PreferKey.clickActionBR -> clickActionBR =
                appCtx.getPrefInt(PreferKey.clickActionBR, 1)

            PreferKey.readBodyToLh -> ReadBookConfig.readBodyToLh =
                appCtx.getPrefBoolean(PreferKey.readBodyToLh, true)

            PreferKey.useZhLayout -> ReadBookConfig.useZhLayout =
                appCtx.getPrefBoolean(PreferKey.useZhLayout)

            PreferKey.userAgent -> userAgent = getPrefUserAgent()

            PreferKey.customHosts -> {
                customHosts = appCtx.getPrefString(PreferKey.customHosts)
                _hostMap = null
                _addressCache = null
            }

            PreferKey.editTheme -> editTheme = appCtx.getPrefInt(PreferKey.editTheme, 0)

            PreferKey.editThemeDark -> editThemeDark = appCtx.getPrefInt(PreferKey.editThemeDark, 0)

            PreferKey.editTemeAuto -> editTemeAuto = appCtx.getPrefBoolean(PreferKey.editTemeAuto)

            PreferKey.antiAlias -> useAntiAlias = appCtx.getPrefBoolean(PreferKey.antiAlias)

            PreferKey.highBrush -> {
                useHighRefreshRate = appCtx.getPrefBoolean(PreferKey.highBrush, true)
            }

            PreferKey.useDefaultCover -> useDefaultCover =
                appCtx.getPrefBoolean(PreferKey.useDefaultCover, false)

            PreferKey.loadCoverHighQuality -> loadCoverHighQuality =
                appCtx.getPrefBoolean(PreferKey.loadCoverHighQuality, false)

            PreferKey.optimizeRender -> optimizeRender = CanvasRecorderFactory.isSupport
                    && appCtx.getPrefBoolean(PreferKey.optimizeRender, false)

            PreferKey.recordLog -> recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)

        }
    }

    //dns配置
    private var _hostMap: Map<String, Any?>? = null
    val hostMap: Map<String, Any?>
        get() = _hostMap ?: run {
            val cache = GSON.fromJsonObject<Map<String, Any?>>(customHosts).getOrNull() ?: emptyMap()
            _hostMap = cache
            cache
        }
    private var _addressCache: Map<String, List<InetAddress>>? = null
    val addressCache: Map<String, List<InetAddress>>
        get() = _addressCache ?: run {
            val cache = hostMap.mapNotNull { (host, ipValue) ->
                val addresses = when (ipValue) {
                    is String -> ipValue.parseIpsFromString()
                    is List<*> -> ipValue.parseIpsFromList()
                    else -> null
                }
                addresses?.let { host to it }
            }.toMap()
            _addressCache = cache
            cache
        }
    private fun List<*>.parseIpsFromList(): List<InetAddress> =
        mapNotNull { element ->
            (element as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?.runCatching { InetAddress.getByName(this) }
                ?.getOrNull()
        }

    var isNightTheme: Boolean
        get() = when (themeMode) {
            "1" -> false
            "2" -> true
            "3" -> false
            else -> sysConfiguration.isNightMode
        }
        set(value) {
            if (isNightTheme != value) {
                if (value) {
                    appCtx.putPrefString(PreferKey.themeMode, "2")
                } else {
                    appCtx.putPrefString(PreferKey.themeMode, "1")
                }
            }
        }
    var showBookname: Int
        get() = appCtx.getPrefInt(PreferKey.showBooknameLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.showBooknameLayout, value)
        }
    var bookshelfMargin: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfMargin, 12)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfMargin, value)
        }

    var showUnread: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showUnread, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showUnread, value)
        }

    var showLastUpdateTime: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showLastUpdateTime, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showLastUpdateTime, value)
        }

    var showWaitUpCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showWaitUpCount, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showWaitUpCount, value)
        }

    var readBrightness: Int
        get() = if (isNightTheme) {
            appCtx.getPrefInt(PreferKey.nightBrightness, 100)
        } else {
            appCtx.getPrefInt(PreferKey.brightness, 100)
        }
        set(value) {
            if (isNightTheme) {
                appCtx.putPrefInt(PreferKey.nightBrightness, value)
            } else {
                appCtx.putPrefInt(PreferKey.brightness, value)
            }
        }

    val textSelectAble: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.textSelectAble, true)

    val isTransparentStatusBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.transparentStatusBar, true)

    val isMainTransparentStatusBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.mainTransparentStatusBar, false)

    val immNavigationBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.immNavigationBar, true)

    val screenOrientation: String?
        get() = appCtx.getPrefString(PreferKey.screenOrientation)

    var bookGroupStyle: Int
        get() = appCtx.getPrefInt(PreferKey.bookGroupStyle, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookGroupStyle, value)
        }

    var bookshelfLayout: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfLayout, value)
        }

    var saveTabPosition: Int
        get() = appCtx.getPrefInt(PreferKey.saveTabPosition, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.saveTabPosition, value)
        }

    var bookExportFileName: String?
        get() = appCtx.getPrefString(PreferKey.bookExportFileName)
        set(value) {
            appCtx.putPrefString(PreferKey.bookExportFileName, value)
        }

    // 保存 自定义导出章节模式 文件名js表达式
    var episodeExportFileName: String?
        get() = appCtx.getPrefString(PreferKey.episodeExportFileName, "")
        set(value) {
            appCtx.putPrefString(PreferKey.episodeExportFileName, value)
        }

    var bookImportFileName: String?
        get() = appCtx.getPrefString(PreferKey.bookImportFileName)
        set(value) {
            appCtx.putPrefString(PreferKey.bookImportFileName, value)
        }

    var backupPath: String?
        get() = appCtx.getPrefString(PreferKey.backupPath)
        set(value) {
            if (value.isNullOrEmpty()) {
                appCtx.removePref(PreferKey.backupPath)
            } else {
                appCtx.putPrefString(PreferKey.backupPath, value)
            }
        }

    // 书籍保存位置
    var defaultBookTreeUri: String?
        get() = appCtx.getPrefString(PreferKey.defaultBookTreeUri)
        set(value) {
            if (value.isNullOrEmpty()) {
                appCtx.removePref(PreferKey.defaultBookTreeUri)
            } else {
                appCtx.putPrefString(PreferKey.defaultBookTreeUri, value)
            }
        }

    val showDiscovery: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showDiscovery, true)

    val modernDiscoveryPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.modernDiscoveryPage, true)

    var modernDiscoverySourceUrl: String?
        get() = appCtx.getPrefString(PreferKey.modernDiscoverySourceUrl)
        set(value) {
            if (value.isNullOrBlank()) {
                appCtx.removePref(PreferKey.modernDiscoverySourceUrl)
            } else {
                appCtx.putPrefString(PreferKey.modernDiscoverySourceUrl, value)
            }
        }

    val showRSS: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showRss, false)

    var bookshelfHiddenTags: Map<Long, Set<String>>
        get() {
            val rawMap = GSON.fromJsonObject<Map<String, List<String>>>(
                appCtx.getPrefString(PreferKey.bookshelfHiddenTags)
            ).getOrDefault(emptyMap())
            return rawMap.mapNotNull { (key, value) ->
                key.toLongOrNull()?.let { groupId ->
                    groupId to value.map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toSet()
                }
            }.toMap()
        }
        set(value) {
            val normalized = value
                .mapValues { (_, tags) -> tags.map { it.trim() }.filter { it.isNotBlank() }.distinct() }
                .filterValues { it.isNotEmpty() }
                .mapKeys { it.key.toString() }
            if (normalized.isEmpty()) {
                appCtx.removePref(PreferKey.bookshelfHiddenTags)
            } else {
                appCtx.putPrefString(PreferKey.bookshelfHiddenTags, GSON.toJson(normalized))
            }
        }

    var bookshelfGroupTags: Map<Long, List<String>>
        get() {
            val rawMap = GSON.fromJsonObject<Map<String, List<String>>>(
                appCtx.getPrefString(PreferKey.bookshelfGroupTags)
            ).getOrDefault(emptyMap())
            return rawMap.mapNotNull { (key, value) ->
                key.toLongOrNull()?.let { groupId ->
                    groupId to value.map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                }
            }.filter { it.second.isNotEmpty() }.toMap()
        }
        set(value) {
            val normalized = value
                .mapValues { (_, tags) -> tags.map { it.trim() }.filter { it.isNotBlank() }.distinct() }
                .filterValues { it.isNotEmpty() }
                .mapKeys { it.key.toString() }
            if (normalized.isEmpty()) {
                appCtx.removePref(PreferKey.bookshelfGroupTags)
            } else {
                appCtx.putPrefString(PreferKey.bookshelfGroupTags, GSON.toJson(normalized))
            }
        }

    var aiAssistantEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiAssistantEnabled, false) && aiCurrentModelConfig != null
        set(value) {
            appCtx.putPrefBoolean(PreferKey.aiAssistantEnabled, value && aiCurrentModelConfig != null)
        }

    var aiProviderList: List<AiProviderConfig>
        get() {
            val providers = readAiProviders()
            syncAiState(providers, readAiModels(providers.map { it.id }.toSet()))
            return providers
        }
        set(value) {
            val providers = normalizeAiProviders(value)
            persistAiProviders(providers)
            val models = normalizeAiModels(
                readAiModelsRaw(),
                providers.map { it.id }.toSet()
            )
            persistAiModels(models)
            syncAiState(providers, models)
        }

    var aiCurrentProviderId: String?
        get() {
            val providers = readAiProviders()
            val models = readAiModels(providers.map { it.id }.toSet())
            syncAiState(providers, models)
            return appCtx.getPrefString(PreferKey.aiCurrentProviderId)
        }
        set(value) {
            val providers = readAiProviders()
            val providerId = providers.firstOrNull { it.id == value }?.id
            if (providerId.isNullOrBlank()) {
                appCtx.removePref(PreferKey.aiCurrentProviderId)
                appCtx.removePref(PreferKey.aiCurrentModelId)
            } else {
                appCtx.putPrefString(PreferKey.aiCurrentProviderId, providerId)
            }
            syncAiState(providers, readAiModels(providers.map { it.id }.toSet()))
        }

    val aiCurrentProvider: AiProviderConfig?
        get() = aiProviderList.firstOrNull { it.id == aiCurrentProviderId }

    var aiModelConfigList: List<AiModelConfig>
        get() {
            val providers = readAiProviders()
            val models = readAiModels(providers.map { it.id }.toSet())
            syncAiState(providers, models)
            return models
        }
        set(value) {
            val providers = readAiProviders()
            val models = normalizeAiModels(value, providers.map { it.id }.toSet())
            persistAiModels(models)
            syncAiState(providers, models)
        }

    var aiCurrentModelId: String?
        get() {
            val providers = readAiProviders()
            val models = readAiModels(providers.map { it.id }.toSet())
            syncAiState(providers, models)
            return appCtx.getPrefString(PreferKey.aiCurrentModelId)
        }
        set(value) {
            val providers = readAiProviders()
            val models = readAiModels(providers.map { it.id }.toSet())
            val model = models.firstOrNull { it.id == value }
            if (model == null) {
                appCtx.removePref(PreferKey.aiCurrentModelId)
            } else {
                appCtx.putPrefString(PreferKey.aiCurrentModelId, model.id)
                appCtx.putPrefString(PreferKey.aiCurrentProviderId, model.providerId)
            }
            syncAiState(providers, models)
        }

    val aiCurrentModelConfig: AiModelConfig?
        get() = aiModelConfigList.firstOrNull { it.id == aiCurrentModelId }

    var aiMcpServerList: List<AiMcpServerConfig>
        get() = readAiMcpServers()
        set(value) {
            persistAiMcpServers(normalizeAiMcpServers(value))
        }

    val aiEnabledMcpServers: List<AiMcpServerConfig>
        get() = aiMcpServerList.filter { it.enabled }

    var aiChatSessionList: List<AiChatSession>
        get() = runCatching {
            GSON.fromJsonArray<AiChatSession>(appCtx.getPrefString(PreferKey.aiChatSessionList))
                .getOrDefault(emptyList())
                .filter { session ->
                    session.id.isNotBlank() &&
                            session.title.isNotBlank() &&
                            session.messages.all { it.content.isNotBlank() }
                }
                .map { session ->
                    session.copy(
                        messages = session.messages.map { message ->
                            message.copy(
                                kind = message.kind ?: io.legado.app.ui.main.ai.AiChatMessage.Kind.TEXT,
                                statusName = message.statusName,
                                statusStage = message.statusStage,
                                statusSuccess = message.statusSuccess
                            )
                        }
                    )
                }
                .sortedByDescending { it.updatedAt }
        }.getOrElse {
            AppLog.put("读取 AI 聊天历史失败, 已清理历史\n${it.localizedMessage}", it)
            appCtx.removePref(PreferKey.aiChatSessionList)
            appCtx.removePref(PreferKey.aiCurrentChatSessionId)
            emptyList()
        }
        set(value) {
            val sessions = value.distinctBy { it.id }
                .mapNotNull { session ->
                    val title = session.title.trim()
                    val normalizedMessages = session.messages
                        .filter { it.content.isNotBlank() }
                        .map { it.copy(pending = false) }
                    if (session.id.isBlank() || title.isBlank() || normalizedMessages.isEmpty()) {
                        null
                    } else {
                        session.copy(
                            id = session.id.trim(),
                            title = title,
                            messages = normalizedMessages
                        )
                    }
                }
                .sortedByDescending { it.updatedAt }
                .take(30)
            if (sessions.isEmpty()) {
                appCtx.removePref(PreferKey.aiChatSessionList)
            } else {
                appCtx.putPrefString(PreferKey.aiChatSessionList, GSON.toJson(sessions))
            }
            val currentId = appCtx.getPrefString(PreferKey.aiCurrentChatSessionId)
            if (currentId != null && sessions.none { it.id == currentId }) {
                appCtx.removePref(PreferKey.aiCurrentChatSessionId)
            }
        }

    var aiCurrentChatSessionId: String?
        get() = appCtx.getPrefString(PreferKey.aiCurrentChatSessionId)
        set(value) {
            if (value.isNullOrBlank()) {
                appCtx.removePref(PreferKey.aiCurrentChatSessionId)
            } else {
                appCtx.putPrefString(PreferKey.aiCurrentChatSessionId, value.trim())
            }
        }

    var aiSystemPrompt: String
        get() = appCtx.getPrefString(PreferKey.aiSystemPrompt, DEFAULT_AI_SYSTEM_PROMPT)
            ?: DEFAULT_AI_SYSTEM_PROMPT
        set(value) {
            val prompt = value.trim()
            if (prompt.isBlank() || prompt == DEFAULT_AI_SYSTEM_PROMPT) {
                appCtx.removePref(PreferKey.aiSystemPrompt)
            } else {
                appCtx.putPrefString(PreferKey.aiSystemPrompt, prompt)
            }
        }

    var aiSkillPrompt: String
        get() = appCtx.getPrefString(PreferKey.aiSkillPrompt).orEmpty()
        set(value) {
            val prompt = value.trim()
            if (prompt.isBlank()) {
                appCtx.removePref(PreferKey.aiSkillPrompt)
            } else {
                appCtx.putPrefString(PreferKey.aiSkillPrompt, prompt)
            }
        }

    var aiSkillList: List<AiSkillConfig>
        get() = readAiSkills()
        set(value) {
            persistAiSkills(normalizeAiSkills(value))
        }

    var aiTavilyEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiTavilyEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiTavilyEnabled, value)

    var aiShowToolSummary: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiShowToolSummary, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiShowToolSummary, value)

    var aiTavilyApiKey: String
        get() = appCtx.getPrefString(PreferKey.aiTavilyApiKey).orEmpty()
        set(value) {
            val key = value.trim()
            if (key.isBlank()) appCtx.removePref(PreferKey.aiTavilyApiKey)
            else appCtx.putPrefString(PreferKey.aiTavilyApiKey, key)
        }

    var aiTavilyBaseUrl: String
        get() = appCtx.getPrefString(PreferKey.aiTavilyBaseUrl, "https://api.tavily.com/search")
            ?: "https://api.tavily.com/search"
        set(value) {
            val url = value.trim().ifBlank { "https://api.tavily.com/search" }
            appCtx.putPrefString(PreferKey.aiTavilyBaseUrl, url)
        }

    var aiTavilySearchDepth: String
        get() = appCtx.getPrefString(PreferKey.aiTavilySearchDepth, "basic") ?: "basic"
        set(value) = appCtx.putPrefString(PreferKey.aiTavilySearchDepth, value.trim().ifBlank { "basic" })

    var aiTavilyTopic: String
        get() = appCtx.getPrefString(PreferKey.aiTavilyTopic, "general") ?: "general"
        set(value) = appCtx.putPrefString(PreferKey.aiTavilyTopic, value.trim().ifBlank { "general" })

    var aiTavilyMaxResults: Int
        get() = appCtx.getPrefInt(PreferKey.aiTavilyMaxResults, 5).coerceIn(1, 10)
        set(value) = appCtx.putPrefInt(PreferKey.aiTavilyMaxResults, value.coerceIn(1, 10))

    val aiEnabledSkills: List<AiSkillConfig>
        get() = aiSkillList.filter { it.enabled }

    private fun readAiProviders(): List<AiProviderConfig> {
        migrateLegacyAiConfigIfNeeded()
        return normalizeAiProviders(
            GSON.fromJsonArray<AiProviderConfig>(appCtx.getPrefString(PreferKey.aiProviderList))
                .getOrDefault(emptyList())
        )
    }

    private fun readAiModels(validProviderIds: Set<String>): List<AiModelConfig> {
        migrateLegacyAiConfigIfNeeded()
        return normalizeAiModels(readAiModelsRaw(), validProviderIds)
    }

    private fun readAiModelsRaw(): List<AiModelConfig> {
        return GSON.fromJsonArray<AiModelConfig>(appCtx.getPrefString(PreferKey.aiModelConfigList))
            .getOrDefault(emptyList())
    }

    private fun normalizeAiProviders(value: List<AiProviderConfig>): List<AiProviderConfig> {
        return value.mapNotNull { provider ->
            val name = provider.name.trim()
            val id = provider.id.trim()
            if (name.isEmpty() || id.isEmpty()) {
                null
            } else {
                provider.copy(
                    id = id,
                    name = name,
                    baseUrl = provider.baseUrl.trim(),
                    apiKey = provider.apiKey.trim(),
                    headers = provider.headers?.trim().orEmpty()
                )
            }
        }.distinctBy { it.id }
    }

    private fun normalizeAiModels(
        value: List<AiModelConfig>,
        validProviderIds: Set<String>
    ): List<AiModelConfig> {
        return value.mapNotNull { model ->
            val id = model.id.trim()
            val providerId = model.providerId.trim()
            val modelId = model.modelId.trim()
            if (id.isEmpty() || providerId !in validProviderIds || modelId.isEmpty()) {
                null
            } else {
                model.copy(id = id, providerId = providerId, modelId = modelId)
            }
        }.distinctBy { "${it.providerId}|${it.modelId}" }
    }

    private fun persistAiProviders(providers: List<AiProviderConfig>) {
        if (providers.isEmpty()) {
            appCtx.removePref(PreferKey.aiProviderList)
        } else {
            appCtx.putPrefString(PreferKey.aiProviderList, GSON.toJson(providers))
        }
    }

    private fun persistAiModels(models: List<AiModelConfig>) {
        if (models.isEmpty()) {
            appCtx.removePref(PreferKey.aiModelConfigList)
        } else {
            appCtx.putPrefString(PreferKey.aiModelConfigList, GSON.toJson(models))
        }
    }

    private fun readAiMcpServers(): List<AiMcpServerConfig> {
        return normalizeAiMcpServers(
            GSON.fromJsonArray<AiMcpServerConfig>(appCtx.getPrefString(PreferKey.aiMcpServerList))
                .getOrDefault(emptyList())
        )
    }

    private fun normalizeAiMcpServers(value: List<AiMcpServerConfig>): List<AiMcpServerConfig> {
        return value.mapNotNull { server ->
            val id = server.id.trim()
            val name = server.name.trim()
            val endpoint = server.endpoint.trim()
            if (id.isEmpty() || name.isEmpty() || endpoint.isEmpty()) {
                null
            } else {
                server.copy(
                    id = id,
                    name = name,
                    endpoint = endpoint,
                    apiKey = server.apiKey.trim()
                )
            }
        }.distinctBy { it.id }
    }

    private fun persistAiMcpServers(servers: List<AiMcpServerConfig>) {
        if (servers.isEmpty()) {
            appCtx.removePref(PreferKey.aiMcpServerList)
        } else {
            appCtx.putPrefString(PreferKey.aiMcpServerList, GSON.toJson(servers))
        }
    }

    private fun readAiSkills(): List<AiSkillConfig> {
        migrateLegacyAiSkillIfNeeded()
        return normalizeAiSkills(
            GSON.fromJsonArray<AiSkillConfig>(appCtx.getPrefString(PreferKey.aiSkillList))
                .getOrDefault(emptyList())
        )
    }

    private fun normalizeAiSkills(value: List<AiSkillConfig>): List<AiSkillConfig> {
        return value.mapNotNull { skill ->
            val id = skill.id.trim()
            val name = skill.name.trim()
            val content = skill.content.trim()
            if (id.isEmpty() || name.isEmpty() || content.isEmpty()) {
                null
            } else {
                skill.copy(
                    id = id,
                    name = name,
                    description = skill.description.trim(),
                    content = content,
                    sourceUrl = skill.sourceUrl.trim()
                )
            }
        }.distinctBy { it.id }
    }

    private fun persistAiSkills(skills: List<AiSkillConfig>) {
        if (skills.isEmpty()) {
            appCtx.removePref(PreferKey.aiSkillList)
        } else {
            appCtx.putPrefString(PreferKey.aiSkillList, GSON.toJson(skills))
        }
    }

    private fun migrateLegacyAiSkillIfNeeded() {
        if (!appCtx.getPrefString(PreferKey.aiSkillList).isNullOrBlank()) {
            return
        }
        val prompt = appCtx.getPrefString(PreferKey.aiSkillPrompt).orEmpty().trim()
        if (prompt.isBlank()) {
            return
        }
        persistAiSkills(
            listOf(
                AiSkillConfig(
                    name = "Legado Skill",
                    description = "从旧版单文本 Skill 配置迁移",
                    content = prompt
                )
            )
        )
        appCtx.removePref(PreferKey.aiSkillPrompt)
    }

    private fun syncAiState(
        providers: List<AiProviderConfig>,
        models: List<AiModelConfig>
    ) {
        val providerId = providers.firstOrNull {
            it.id == appCtx.getPrefString(PreferKey.aiCurrentProviderId)
        }?.id ?: providers.firstOrNull()?.id

        if (providerId.isNullOrBlank()) {
            appCtx.removePref(PreferKey.aiCurrentProviderId)
            appCtx.removePref(PreferKey.aiCurrentModelId)
            appCtx.putPrefBoolean(PreferKey.aiAssistantEnabled, false)
            return
        }

        if (providerId != appCtx.getPrefString(PreferKey.aiCurrentProviderId)) {
            appCtx.putPrefString(PreferKey.aiCurrentProviderId, providerId)
        }

        val providerModels = models.filter { it.providerId == providerId }
        val currentModelId = providerModels.firstOrNull {
            it.id == appCtx.getPrefString(PreferKey.aiCurrentModelId)
        }?.id ?: providerModels.firstOrNull()?.id

        if (currentModelId.isNullOrBlank()) {
            appCtx.removePref(PreferKey.aiCurrentModelId)
            appCtx.putPrefBoolean(PreferKey.aiAssistantEnabled, false)
        } else if (currentModelId != appCtx.getPrefString(PreferKey.aiCurrentModelId)) {
            appCtx.putPrefString(PreferKey.aiCurrentModelId, currentModelId)
        }
    }

    private fun migrateLegacyAiConfigIfNeeded() {
        if (!appCtx.getPrefString(PreferKey.aiProviderList).isNullOrBlank()
            || !appCtx.getPrefString(PreferKey.aiModelConfigList).isNullOrBlank()
        ) {
            return
        }
        val legacyBaseUrl = appCtx.getPrefString(PreferKey.aiBaseUrl, "")?.trim().orEmpty()
        val legacyApiKey = appCtx.getPrefString(PreferKey.aiApiKey, "")?.trim().orEmpty()
        val legacyModels = GSON.fromJsonArray<String>(appCtx.getPrefString(PreferKey.aiModelList))
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (legacyBaseUrl.isBlank() && legacyApiKey.isBlank() && legacyModels.isEmpty()) {
            return
        }
        val provider = AiProviderConfig(
            name = resolveAiProviderName(legacyBaseUrl),
            baseUrl = legacyBaseUrl,
            apiKey = legacyApiKey
        )
        val models = legacyModels.map { modelId ->
            AiModelConfig(providerId = provider.id, modelId = modelId)
        }
        persistAiProviders(listOf(provider))
        if (models.isNotEmpty()) {
            persistAiModels(models)
        }
        appCtx.putPrefString(PreferKey.aiCurrentProviderId, provider.id)
        val legacyCurrentModel = appCtx.getPrefString(PreferKey.aiCurrentModel)?.trim().orEmpty()
        val currentModel = models.firstOrNull { it.modelId == legacyCurrentModel } ?: models.firstOrNull()
        if (currentModel == null) {
            appCtx.removePref(PreferKey.aiCurrentModelId)
            appCtx.putPrefBoolean(PreferKey.aiAssistantEnabled, false)
        } else {
            appCtx.putPrefString(PreferKey.aiCurrentModelId, currentModel.id)
        }
    }

    private fun resolveAiProviderName(baseUrl: String): String {
        if (baseUrl.isBlank()) return "Provider 1"
        return runCatching {
            URI(baseUrl).host?.removePrefix("www.")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Provider 1"
    }

    val autoRefreshBook: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoRefresh)

    val onlyUpdateRead: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.onlyUpdateRead)

    var enableReview: Boolean
        get() = BuildConfig.DEBUG && appCtx.getPrefBoolean(PreferKey.enableReview, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableReview, value)
        }

    var threadCount: Int
        get() = appCtx.getPrefInt(PreferKey.threadCount, 16)
        set(value) {
            appCtx.putPrefInt(PreferKey.threadCount, value)
        }

    var remoteServerId: Long
        get() = appCtx.getPrefLong(PreferKey.remoteServerId)
        set(value) {
            appCtx.putPrefLong(PreferKey.remoteServerId, value)
        }

    // 添加本地选择的目录
    var importBookPath: String?
        get() = appCtx.getPrefString("importBookPath")
        set(value) {
            if (value == null) {
                appCtx.removePref("importBookPath")
            } else {
                appCtx.putPrefString("importBookPath", value)
            }
        }

    var ttsFlowSys: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.ttsFollowSys, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.ttsFollowSys, value)
        }

    val noAnimScrollPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.noAnimScrollPage, false)

    const val defaultSpeechRate = 5

    var ttsSpeechRate: Int
        get() = appCtx.getPrefInt(PreferKey.ttsSpeechRate, defaultSpeechRate)
        set(value) {
            appCtx.putPrefInt(PreferKey.ttsSpeechRate, value)
        }

    var ttsTimer: Int
        get() = appCtx.getPrefInt(PreferKey.ttsTimer, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.ttsTimer, value)
        }

    val speechRatePlay: Int get() = if (ttsFlowSys) defaultSpeechRate else ttsSpeechRate

    var chineseConverterType: Int
        get() = appCtx.getPrefInt(PreferKey.chineseConverterType)
        set(value) {
            appCtx.putPrefInt(PreferKey.chineseConverterType, value)
        }

    var systemTypefaces: Int
        get() = appCtx.getPrefInt(PreferKey.systemTypefaces)
        set(value) {
            appCtx.putPrefInt(PreferKey.systemTypefaces, value)
        }

    var elevation: Int
        get() = if (isEInkMode) 0 else appCtx.getPrefInt(
            PreferKey.barElevation,
            AppConst.sysElevation
        )
        set(value) {
            appCtx.putPrefInt(PreferKey.barElevation, value)
        }

    var frostedGlassLevel: Int
        get() = appCtx.getPrefInt(PreferKey.frostedGlassLevel, 70).coerceIn(0, 100)
        set(value) {
            appCtx.putPrefInt(PreferKey.frostedGlassLevel, value.coerceIn(0, 100))
        }

    var liquidGlassLevel: Int
        get() = appCtx.getPrefInt(PreferKey.liquidGlassLevel, 68).coerceIn(0, 100)
        set(value) {
            appCtx.putPrefInt(PreferKey.liquidGlassLevel, value.coerceIn(0, 100))
        }

    var bottomBarEffectMode: String
        get() = appCtx.getPrefString(PreferKey.bottomBarEffectMode, "glass")
            ?.takeIf { it in setOf("glass", "frosted", "solid") }
            ?: "glass"
        set(value) {
            appCtx.putPrefString(
                PreferKey.bottomBarEffectMode,
                value.takeIf { it in setOf("glass", "frosted", "solid") } ?: "glass"
            )
        }

    var readUrlInBrowser: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readUrlOpenInBrowser)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.readUrlOpenInBrowser, value)
        }

    var exportCharset: String
        get() {
            val c = appCtx.getPrefString(PreferKey.exportCharset)
            if (c.isNullOrBlank()) {
                return "UTF-8"
            }
            return c
        }
        set(value) {
            appCtx.putPrefString(PreferKey.exportCharset, value)
        }

    var exportUseReplace: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportUseReplace, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportUseReplace, value)
        }

    var exportToWebDav: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportToWebDav)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportToWebDav, value)
        }
    var exportNoChapterName: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportNoChapterName)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportNoChapterName, value)
        }

    // 是否启用自定义导出 default->false
    var enableCustomExport: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.enableCustomExport, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableCustomExport, value)
        }

    var exportType: Int
        get() = appCtx.getPrefInt(PreferKey.exportType)
        set(value) {
            appCtx.putPrefInt(PreferKey.exportType, value)
        }
    var exportPictureFile: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportPictureFile, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportPictureFile, value)
        }

    var parallelExportBook: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.parallelExportBook, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.parallelExportBook, value)
        }

    var changeSourceCheckAuthor: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceCheckAuthor)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceCheckAuthor, value)
        }

    var ttsEngine: String?
        get() = appCtx.getPrefString(PreferKey.ttsEngine)
        set(value) {
            appCtx.putPrefString(PreferKey.ttsEngine, value)
        }

    var webPort: Int
        get() = appCtx.getPrefInt(PreferKey.webPort, 1122)
        set(value) {
            appCtx.putPrefInt(PreferKey.webPort, value)
        }

    var tocUiUseReplace: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocUiUseReplace)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocUiUseReplace, value)
        }

    var tocCountWords: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocCountWords, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocCountWords, value)
        }

    var enableReadRecord: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.enableReadRecord, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableReadRecord, value)
        }

    val autoChangeSource: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoChangeSource, true)

    var changeSourceLoadInfo: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadInfo)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadInfo, value)
        }

    var changeSourceLoadToc: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadToc)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadToc, value)
        }

    var changeSourceLoadWordCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadWordCount)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadWordCount, value)
        }

    var openBookInfoByClickTitle: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.openBookInfoByClickTitle, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.openBookInfoByClickTitle, value)
        }

    var showBookshelfFastScroller: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showBookshelfFastScroller, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showBookshelfFastScroller, value)
        }

    var contentSelectSpeakMod: Int
        get() = appCtx.getPrefInt(PreferKey.contentSelectSpeakMod)
        set(value) {
            appCtx.putPrefInt(PreferKey.contentSelectSpeakMod, value)
        }

    var batchChangeSourceDelay: Int
        get() = appCtx.getPrefInt(PreferKey.batchChangeSourceDelay)
        set(value) {
            appCtx.putPrefInt(PreferKey.batchChangeSourceDelay, value)
        }

    val importKeepName get() = appCtx.getPrefBoolean(PreferKey.importKeepName)
    val importKeepGroup get() = appCtx.getPrefBoolean(PreferKey.importKeepGroup)
    var importKeepEnable: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.importKeepEnable, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.importKeepEnable, value)
        }
    var importShowComment: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.importShowComment, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.importShowComment, value)
        }

    val clickImgWay: String?
        get() = appCtx.getPrefString(PreferKey.clickImgWay)

    var preDownloadNum
        get() = appCtx.getPrefInt(PreferKey.preDownloadNum, 10)
        set(value) {
            appCtx.putPrefInt(PreferKey.preDownloadNum, value)
        }

    val syncBookProgress get() = appCtx.getPrefBoolean(PreferKey.syncBookProgress, true)

    val syncBookProgressPlus get() = appCtx.getPrefBoolean(PreferKey.syncBookProgressPlus, false)

    val mediaButtonOnExit get() = appCtx.getPrefBoolean("mediaButtonOnExit", true)

    val readAloudByMediaButton
        get() = appCtx.getPrefBoolean(PreferKey.readAloudByMediaButton, false)

    val replaceEnableDefault get() = appCtx.getPrefBoolean(PreferKey.replaceEnableDefault, true)

    val webDavDir get() = appCtx.getPrefString(PreferKey.webDavDir, "legado")

    val webDavDeviceName get() = appCtx.getPrefString(PreferKey.webDavDeviceName, Build.MODEL)

    var syncThemePackages
        get() = appCtx.getPrefBoolean(PreferKey.syncThemePackages, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.syncThemePackages, value)

    val recordHeapDump get() = appCtx.getPrefBoolean(PreferKey.recordHeapDump, false)

    val loadCoverOnlyWifi get() = appCtx.getPrefBoolean(PreferKey.loadCoverOnlyWifi, false)

    val showAddToShelfAlert get() = appCtx.getPrefBoolean(PreferKey.showAddToShelfAlert, true)

    val ignoreAudioFocus get() = appCtx.getPrefBoolean(PreferKey.ignoreAudioFocus, false)

    var pauseReadAloudWhilePhoneCalls
        get() = appCtx.getPrefBoolean(PreferKey.pauseReadAloudWhilePhoneCalls, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.pauseReadAloudWhilePhoneCalls, value)

    val onlyLatestBackup get() = appCtx.getPrefBoolean(PreferKey.onlyLatestBackup, true)

    val autoCheckNewBackup get() = appCtx.getPrefBoolean(PreferKey.autoCheckNewBackup, true)

    val defaultHomePage get() = appCtx.getPrefString(PreferKey.defaultHomePage, "bookshelf")

    val updateToVariant get() = appCtx.getPrefString(PreferKey.updateToVariant, "default_version")

    val streamReadAloudAudio get() = appCtx.getPrefBoolean(PreferKey.streamReadAloudAudio, false)

    val doublePageHorizontal: String?
        get() = appCtx.getPrefString(PreferKey.doublePageHorizontal)

    val progressBarBehavior: String?
        get() = appCtx.getPrefString(PreferKey.progressBarBehavior, "page")

    val keyPageOnLongPress
        get() = appCtx.getPrefBoolean(PreferKey.keyPageOnLongPress, false)

    val volumeKeyPage
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPage, true)

    val volumeKeyPageOnPlay
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPageOnPlay, true)

    val mouseWheelPage
        get() = appCtx.getPrefBoolean(PreferKey.mouseWheelPage, true)

    val paddingDisplayCutouts
        get() = appCtx.getPrefBoolean(PreferKey.paddingDisplayCutouts, false)

    var searchScope: String
        get() = appCtx.getPrefString("searchScope") ?: ""
        set(value) {
            appCtx.putPrefString("searchScope", value)
        }

    var searchGroup: String
        get() = appCtx.getPrefString("searchGroup") ?: ""
        set(value) {
            appCtx.putPrefString("searchGroup", value)
        }

    var pageTouchSlop: Int
        get() = appCtx.getPrefInt(PreferKey.pageTouchSlop, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.pageTouchSlop, value)
        }

    var pageTouchClick: Int
        get() = appCtx.getPrefInt(PreferKey.pageTouchClick, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.pageTouchClick, value)
        }

    var bookshelfSort: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfSort, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfSort, value)
        }

    fun getBookSortByGroupId(groupId: Long): Int {
        return appDb.bookGroupDao.getByID(groupId)?.getRealBookSort()
            ?: bookshelfSort
    }

    private fun getPrefUserAgent(): String {
        val ua = appCtx.getPrefString(PreferKey.userAgent)
        if (ua.isNullOrBlank()) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + BuildConfig.Cronet_Main_Version + " Safari/537.36"
        }
        return ua
    }

    var bitmapCacheSize: Int
        get() = appCtx.getPrefInt(PreferKey.bitmapCacheSize, 50)
        set(value) {
            appCtx.putPrefInt(PreferKey.bitmapCacheSize, value)
        }

    var imageRetainNum: Int
        get() = appCtx.getPrefInt(PreferKey.imageRetainNum, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.imageRetainNum, value)
        }

    var showReadTitleBarAddition: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showReadTitleAddition, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showReadTitleAddition, value)
        }
    var readBarStyleFollowPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readBarStyleFollowPage, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.readBarStyleFollowPage, value)
        }
    var readScrollFollowBackground: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readScrollFollowBackground, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.readScrollFollowBackground, value)
        }
    var readMenuAlpha: Int
        get() = appCtx.getPrefInt(PreferKey.readMenuAlpha, 75).coerceIn(35, 100)
        set(value) {
            appCtx.putPrefInt(PreferKey.readMenuAlpha, value.coerceIn(35, 100))
        }

    var sourceEditMaxLine: Int
        get() {
            val maxLine = appCtx.getPrefInt(PreferKey.sourceEditMaxLine, Int.MAX_VALUE)
            if (maxLine < 10) {
                return Int.MAX_VALUE
            }
            return maxLine
        }
        set(value) {
            appCtx.putPrefInt(PreferKey.sourceEditMaxLine, value)
        }

    var audioPlayUseWakeLock: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.audioPlayWakeLock)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.audioPlayWakeLock, value)
        }

    var brightnessVwPos: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.brightnessVwPos)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.brightnessVwPos, value)
        }

    fun detectClickArea() {
        if (clickActionTL * clickActionTC * clickActionTR
            * clickActionML * clickActionMC * clickActionMR
            * clickActionBL * clickActionBC * clickActionBR != 0
        ) {
            appCtx.putPrefInt(PreferKey.clickActionMC, 0)
            appCtx.toastOnUi("当前没有配置菜单区域,自动恢复中间区域为菜单.")
        }
    }

    //跳转到漫画界面不使用富文本模式
    val showMangaUi: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showMangaUi, true)

    //禁用漫画缩放
    var disableMangaScale: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.disableMangaScale, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableMangaScale, value)
        }

    var disableMangaPageAnim: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.disableMangaPageAnim, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableMangaPageAnim, value)
        }

    //漫画预加载数量
    var mangaPreDownloadNum
        get() = appCtx.getPrefInt(PreferKey.mangaPreDownloadNum, 10)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaPreDownloadNum, value)
        }

    //点击翻页
    var disableClickScroll
        get() = appCtx.getPrefBoolean(PreferKey.disableClickScroll, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableClickScroll, value)
        }

    //漫画滚动速度
    var mangaAutoPageSpeed
        get() = appCtx.getPrefInt(PreferKey.mangaAutoPageSpeed, 3)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaAutoPageSpeed, value)
        }

    //漫画页脚配置
    var mangaFooterConfig
        get() = appCtx.getPrefString(PreferKey.mangaFooterConfig, "")
        set(value) {
            appCtx.putPrefString(PreferKey.mangaFooterConfig, value)
        }

    //漫画水平滚动
    var enableMangaHorizontalScroll
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaHorizontalScroll, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaHorizontalScroll, value)
        }

    var mangaColorFilter
        get() = appCtx.getPrefString(PreferKey.mangaColorFilter, "")
        set(value) {
            appCtx.putPrefString(PreferKey.mangaColorFilter, value)
        }

    //禁用漫画内标题
    var hideMangaTitle
        get() = appCtx.getPrefBoolean(PreferKey.hideMangaTitle, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.hideMangaTitle, value)
        }

    //开启墨水屏模式
    var enableMangaEInk
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaEInk, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaEInk, value)
        }

    var mangaEInkThreshold
        get() = appCtx.getPrefInt(PreferKey.mangaEInkThreshold, 150)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaEInkThreshold, value)
        }

    var disableHorizontalPageSnap
        get() = appCtx.getPrefBoolean(PreferKey.disableHorizontalPageSnap, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableHorizontalPageSnap, value)
        }

    var enableMangaGray
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaGray, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaGray, value)
        }

    var welcomeImage
        get() = appCtx.getPrefString(PreferKey.welcomeImage)
        set(value) {
            appCtx.putPrefString(PreferKey.welcomeImage, value)
        }

    var welcomeShowText
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowText, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowText, value)
        }

    var welcomeShowIcon
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowIcon, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowIcon, value)
        }

    var welcomeImageDark
        get() = appCtx.getPrefString(PreferKey.welcomeImageDark)
        set(value) {
            appCtx.putPrefString(PreferKey.welcomeImageDark, value)
        }

    var welcomeShowTextDark
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowTextDark, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowTextDark, value)
        }

    var welcomeShowIconDark
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowIconDark, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowIconDark, value)
        }

    val autoUpdateVariant get() = appCtx.getPrefBoolean("autoUpdateVariant", true)
}
