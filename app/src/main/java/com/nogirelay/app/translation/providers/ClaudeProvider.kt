package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.BaseAIProvider
import org.json.JSONArray
import org.json.JSONObject

class ClaudeProvider : BaseAIProvider() {
    override val type = AIProviderType.CLAUDE
    override val baseUrl = "https://api.anthropic.com"
    override val modelsEndpoint = "/v1/models"
    
    override fun buildHeaders(apiKey: String) = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to "2023-06-01",
        "Content-Type" to "application/json"
    )
    
    override fun buildTranslateRequest(model: String, text: String, nickname: String): String {
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("max_tokens", 4096)
        payload.put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", createPrompt(text, nickname))
            })
        })
        return payload.toString()
    }
    
    override fun parseTranslateResponse(response: String): String {
        val json = JSONObject(response)
        return json.getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
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
    
    override fun filterChatModels(models: List<AIModel>): List<AIModel> {
        return models.filter { 
            it.id.startsWith("claude-") && 
            !it.id.contains("opus") // 过滤太贵的模型,可选
        }.sortedBy { model ->
            when {
                model.id.contains("haiku") -> 0
                model.id.contains("sonnet") -> 1
                model.id.contains("opus") -> 2
                else -> 10
            }
        }
    }
    
    override suspend fun fetchModels(apiKey: String): Result<List<AIModel>> {
        return TranslationNetworkHelper.fetchModels(this, apiKey)
    }
    
    override suspend fun translate(apiKey: String, model: String, text: String, nickname: String): Result<String> {
        return TranslationNetworkHelper.translate(this, apiKey, model, text, nickname, "$baseUrl/v1/messages")
    }
}
