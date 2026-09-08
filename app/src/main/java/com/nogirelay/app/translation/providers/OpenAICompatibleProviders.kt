package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.BaseAIProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容 API 的通用实现
 * 适用于使用相同 API 格式的供应商: DeepSeek, GLM, Qwen, Grok, MiniMax, MiMo, HunYuan
 */
abstract class OpenAICompatibleProvider : BaseAIProvider() {
    protected abstract val chatCompletionEndpoint: String
    protected open val apiKeyHeader: String = "Authorization"
    protected open val apiKeyPrefix: String = "Bearer"
    
    override fun buildHeaders(apiKey: String) = mapOf(
        apiKeyHeader to "$apiKeyPrefix $apiKey",
        "Content-Type" to "application/json"
    )
    
    override fun buildTranslateRequest(model: String, text: String, nickname: String): String {
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", createPrompt(text, nickname))
            })
        })
        payload.put("temperature", 0.3)
        return payload.toString()
    }
    
    override fun parseTranslateResponse(response: String): String {
        val json = JSONObject(response)
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }
    
    override fun parseModelsResponse(response: String): List<AIModel> {
        val json = JSONObject(response)
        val data = json.getJSONArray("data")
        return (0 until data.length()).map { i ->
            val model = data.getJSONObject(i)
            val id = model.getString("id")
            AIModel(id, id)
        }
    }
    
    override suspend fun fetchModels(apiKey: String): Result<List<AIModel>> {
        return TranslationNetworkHelper.fetchModels(this, apiKey)
    }
    
    override suspend fun translate(apiKey: String, model: String, text: String, nickname: String): Result<String> {
        return TranslationNetworkHelper.translate(this, apiKey, model, text, nickname, chatCompletionEndpoint)
    }
}

// DeepSeek
class DeepSeekProvider : OpenAICompatibleProvider() {
    override val type = AIProviderType.DEEPSEEK
    override val baseUrl = "https://api.deepseek.com"
    override val modelsEndpoint = "/v1/models"
    override val chatCompletionEndpoint = "$baseUrl/v1/chat/completions"
    
    override fun filterChatModels(models: List<AIModel>): List<AIModel> {
        return models.filter { it.id.startsWith("deepseek-") && it.id.contains("chat") }
    }
}

// 智谱 GLM
class GLMProvider : OpenAICompatibleProvider() {
    override val type = AIProviderType.GLM
    override val baseUrl = "https://open.bigmodel.cn"
    override val modelsEndpoint = "/api/paas/v4/models"
    override val chatCompletionEndpoint = "$baseUrl/api/paas/v4/chat/completions"
    
    override fun filterChatModels(models: List<AIModel>): List<AIModel> {
        return models.filter { 
            val id = it.id.lowercase()
            (id.startsWith("glm-") || id.startsWith("chatglm")) && !id.contains("embedding")
        }
    }
}

// 通义千问
class QwenProvider : OpenAICompatibleProvider() {
    override val type = AIProviderType.QWEN
    override val baseUrl = "https://dashscope.aliyuncs.com"
    override val modelsEndpoint = "/compatible-mode/v1/models"
    override val chatCompletionEndpoint = "$baseUrl/compatible-mode/v1/chat/completions"
    override val apiKeyHeader = "Authorization"
    override val apiKeyPrefix = "Bearer"
    
    override fun filterChatModels(models: List<AIModel>): List<AIModel> {
        return models.filter { 
            it.id.contains("qwen") && !it.id.contains("embedding") && !it.id.contains("audio")
        }
    }
}

// MiniMax
class MiniMaxProvider : OpenAICompatibleProvider() {
    override val type = AIProviderType.MINIMAX
    override val baseUrl = "https://api.minimax.chat"
    override val modelsEndpoint = "/v1/text/chatcompletion_v2"
    override val chatCompletionEndpoint = "$baseUrl/v1/text/chatcompletion_v2"
    override val apiKeyHeader = "Authorization"
    override val apiKeyPrefix = "Bearer"
    
    override fun filterChatModels(models: List<AIModel>): List<AIModel> {
        // MiniMax 模型列表可能需要特殊处理,这里返回常见模型
        return listOf(
            AIModel("abab6.5s-chat", "abab6.5s-chat"),
            AIModel("abab6.5-chat", "abab6.5-chat"),
            AIModel("abab5.5-chat", "abab5.5-chat")
        )
    }
    
    override suspend fun fetchModels(apiKey: String): Result<List<AIModel>> {
        // MiniMax 可能没有标准的 models 接口,返回预定义列表
        return Result.success(filterChatModels(emptyList()))
    }
}

// 小米 MiMo
class MiMoProvider : OpenAICompatibleProvider() {
    override val type = AIProviderType.MIMO
    override val baseUrl = "https://api.miaoxiaomi.ai"
    override val modelsEndpoint = "/v1/models"
    override val chatCompletionEndpoint = "$baseUrl/v1/chat/completions"
    
    override fun filterChatModels(models: List<AIModel>): List<AIModel> {
        return models.filter { it.id.contains("mimo") || it.id.contains("xiaomi") }
    }
}

// 腾讯混元
class HunYuanProvider : OpenAICompatibleProvider() {
    override val type = AIProviderType.HUNYUAN
    override val baseUrl = "https://api.hunyuan.cloud.tencent.com"
    override val modelsEndpoint = "/v1/models"
    override val chatCompletionEndpoint = "$baseUrl/v1/chat/completions"
    
    override fun filterChatModels(models: List<AIModel>): List<AIModel> {
        return models.filter { it.id.startsWith("hunyuan-") && !it.id.contains("embedding") }
    }
}
