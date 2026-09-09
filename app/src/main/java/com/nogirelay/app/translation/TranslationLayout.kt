package com.nogirelay.app.translation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Separates translatable text from its original whitespace and line separators.
 * All text segments are sent in one request so the model retains full context;
 * formatting is restored locally instead of trusting the model to reproduce it.
 */
internal class TranslationLayout private constructor(
    source: String,
    private val parts: List<Part>,
    private val segments: List<String>,
) {
    val requestPayload: String = JSONObject()
        .put("full_text", source)
        .put("segments", JSONArray().apply { segments.forEach(::put) })
        .toString()

    fun restore(modelOutput: String): String {
        val output = unwrapCodeFence(modelOutput)
        val translated = JSONArray(output)
        require(translated.length() == segments.size) {
            "译文片段数量不匹配：需要 ${segments.size} 个，实际收到 ${translated.length()} 个"
        }

        val values = (0 until translated.length()).map { index ->
            require(translated.get(index) is String) { "译文片段 ${index + 1} 不是字符串" }
            translated.getString(index).also { value ->
                require('\n' !in value && '\r' !in value) { "译文片段 ${index + 1} 包含额外换行" }
            }
        }

        return buildString {
            parts.forEach { part ->
                when (part) {
                    is Part.Literal -> append(part.value)
                    is Part.Translated -> {
                        append(part.prefix)
                        append(values[part.index])
                        append(part.suffix)
                    }
                }
            }
        }
    }

    private sealed interface Part {
        data class Literal(val value: String) : Part
        data class Translated(
            val index: Int,
            val prefix: String,
            val suffix: String,
        ) : Part
    }

    companion object {
        private val lineSeparator = Regex("\\r\\n|\\r|\\n")

        fun from(source: String): TranslationLayout {
            val parts = mutableListOf<Part>()
            val segments = mutableListOf<String>()
            var cursor = 0

            lineSeparator.findAll(source).forEach { match ->
                addTextPart(source.substring(cursor, match.range.first), parts, segments)
                parts += Part.Literal(match.value)
                cursor = match.range.last + 1
            }
            addTextPart(source.substring(cursor), parts, segments)

            return TranslationLayout(source, parts, segments)
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

            val contentStart = trimmed.indexOf('\n')
            val contentEnd = trimmed.lastIndexOf("```")
            require(contentStart >= 0 && contentEnd > contentStart) { "模型返回了不完整的 JSON 代码块" }
            return trimmed.substring(contentStart + 1, contentEnd).trim()
        }
    }
}
