package com.nogirelay.app.data

data class AppSettings(
    val relayUrl: String = "",
    val accessToken: String = "",
    val openAiApiKey: String = "",
    val openAiModel: String = "",
    val translationEnabled: Boolean = false,
    val userNickname: String = "",
)
