package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProtocol
import com.nogirelay.app.translation.BaseAIProvider
import org.json.JSONArray
import org.json.JSONObject

abstract class AnthropicMessagesProvider : BaseAIProvider() {
    final override val protocol = AIProtocol.MESSAGES
    protected abstract val messagesEndpoint: String
    protected open val includeAnthropicVersion = true

    override fun buildModelHeaders(apiKey: String) = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
    )

    override fun buildHeaders(apiKey: String) = buildMap {
        put("x-api-key", apiKey)
        put("Content-Type", "application/json")
        if (includeAnthropicVersion) put("anthropic-version", "2023-06-01")
    }

    override fun buildTranslateRequest(model: String, text: String, nickname: String): String =
        JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", createPrompt(text, nickname))
                })
            })
        }.toString()

    override fun parseTranslateResponse(response: String): String {
        val content = JSONObject(response).getJSONArray("content")
        return (0 until content.length())
            .map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString(separator = "") { it.optString("text") }
            .trim()
            .ifEmpty { throw IllegalStateException("Messages 响应中没有文本内容") }
    }

    override fun parseModelsResponse(response: String): List<AIModel> = parseOpenAIModelList(response)

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
        messagesEndpoint,
    )
}

abstract class OpenAIResponsesProvider : BaseAIProvider() {
    final override val protocol = AIProtocol.RESPONSES
    protected abstract val responsesEndpoint: String

    override fun buildHeaders(apiKey: String) = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
    )

    override fun buildTranslateRequest(model: String, text: String, nickname: String): String =
        JSONObject().apply {
            put("model", model)
            put("input", createPrompt(text, nickname))
        }.toString()

    override fun parseTranslateResponse(response: String): String {
        val json = JSONObject(response)
        json.optString("output_text").trim().takeIf { it.isNotEmpty() }?.let { return it }

        val output = json.optJSONArray("output") ?: JSONArray()
        val texts = buildList {
            for (outputIndex in 0 until output.length()) {
                val item = output.optJSONObject(outputIndex) ?: continue
                if (item.optString("type") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (contentIndex in 0 until content.length()) {
                    val part = content.optJSONObject(contentIndex) ?: continue
                    if (part.optString("type") == "output_text") {
                        part.optString("text").takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
        }
        return texts.joinToString(separator = "").trim()
            .ifEmpty { throw IllegalStateException("Responses 响应中没有文本内容") }
    }

    override fun parseModelsResponse(response: String): List<AIModel> = parseOpenAIModelList(response)

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
        responsesEndpoint,
    )
}

private fun parseOpenAIModelList(response: String): List<AIModel> {
    val data = JSONObject(response).getJSONArray("data")
    return (0 until data.length()).map { index ->
        val model = data.getJSONObject(index)
        val id = model.getString("id")
        val displayName = model.optString("display_name")
            .ifBlank { model.optString("displayName") }
            .ifBlank { id }
        AIModel(id, displayName)
    }
}
