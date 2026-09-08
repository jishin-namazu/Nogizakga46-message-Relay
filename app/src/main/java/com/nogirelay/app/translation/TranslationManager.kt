package com.nogirelay.app.translation

import android.content.Context
import android.util.Log
import com.nogirelay.app.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object TranslationManager {
    private const val TAG = "NogiTranslation"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val retryCount = ConcurrentHashMap<String, Int>()
    private val requestSlots = Semaphore(3)

    fun enqueue(context: Context) {
        AppGraph.initialize(context)
        val settings = AppGraph.settings.read()
        if (!settings.translationEnabled || settings.aiApiKey.isBlank() || settings.aiModel.isBlank()) return
        
        val provider = AIProviderFactory.getProvider(settings.aiProvider)
        val model = settings.aiModel.trim()
        val nickname = settings.userNickname

        val pending = runCatching { AppGraph.database.pendingTranslations() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        pending.forEach { message ->
            if ((retryAfter[message.id] ?: 0L) > now) return@forEach
            if (!inFlight.add(message.id)) return@forEach
            scope.launch {
                try {
                    requestSlots.withPermit {
                        val originalText = message.text
                        val text = substituteNickname(originalText, nickname)?.trim().orEmpty()
                        if (!shouldTranslate(text)) {
                            AppGraph.database.saveTranslation(message.id, null)
                        } else {
                            val result = provider.translate(settings.aiApiKey, model, text, nickname)
                            result.onSuccess { translation ->
                                AppGraph.database.saveTranslation(message.id, translation.takeIf { it.isNotBlank() })
                                retryAfter.remove(message.id)
                                retryCount.remove(message.id)
                            }.onFailure { error ->
                                throw error
                            }
                        }
                    }
                } catch (error: Exception) {
                    val attempts = (retryCount.merge(message.id, 1, Int::plus) ?: 1).coerceAtMost(8)
                    val delayMs = (5_000L * (1L shl (attempts - 1))).coerceAtMost(5 * 60_000L)
                    retryAfter[message.id] = System.currentTimeMillis() + delayMs
                    Log.w(TAG, "Translation failed for ${message.id}; retrying in ${delayMs / 1000}s: ${error.message}", error)
                } finally {
                    inFlight.remove(message.id)
                }
            }
        }
    }

    fun resetRetries() {
        retryAfter.clear()
        retryCount.clear()
    }

    suspend fun fetchAvailableModels(providerType: AIProviderType, apiKey: String): Result<List<AIModel>> {
        require(apiKey.isNotBlank()) { "请先填写 API Key" }
        val provider = AIProviderFactory.getProvider(providerType)
        return provider.fetchModels(apiKey)
    }

    private fun shouldTranslate(text: String): Boolean {
        if (text.isBlank() || isPureEmojiOrSymbols(text)) return false
        var hasHan = false
        var hasKana = false
        text.codePoints().forEach { codePoint ->
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HAN -> hasHan = true
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                -> hasKana = true
                else -> Unit
            }
        }
        return hasHan || hasKana
    }

    private fun isPureEmojiOrSymbols(text: String): Boolean {
        var hasVisibleSymbol = false
        var hasLetterOrDigit = false
        text.codePoints().forEach { codePoint ->
            if (Character.isWhitespace(codePoint)) return@forEach
            val type = Character.getType(codePoint)
            when {
                Character.isLetterOrDigit(codePoint) -> hasLetterOrDigit = true
                type == Character.FORMAT.toInt() || type == Character.NON_SPACING_MARK.toInt() -> Unit
                type == Character.OTHER_SYMBOL.toInt() || type == Character.MATH_SYMBOL.toInt() -> hasVisibleSymbol = true
                type in punctuationTypes -> hasVisibleSymbol = true
                else -> hasLetterOrDigit = true
            }
        }
        return hasVisibleSymbol && !hasLetterOrDigit
    }

    private val punctuationTypes = setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
    )
}
