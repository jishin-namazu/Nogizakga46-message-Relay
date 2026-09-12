package com.nogirelay.app.data

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BlogClient {
    fun fetchPage(limit: Int = 100, offset: Int = 0): BlogPage {
        require(limit in 1..500) { "BLOG 同步数量必须在 1 到 500 之间" }
        require(offset >= 0) { "BLOG 同步偏移量不能为负数" }
        return requestPage(limit, offset)
    }

    fun fetchCount(): Int = requestPage(limit = 0, offset = 0).total

    fun fetchMembers(): List<BlogMember> {
        val root = requestJsonp(
            Uri.parse(MEMBER_API_URL).buildUpon()
                .appendQueryParameter("rw", "500")
                .appendQueryParameter("st", "0")
                .build()
                .toString(),
            "乃木坂46成员接口",
        )
        val data = root.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                val member = data.optJSONObject(index) ?: continue
                val id = member.optString("code").trim()
                if (id.isEmpty() || id == "10001") continue
                add(
                    BlogMember(
                        id = id,
                        name = member.optString("name").trim(),
                        category = member.optString("cate").trim().ifBlank { "其他" },
                        avatarUrl = officialUrl(member.optString("img")),
                        displayOrder = index,
                    ),
                )
            }
        }
    }

    private fun requestPage(limit: Int, offset: Int): BlogPage {
        val url = Uri.parse(BLOG_API_URL).buildUpon()
            .appendQueryParameter("rw", limit.toString())
            .appendQueryParameter("st", offset.toString())
            .build()
            .toString()
        return parsePageObject(requestJsonp(url, "乃木坂46 BLOG 接口"))
    }

    private fun requestJsonp(url: String, endpointLabel: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", BLOG_PAGE_URL)
            setRequestProperty("User-Agent", "Nogi Relay Android")
        }
        return try {
            val status = connection.responseCode
            if (status !in 200..299) error("$endpointLabel 返回 $status")
            parseJsonpObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    fun parsePage(jsonp: String): BlogPage = parsePageObject(parseJsonpObject(jsonp))

    private fun parseJsonpObject(jsonp: String): JSONObject {
        val value = jsonp.trim().removePrefix("\uFEFF")
        require(value.startsWith("res(") && (value.endsWith(")") || value.endsWith(");"))) {
            "BLOG 接口返回格式无效"
        }
        val end = if (value.endsWith(";")) value.length - 2 else value.length - 1
        return JSONObject(value.substring(4, end))
    }

    private fun parsePageObject(root: JSONObject): BlogPage {
        val data = root.optJSONArray("data")
        val posts = buildList {
            if (data != null) {
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.let { add(parsePost(it)) }
                }
            }
        }
        return BlogPage(root.optString("count").toIntOrNull() ?: posts.size, posts)
    }

    fun fromPush(data: Map<String, String>): BlogPost {
        val id = data["blog_id"].orEmpty().trim()
        require(id.isNotEmpty()) { "BLOG 推送缺少 blog_id" }
        return BlogPost(
            id = id,
            memberId = data["member_id"].orEmpty(),
            memberName = data["member_name"].orEmpty().ifBlank { "乃木坂46" },
            memberAvatarUrl = data["member_avatar_url"].nullIfBlank(),
            title = data["title"].orEmpty(),
            bodyHtml = "",
            imageUrl = data["image_url"].nullIfBlank(),
            publishedAt = data["published_at"].orEmpty(),
            postUrl = data["post_url"].orEmpty().ifBlank {
                "https://www.nogizaka46.com/s/n46/diary/detail/$id"
            },
        )
    }

    private fun parsePost(json: JSONObject): BlogPost {
        val id = json.optString("code").trim()
        require(id.isNotEmpty()) { "BLOG 缺少 code" }
        return BlogPost(
            id = id,
            memberId = json.optString("arti_code").trim(),
            memberName = json.optString("name").trim().ifBlank { "乃木坂46" },
            memberAvatarUrl = officialUrl(json.optString("artist_img")),
            title = json.optString("title").trim(),
            bodyHtml = json.optString("text"),
            imageUrl = officialUrl(json.optString("img")),
            publishedAt = normalizePublishedAt(json.optString("date")),
            postUrl = officialUrl(json.optString("link"))
                ?: "https://www.nogizaka46.com/s/n46/diary/detail/$id",
        )
    }

    private fun officialUrl(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        return when {
            trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "https://www.nogizaka46.com$trimmed"
            else -> null
        }
    }

    private fun normalizePublishedAt(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || 'T' in trimmed) return trimmed
        return trimmed.replace('/', '-').replace(' ', 'T') + "+09:00"
    }

    private fun String?.nullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val BLOG_PAGE_URL = "https://www.nogizaka46.com/s/n46/diary/MEMBER"
        const val BLOG_API_URL = "https://www.nogizaka46.com/s/n46/api/list/blog"
        const val MEMBER_API_URL = "https://www.nogizaka46.com/s/n46/api/list/member"
    }
}
