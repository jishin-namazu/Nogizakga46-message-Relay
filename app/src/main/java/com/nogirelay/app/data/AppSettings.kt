package com.nogirelay.app.data

import com.nogirelay.app.translation.AIProviderType

data class AppSettings(
    val relayUrl: String = "",
    val accessToken: String = "",
    val aiProvider: AIProviderType = AIProviderType.OPENAI,
    val aiApiKey: String = "",
    val aiModel: String = "",
    val translationEnabled: Boolean = false,
    val userNickname: String = "",
)
