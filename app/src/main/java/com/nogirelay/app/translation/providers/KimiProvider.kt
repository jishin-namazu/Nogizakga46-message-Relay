package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType

class KimiProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.KIMI
    override val baseUrl = "https://api.moonshot.cn"
    override val modelsEndpoint = "/v1/models"
    override val messagesEndpoint = "$baseUrl/anthropic/v1/messages"

    private fun buildMessagesHeaders(apiKey: String) = mapOf(
        "Authorization" to "Bearer $apiKey",
        "anthropic-version" to "2023-06-01",
        "Content-Type" to "application/json",
    )

    override fun buildModelHeaders(apiKey: String) = buildMessagesHeaders(apiKey)

    override fun buildHeaders(apiKey: String) = buildMessagesHeaders(apiKey)

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        id.startsWith("kimi-") || id.startsWith("moonshot-")
    }
}
