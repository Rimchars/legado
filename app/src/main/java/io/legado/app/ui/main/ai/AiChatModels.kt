package io.legado.app.ui.main.ai

import androidx.annotation.Keep
import java.util.UUID

@Keep
data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val pending: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    @Keep
    enum class Role {
        USER,
        ASSISTANT
    }
}

@Keep
data class AiChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<AiChatMessage> = emptyList()
)

@Keep
class AiChatException(
    override val message: String,
    val debugLog: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
