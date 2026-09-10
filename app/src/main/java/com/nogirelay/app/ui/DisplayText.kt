package com.nogirelay.app.ui

/**
 * VS15 (U+FE0E) requests the monochrome "text presentation" of the preceding
 * character. Member messages can contain it (for example "☺︎"), but Android font
 * stacks often have no monochrome glyph for the base character, so the pair is
 * rendered as a tofu box while the plain or emoji-presentation form ("☺" / "☺️")
 * displays fine. The selector is invisible, so dropping it only relaxes the
 * requested presentation; stored messages and translation input stay untouched.
 */
private const val TEXT_PRESENTATION_SELECTOR = "\uFE0E"

fun String.withoutTextPresentationSelector(): String =
    if (contains(TEXT_PRESENTATION_SELECTOR)) {
        replace(TEXT_PRESENTATION_SELECTOR, "")
    } else {
        this
    }
