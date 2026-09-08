package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object TranslationNetworkHelper {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    
    suspend fun fetchModels(provider: AIProvider, apiKey: String): Result<List<AIModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${provider.baseUrl}${provider.modelsEndpoint}")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            
            provider.buildModelHeaders(apiKey).forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            
            val responseCode = connection.responseCode
            val response = if (responseCode == 200) {
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                val error = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                throw Exception("HTTP $responseCode: ${error ?: "Unknown error"}")
            }
            
            val allModels = provider.parseModelsResponse(response)
            provider.filterChatModels(allModels)
        }
    }
    
    suspend fun translate(
        provider: AIProvider,
        apiKey: String,
        model: String,
        text: String,
        nickname: String,
        endpoint: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            
            provider.buildHeaders(apiKey).forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            
            val requestBody = provider.buildTranslateRequest(model, text, nickname)
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(StandardCharsets.UTF_8))
            }
            
            val responseCode = connection.responseCode
            val response = if (responseCode == 200) {
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                val error = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                throw Exception("HTTP $responseCode: ${error ?: "Unknown error"}")
            }
            
            provider.parseTranslateResponse(response)
        }
    }
}
