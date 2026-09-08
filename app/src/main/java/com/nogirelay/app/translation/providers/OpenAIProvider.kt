package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.BaseAIProvider
import org.json.JSONArray
import org.json.JSONObject

class OpenAIProvider : BaseAIProvider() {
    override val type = AIProviderType.OPENAI
    override val baseUrl = "https://api.openai.com"
    override val modelsEndpoint = "/v1/models"
    
    override fun buildHeaders(apiKey: String) = mapOf(
        "Authorization" to "Bearer $apiKey",
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
    
    override fun filterChatModels(models: List<AIModel>): List<AIModel> {
        return models.filter { model ->
            val id = model.id.lowercase()
            val chatFamily = id.startsWith("gpt-") || id.startsWith("o1")
            val nonChatFamily = listOf(
                "embedding", "whisper", "tts", "dall-e", "image", "moderation"
            ).any { id.contains(it) }
            chatFamily && !nonChatFamily
        }.sortedBy { model ->
            when (model.id) {
                "gpt-4o-mini" -> 0
                "gpt-4.1-mini" -> 1
                "gpt-4.1" -> 2
                "gpt-4o" -> 3
                else -> if (model.id.startsWith("gpt-")) 10 else 20
            }
        }
    }
    
    override suspend fun fetchModels(apiKey: String): Result<List<AIModel>> {
        return TranslationNetworkHelper.fetchModels(this, apiKey)
    }
    
    override suspend fun translate(apiKey: String, model: String, text: String, nickname: String): Result<String> {
        return TranslationNetworkHelper.translate(this, apiKey, model, text, nickname, "$baseUrl/v1/chat/completions")
    }
}
