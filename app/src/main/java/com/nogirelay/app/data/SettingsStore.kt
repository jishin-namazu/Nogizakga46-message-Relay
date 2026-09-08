package com.nogirelay.app.data

import android.content.Context
import com.nogirelay.app.translation.AIProviderType

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): AppSettings = AppSettings(
        relayUrl = prefs.getString(KEY_RELAY_URL, "").orEmpty(),
        accessToken = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty(),
        aiProvider = AIProviderType.valueOf(prefs.getString(KEY_AI_PROVIDER, AIProviderType.OPENAI.name) ?: AIProviderType.OPENAI.name),
        aiApiKey = prefs.getString(KEY_AI_API_KEY, "").orEmpty(),
        aiModel = prefs.getString(KEY_AI_MODEL, "").orEmpty(),
        translationEnabled = prefs.getBoolean(KEY_TRANSLATION_ENABLED, false),
        userNickname = prefs.getString(KEY_USER_NICKNAME, "").orEmpty(),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_RELAY_URL, settings.relayUrl.trim().trimEnd('/'))
            .putString(KEY_ACCESS_TOKEN, settings.accessToken.trim())
            .putString(KEY_AI_PROVIDER, settings.aiProvider.name)
            .putString(KEY_AI_API_KEY, settings.aiApiKey.trim())
            .putString(KEY_AI_MODEL, settings.aiModel.trim())
            .putBoolean(KEY_TRANSLATION_ENABLED, settings.translationEnabled)
            .putString(KEY_USER_NICKNAME, settings.userNickname.trim())
            .apply()
    }

    fun pushToken(): String = prefs.getString(KEY_PUSH_TOKEN, "").orEmpty()

    fun savePushToken(token: String) {
        prefs.edit().putString(KEY_PUSH_TOKEN, token).apply()
    }

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_TRANSLATION_ENABLED = "translation_enabled"
        private const val KEY_USER_NICKNAME = "user_nickname"
        private const val KEY_PUSH_TOKEN = "push_token"
    }
}
