package com.nogirelay.app.translation

import android.content.Context
import android.util.Log
import com.nogirelay.app.blog.BlogContentParser
import com.nogirelay.app.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object BlogTranslationManager {
    private const val TAG = "NogiBlogTranslation"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun enqueue(context: Context, blogId: String, force: Boolean = false) {
        val appContext = context.applicationContext
        scope.launch {
            translate(appContext, blogId, force)
        }
    }

    suspend fun translate(context: Context, blogId: String, force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        AppGraph.initialize(context)
        if (!inFlight.add(blogId)) return@withContext Result.success(Unit)
        try {
            val settings = AppGraph.settings.read()
            require(settings.translationEnabled) { "请先启用翻译" }
            require(settings.aiApiKey.isNotBlank() && settings.aiModel.isNotBlank()) { "请先配置 API Key 和翻译模型" }
            if (force) AppGraph.database.markBlogForRetranslation(blogId)
            val blog = AppGraph.database.findBlog(blogId) ?: error("BLOG 不存在")
            if (blog.translationDone && !force) return@withContext Result.success(Unit)
            val blocks = BlogContentParser.blocks(blog.bodyHtml)
            val bodyText = BlogContentParser.plainText(blocks)
            val source = listOf(blog.title.trim(), bodyText).filter(String::isNotBlank).joinToString("\n\n\n")
            if (source.isBlank()) {
                AppGraph.database.saveBlogTranslation(blogId, null)
                return@withContext Result.success(Unit)
            }
            val layout = BlogTranslationLayout.from(source)
            val provider = AIProviderFactory.getProvider(settings.aiProvider)
            provider.translate(
                settings.aiApiKey,
                settings.aiModel.trim(),
                layout.requestPayload,
                settings.userNickname,
            ).mapCatching(layout::validateAndSerialize)
                .onSuccess { AppGraph.database.saveBlogTranslation(blogId, it) }
                .onFailure { Log.w(TAG, "BLOG translation failed for $blogId: ${it.message}", it) }
                .map { Unit }
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            inFlight.remove(blogId)
        }
    }
}
