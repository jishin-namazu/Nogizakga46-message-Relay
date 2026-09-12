package com.nogirelay.app.blog

import android.content.Context
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.data.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object BlogMediaDownloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val slots = Semaphore(3)

    fun enqueue(context: Context, post: BlogPost) {
        val urls = buildList {
            post.imageUrl?.let(::add)
            addAll(BlogContentParser.blocks(post.bodyHtml).filterIsInstance<BlogContentBlock.Image>().map { it.url })
        }.distinct()
        urls.forEach { url ->
            if (!queued.add(url)) return@forEach
            scope.launch {
                try {
                    slots.withPermit { MediaDownloader.downloadUrl(context.applicationContext, url, MessageType.IMAGE) }
                } finally {
                    queued.remove(url)
                }
            }
        }
    }
}
