package com.nogirelay.app.translation

fun substituteNickname(text: String?, nickname: String): String? {
    if (text.isNullOrBlank()) return text
    if (nickname.isBlank()) return text
    return text.replace("%%%", nickname)
}
