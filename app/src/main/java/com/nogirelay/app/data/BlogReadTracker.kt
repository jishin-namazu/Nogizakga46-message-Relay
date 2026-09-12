package com.nogirelay.app.data

/** Process-local visibility used to avoid marking an already-open BLOG unread. */
object BlogReadTracker {
    @Volatile
    private var appVisible = false

    @Volatile
    private var openBlogId: String? = null

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
    }

    fun openBlog(blogId: String) {
        openBlogId = blogId
    }

    fun closeBlog(blogId: String) {
        if (openBlogId == blogId) openBlogId = null
    }

    fun isViewing(blogId: String): Boolean = appVisible && openBlogId == blogId
}
