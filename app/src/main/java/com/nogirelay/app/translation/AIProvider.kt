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

    @Suppress("UNUSED_PARAMETER")
    protected fun createPrompt(text: String, nickname: String): String {
        return """
            你将收到一份完整日语内容（消息或 BLOG），以及按原文顺序排列的文本片段。请结合完整内容的上下文，将所有文本片段翻译成简体中文。

            要求：
            1. 人名必须保持原文，不得翻译、音译、改写或替换。
            2. 对于不应翻译的内容（例如专有名词、代码等），请保留原文。
            3. 每个输入片段必须对应一个输出片段，不得合并、拆分、遗漏或增加片段。
            4. 输出片段的数量和顺序必须与输入完全一致，每个片段内部不得添加换行。
            5. 保持原文的语气和情感。
            6. 只输出合法的 JSON 字符串数组，不要输出 Markdown、代码块或任何解释。

            输入：
            $text
        """.trimIndent()
    }
}
