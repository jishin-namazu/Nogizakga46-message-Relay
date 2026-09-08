package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.BaseAIProvider
import org.json.JSONArray
import org.json.JSONObject

// Google Gemini
class GeminiProvider : BaseAIProvider() {
    override val type = AIProviderType.GEMINI
    override val baseUrl = "https://generativelanguage.googleapis.com"
    override val modelsEndpoint = "/v1beta/models"
    
    override fun buildHeaders(apiKey: String) = mapOf(
        "Content-Type" to "application/json"
    )
    
    override fun buildTranslateRequest(model: String, text: String, nickname: String): String {
        val payload = JSONObject()
        payload.put("contents", JSONArray().apply {
            put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", createPrompt(text, nickname))
                    })
                })
            })
        })
        return payload.toString()
    }
    
    override fun parseTranslateResponse(response: String): String {
        val json = JSONObject(response)
        return json.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .trim()
    }
    
    override fun parseModelsResponse(response: String): List<AIModel> {
        val json = JSONObject(response)
        val models = json.getJSONArray("models")
        return (0 until models.length()).map { i ->
            val model = models.getJSONObject(i)
            val name = model.getString("name").substringAfter("models/")
            AIModel(name, name)
        }
    }
    
    override fun filterChatModels(models: List<AIModel>): List<AIModel> {
        return models.filter { 
            it.id.startsWith("gemini-") && 
            !it.id.contains("embedding") &&
            !it.id.contains("vision")
        }
    }
    
    override suspend fun fetchModels(apiKey: String): Result<List<AIModel>> {
        return TranslationNetworkHelper.fetchModels(this, apiKey)
    }
    
    override suspend fun translate(apiKey: String, model: String, text: String, nickname: String): Result<String> {
        val endpoint = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey"
        return TranslationNetworkHelper.translate(this, apiKey, model, text, nickname, endpoint)
    }
}

// Grok (xAI)
class GrokProvider : OpenAICompatibleProvider() {
    override val type = AIProviderType.GROK
    override val baseUrl = "https://api.x.ai"
    override val modelsEndpoint = "/v1/models"
    override val chatCompletionEndpoint = "$baseUrl/v1/chat/completions"
    
    override fun filterChatModels(models: List<AIModel>): List<AIModel> {
        return models.filter { it.id.startsWith("grok-") }
    }
}
