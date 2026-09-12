package com.nogirelay.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MessageDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                member_id TEXT NOT NULL,
                member_name TEXT NOT NULL,
                member_avatar_url TEXT,
                phone_image_url TEXT,
                type TEXT NOT NULL,
                text_content TEXT,
                media_url TEXT,
                thumbnail_url TEXT,
                duration_seconds INTEGER,
                sent_at TEXT NOT NULL,
                incoming_call_from TEXT,
                ringtone_url TEXT,
                is_played INTEGER NOT NULL DEFAULT 0,
                is_unread INTEGER NOT NULL DEFAULT 0,
                translation TEXT,
                translation_done INTEGER NOT NULL DEFAULT 0,
                received_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_sent_at ON messages(sent_at DESC)")
        db.execSQL("CREATE INDEX idx_messages_unread_member ON messages(is_unread, member_id, member_name)")
        createBlogTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN translation TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN translation_done INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // Older builds used test_... IDs, so remove those records during migration.
            db.delete("messages", "id GLOB ?", arrayOf(TEST_MESSAGE_GLOB))
        }
        if (oldVersion < 4) {
            // Existing local history is the read baseline when unread tracking is introduced.
            db.execSQL("ALTER TABLE messages ADD COLUMN is_unread INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX idx_messages_unread_member ON messages(is_unread, member_id, member_name)")
        }
        if (oldVersion < 5) createBlogTables(db)
        if (oldVersion in 5 until 6) {
            db.execSQL("ALTER TABLE blog_posts ADD COLUMN is_unread INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_posts_unread ON blog_posts(is_unread, published_at DESC)")
        }
        if (oldVersion < 7) {
            createBlogMemberTable(db)
            // v7 restores every translated BLOG line break from the source layout.
            db.execSQL("UPDATE blog_posts SET translation = NULL, translation_done = 0")
        }
    }

    private fun createBlogTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS blog_posts (
                id TEXT PRIMARY KEY,
                member_id TEXT NOT NULL,
                member_name TEXT NOT NULL,
                member_avatar_url TEXT,
                title TEXT NOT NULL,
                body_html TEXT NOT NULL,
                image_url TEXT,
                published_at TEXT NOT NULL,
                post_url TEXT NOT NULL,
                translation TEXT,
                translation_done INTEGER NOT NULL DEFAULT 0,
                is_unread INTEGER NOT NULL DEFAULT 0,
                received_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_posts_date ON blog_posts(published_at DESC, id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_posts_member ON blog_posts(member_id, member_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_posts_unread ON blog_posts(is_unread, published_at DESC)")
        createBlogMemberTable(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_state (
                state_key TEXT PRIMARY KEY,
                state_value TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createBlogMemberTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS blog_members (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                avatar_url TEXT,
                display_order INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_members_order ON blog_members(display_order ASC)")
    }

    fun insert(message: RelayMessage, isUnread: Boolean = false): Boolean {
        val values = ContentValues().apply {
            put("id", message.id)
            put("member_id", message.memberId)
            put("member_name", message.memberName)
            put("member_avatar_url", message.memberAvatarUrl)
            put("phone_image_url", message.phoneImageUrl)
            put("type", message.type.name)
            put("text_content", message.text)
            put("media_url", message.mediaUrl)
            put("thumbnail_url", message.thumbnailUrl)
            put("duration_seconds", message.durationSeconds)
            put("sent_at", message.sentAt)
            put("incoming_call_from", message.incomingCallFrom)
            put("ringtone_url", message.ringtoneUrl)
            put("is_played", if (message.isPlayed) 1 else 0)
            put("is_unread", if (isUnread) 1 else 0)
            put("translation", message.translation)
            put("translation_done", if (message.translationDone) 1 else 0)
            put("received_at", System.currentTimeMillis())
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "messages",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        if (!inserted) {
            // A history sync can replace an old direct CDN URL with the
            // protected relay URL without resetting playback state.
            val mediaValues = ContentValues().apply {
                message.mediaUrl?.takeIf { it.isNotBlank() }?.let { put("media_url", it) }
                message.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { put("thumbnail_url", it) }
                message.memberAvatarUrl?.takeIf { it.isNotBlank() }?.let { put("member_avatar_url", it) }
                message.phoneImageUrl?.takeIf { it.isNotBlank() }?.let { put("phone_image_url", it) }
            }
            if (mediaValues.size() > 0) {
                writableDatabase.update("messages", mediaValues, "id = ?", arrayOf(message.id))
            }
        }
        return inserted
    }

    fun latest(limit: Int = 200): List<RelayMessage> {
        val result = mutableListOf<RelayMessage>()
        readableDatabase.query(
            "messages",
            null,
            "id NOT GLOB ? AND (text_content IS NOT NULL OR media_url IS NOT NULL)",
            arrayOf(TEST_MESSAGE_GLOB),
            null,
            null,
            "sent_at DESC, received_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMessage()
        }
        return result
    }

    fun messagesForMember(
        memberKey: String,
        searchQuery: String = "",
        limit: Int = 20,
        offset: Int = 0,
    ): List<RelayMessage> {
        val result = mutableListOf<RelayMessage>()
        val filter = memberFilter(memberKey, searchQuery)
        readableDatabase.query(
            "messages",
            null,
            filter.selection,
            filter.arguments,
            null,
            null,
            MEMBER_MESSAGE_ORDER,
            "${limit.coerceIn(1, 100)} OFFSET ${offset.coerceAtLeast(0)}",
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMessage()
        }
        return result
    }

    fun messageIndexForMember(memberKey: String, messageId: String, searchQuery: String = ""): Int {
        val filter = memberFilter(memberKey, searchQuery)
        readableDatabase.query(
            "messages",
            arrayOf("id"),
            filter.selection,
            filter.arguments,
            null,
            null,
            MEMBER_MESSAGE_ORDER,
        ).use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                if (cursor.getString(0) == messageId) return index
                index += 1
            }
        }
        return -1
    }

    fun countMessagesForMember(memberKey: String, searchQuery: String = ""): Int {
        val filter = memberFilter(memberKey, searchQuery)
        readableDatabase.query(
            "messages",
            arrayOf("COUNT(*)"),
            filter.selection,
            filter.arguments,
            null,
            null,
            null,
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun unreadCountsByMember(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val memberKeyExpression = "CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END"
        readableDatabase.query(
            "messages",
            arrayOf("$memberKeyExpression AS member_key", "COUNT(*) AS unread_count"),
            "is_unread = 1 AND id NOT GLOB ? AND (text_content IS NOT NULL OR media_url IS NOT NULL)",
            arrayOf(TEST_MESSAGE_GLOB),
            memberKeyExpression,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = cursor.getInt(1)
            }
        }
        return result
    }

    fun countUnreadMessages(): Int {
        readableDatabase.query(
            "messages",
            arrayOf("COUNT(*)"),
            "is_unread = 1 AND id NOT GLOB ? AND (text_content IS NOT NULL OR media_url IS NOT NULL)",
            arrayOf(TEST_MESSAGE_GLOB),
            null,
            null,
            null,
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun markMessagesReadForMember(memberKey: String): Int {
        val filter = memberFilter(memberKey, "")
        val values = ContentValues().apply { put("is_unread", 0) }
        return writableDatabase.update(
            "messages",
            values,
            "is_unread = 1 AND ${filter.selection}",
            filter.arguments,
        )
    }

    fun find(id: String): RelayMessage? {
        readableDatabase.query(
            "messages",
            null,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.toMessage() else null }
    }

    fun markPlayed(id: String) {
        val values = ContentValues().apply { put("is_played", 1) }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id))
    }

    /** Removes transient test messages and returns their IDs for notification cleanup. */
    fun deleteTestMessages(): List<String> {
        val ids = mutableListOf<String>()
        writableDatabase.query(
            "messages",
            arrayOf("id"),
            "id GLOB ?",
            arrayOf(TEST_MESSAGE_GLOB),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        if (ids.isNotEmpty()) {
            writableDatabase.delete("messages", "id GLOB ?", arrayOf(TEST_MESSAGE_GLOB))
        }
        return ids
    }

    fun pendingTranslations(limit: Int = 100): List<RelayMessage> {
        val result = mutableListOf<RelayMessage>()
        readableDatabase.query(
            "messages",
            null,
            "id NOT GLOB ? AND translation_done = 0 AND text_content IS NOT NULL AND TRIM(text_content) <> ''",
            arrayOf(TEST_MESSAGE_GLOB),
            null,
            null,
            "sent_at DESC, received_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMessage()
        }
        return result
    }

    fun saveTranslation(id: String, translation: String?) {
        val values = ContentValues().apply {
            put(
                "translation",
                translation
                    ?.takeIf { it.isNotBlank() },
            )
            put("translation_done", 1)
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id))
    }

    fun markForRetranslation(id: String) {
        val values = ContentValues().apply {
            put("translation", null as String?)
            put("translation_done", 0)
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id))
    }

    fun upsertBlog(post: BlogPost, isUnread: Boolean = false): Boolean {
        val values = ContentValues().apply {
            put("id", post.id)
            put("member_id", post.memberId)
            put("member_name", post.memberName)
            put("member_avatar_url", post.memberAvatarUrl)
            put("title", post.title)
            put("body_html", post.bodyHtml)
            put("image_url", post.imageUrl)
            put("published_at", post.publishedAt)
            put("post_url", post.postUrl)
            put("translation", post.translation)
            put("translation_done", if (post.translationDone) 1 else 0)
            put("is_unread", if (isUnread || post.isUnread) 1 else 0)
            put("received_at", System.currentTimeMillis())
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "blog_posts",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        if (inserted) return true

        val existingBody = readableDatabase.query(
            "blog_posts",
            arrayOf("body_html"),
            "id = ?",
            arrayOf(post.id),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }
        val update = ContentValues().apply {
            put("member_id", post.memberId)
            put("member_name", post.memberName)
            put("member_avatar_url", post.memberAvatarUrl)
            put("title", post.title)
            put("image_url", post.imageUrl)
            put("published_at", post.publishedAt)
            put("post_url", post.postUrl)
            if (post.bodyHtml.isNotBlank()) {
                put("body_html", post.bodyHtml)
                if (existingBody.isNotBlank() && existingBody != post.bodyHtml) {
                    put("translation", null as String?)
                    put("translation_done", 0)
                }
            }
        }
        writableDatabase.update("blog_posts", update, "id = ?", arrayOf(post.id))
        return false
    }

    fun hasBlog(id: String): Boolean = readableDatabase.query(
        "blog_posts",
        arrayOf("id"),
        "id = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use(Cursor::moveToFirst)

    fun blogSummaries(
        memberIds: Set<String>? = null,
        oldestFirst: Boolean = false,
        limit: Int = 20,
        offset: Int = 0,
    ): List<BlogSummary> {
        val result = mutableListOf<BlogSummary>()
        val filter = blogMemberFilter(memberIds)
        readableDatabase.query(
            "blog_posts",
            arrayOf("id", "member_id", "member_name", "member_avatar_url", "title", "image_url", "published_at", "is_unread", "translation"),
            filter.selection,
            filter.arguments,
            null,
            null,
            if (oldestFirst) "published_at ASC, id ASC" else "published_at DESC, id DESC",
            "${limit.coerceIn(1, 100)} OFFSET ${offset.coerceAtLeast(0)}",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += BlogSummary(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    memberId = cursor.getString(cursor.getColumnIndexOrThrow("member_id")),
                    memberName = cursor.getString(cursor.getColumnIndexOrThrow("member_name")),
                    memberAvatarUrl = cursor.nullableString("member_avatar_url"),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    imageUrl = cursor.nullableString("image_url"),
                    publishedAt = cursor.getString(cursor.getColumnIndexOrThrow("published_at")),
                    isUnread = cursor.getInt(cursor.getColumnIndexOrThrow("is_unread")) == 1,
                    translatedTitle = cursor.nullableString("translation")?.let(::translatedTitleFromJson),
                )
            }
        }
        return result
    }

    private fun translatedTitleFromJson(serialized: String): String? = runCatching {
        org.json.JSONArray(serialized).optString(0).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun countBlogs(memberIds: Set<String>? = null): Int {
        val filter = blogMemberFilter(memberIds)
        return readableDatabase.query(
            "blog_posts",
            arrayOf("COUNT(*)"),
            filter.selection,
            filter.arguments,
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun blogMembers(): List<BlogMember> {
        val result = mutableListOf<BlogMember>()
        readableDatabase.query(
            "blog_members",
            null,
            null,
            null,
            null,
            null,
            "display_order ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += BlogMember(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                    avatarUrl = cursor.nullableString("avatar_url"),
                    displayOrder = cursor.getInt(cursor.getColumnIndexOrThrow("display_order")),
                )
            }
        }
        return result
    }

    fun replaceBlogMembers(members: List<BlogMember>) {
        require(members.isNotEmpty()) { "官网成员列表为空" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("blog_members", null, null)
            members.forEach { member ->
                val values = ContentValues().apply {
                    put("id", member.id)
                    put("name", member.name)
                    put("category", member.category)
                    put("avatar_url", member.avatarUrl)
                    put("display_order", member.displayOrder)
                }
                db.insertOrThrow("blog_members", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun findBlog(id: String): BlogPost? = readableDatabase.query(
        "blog_posts",
        null,
        "id = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toBlogPost() else null }

    fun saveBlogTranslation(id: String, translation: String?) {
        val values = ContentValues().apply {
            put("translation", translation?.takeIf { it.isNotBlank() })
            put("translation_done", 1)
        }
        writableDatabase.update("blog_posts", values, "id = ?", arrayOf(id))
    }

    fun markBlogForRetranslation(id: String) {
        val values = ContentValues().apply {
            put("translation", null as String?)
            put("translation_done", 0)
        }
        writableDatabase.update("blog_posts", values, "id = ?", arrayOf(id))
    }

    fun countUnreadBlogs(): Int = readableDatabase.query(
        "blog_posts",
        arrayOf("COUNT(*)"),
        "is_unread = 1",
        null,
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun markBlogRead(id: String): Int {
        val values = ContentValues().apply { put("is_unread", 0) }
        return writableDatabase.update("blog_posts", values, "id = ? AND is_unread = 1", arrayOf(id))
    }

    fun isBlogFullSyncComplete(): Boolean = readableDatabase.query(
        "sync_state",
        arrayOf("state_value"),
        "state_key = ?",
        arrayOf(BLOG_FULL_SYNC_KEY),
        null,
        null,
        null,
        "1",
    ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "1" }

    fun blogSyncHeadId(): String? = readableDatabase.query(
        "sync_state",
        arrayOf("state_value"),
        "state_key = ?",
        arrayOf(BLOG_SYNC_HEAD_KEY),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).takeIf { it.isNotBlank() } else null }

    fun markBlogFullSyncComplete(headId: String?) {
        val values = ContentValues().apply {
            put("state_key", BLOG_FULL_SYNC_KEY)
            put("state_value", "1")
        }
        writableDatabase.insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        markBlogSyncHead(headId)
    }

    fun markBlogSyncHead(headId: String?) {
        if (headId.isNullOrBlank()) return
        val values = ContentValues().apply {
            put("state_key", BLOG_SYNC_HEAD_KEY)
            put("state_value", headId)
        }
        writableDatabase.insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun blogMemberFilter(memberIds: Set<String>?): QueryFilter = when {
        memberIds == null -> QueryFilter("", emptyArray())
        memberIds.isEmpty() -> QueryFilter("0", emptyArray())
        else -> QueryFilter(
            "member_id IN (${memberIds.joinToString(",") { "?" }})",
            memberIds.toTypedArray(),
        )
    }.let { filter ->
        if (filter.selection.isBlank()) QueryFilter("1", emptyArray()) else filter
    }

    private fun memberFilter(memberKey: String, searchQuery: String): QueryFilter {
        val clauses = mutableListOf(
            "id NOT GLOB ?",
            "(text_content IS NOT NULL OR media_url IS NOT NULL)",
            "((TRIM(member_id) <> '' AND member_id = ?) OR (TRIM(member_id) = '' AND member_name = ?))",
        )
        val arguments = mutableListOf(TEST_MESSAGE_GLOB, memberKey, memberKey)
        val query = searchQuery.trim()
        if (query.isNotEmpty()) {
            clauses += """
                (
                    COALESCE(text_content, '') LIKE ? ESCAPE '\'
                    OR COALESCE(translation, '') LIKE ? ESCAPE '\'
                    OR member_name LIKE ? ESCAPE '\'
                    OR COALESCE(sent_at, '') LIKE ? ESCAPE '\'
                    OR LOWER(type) LIKE LOWER(?) ESCAPE '\'
                    OR CASE type
                        WHEN 'IMAGE' THEN '图片'
                        WHEN 'AUDIO' THEN '语音'
                        WHEN 'VIDEO' THEN '视频'
                        ELSE '文字'
                    END LIKE ? ESCAPE '\'
                )
            """.trimIndent()
            val pattern = "%${escapeLike(query)}%"
            repeat(6) { arguments += pattern }
        }
        return QueryFilter(clauses.joinToString(" AND "), arguments.toTypedArray())
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private data class QueryFilter(val selection: String, val arguments: Array<String>)

    private fun Cursor.toMessage(): RelayMessage = RelayMessage(
        id = getString(getColumnIndexOrThrow("id")),
        memberId = getString(getColumnIndexOrThrow("member_id")),
        memberName = getString(getColumnIndexOrThrow("member_name")),
        memberAvatarUrl = nullableString("member_avatar_url"),
        phoneImageUrl = nullableString("phone_image_url"),
        type = MessageType.valueOf(getString(getColumnIndexOrThrow("type"))),
        text = nullableString("text_content"),
        mediaUrl = nullableString("media_url"),
        thumbnailUrl = nullableString("thumbnail_url"),
        durationSeconds = nullableInt("duration_seconds"),
        sentAt = getString(getColumnIndexOrThrow("sent_at")),
        incomingCallFrom = nullableString("incoming_call_from"),
        ringtoneUrl = nullableString("ringtone_url"),
        isPlayed = getInt(getColumnIndexOrThrow("is_played")) == 1,
        translation = nullableString("translation"),
        translationDone = getInt(getColumnIndexOrThrow("translation_done")) == 1,
    )

    private fun Cursor.toBlogPost(): BlogPost = BlogPost(
        id = getString(getColumnIndexOrThrow("id")),
        memberId = getString(getColumnIndexOrThrow("member_id")),
        memberName = getString(getColumnIndexOrThrow("member_name")),
        memberAvatarUrl = nullableString("member_avatar_url"),
        title = getString(getColumnIndexOrThrow("title")),
        bodyHtml = getString(getColumnIndexOrThrow("body_html")),
        imageUrl = nullableString("image_url"),
        publishedAt = getString(getColumnIndexOrThrow("published_at")),
        postUrl = getString(getColumnIndexOrThrow("post_url")),
        translation = nullableString("translation"),
        translationDone = getInt(getColumnIndexOrThrow("translation_done")) == 1,
        isUnread = getInt(getColumnIndexOrThrow("is_unread")) == 1,
    )

    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.nullableInt(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    companion object {
        private const val DB_NAME = "messages.db"
        private const val DB_VERSION = 7
        private const val TEST_MESSAGE_GLOB = "test[-_]*"
        private const val MEMBER_MESSAGE_ORDER = "sent_at DESC, received_at DESC, id DESC"
        private const val BLOG_FULL_SYNC_KEY = "blog_full_sync_complete_v2"
        private const val BLOG_SYNC_HEAD_KEY = "blog_sync_head_id_v2"
    }
}
