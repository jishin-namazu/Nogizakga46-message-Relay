package com.nogirelay.app.translation

import com.nogirelay.app.translation.providers.*

object AIProviderFactory {
    fun getProvider(type: AIProviderType): AIProvider {
        return when (type) {
            AIProviderType.OPENAI -> OpenAIProvider()
            AIProviderType.KIMI -> KimiProvider()
            AIProviderType.CLAUDE -> ClaudeProvider()
            AIProviderType.DEEPSEEK -> DeepSeekProvider()
            AIProviderType.GLM -> GLMProvider()
            AIProviderType.GEMINI -> GeminiProvider()
            AIProviderType.QWEN -> QwenProvider()
            AIProviderType.GROK -> GrokProvider()
            AIProviderType.MINIMAX -> MiniMaxProvider()
            AIProviderType.MIMO -> MiMoProvider()
            AIProviderType.HUNYUAN -> HunYuanProvider()
        }
    }
}
