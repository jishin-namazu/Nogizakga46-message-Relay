package com.nogirelay.app.translation

import com.nogirelay.app.blog.BlogContentParser
import org.json.JSONArray
import org.json.JSONObject

/** Sends the complete BLOG once, then restores every paragraph's source line breaks. */
class BlogTranslationLayout private constructor(
    source: String,
    val paragraphs: List<String>,
    private val paragraphParts: List<List<Part>>,
    private val segments: List<String>,
) {
    val requestPayload: String = JSONObject()
        .put("full_text", source)
        .put("segments", JSONArray().apply { segments.forEach(::put) })
        .toString()

    fun validateAndSerialize(modelOutput: String): String {
        val values = JSONArray(unwrapCodeFence(modelOutput))
        require(values.length() == segments.size) {
            "BLOG 译文片段数量不匹配：需要 ${segments.size} 个，实际收到 ${values.length()} 个"
        }
        val translated = List(values.length()) { index ->
            require(values.get(index) is String) { "BLOG 译文片段 ${index + 1} 不是字符串" }
            values.getString(index).also { value ->
                require('\n' !in value && '\r' !in value) { "BLOG 译文片段 ${index + 1} 包含额外换行" }
            }
        }
        return JSONArray().apply {
            paragraphParts.forEach { parts ->
                put(buildString {
                    parts.forEach { part ->
                        when (part) {
                            is Part.Literal -> append(part.value)
                            is Part.Translated -> {
                                append(part.prefix)
                                append(translated[part.index])
                                append(part.suffix)
                            }
                        }
                    }
                })
            }
        }.toString()
    }

    fun decode(serialized: String?): List<String> {
        if (serialized.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(serialized)
            List(array.length()) { index -> array.getString(index) }
                .takeIf { it.size == paragraphs.size }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private sealed interface Part {
        data class Literal(val value: String) : Part
        data class Translated(val index: Int, val prefix: String, val suffix: String) : Part
    }

    companion object {
        fun from(source: String): BlogTranslationLayout {
            val paragraphs = BlogContentParser.paragraphs(source)
            val segments = mutableListOf<String>()
            val paragraphParts = paragraphs.map { buildParagraphParts(it, segments) }
            return BlogTranslationLayout(source, paragraphs, paragraphParts, segments)
        }

        private fun buildParagraphParts(source: String, segments: MutableList<String>): List<Part> {
            val parts = mutableListOf<Part>()
            var cursor = 0
            Regex("\n").findAll(source).forEach { match ->
                addTextPart(source.substring(cursor, match.range.first), parts, segments)
                parts += Part.Literal(match.value)
                cursor = match.range.last + 1
            }
            addTextPart(source.substring(cursor), parts, segments)
            return parts
        }

        private fun addTextPart(
            value: String,
            parts: MutableList<Part>,
            segments: MutableList<String>,
        ) {
            if (value.isBlank()) {
                if (value.isNotEmpty()) parts += Part.Literal(value)
                return
            }
            val coreStart = value.indexOfFirst { it != ' ' && it != '\t' }
            val coreEnd = value.indexOfLast { it != ' ' && it != '\t' } + 1
            val segmentIndex = segments.size
            segments += value.substring(coreStart, coreEnd)
            parts += Part.Translated(
                index = segmentIndex,
                prefix = value.substring(0, coreStart),
                suffix = value.substring(coreEnd),
            )
        }

        private fun unwrapCodeFence(value: String): String {
            val trimmed = value.trim().removePrefix("\uFEFF")
            if (!trimmed.startsWith("```")) return trimmed
            val start = trimmed.indexOf('\n')
            val end = trimmed.lastIndexOf("```")
            require(start >= 0 && end > start) { "模型返回了不完整的 JSON 代码块" }
            return trimmed.substring(start + 1, end).trim()
        }
    }
}
