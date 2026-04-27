package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import org.json.JSONObject

data class AiResolvedTool(
    val name: String,
    val definition: JSONObject,
    val execute: suspend (JSONObject?) -> String
)

object AiToolRegistry {

    suspend fun resolveAvailableTools(): List<AiResolvedTool> {
        val tools = AiBookshelfTool.resolvedTools().toMutableList()
        tools += AiBookSourceTool.resolvedTools()
        tools += AiMcpClient.resolveTools(AppConfig.aiEnabledMcpServers)
        return tools.distinctBy { it.name }
    }
}
