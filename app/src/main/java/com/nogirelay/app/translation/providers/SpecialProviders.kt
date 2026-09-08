package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProtocol
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.BaseAIProvider
import org.json.JSONArray
import org.json.JSONObject

class GeminiProvider : BaseAIProvider() {
    override val type = AIProviderType.GEMINI
    override val protocol = AIProtocol.GEMINI_GENERATE_CONTENT
    override val baseUrl = "https://generativelanguage.googleapis.com"
    override val modelsEndpoint = "/v1beta/models?pageSize=1000"

    override fun buildHeaders(apiKey: String) = mapOf(
        "x-goog-api-key" to apiKey,
        "Content-Type" to "application/json",
    )

    override fun buildTranslateRequest(model: String, text: String, nickname: String): String =
        JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", createPrompt(text, nickname)))
                    })
                })
            })
        }.toString()

    override fun parseTranslateResponse(response: String): String {
        val parts = JSONObject(response)
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        return (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it)?.optString("text")?.takeIf(String::isNotEmpty) }
            .joinToString(separator = "")
            .trim()
            .ifEmpty { throw IllegalStateException("Gemini 响应中没有文本内容") }
    }

    override fun parseModelsResponse(response: String): List<AIModel> {
        val models = JSONObject(response).getJSONArray("models")
        return (0 until models.length()).mapNotNull { index ->
            val model = models.getJSONObject(index)
            val methods = model.optJSONArray("supportedGenerationMethods")
            val supportsGenerateContent = methods != null &&
                (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            if (!supportsGenerateContent) return@mapNotNull null

            val id = model.getString("name").substringAfter("models/")
            AIModel(id, model.optString("displayName", id))
        }
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models

    override suspend fun fetchModels(apiKey: String): Result<List<AIModel>> =
        TranslationNetworkHelper.fetchModels(this, apiKey)

    override suspend fun translate(
        apiKey: String,
        model: String,
        text: String,
        nickname: String,
    ): Result<String> = TranslationNetworkHelper.translate(
        this,
        apiKey,
        model,
        text,
        nickname,
        "$baseUrl/v1beta/models/$model:generateContent",
    )
}

class GrokProvider : OpenAIResponsesProvider() {
    override val type = AIProviderType.GROK
    override val baseUrl = "https://api.x.ai"
    override val modelsEndpoint = "/v1/models"
    override val responsesEndpoint = "$baseUrl/v1/responses"

    override fun filterChatModels(models: List<AIModel>): List<AIModel> =
        models.filter { it.id.startsWith("grok-", ignoreCase = true) }
}
