package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType

class ClaudeProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.CLAUDE
    override val baseUrl = "https://api.anthropic.com"
    override val modelsEndpoint = "/v1/models"
    override val messagesEndpoint = "$baseUrl/v1/messages"
    override val includeAnthropicVersion = true

    override fun buildModelHeaders(apiKey: String) = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to "2023-06-01",
        "Content-Type" to "application/json",
    )

    override fun filterChatModels(models: List<AIModel>): List<AIModel> =
        models.filter { it.id.startsWith("claude-", ignoreCase = true) }
}
