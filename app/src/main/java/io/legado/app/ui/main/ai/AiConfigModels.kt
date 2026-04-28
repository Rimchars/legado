package io.legado.app.ui.main.ai

import androidx.annotation.Keep
import java.util.UUID

@Keep
data class AiProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val headers: String? = ""
)

@Keep
data class AiModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val modelId: String
)

@Keep
data class AiMcpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val endpoint: String,
    val apiKey: String = "",
    val enabled: Boolean = true
)

@Keep
data class AiSkillConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val content: String,
    val sourceUrl: String = "",
    val enabled: Boolean = true
)
