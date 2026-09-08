package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType

class DeepSeekProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.DEEPSEEK
    override val baseUrl = "https://api.deepseek.com"
    override val modelsEndpoint = "/models"
    override val messagesEndpoint = "$baseUrl/anthropic/v1/messages"

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter {
        it.id.startsWith("deepseek-", ignoreCase = true) &&
            !it.id.contains("embedding", ignoreCase = true)
    }
}

class GLMProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.GLM
    override val baseUrl = "https://open.bigmodel.cn"
    override val modelsEndpoint = "/api/paas/v4/models"
    override val messagesEndpoint = "$baseUrl/api/anthropic/v1/messages"

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        (id.startsWith("glm-") || id.startsWith("chatglm")) &&
            listOf("embedding", "image", "tts", "asr").none(id::contains)
    }
}

class QwenProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.QWEN
    override val baseUrl = "https://dashscope.aliyuncs.com"
    override val modelsEndpoint = "/compatible-mode/v1/models"
    override val messagesEndpoint = "$baseUrl/apps/anthropic/v1/messages"

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        id.contains("qwen") &&
            listOf("embedding", "audio", "tts", "asr", "image").none(id::contains)
    }
}

class MiniMaxProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.MINIMAX
    override val baseUrl = "https://api.minimaxi.com"
    override val modelsEndpoint = "/v1/models"
    override val messagesEndpoint = "$baseUrl/anthropic/v1/messages"

    override fun filterChatModels(models: List<AIModel>): List<AIModel> =
        models.filter { it.id.startsWith("MiniMax-M", ignoreCase = true) }
}

class MiMoProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.MIMO
    override val baseUrl = "https://api.xiaomimimo.com"
    override val modelsEndpoint = "/v1/models"
    override val messagesEndpoint = "$baseUrl/anthropic/v1/messages"

    override fun buildModelHeaders(apiKey: String) = mapOf(
        "api-key" to apiKey,
        "Content-Type" to "application/json",
    )

    override fun buildHeaders(apiKey: String) = buildModelHeaders(apiKey)

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        id.startsWith("mimo-") && listOf("asr", "tts").none(id::contains)
    }
}

class HunYuanProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.HUNYUAN
    override val baseUrl = "https://tokenhub.tencentmaas.com"
    override val modelsEndpoint = "/v1/models"
    override val messagesEndpoint = "$baseUrl/v1/messages"

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        id.startsWith("hy") || id.startsWith("hunyuan-")
    }
}
