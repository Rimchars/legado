package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import org.json.JSONObject

data class AiResolvedTool(
    val name: String,
    val definition: JSONObject,
    val execute: suspend (JSONObject?) -> String
)

object AiToolRegistry {

    private val defaultEnabledTools = setOf(
        "query_bookshelf",
        "get_bookshelf_book_info",
        "manage_bookshelf_group",
        "manage_bookshelf_tag",
        "set_bookshelf_book_group",
        "set_bookshelf_book_tags",
        "list_book_chapters",
        "read_book_chapter_content",
        "query_read_records",
        "list_book_sources",
        "search_book_source",
        "search_web_tavily",
        "create_book_source",
        "get_book_source",
        "update_book_source",
        "fetch_source_html",
        "debug_book_source",
        "get_app_settings",
        "set_app_setting",
        "set_app_settings_batch"
    )

    suspend fun resolveAvailableTools(): List<AiResolvedTool> {
        val tools = AiBookshelfTool.resolvedTools().toMutableList()
        tools += AiLibraryTool.resolvedTools()
        tools += AiTavilyTool.resolvedTools()
        tools += AiBookSourceTool.resolvedTools()
        tools += AiSettingsTool.resolvedTools()
        tools += AiMcpClient.resolveTools(AppConfig.aiEnabledMcpServers)
        val enabled = AppConfig.aiEnabledToolNames.ifEmpty { defaultEnabledTools }
        return tools
            .distinctBy { it.name }
            .filter { it.name in enabled }
    }
}
