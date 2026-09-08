package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType

class OpenAIProvider : OpenAIResponsesProvider() {
    override val type = AIProviderType.OPENAI
    override val baseUrl = "https://api.openai.com"
    override val modelsEndpoint = "/v1/models"
    override val responsesEndpoint = "$baseUrl/v1/responses"

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        val textFamily = id.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(id)
        val nonTextFamily = listOf(
            "embedding",
            "whisper",
            "transcribe",
            "tts",
            "audio",
            "realtime",
            "dall-e",
            "image",
            "moderation",
        ).any(id::contains)
        textFamily && !nonTextFamily
    }
}
