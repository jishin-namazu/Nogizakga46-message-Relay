package com.nogirelay.app.translation

/** Returns the translation without changing its source-controlled formatting. */
@Suppress("UNUSED_PARAMETER")
fun normalizeTranslationText(source: String?, translated: String?): String? {
    return translated?.takeIf { it.isNotBlank() }
}
