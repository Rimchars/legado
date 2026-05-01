package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import org.json.JSONObject

data class AiResolvedTool(
    val name: String,
    val definition: JSONObject,
    val execute: suspend (JSONObject?) -> String
)

object AiToolRegistry {

    val defaultEnabledTools = setOf(
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

    private val toolGroupPrefixes = listOf(
        "mcp_" to "MCP",
        "query_bookshelf" to "书架",
        "get_bookshelf" to "书架",
        "manage_bookshelf" to "书架",
        "set_bookshelf" to "书架",
        "query_read_records" to "书架",
        "list_book_sources" to "书源",
        "search_book_source" to "书源",
        "create_book_source" to "书源",
        "get_book_source" to "书源",
        "update_book_source" to "书源",
        "fetch_source_html" to "书源",
        "debug_book_source" to "书源",
        "list_book_chapters" to "阅读",
        "read_book_chapter_content" to "阅读",
        "search_web_tavily" to "联网搜索",
        "get_app_settings" to "设置",
        "set_app_setting" to "设置"
    )

    fun groupLabelOfTool(name: String): String {
        return toolGroupPrefixes.firstOrNull { name.startsWith(it.first) }?.second ?: "其他"
    }

    private fun nativeResolvedTools(): List<AiResolvedTool> {
        val tools = AiBookshelfTool.resolvedTools().toMutableList()
        tools += AiLibraryTool.resolvedTools()
        tools += AiTavilyTool.resolvedTools()
        tools += AiBookSourceTool.resolvedTools()
        tools += AiSettingsTool.resolvedTools()
        return tools.distinctBy { it.name }
    }

    suspend fun resolveAllToolNamesForManage(): List<String> {
        val dynamic = mutableSetOf<String>()
        dynamic += nativeResolvedTools().map { it.name }
        dynamic += AiMcpClient.resolveTools(AppConfig.aiEnabledMcpServers).map { it.name }
        dynamic += AppConfig.aiEnabledToolNames
        dynamic += defaultEnabledTools
        return dynamic.toList().sorted()
    }

    suspend fun resolveAvailableTools(): List<AiResolvedTool> {
        val tools = nativeResolvedTools().toMutableList()
        tools += AiMcpClient.resolveTools(AppConfig.aiEnabledMcpServers)
        val enabled = AppConfig.aiEnabledToolNames.ifEmpty { defaultEnabledTools }
        return tools
            .distinctBy { it.name }
            .filter { it.name in enabled }
    }
}
