package com.nogirelay.app.translation

data class AIModel(
    val id: String,
    val displayName: String,
)

enum class AIProtocol {
    MESSAGES,
    RESPONSES,
    GEMINI_GENERATE_CONTENT,
}

enum class AIProviderType(val displayName: String) {
    OPENAI("OpenAI"),
    KIMI("Kimi (Moonshot)"),
    CLAUDE("Claude (Anthropic)"),
    DEEPSEEK("DeepSeek"),
    GLM("智谱 GLM"),
    GEMINI("Google Gemini"),
    QWEN("通义千问"),
    GROK("Grok (xAI)"),
    MINIMAX("MiniMax"),
    MIMO("小米 MiMo"),
    HUNYUAN("腾讯混元");
}

interface AIProvider {
    val type: AIProviderType
    val protocol: AIProtocol
    val baseUrl: String
    val modelsEndpoint: String
    
    suspend fun fetchModels(apiKey: String): Result<List<AIModel>>
    suspend fun translate(apiKey: String, model: String, text: String, nickname: String): Result<String>
    
    fun buildModelHeaders(apiKey: String): Map<String, String>
    fun buildHeaders(apiKey: String): Map<String, String>
    fun buildTranslateRequest(model: String, text: String, nickname: String): String
    fun parseTranslateResponse(response: String): String
    fun parseModelsResponse(response: String): List<AIModel>
    fun filterChatModels(models: List<AIModel>): List<AIModel>
}

abstract class BaseAIProvider : AIProvider {
    override fun buildModelHeaders(apiKey: String): Map<String, String> = buildHeaders(apiKey)

    protected fun createPrompt(text: String, nickname: String): String {
        val nicknameInstruction = if (nickname.isNotBlank()) {
            "并且将成员姓名替换为\"$nickname\"（保持敬语和语气）"
        } else ""
        return "将以下日语消息翻译成简体中文$nicknameInstruction，保持原文的语气和情感，直接输出译文，不要添加任何解释：\n\n$text"
    }
}
