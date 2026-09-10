package com.nogirelay.app.data

/** Process-local visibility used to avoid marking a message unread while its thread is on screen. */
object MessageReadTracker {
    @Volatile
    private var appVisible = false

    @Volatile
    private var openMemberKey: String? = null

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
    }

    fun openMember(memberKey: String) {
        openMemberKey = memberKey
    }

    fun closeMember(memberKey: String) {
        if (openMemberKey == memberKey) openMemberKey = null
    }

    fun isViewing(memberKey: String): Boolean = appVisible && openMemberKey == memberKey
}
