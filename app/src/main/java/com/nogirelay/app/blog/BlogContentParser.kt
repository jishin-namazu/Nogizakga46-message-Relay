package com.nogirelay.app.blog

import android.text.Html

sealed interface BlogContentBlock {
    data class Text(val value: String) : BlogContentBlock
    data class Image(val url: String) : BlogContentBlock
}

object BlogContentParser {
    private val imageTag = Regex("<img\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val imageSource = Regex("\\bsrc\\s*=\\s*(['\"])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun blocks(html: String): List<BlogContentBlock> {
        val result = mutableListOf<BlogContentBlock>()
        var cursor = 0
        imageTag.findAll(html).forEach { match ->
            addText(html.substring(cursor, match.range.first), result)
            imageSource.find(match.value)?.groupValues?.getOrNull(2)?.let(::officialUrl)?.let {
                result += BlogContentBlock.Image(it)
            }
            cursor = match.range.last + 1
        }
        addText(html.substring(cursor), result)
        return result
    }

    fun plainText(blocks: List<BlogContentBlock>): String = blocks
        .filterIsInstance<BlogContentBlock.Text>()
        .map(BlogContentBlock.Text::value)
        .filter(String::isNotBlank)
        .joinToString("\n\n\n")

    fun paragraphs(value: String): List<String> = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("\n{3,}"))
        .map { it.trim('\n') }
        .filter(String::isNotBlank)

    private fun addText(html: String, blocks: MutableList<BlogContentBlock>) {
        if (html.isBlank()) return
        val text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace('\u00A0', ' ')
            .trim('\n', '\r')
        if (text.isNotBlank()) blocks += BlogContentBlock.Text(text)
    }

    private fun officialUrl(value: String): String? {
        val decoded = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        return when {
            decoded.startsWith("https://") -> decoded
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("/") -> "https://www.nogizaka46.com$decoded"
            else -> null
        }
    }
}
