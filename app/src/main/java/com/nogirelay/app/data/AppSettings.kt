package com.nogirelay.app.data

import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.AIModel

data class AppSettings(
    val relayUrl: String = "",
    val accessToken: String = "",
    val aiProvider: AIProviderType = AIProviderType.OPENAI,
    val aiApiKey: String = "",
    val aiModel: String = "",
    val cachedAiModels: List<AIModel> = emptyList(),
    val translationEnabled: Boolean = false,
    val userNickname: String = "",
)
